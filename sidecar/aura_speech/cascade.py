"""The cheap end of the audio cascade.

The point of a cascade is **not to run neural networks when there is nobody to
talk.** In a quiet room only what is here should be alive: arithmetic over
frames on the CPU, costing a fraction of a core, with the VAD, the wake word,
the speaker check and recognition all still asleep behind it.

Time is passed in rather than read. The noise floor is a thirty-second window,
and an estimator that consulted the clock itself could only be tested by
waiting thirty seconds.
"""

import numpy as np

RATE = 16000
FRAME = 512                       # 32 ms — the window Silero VAD expects
FRAME_SECONDS = FRAME / RATE


class NoiseFloor:
    """A rolling estimate of what the room sounds like when nobody is speaking.

    Not a maximum and not a mean. Taking the loudest thing heard would raise the
    floor until nothing ever opened the gate again; taking the mean would drag it
    up with every sentence spoken. A low quantile of recent frames is what a
    quiet room actually is, and it comes back down when a fan is switched off.
    """

    def __init__(self, window_seconds: float = 30.0, margin_db: float = 6.0,
                 quantile: float = 0.2, initial: float = 0.01):
        self._window = window_seconds
        self._gain = 10.0 ** (margin_db / 20.0)
        self._quantile = quantile
        self._initial = initial
        self._samples: list[tuple[float, float]] = []

    def update(self, rms: float, at: float) -> None:
        self._samples.append((at, float(rms)))
        cutoff = at - self._window
        # A window measured in time, not in frames: the caller may feed us at any
        # rate, and thirty seconds has to mean thirty seconds.
        while self._samples and self._samples[0][0] < cutoff:
            self._samples.pop(0)

    def level(self) -> float:
        if not self._samples:
            return self._initial
        return float(np.quantile([rms for _, rms in self._samples], self._quantile))

    def threshold(self) -> float:
        """The floor plus a margin. Never zero: a silent start must not open the gate."""
        return max(self.level(), 1e-6) * self._gain


class EnergyGate:
    """Stage 0: is there anything here worth waking a network for?

    Energy alone, no model. It is wrong often — a door closing opens it — and
    that is acceptable, because everything behind it is cheap enough to run on a
    false positive and expensive enough to matter on every frame.
    """

    def __init__(self, floor: NoiseFloor):
        self._floor = floor

    def open(self, frame: np.ndarray, at: float) -> bool:
        rms = float(np.sqrt(np.mean(np.square(frame, dtype=np.float64))))
        opened = rms > self._floor.threshold()
        # Updated with every frame, including loud ones. The quantile is what
        # keeps speech from becoming the floor; excluding loud frames outright
        # would leave a room that got permanently noisier stuck at its old floor.
        self._floor.update(rms, at)
        return opened


class Segmenter:
    """Turns a stream of speech / not-speech decisions into whole utterances.

    Two rules, both from the design and both about the same thing — what counts
    as the end of what somebody said.

    **A tail of half a second.** People pause inside sentences; cutting at the
    first silence turns one command into two halves, and neither half means
    anything. The tail travels with the utterance, because recognition hears the
    end of a word better with a little silence after it.

    **A ceiling of thirty seconds.** Otherwise a microphone left open on a
    television produces a recording rather than a command.
    """

    def __init__(self, tail_seconds: float = 0.5, max_seconds: float = 30.0,
                 rate: int = RATE):
        self._tail = tail_seconds
        self._max_samples = int(max_seconds * rate)
        self._frames: list[np.ndarray] = []
        self._speaking = False
        self._last_speech_at = 0.0

    def push(self, frame: np.ndarray, is_speech: bool, at: float):
        """Returns a finished utterance, or None while one is still being said."""
        if is_speech:
            if not self._speaking:
                self._speaking = True
                self._frames = []
            self._last_speech_at = at

        if not self._speaking:
            return None

        self._frames.append(frame)

        if sum(len(f) for f in self._frames) >= self._max_samples:
            return self._finish()

        if not is_speech and at - self._last_speech_at >= self._tail:
            return self._finish()

        return None

    def _finish(self) -> np.ndarray:
        utterance = np.concatenate(self._frames) if self._frames else np.zeros(0, np.float32)
        self._frames = []
        self._speaking = False
        return utterance[:self._max_samples]
