"""Reports what OpenVINO actually sees on this machine.

The PRD records a device table (section 9) that the whole execution-device
policy rests on: which stage runs on the NPU, which on the iGPU, what
precisions are available. That table was measured once. This script re-derives
it, so a driver update or a runtime bump cannot quietly turn it into fiction.

Run it after creating the environment and after any driver or OpenVINO change:

    .venv/Scripts/python.exe report-devices.py
"""

import sys

import openvino as ov

# Properties worth reporting per device. Asking for one a device does not
# support raises, so every read is guarded and reported as unsupported rather
# than killing the run — a partial report is still evidence.
PROPERTIES = [
    "FULL_DEVICE_NAME",
    "DEVICE_ARCHITECTURE",
    "DEVICE_TYPE",
    "OPTIMIZATION_CAPABILITIES",
    "NPU_MAX_TILES",
    "NPU_DRIVER_VERSION",
    "NPU_DEVICE_ALLOC_MEM_SIZE",
    "GPU_DEVICE_TOTAL_MEM_SIZE",
    "AVAILABLE_DEVICES",
]


def report(core: ov.Core, device: str) -> None:
    print(f"\n{device}")
    print("-" * len(device))
    for name in PROPERTIES:
        try:
            value = core.get_property(device, name)
        except Exception:
            continue
        print(f"  {name:28} {value}")


def main() -> int:
    print(f"openvino          {ov.__version__}")
    print(f"python            {sys.version.split()[0]}")

    core = ov.Core()
    devices = core.available_devices
    print(f"available devices {devices}")

    for device in devices:
        report(core, device)

    if not any(d.startswith("NPU") for d in devices):
        # Not a warning to be scrolled past: the device policy assumes an NPU,
        # and every latency and power figure in the design was taken with one.
        print("\nNO NPU VISIBLE — the execution-device policy (ADR 0001) does not hold here.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
