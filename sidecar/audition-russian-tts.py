"""Renders the narrator's own phrases with every candidate Russian voice.

This is the machine half of RISK-4. The risk is decided by ear - the owner
listens blind and picks - but nothing could be listened to until the candidates
existed. This produces them, and measures the one part of the question that is
not a matter of taste: **how long synthesis takes**, which comes out of the same
900 ms budget as the narrator's generation (RISK-7).

The phrases are the narrator's real vocabulary, taken from the product
scenarios: short, functional lines about tests and permissions. The risk
register is explicit that this is what gets judged, not literary text -
expressiveness over a long paragraph is not what this component is for.

    .venv-export/Scripts/python.exe audition-russian-tts.py

Writes one WAV per voice per phrase, plus a latency table. Audio is written
outside the repository: it is an evaluation artefact, regenerable, and not
something git history should carry.
"""

import argparse
import pathlib
import statistics
import time
import wave

# The narrator's real lines, from the product scenarios. Two of them carry an
# English technical term inside Russian speech, which is the profile the product
# is actually for and the thing a Russian voice is most likely to mangle.
PHRASES = [
    ("agent-took-it", "Клод взялся, проект backend"),
    ("running-tests", "запускаю тесты"),
    ("one-test-failed", "один тест упал - проверка срока токена"),
    ("all-green", "починил, четыре из четырёх зелёные"),
    ("permission-ask", "Клод хочет выполнить: удалить папку build. Разрешить?"),
    ("declined-timeout", "отклонил по таймауту"),
    ("stopped", "остановил"),
    ("wrong-project", "не понял, в каком проекте - назовите проект"),
    ("agent-failed", "агент упал: не найден модуль авторизации"),
    ("done", "готово, три файла изменены"),
]


def write_wav(path: pathlib.Path, samples, rate: int) -> None:
    import numpy as np
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(rate)
        f.writeframes(pcm.tobytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=r"C:\Aura\tts-audition",
                        help="where to write the samples (outside the repository)")
    parser.add_argument("--rate", type=int, default=24000)
    parser.add_argument("--models", default="v4_ru,v5_ru")
    args = parser.parse_args()

    import torch
    torch.set_num_threads(4)

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    for release in [m.strip() for m in args.models.split(",") if m.strip()]:
        try:
            model, _ = torch.hub.load(repo_or_dir="snakers4/silero-models",
                                      model="silero_tts", language="ru",
                                      speaker=release, trust_repo=True)
        except Exception as e:
            print(f"{release}: unavailable - {type(e).__name__}: {e}")
            continue

        voices = [v for v in model.speakers if v != "random"]
        print(f"\n=== silero {release} - {len(voices)} voices ===")

        for voice in voices:
            timings, spoken = [], 0.0
            folder = out / f"silero-{release}" / voice
            folder.mkdir(parents=True, exist_ok=True)

            for name, text in PHRASES:
                started = time.perf_counter()
                audio = model.apply_tts(text=text, speaker=voice,
                                        sample_rate=args.rate)
                timings.append((time.perf_counter() - started) * 1000.0)
                spoken += len(audio) / args.rate
                write_wav(folder / f"{name}.wav", audio.numpy(), args.rate)

            total = sum(timings)
            print(f"  {voice:10} synthesis p50 {statistics.median(timings):6.0f} ms · "
                  f"{total / 1000:.1f} s for {spoken:.1f} s of speech "
                  f"(x{spoken / (total / 1000):.1f} faster than real time)")

    print(f"\nSamples in {out}")
    print("Listen blind: the register asks which voice is judged, not which name.")
    print("Synthesis latency shares the 900 ms budget with the narrator's own")
    print("generation - see RISK-7 for the other half.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
