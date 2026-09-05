"""The models and the microphone behind the cascade.

Kept apart from `listener.py` on purpose: the wiring is what goes wrong and is
worth testing, while what is here is loading somebody else's model and opening a
device. Both are lazy, so a sidecar that never listens never pays for either.
"""

import pathlib

import numpy as np

from .cascade import FRAME, RATE

DEFAULT_CAPTURE_RATE = 48000

# openWakeWord's own release assets — melspectrogram.onnx and embedding_model.onnx
# — placed here by hand, not by pip: the openwakeword package pulls in scipy and
# scikit-learn to train its own classifiers, about 150 MB, into a sidecar meant to
# sit idle all day. `models/` is git-ignored like every other model in this
# project, so this is a one-time manual step, not something cloning the repository
# provides.
#
# `train-wake-word.py`'s DEFAULT_FEATURE_MODELS points at this same directory,
# computed independently through its own `parents[N]`. Moving either file
# changes what N needs to be; the two are not derived from one another, so a
# move that updates one and forgets the other would fail silently rather than
# with an ImportError.
DEFAULT_WAKE_FEATURE_MODELS = pathlib.Path(__file__).resolve().parents[2] / "models" / "openwakeword"


def silero_vad(threshold: float = 0.5):
    """Returns `is_speech(frame) -> bool`, loading the model on the first call.

    ONNX rather than the torch build: it needs no torchaudio, it shares the
    onnxruntime the wake word already requires, and measured on this machine it
    answers in 0.27 ms a frame against the millisecond the design budgeted.
    """
    state = {}

    def is_speech(frame: np.ndarray) -> bool:
        if "model" not in state:
            import torch
            from silero_vad import load_silero_vad
            torch.set_num_threads(2)
            state["torch"] = torch
            state["model"] = load_silero_vad(onnx=True)
        torch = state["torch"]
        # copy(): the frame is a view into the capture buffer, and torch will not
        # take a non-owning array.
        probability = state["model"](torch.from_numpy(frame.copy()), RATE).item()
        return probability > threshold

    return is_speech


def whisper(model_dir, device: str = "GPU", cache_dir=None, language: str = "<|ru|>"):
    """Returns `recognise(audio) -> str`, loading the model on the first call."""
    state = {}

    def recognise(audio: np.ndarray) -> str:
        if "pipeline" not in state:
            import openvino_genai
            pipeline = openvino_genai.WhisperPipeline(
                str(model_dir), device=device,
                **({"CACHE_DIR": str(cache_dir)} if cache_dir else {}))
            config = pipeline.get_generation_config()
            config.language = language
            config.task = "transcribe"
            state["pipeline"] = pipeline
            state["config"] = config
        return str(state["pipeline"].generate(audio, state["config"]))

    return recognise


class Microphone:
    """Frames from the default input device, at the rate the cascade expects.

    **Opening this records the room.** Nothing here starts on its own: the
    sidecar opens it only when told to, because a voice assistant that begins
    listening because it was installed is not a feature anyone agreed to.
    """

    def __init__(self, device_index=None, frame=FRAME):
        self._device_index = device_index
        self._frame = frame
        self._audio = None
        self._stream = None
        self._rate = DEFAULT_CAPTURE_RATE

    def __enter__(self):
        import pyaudiowpatch as pyaudio
        self._audio = pyaudio.PyAudio()
        index = self._device_index
        if index is None:
            index, self._rate = _wasapi_input(self._audio, pyaudio)
        else:
            self._rate = int(self._audio.get_device_info_by_index(index)["defaultSampleRate"])

        # Read in whole output frames' worth of input, so a block always converts
        # to an exact number of 16 kHz frames.
        self._block = max(1, round(self._frame * self._rate / RATE))
        self._stream = self._audio.open(
            format=pyaudio.paFloat32,
            channels=1,
            rate=self._rate,
            input=True,
            input_device_index=index,
            frames_per_buffer=self._block)
        return self

    def frames(self):
        """Yields 512-sample frames at 16 kHz until the stream is closed."""
        while self._stream is not None and self._stream.is_active():
            raw = self._stream.read(self._block, exception_on_overflow=False)
            yield to_16k(np.frombuffer(raw, dtype=np.float32), self._rate, self._frame)

    def __exit__(self, *exc):
        if self._stream is not None:
            self._stream.stop_stream()
            self._stream.close()
            self._stream = None
        if self._audio is not None:
            self._audio.terminate()
            self._audio = None


