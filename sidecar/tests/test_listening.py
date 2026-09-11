"""The listening thread: starting, letting go of the microphone, taking it back.

A fake microphone stands in for the real one. What is being tested is the
handover - the sidecar has to be able to release the capture device so a
recording can use it, and to be sure it has been released before it says so.
"""

import pathlib
import sys
import threading
import time

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from aura_speech.cascade import FRAME_SECONDS  # noqa: E402
from aura_speech.listening import Listening  # noqa: E402
from aura_speech.main import _feeder  # noqa: E402


class FakeMicrophone:
    """Counts how many are open at once, which is the thing that must never be two."""

    open_now = 0
    opened_total = 0
    lock = threading.Lock()

    def __enter__(self):
        with FakeMicrophone.lock:
            FakeMicrophone.open_now += 1
            FakeMicrophone.opened_total += 1
        return self

    def frames(self):
        while True:
            yield np.zeros(512, dtype=np.float32)
            time.sleep(0.001)

    def __exit__(self, *exc):
        with FakeMicrophone.lock:
            FakeMicrophone.open_now -= 1
        return False


def fresh():
    FakeMicrophone.open_now = 0
    FakeMicrophone.opened_total = 0
    fed = []
    listening = Listening(FakeMicrophone, fed.append)
    return listening, fed


def wait_until(predicate, timeout=3.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.01)
    return False


def test_nothing_is_opened_until_it_is_started():
    listening, fed = fresh()
    try:
        time.sleep(0.1)
        assert FakeMicrophone.opened_total == 0
        assert fed == []
    finally:
        listening.close()


def test_starting_opens_the_microphone_and_feeds_frames():
    listening, fed = fresh()
    try:
        listening.start()
        assert wait_until(lambda: len(fed) > 3), "no frames arrived"
        assert FakeMicrophone.open_now == 1
    finally:
        listening.close()


def test_pausing_releases_the_device_before_it_returns():
    # The whole reason this class exists: a recording is about to open the same
    # device, and "paused" has to mean the device is actually free.
    listening, fed = fresh()
    try:
        listening.start()
        assert wait_until(lambda: len(fed) > 3)

        assert listening.pause() is True
        assert FakeMicrophone.open_now == 0
        assert listening.active() is False

        settled = len(fed)
        time.sleep(0.1)
        assert len(fed) == settled, "frames kept arriving after pause returned"
    finally:
        listening.close()


def test_resuming_opens_it_again():
    listening, fed = fresh()
    try:
        listening.start()
        assert wait_until(lambda: len(fed) > 3)
        assert listening.pause() is True

        listening.resume()

        assert wait_until(lambda: FakeMicrophone.opened_total == 2)
        assert listening.active() is True
    finally:
        listening.close()


def test_a_microphone_that_throws_is_reported_and_does_not_end_the_thread():
    # Deaf, not dead: the sidecar still narrates and still speaks.
    attempts = []
    complaints = []

    class Flaky:
        def __enter__(self):
            attempts.append(True)
            if len(attempts) == 1:
                raise RuntimeError("device busy")
            return self

        def frames(self):
            while True:
                yield np.zeros(512, dtype=np.float32)
                time.sleep(0.001)

        def __exit__(self, *exc):
            return False

    fed = []
    listening = Listening(Flaky, fed.append, on_error=complaints.append)
    try:
        listening.start()
        assert wait_until(lambda: complaints and len(fed) > 3, timeout=5.0)
        assert "device busy" in complaints[0]
    finally:
        listening.close()


def test_pausing_after_a_retry_still_waits_for_the_device_to_close():
    # A retry after a device error loops back to the top of _run with _wanted
    # already set, so it never goes through start() again. If idle were only
    # re-armed in start(), it would still read True here from the failed first
    # attempt's own finally, for the whole life of the retry's live session -
    # not a narrow window, but true for as long as that session runs.
    open_now = {"n": 0}
    attempts = []

    class FlakyOnce:
        def __enter__(self):
            attempts.append(True)
            if len(attempts) == 1:
                raise RuntimeError("device busy")
            open_now["n"] += 1
            return self

        def frames(self):
            while True:
                yield np.zeros(512, dtype=np.float32)
                time.sleep(0.001)

        def __exit__(self, *exc):
            open_now["n"] -= 1
            return False

    fed = []
    listening = Listening(FlakyOnce, fed.append)
    try:
        listening.start()
        assert wait_until(lambda: len(attempts) > 1 and len(fed) > 3)

        assert listening.pause() is True
        assert open_now["n"] == 0
    finally:
        listening.close()


def test_closing_stops_everything():
    listening, fed = fresh()
    listening.start()
    assert wait_until(lambda: len(fed) > 3)

    listening.close()

    assert wait_until(lambda: FakeMicrophone.open_now == 0)
    settled = len(fed)
    time.sleep(0.1)
    assert len(fed) == settled


def test_the_feeder_advances_the_clock_one_frame_at_a_time():
    # A clock that stops advancing breaks the segmenter's tail and the wake
    # word's arm window, and every existing test would still pass.
    heard = []

    class Recorder:
        def feed(self, frame, when):
            heard.append(when)

    feed = _feeder(Recorder())
    for _ in range(3):
        feed(object())

    assert heard == [0.0, FRAME_SECONDS, 2 * FRAME_SECONDS]
