# Sidecar

Python process that owns every model and every millisecond of audio. It exists
so the JVM never touches PCM: short sleeps in Java raise the Windows timer
resolution process-wide, and an always-on application that does that to the
whole machine is a battery bug wearing a feature's clothes.

In M1 this is a protocol stub — `aura_speech/stub.py` speaks the JSON-lines
contract and loads no models. Recognition, narration and the wake word arrive
in M2.

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

Not part of the runtime. Exporting a model to OpenVINO IR needs a heavier
toolchain — `optimum-intel`, `nncf`, `transformers`, and torch behind them —
which the running sidecar must never carry into memory. It lives in its own
environment:

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
