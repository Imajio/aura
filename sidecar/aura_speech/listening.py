"""The capture thread, as something that can be asked to let go.

Until now listening was a daemon thread with an endless loop: correct while the
only question was whether to start it. Recording the owner's voice asks a second
question - can the microphone be borrowed and given back - and a thread with no
answer to that is a thread that has to be killed and rebuilt, or a second stream
opened on a device that may or may not allow one.

`pause()` returns only once the device is closed. That is the whole point of the
class: the caller is about to open the same device, and "paused" has to be a fact
rather than a request.
"""

import threading


class Listening:
    """Runs the capture loop and hands the device back on request.

    The microphone is a factory rather than an instance because the device is
    reopened on every resume: holding a closed stream open across a pause is what
    the pause exists to avoid.
    """

    def __init__(self, microphone_factory, feed, on_error=None):
        self._microphone = microphone_factory
        self._feed = feed
        self._on_error = on_error or (lambda message: None)
        self._wanted = threading.Event()
        self._idle = threading.Event()
        self._idle.set()
        self._closed = threading.Event()
        self._thread = threading.Thread(target=self._run, name="aura-listening",
                                        daemon=True)
        self._thread.start()

    def start(self) -> None:
        # Cleared here too, synchronously in the caller's thread, not only in
        # _run: a pause() landing in the gap between this call and the capture
        # thread waking from wait() must not see idle still set from the
        # previous cycle and return True while the device is about to reopen.
        # _run's own clear (below) cannot cover this gap because it runs later,
        # on the capture thread, after the wake-up has already happened.
        self._idle.clear()
        self._wanted.set()

    def resume(self) -> None:
        self.start()

    def active(self) -> bool:
        return self._wanted.is_set() and not self._closed.is_set()

    def pause(self, timeout: float = 5.0) -> bool:
        """Stops capturing and waits for the device to be closed.

        Returns False if it was still not closed after `timeout` seconds, and the
        caller must then not open the device: a recording started on top of a
        stream that refused to die produces silence, or an error from the driver,
        and either way the owner is told to speak into nothing. The same False,
        and the same required response, also covers a start() that lands during
        the pause: the device is about to be open rather than refusing to close,
        but the caller cannot tell which from here, and does not need to.
        """
        self._wanted.clear()
        return self._idle.wait(timeout)

    def close(self) -> None:
        self._closed.set()
        self._wanted.clear()
        # Released even if nothing was ever started, so a close during startup
        # does not wait out the whole timeout.
        self._wanted.set()
        self._thread.join(timeout=5.0)

    def _run(self) -> None:
        while not self._closed.is_set():
            self._wanted.wait()
            if self._closed.is_set():
                return
            # Also cleared here, not only in start(): a retry after a device
            # error loops back to here with _wanted already set, so wait()
            # above returns without start() ever being called again, and
            # idle would otherwise still read True from the failed attempt's
            # finally below, all through the retry's own capture session.
            self._idle.clear()
            try:
                with self._microphone() as microphone:
                    for frame in microphone.frames():
                        if not self._wanted.is_set() or self._closed.is_set():
                            break
                        self._feed(frame)
            except Exception as e:
                # Deaf, not dead. The sidecar still narrates and still speaks, and
                # a device that came back after being unplugged is common enough
                # to be worth surviving.
                self._on_error(f"{type(e).__name__}: {e}"[:200])
                # Without this the loop spins on a device that keeps failing.
                self._closed.wait(0.5)
            finally:
                self._idle.set()
