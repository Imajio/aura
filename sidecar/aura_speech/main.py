"""The real sidecar: speaks the protocol and loads models to answer it.

Run by the Java side, not by hand:

    python -m aura_speech.main [--model DIR] [--device GPU]

Every capability is asked for, and its absence is reported rather than hidden:
without `--voice` the narrator writes text, `speak` answers `SPEECH_UNAVAILABLE`
and `ready` reports `tts: absent`; without `--listen` no microphone is opened,
and with it but no `--wake-model` the refusal is `NO_WAKE_WORD`. `stub.py`
remains the model-free sidecar the Java contract test drives.
"""

import argparse
import pathlib
import sys

from .cascade import FRAME_SECONDS
from .hearing import (DEFAULT_WAKE_FEATURE_MODELS, Microphone, silero_vad, speaker_session,
                      trained_wake_word, whisper)
from .listener import Listener
from .listening import Listening
from .narrator import Narrator
from .protocol import VoiceResources, serve
from .speaker import embed, verifier
from .voice import Voice, silero

DEFAULT_MODEL = pathlib.Path(__file__).resolve().parents[2] / "models" / "qwen3-4b-int4-ov"
DEFAULT_SMALL_MODEL = (pathlib.Path(__file__).resolve().parents[2]
                       / "models" / "qwen3-1.7b-int4-ov")
DEFAULT_WHISPER = (pathlib.Path(__file__).resolve().parents[2]
                   / "models" / "whisper-large-v3-turbo-int8")
DEFAULT_SPEAKER_MODEL = (pathlib.Path(__file__).resolve().parents[2]
                         / "models" / "wespeaker-resnet34" / "voxceleb_resnet34_LM.onnx")
DEFAULT_VOICE = pathlib.Path(__file__).resolve().parents[2] / "voice"
DEFAULT_REFERENCE = DEFAULT_VOICE / "reference.npy"
# The artefact record/enrol/train.wake manage. Independent of --wake-model,
# which is the (possibly different) file --listen loads for live detection.
DEFAULT_WAKE_MODEL = DEFAULT_VOICE / "wake-word.npz"
DEFAULT_NEGATIVE_AUDIO = r"C:\Aura\tts-audition"
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
    parser.add_argument("--listen", action="store_true",
                        help="open the microphone. Off unless asked for: a voice "
                             "assistant that starts recording the room because it "
                             "was installed is not a feature anyone agreed to.")
    parser.add_argument("--whisper", default=str(DEFAULT_WHISPER))
    parser.add_argument("--wake-model", default=None,
                        help="the wake-word model, trained on the owner's voice. "
                             "Listening does nothing without one: an assistant "
                             "that acts on every sentence in the room is not the "
                             "product.")
    parser.add_argument("--speaker-model", default=str(DEFAULT_SPEAKER_MODEL))
    parser.add_argument("--speaker-reference", default=str(DEFAULT_REFERENCE))
    parser.add_argument("--speaker-threshold", type=float, default=0.5)
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

    emitted = _emit_to(sys.stdout)
    hearing = _start_listening(args, cache, emitted) if args.listen else None
    if args.listen and hearing is None:
        emitted({"ev": "error", "code": "NO_WAKE_WORD",
                 "detail": "listening was asked for without a wake-word model; the "
                           "microphone stays shut rather than record a room whose "
                           "speech could never be acted on",
                 "fatal": False})

    # Paths only — nothing here loads a model. record/enrol/train.wake each
    # start a worker thread on request, and every model load stays on that
    # thread, so `ready` below still reaches Java before anything is compiled.
    voice_resources = VoiceResources(
        reference_dir=DEFAULT_VOICE / "reference",
        wake_dir=DEFAULT_VOICE / "wake",
        reference_path=pathlib.Path(args.speaker_reference),
        wake_model_path=(pathlib.Path(args.wake_model) if args.wake_model
                         else DEFAULT_WAKE_MODEL),
        speaker_model_path=pathlib.Path(args.speaker_model),
        feature_models_dir=DEFAULT_WAKE_FEATURE_MODELS,
        negative_dir=DEFAULT_NEGATIVE_AUDIO,
        listening=hearing,
    )

    serve(sys.stdin, sys.stdout, narrate=narrate,
          speak=voice.speak if voice else None,
          cancel=voice.cancel if voice else None,
          devices={"npu": False, "gpu": args.device == "GPU"},
          hearing=hearing is not None,
          voice=voice_resources)
    return 0


def _emit_to(stdout):
    import json
    import threading
    lock = threading.Lock()

    def emit(payload):
        # The listener runs on its own thread while the protocol loop reads
        # stdin. Two writers on one pipe interleave into unparseable lines
        # without this.
        with lock:
            stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
            stdout.flush()

    return emit


def _start_listening(args, cache, emit):
    """Opens the microphone and reports what is said. Only ever called on --listen.

    Returns None when there is no wake-word model. Recording a room whose speech
    can never be acted on is not a degraded feature, it is surveillance with no
    upside, so the microphone stays shut and the reason is reported.
    """
    if not args.wake_model:
        return None

    reference = pathlib.Path(args.speaker_reference)
    model = pathlib.Path(args.speaker_model)
    is_owner = None
    if reference.is_file() and model.is_file():
        session = speaker_session(model)
        is_owner = verifier(reference,
                            embed=lambda audio: embed(audio, session),
                            threshold=args.speaker_threshold)
    else:
        # Said once, at startup, rather than on every utterance: the design makes
        # verification mandatory, so running without it is a state the user has
        # to know they are in.
        #
        # Whichever of the two is actually absent, by name. The reference and the
        # embedding model disable the same stage but for different reasons, and
        # pointing at enrol-speaker.py when the ONNX model is what is missing sends
        # the owner to a script that would exit on the very same file. The event
        # code stays NO_SPEAKER_REFERENCE either way: it is what the tray matches
        # on to raise its alert, and what the manual test plan checks for.
        absent = []
        if not reference.is_file():
            absent.append(f"no enrolled voice at {reference} (run enrol-speaker.py)")
        if not model.is_file():
            absent.append(f"no speaker model at {model} (see sidecar/README.md for "
                          f"the download)")
        emit({"ev": "error", "code": "NO_SPEAKER_REFERENCE",
              "detail": "; ".join(absent) + ". Every voice will be accepted as the "
                        "owner until that is fixed",
              "fatal": False})

    listener = Listener(
        is_speech=silero_vad(),
        recognise=whisper(args.whisper, device=args.device,
                          cache_dir=cache / f"{args.device}-stt"),
        on_utterance=lambda text: emit({"ev": "utterance", "text": text}),
        is_wake=trained_wake_word(args.wake_model),
        on_wake=lambda at: emit({"ev": "wake"}),
        is_owner=is_owner,
        on_rejected=lambda: emit({"ev": "rejected"}),
        on_error=lambda detail: emit({"ev": "error", "code": "RECOGNITION_FAILED",
                                      "detail": detail, "fatal": False}))

    listening = Listening(
        Microphone,
        _feeder(listener),
        on_error=lambda detail: emit({"ev": "error", "code": "MICROPHONE_FAILED",
                                      "detail": detail, "fatal": False}))
    listening.start()
    return listening


def _feeder(listener):
    """Turns the frame stream into the cascade's (frame, time) calls."""
    clock = {"at": 0.0}

    def feed(frame):
        listener.feed(frame, clock["at"])
        clock["at"] += FRAME_SECONDS

    return feed


if __name__ == "__main__":
    raise SystemExit(main())
