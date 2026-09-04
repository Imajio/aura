# Sidecar

Python process that owns every model and every millisecond of audio. It exists
so the JVM never touches PCM: short sleeps in Java raise the Windows timer
resolution process-wide, and an always-on application that does that to the
whole machine is a battery bug wearing a feature's clothes.

In M1 this is a protocol stub — `aura_speech/stub.py` speaks the JSON-lines
contract and loads no models. Recognition, narration and the wake word arrive
in M2.

## The models, and why these ones

| Stage | Model | Device |
|---|---|---|
| recognition | `whisper-large-v3-turbo` INT8 | iGPU |
| narration | `Qwen3-4B` INT4 (`Qwen3-1.7B` on battery) | iGPU |
| Russian speech | Silero v4, voice chosen by ear | CPU |

Speech is the one stage that runs under torch rather than OpenVINO. Converting
Silero to IR was tried and is not possible: it is a single TorchScript system
taking strings, with accent placement inside the graph, and OpenVINO refuses it
on `SequenceInsert`. Torch costs 183 MB and the voice 99 more, against the
~3.4 GB the sidecar already holds resident — the rule it breaks was written to
keep a lean process, and residency ended that.

All three were measured rather than chosen, and two obvious-looking
substitutions were measured and rejected:

* **Whisper tiny** is twice as fast and unusable: 43% word error rate on
  Russian against turbo's 17%, and what it loses first is the project name
  routing matches on — "бэкенд" comes back as "бакант".
* **Whisper small** is slower *and* less accurate than turbo. "Turbo" is not
  the large model; it is the one with a cut-down decoder.
* **Qwen3.5-4B**, the newest 4B, is a vision-language model: four times slower
  at the same narration, carrying vision towers that never run.

Neither model is unloaded when idle. Bringing one back costs about six seconds
before it answers, against latency budgets of 1.2 s and 900 ms — see the risk
register, RISK-9.

## Environment

Python 3.13 on the target machine. The runtime is pinned exactly in
`requirements.txt`, and the pin is load-bearing: the device measurements the
design rests on (PRD §9, ADR 0001) were taken on OpenVINO 2026.3.0, and a
minor bump changes which kernels the NPU plugin emits.

```bash
cd sidecar
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r requirements.txt
```

The virtual environment is not committed — `.venv/` is ignored. The pin is what
travels; the environment is rebuilt from it.

## Checking the machine

```bash
.venv/Scripts/python.exe report-devices.py
```

Prints what OpenVINO actually sees and exits non-zero when no NPU is visible.
Run it after creating the environment, and after any driver or OpenVINO change:
its whole purpose is to stop the PRD's device table from quietly becoming
fiction.

Reproduced on the target machine 2026-09-04, matching PRD §9 exactly:

```
available devices ['CPU', 'GPU', 'NPU']
NPU   Intel(R) AI Boost · architecture 3720 · 2 tiles · driver 1004512
      FP16, INT8, EXPORT_IMPORT — no INT4
GPU   Intel(R) Arc(TM) 140T (16GB, iGPU)
      FP16, INT8, GPU_HW_MATMUL, GPU_USM_MEMORY, EXPORT_IMPORT
CPU   Intel(R) Core(TM) Ultra 9 285H
```

## Exporting models

Exporting a model to OpenVINO IR needs a heavier toolchain — `optimum-intel`,
`nncf`, `transformers` — that the running sidecar has no use for. It lives in
its own environment:

```bash
python -m venv .venv-export
.venv-export/Scripts/python.exe -m pip install -r requirements-export.txt
.venv-export/Scripts/python.exe export-whisper.py
```

INT8, not INT4: the NPU here reports FP16 and INT8 only. Asking for INT4 buys a
silent fallback or a compile error — a worse way to learn the same fact.

Exported models live in `models/`, compiled blobs in `.ov_cache/`. Both are
ignored: gigabytes, and reproducible from the export step.

## Measuring recognition

