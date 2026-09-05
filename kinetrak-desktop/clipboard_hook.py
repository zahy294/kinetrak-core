"""
clipboard_hook.py — Dev 2 / kinetrak-desktop

Background clipboard watcher implementing the TDD v4.1 contract:
  - runs in its own thread so it never blocks the 60FPS PyOpenGL render loop
  - drops malformed frames
  - SEQ-based ACTION de-duplication (v4.1 S10.1) — executes an ACTION only the
    first time a new SEQ carries it, ignoring the rest of its ~500ms latch window
  - 500ms stale-data timeout, fired ONCE on transition (not every tick)
  - recovers automatically after an Android-side app restart, using the stale
    timeout itself as the reset signal (see _parse_payload)

IMPORTANT — thread safety: state_callback runs on THIS background thread, not
the main render thread. It must only write plain data into a shared state
object/dict. Never call any OpenGL function from inside it or from anything
it triggers — GL contexts are thread-affine, and doing GL work here will
cause hard-to-diagnose crashes or corruption in the render loop. Read the
shared state from the main thread each frame instead.
"""

import time
import threading
import pyperclip


class ClipboardWatcher:
    def __init__(self, state_callback):
        """
        state_callback: a function passed from main.py that takes a dict of
        parsed data. Must be cheap and non-blocking — see thread-safety note
        above. It should merge fields into shared state, not replace it.
        """
        self.state_callback = state_callback
        self.running = False
        self.last_seq = 0
        self.last_acted_seq = 0
        self.last_action = "NULL"
        self.last_update_time = time.time()
        self.is_stale = False  # edge-trigger flag for the stale callback
        self.thread = None
        try:
            self.last_clipboard_content = pyperclip.paste().strip()
        except Exception:
            self.last_clipboard_content = ""

    def start(self):
        """Starts the background clipboard polling thread."""
        self.running = True
        self.thread = threading.Thread(target=self._watch_loop, daemon=True)
        self.thread.start()
        print("[KineTrak] Clipboard watcher thread started.")

    def stop(self):
        """Stops the thread gracefully."""
        self.running = False
        if self.thread:
            self.thread.join()
        print("[KineTrak] Clipboard watcher thread stopped.")

    def _watch_loop(self):
        while self.running:
            try:
                content = pyperclip.paste().strip()

                if content != self.last_clipboard_content and content.startswith("KT|"):
                    self.last_clipboard_content = content
                    self._parse_payload(content)

            except Exception:
                # pyperclip can occasionally throw if the OS locks the clipboard
                # mid-read. In a hackathon, catch and ignore so the thread survives.
                pass

            # Poll at ~60Hz (16ms) to comfortably catch the phone's 15Hz (66ms) writes
            time.sleep(0.016)

            # Stale-data timeout — fire the "tracking lost" callback ONCE on the
            # transition into staleness, not on every loop tick. Firing repeatedly
            # with a partial {"state": 0} dict would otherwise stomp other fields
            # in shared state 60x/sec.
            if time.time() - self.last_update_time > 0.5 and not self.is_stale:
                self.is_stale = True
                self.state_callback({"state": 0})

    def _parse_payload(self, raw_str):
        # Contract: KT|SEQ|STATE|X|Y|Z|QW|QX|QY|QZ|GESTURE_STATE|ACTION
        parts = raw_str.split("|")

        if len(parts) < 12:
            return  # malformed frame, drop instantly

        try:
            seq = int(parts[1])

            # Restart recovery: if we were stale (no valid frame for >500ms) and a
            # new frame just arrived, treat it as a fresh session regardless of its
            # SEQ value — an Android-side restart resets SEQ to a low number, and
            # without this the old high last_seq/last_acted_seq would cause every
            # subsequent frame to be silently dropped forever. A stale gap is a
            # reliable signal for "this is a restart," not random jitter, since a
            # single dropped clipboard read never produces a 500ms gap on its own.
            if self.is_stale:
                self.last_seq = seq - 1
                self.last_acted_seq = 0
                self.last_action = "NULL"
                self.is_stale = False

            # Drop out-of-order or duplicate SEQ updates
            if seq <= self.last_seq and self.last_seq != 0:
                return

            self.last_seq = seq
            self.last_update_time = time.time()

            state = int(parts[2])
            x, y, z = float(parts[3]), float(parts[4]), float(parts[5])
            qw, qx, qy, qz = float(parts[6]), float(parts[7]), float(parts[8]), float(parts[9])
            gesture_state = parts[10]
            raw_action = parts[11]

            # SEQ & State based ACTION de-duplication (TDD v4.1 S10.1)
            # Only trigger an action the FIRST time it appears in a new latch window.
            action_to_execute = "NULL"
            if raw_action != "NULL":
                if raw_action != self.last_action or (seq - self.last_acted_seq > 10):
                    self.last_acted_seq = seq
                    self.last_action = raw_action
                    action_to_execute = raw_action
            else:
                self.last_action = "NULL"

            parsed_data = {
                "seq": seq,
                "state": state,
                "pos": [x, y, z],
                "rot": [qw, qx, qy, qz],
                "gesture_state": gesture_state,
                "action": action_to_execute,
            }

            self.state_callback(parsed_data)

        except ValueError:
            # Drop frame if float/int conversion fails due to corrupted clipboard text
            return