"""An 80-bin log-mel filterbank: what a speaker embedding model eats.

Written out in numpy rather than pulled in with torchaudio. The dependency is
about a hundred megabytes in a process meant to sit idle all day, and what it
would provide is thirty lines of arithmetic that never changes.
"""

import numpy as np

RATE = 16000
N_MELS = 80
WINDOW_MS = 25
HOP_MS = 10
N_FFT = 512


def _mel_filters(n_mels: int, rate: int, n_fft: int) -> np.ndarray:
    def to_mel(hz):
        return 2595.0 * np.log10(1.0 + hz / 700.0)

    def to_hz(mel):
        return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)

    edges = to_hz(np.linspace(to_mel(20.0), to_mel(rate / 2), n_mels + 2))
    bins = np.floor((n_fft + 1) * edges / rate).astype(int)
    filters = np.zeros((n_mels, n_fft // 2 + 1), dtype=np.float32)
    for i in range(n_mels):
        left, centre, right = bins[i], bins[i + 1], bins[i + 2]
        if centre > left:
            filters[i, left:centre] = (np.arange(left, centre) - left) / (centre - left)
        if right > centre:
            filters[i, centre:right] = (right - np.arange(centre, right)) / (right - centre)
    return filters


def fbank(samples: np.ndarray, rate: int = RATE, n_mels: int = N_MELS) -> np.ndarray:
    """Log-mel features, `[frames, n_mels]`, mean-normalised over time."""
    window = int(rate * WINDOW_MS / 1000)
    hop = int(rate * HOP_MS / 1000)
    if len(samples) < window:
        # Padding would produce the features of silence, and a speaker check
        # would then compare somebody to nothing, confidently.
        raise ValueError(f"need at least {window} samples, got {len(samples)}")

    signal = np.asarray(samples, dtype=np.float32)
    signal = signal - signal.mean()
    frames = np.lib.stride_tricks.sliding_window_view(signal, window)[::hop]
    spectrum = np.abs(np.fft.rfft(frames * np.hamming(window), n=N_FFT)) ** 2
    feats = np.log(np.maximum(spectrum @ _mel_filters(n_mels, rate, N_FFT).T, 1e-10))
    # Cepstral mean normalisation: what survives is the shape of the voice
    # rather than the gain of the microphone that recorded it.
    return (feats - feats.mean(axis=0)).astype(np.float32)
