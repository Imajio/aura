"""Exports a Whisper checkpoint to OpenVINO IR with INT8 weights.

Run once per model, on a workstation - never by the running sidecar. The result
lands in `models/`, which is ignored: gigabytes, and reproducible from here.

INT8 and not INT4: the NPU on this machine reports FP16 and INT8 only (see
`report-devices.py`). Asking for INT4 would silently fall back or fail at
compile time, which is a worse way to learn the same fact.

    .venv-export/Scripts/python.exe export-whisper.py
    .venv-export/Scripts/python.exe export-whisper.py --model openai/whisper-small
"""

import argparse
import json
import pathlib
import sys
import time


def write_preprocessor_config(out: pathlib.Path) -> None:
    """Bridges a naming change between transformers 5.x and openvino-genai 2026.3.

    transformers 5 writes the feature extractor nested inside `processor_config.json`.
    `WhisperPipeline` still reads a flat `preprocessor_config.json`, and when it finds
    none it assumes 80 mel bins - the large-v2 geometry. large-v3 and turbo use 128,
    so the encoder is then handed `[1, 80, 3000]` while its first convolution expects
    128 input channels, and compilation dies with a channel-count mismatch.

    That failure looks exactly like "this model does not fit the device", on the NPU
    and on the CPU alike. It is not: it is this file being absent. Writing it here
    keeps the next person from retiring a model over a rename.
    """
    processor = out / "processor_config.json"
    target = out / "preprocessor_config.json"
    if target.exists() or not processor.exists():
        return
    config = json.loads(processor.read_text(encoding="utf-8"))
    extractor = config.get("feature_extractor")
    if not extractor:
        return
    target.write_text(json.dumps(extractor, indent=2), encoding="utf-8")
    print(f"wrote      : {target.name} (feature_size {extractor.get('feature_size')})")


def write_openvino_tokenizer(checkpoint: str, out: pathlib.Path) -> None:
    """Exports the tokenizer and detokenizer as OpenVINO models.

    `save_pretrained` leaves a `tokenizer.json`, which `WhisperPipeline` cannot use:
    it wants `openvino_tokenizer.xml` and `openvino_detokenizer.xml`. Without the
    detokenizer the pipeline compiles, runs the model, and then fails at the last
    step with "Detokenizer model has not been provided" - the whole inference
    completed and there is simply no way to turn the tokens back into words.
    """
    import openvino as ov
    from openvino_tokenizers import convert_tokenizer
    from transformers import AutoTokenizer

    tokenizer, detokenizer = convert_tokenizer(
        AutoTokenizer.from_pretrained(checkpoint), with_detokenizer=True)
    ov.save_model(tokenizer, out / "openvino_tokenizer.xml")
    ov.save_model(detokenizer, out / "openvino_detokenizer.xml")
    print("wrote      : openvino_tokenizer.xml, openvino_detokenizer.xml")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default="openai/whisper-large-v3-turbo",
                        help="HuggingFace checkpoint to export")
    parser.add_argument("--out", default=None,
                        help="output directory (default: models/<checkpoint name>-int8)")
    args = parser.parse_args()

    # Imported here, not at module scope: the toolchain is heavy, and a bad
    # argument should fail before torch is dragged into memory.
    from optimum.intel import OVModelForSpeechSeq2Seq
    from optimum.intel import OVWeightQuantizationConfig
    from transformers import AutoProcessor

    name = args.model.rsplit("/", 1)[-1]
    out = pathlib.Path(args.out or (pathlib.Path(__file__).parent.parent / "models" / f"{name}-int8"))
    out.mkdir(parents=True, exist_ok=True)

    print(f"checkpoint : {args.model}")
    print(f"output     : {out}")

    started = time.perf_counter()
    model = OVModelForSpeechSeq2Seq.from_pretrained(
        args.model,
        export=True,
        quantization_config=OVWeightQuantizationConfig(bits=8),
    )
    model.save_pretrained(out)
    AutoProcessor.from_pretrained(args.model).save_pretrained(out)
    write_preprocessor_config(out)
    write_openvino_tokenizer(args.model, out)
    print(f"exported in {time.perf_counter() - started:.1f} s")

    files = sorted(p.name for p in out.iterdir())
    total = sum(p.stat().st_size for p in out.iterdir() if p.is_file())
    print(f"files      : {files}")
    print(f"size       : {total / 1e6:.0f} MB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