```bash
powershell -ExecutionPolicy Bypass -File make-bench-sample.ps1
.venv-export/Scripts/python.exe benchmark-whisper.py --device NPU
```

`benchmark-whisper.py` reports two numbers that answer different questions:
**compile**, paid once per model per driver version and then served from the
blob cache — the step that fails outright when a model cannot be made static
for the NPU — and **recognition**, measured warm and repeated, which is what
the user actually waits for.

The threshold is **550 ms**, not the PRD's 1.2 s: that budget also covers the
VAD tail, speaker verification and routing. Judging recognition against the
whole 1.2 s would report success at twice the real overrun.

The sample comes from Windows' own speech synthesiser, so it needs no network,
no dataset licence and no download, and it is identical on every machine.
Windows ships no Russian voice by default, so it is English — and Russian
decodes to more tokens per second of speech, which makes any figure measured
this way a **lower bound** for the Russian profile rather than a stand-in
for it.

## Measuring the narrator

```bash
.venv-export/Scripts/python.exe benchmark-narrator.py --device GPU
```

Reports time to first token and time to first complete sentence, per language
profile, against the design's 900 ms from event to first spoken word — a budget
synthesis has to fit inside as well.

Models are the pre-quantised OpenVINO builds, which saves exporting an 8 GB
checkpoint to get a 2 GB one:

```bash
huggingface-cli download OpenVINO/Qwen3-1.7B-int4-ov --local-dir ../models/qwen3-1.7b-int4-ov
```

Qwen3 reasons before answering unless told not to. `/no_think` in the message
text is what works; the chat template's `enable_thinking` hook does not, because
`LLMPipeline` re-applies its own template to any string handed to it and buries
the prefill. The 0.6B build ignores the marker altogether and narrates nothing,
so it is not a smaller narrator — it is not one at all.

## Measuring the cost of unloading

```bash
.venv-export/Scripts/python.exe benchmark-model-switching.py --device GPU
```

Compares both models resident and alternating against dropped and rebuilt
before every use — the two sides of the registry's idle-unload policy. Load and
first inference are timed apart, because the first call after a load costs more
than the load.

## Auditioning Russian voices

```bash
.venv-export/Scripts/python.exe audition-russian-tts.py
```

Renders ten of the narrator's real lines in every Silero v4 and v5 Russian
voice, and reports synthesis latency. The samples land outside the repository —
they are an evaluation artefact and regenerable from the script.

Which voice ships is decided by listening, not by a number. What the numbers
settle is that synthesis is not the expensive half: about 110 ms a phrase on
v4, on the CPU, so it neither breaks the 900 ms budget nor competes with the
iGPU that recognition and the narrator share.

## Comparing recognition models

```bash
.venv-export/Scripts/python.exe compare-recognition.py \
    --models ../models/whisper-tiny-int8,../models/whisper-large-v3-turbo-int8
```

Word error rate on Russian commands carrying English technical terms — the
case that decides whether routing can find the project at all. Speech is
synthesised until the corpus in `testdata/audio` has recordings, which flatters
every model equally.

## Running it

```bash
.venv/Scripts/python.exe -m aura_speech.main            # the real sidecar
.venv/Scripts/python.exe -m pytest tests/ -q            # its tests
```

Started by the Java side, not by hand. It announces `ready` immediately and
loads the narrator on the first narration: compiling for the iGPU takes tens of
seconds, and a sidecar that stays silent until it finishes is indistinguishable
from one that failed to start.

M2 in progress. Narration works in both language profiles; speech and
recognition do not yet, and say so — `speak` answers `SPEECH_UNAVAILABLE` and
`ready` reports `tts: absent`, rather than acknowledging speech nobody hears.

`aura_speech/stub.py` stays as it is: it loads no models, it starts anywhere,
and it is the fixture the Java contract test drives.

Test tooling is separate from the runtime, in `requirements-dev.txt` — the
always-on process should carry nothing it does not use:

```bash
.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
```