def to_16k(block: np.ndarray, rate: int, frame: int = FRAME) -> np.ndarray:
    """Converts one captured block to exactly `frame` samples at 16 kHz.

    An integer ratio is averaged, which is a decimating filter and both cheaper
    and less wrong than taking every nth sample. Anything else is interpolated:
    44.1 kHz is 2.75 times 16 kHz, and a microphone that reports it is common
    enough that assuming 48 kHz would simply produce distorted audio — audio
    that a VAD would still answer about, confidently and wrongly.
    """
    if rate == RATE:
        return block[:frame].astype(np.float32)
    if rate % RATE == 0:
        step = rate // RATE
        usable = (len(block) // step) * step
        return block[:usable].reshape(-1, step).mean(axis=1).astype(np.float32)[:frame]
    wanted = np.linspace(0, len(block) - 1, frame)
    return np.interp(wanted, np.arange(len(block)), block).astype(np.float32)


def _wasapi_input(audio, pyaudio):
    """The WASAPI input the design asks for, rather than whatever MME offers.

    The default input device on this machine is MME at 44.1 kHz; the WASAPI one
    is the same microphone at 48 kHz, and WASAPI is what the design specifies
    for its event-driven capture and, later, for loopback.
    """
    try:
        api = audio.get_host_api_info_by_type(pyaudio.paWASAPI)
        index = api["defaultInputDevice"]
        if index is not None and index >= 0:
            info = audio.get_device_info_by_index(index)
            return index, int(info["defaultSampleRate"])
    except Exception:
        pass
    info = audio.get_default_input_device_info()
    return info["index"], int(info["defaultSampleRate"])


def trained_wake_word(model_path, threshold: float = 0.5):
    """Returns `is_wake(frame) -> bool` for the model trained on the owner's voice.

    The classifier is the owner's, produced by `train-wake-word.py` from their own
    recordings. No pretrained stand-in is offered: shipping somebody else's wake
    word would make the cascade look finished while listening for the wrong thing.

    The two feature models behind it are loaded straight through onnxruntime
    rather than through the `openwakeword` package — see
    `DEFAULT_WAKE_FEATURE_MODELS` for why — and `wake_features` in `wake.py` is
    the same function `train-wake-word.py` calls, so the classifier is scored on
    exactly the numbers it was trained on.
    """
    from .wake import WakeClassifier, wake_features

    state = {"buffer": np.zeros(0, dtype=np.float32)}

    def is_wake(frame: np.ndarray) -> bool:
        if "classifier" not in state:
            import onnxruntime as ort
            # All three into locals, and into `state` only once all three exist.
            # Assigning them as they are built leaves the guard above satisfied by
            # a half-initialised state: every later frame then skips this block
            # and dies on `KeyError: 'melspec'`, roughly every sixteen speech
            # frames for the rest of the session, naming neither the wake word nor
            # the file that was missing. Both sessions are built on the first
            # speech frame of live listening rather than at startup, so a
            # transient failure lands exactly here — and leaving `state` empty is
            # what lets the next frame retry instead.
            classifier = WakeClassifier.load(model_path)
            melspec = ort.InferenceSession(
                str(DEFAULT_WAKE_FEATURE_MODELS / "melspectrogram.onnx"),
                providers=["CPUExecutionProvider"])
            embedding = ort.InferenceSession(
                str(DEFAULT_WAKE_FEATURE_MODELS / "embedding_model.onnx"),
                providers=["CPUExecutionProvider"])
            state["classifier"] = classifier
            state["melspec"] = melspec
            state["embedding"] = embedding
        state["buffer"] = np.concatenate([state["buffer"], frame])
        if len(state["buffer"]) < RATE:
            return False
        window = state["buffer"][-RATE:]
        state["buffer"] = state["buffer"][-RATE // 2:]
        # int16 magnitude: see wake_features' docstring for why this and
        # train-wake-word.py both scale by it before calling that function.
        features = wake_features(window * 32767.0, state["melspec"], state["embedding"])
        return state["classifier"].score(features) > threshold

    return is_wake


def speaker_session(model_path):
    """The WeSpeaker ResNet-34 embedding model, as an ONNX session.

    CPU on purpose: it runs once per utterance, for about fifteen milliseconds,
    and the iGPU is busy with recognition and the narrator.
    """
    import onnxruntime as ort
    return ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
