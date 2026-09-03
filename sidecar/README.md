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
which the running sidecar must never carry into memory. It is installed
separately, and its versions are recorded once an export has actually
succeeded against the pinned runtime, rather than guessed in advance.

Exported models live in `models/`, compiled blobs in `.ov_cache/`. Both are
ignored: gigabytes, and reproducible from the export step.
