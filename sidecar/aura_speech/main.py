"""The real sidecar: speaks the protocol and loads models to answer it.

Run by the Java side, not by hand:

    python -m aura_speech.main [--model DIR] [--device GPU]

M2 in progress. Narration works; speech and recognition do not yet, and say so
rather than pretending — `speak` answers `SPEECH_UNAVAILABLE`, and `ready`
reports `tts: absent`. `stub.py` remains the model-free sidecar the Java
contract test drives.
"""

import argparse
import pathlib
import sys

from .narrator import Narrator
from .protocol import serve
from .voice import Voice, silero

DEFAULT_MODEL = pathlib.Path(__file__).resolve().parents[2] / "models" / "qwen3-4b-int4-ov"
DEFAULT_SMALL_MODEL = (pathlib.Path(__file__).resolve().parents[2]
                       / "models" / "qwen3-1.7b-int4-ov")
DEFAULT_CACHE = pathlib.Path(__file__).resolve().parents[2] / ".ov_cache"
MAX_NARRATION_TOKENS = 40


def build_narrator(model: pathlib.Path, device: str, cache: pathlib.Path):
    """Returns `generate(prompt) -> str`, loading the model on the first call.

    Lazy on purpose. Compiling for the iGPU takes tens of seconds, and the
    protocol's `ready` event has to reach Java before that — otherwise the tray
    cannot tell a sidecar that is warming up from one that died on startup.
    """
    state = {}

    def generate(prompt: str) -> str:
        if "pipeline" not in state:
            import openvino_genai

            pipeline = openvino_genai.LLMPipeline(str(model), device=device,
                                                  CACHE_DIR=str(cache / f"{device}-narrator"))
            config = pipeline.get_generation_config()
            config.max_new_tokens = MAX_NARRATION_TOKENS
            config.do_sample = False  # a narrator that paraphrases itself run to
                                      # run is a narrator nobody learns to trust
            state["pipeline"] = pipeline
            state["config"] = config
        return str(state["pipeline"].generate(prompt, state["config"]))

    return generate


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default=str(DEFAULT_MODEL))
    parser.add_argument("--small-model", default=str(DEFAULT_SMALL_MODEL),
                        help="the model the quiet level uses. A quiet narrator says "
                             "less about simpler things and does not need the larger "
                             "one; it is also what makes running on battery workable.")
    parser.add_argument("--device", default="GPU")
    parser.add_argument("--cache", default=str(DEFAULT_CACHE))
    parser.add_argument("--voice", default=None,
                        help="Silero voice to speak with. Without it the sidecar "
                             "narrates in text and answers SPEECH_UNAVAILABLE to "
                             "speak, so that starting it never makes noise by "
                             "accident.")
    args = parser.parse_args(argv)

    # Pin both pipes to UTF-8. A piped child on Windows inherits the console code
    # page, which here is cp1252 — a codec with no mapping for Cyrillic at all.
    # Left alone, the first Russian narration line raises UnicodeEncodeError and
    # kills the sidecar, with the traceback going to a stderr stream the client
    # logs below its default level. The stub carries the same two lines and the
    # same reason.
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    sys.stdin.reconfigure(encoding="utf-8")

    cache = pathlib.Path(args.cache)
    generators = {}

    def generator_for(verbosity: str):
        """The quiet level narrates with the smaller model, as the registry says."""
        wanted = pathlib.Path(args.small_model if verbosity == "quiet" else args.model)
        if not wanted.is_dir():
            raise FileNotFoundError(f"no narrator model at {wanted}")
        if wanted not in generators:
            generators[wanted] = build_narrator(wanted, args.device, cache)
        return generators[wanted]

    def narrate(lines: list[str], profile: str, verbosity: str) -> str:
        return Narrator(generator_for(verbosity), profile=profile).line(lines)

    voice = Voice(silero(), name=args.voice) if args.voice else None

    serve(sys.stdin, sys.stdout, narrate=narrate,
          speak=voice.speak if voice else None,
          cancel=voice.cancel if voice else None,
          devices={"npu": False, "gpu": args.device == "GPU"})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
