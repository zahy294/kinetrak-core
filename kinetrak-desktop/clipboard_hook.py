"""
clipboard_hook.py — Dev 2 / kinetrak-desktop

Background clipboard watcher implementing the TDD v4.2 contract:
  - runs in its own daemon thread so it never blocks the 60FPS PyOpenGL render loop
  - drops malformed frames (pipe format requires 12 fields, JSON fallback must be valid dict)
  - SEQ-based ACTION de-duplication (v4.2 S10.1) — executes an ACTION only on state
    transitions or sequence gaps >10 ticks, ignoring the rest of its ~500ms latch window
  - 500ms stale-data timeout, fired ONCE on transition (edge-triggered)
  - recovers automatically after an Android-side app restart, using the stale
    timeout state itself as the reset signal (see _parse_payload)
  - suppresses temporary Win32 clipboard locking collisions cleanly

IMPORTANT — thread safety: state_callback runs on THIS background thread, not
the main render thread. It must only write plain data into a shared state
object/dict. Never call any OpenGL function from inside it or from anything
it triggers — GL contexts are thread-affine, and doing GL work here will
cause hard-to-diagnose crashes or corruption in the render loop. Read the
shared state from the main thread each frame instead.
"""

import json
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
        self.last_action = "NULL"  # Tracks active latched action token
        self.last_update_time = time.time()
        self.is_stale = False  # Edge-trigger flag for the stale callback
        self.thread = None
        try:
            self.last_clipboard_content = pyperclip.paste().strip()
        except Exception:
            self.last_clipboard_content = ""

    def start(self):
        """Starts the background clipboard polling thread."""
        self.running = True
        self.thread = threading.Thread(target=self._poll_loop, daemon=True)
        self.thread.start()
        print("[KineTrak] Clipboard watcher thread started.")

    def stop(self):
        """Stops the thread gracefully."""
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
        print("[KineTrak] Clipboard watcher thread stopped.")

    def _poll_loop(self):
        """Polls the OS clipboard at ~60Hz to catch 15Hz mobile updates with minimal jitter."""
        while self.running:
            try:
                raw_text = pyperclip.paste()
                if raw_text:
                    content = raw_text.strip()
                    if content != self.last_clipboard_content and (content.startswith("KT|") or content.startswith("{")):
                        self.last_clipboard_content = content
                        self._parse_payload(content)
            except Exception:
                # pyperclip can occasionally throw if the Win32 OS locks the clipboard
                # mid-read during high frequency operations. Catch and ignore cleanly.
                pass

            # 500ms Stale Watchdog
            if not self.is_stale and (time.time() - self.last_update_time > 0.5):
                self.is_stale = True
                self.state_callback({"state": 0, "stale": True})

            time.sleep(0.016)  # ~60Hz polling interval

    def _parse_payload(self, text: str):
        if not text:
            return

        text = text.strip()

        # Primary Contract: KT|SEQ|STATE|X|Y|Z|QW|QX|QY|QZ|GESTURE_STATE|ACTION
        if text.startswith("KT|"):
            parts = text.split("|")
            if len(parts) != 12:
                return  # Malformed frame, drop instantly

            try:
                seq = int(parts[1])

                # Restart recovery: re-establish baseline when recovering from a stale gap
                if self.is_stale:
                    self.last_seq = seq - 1
                    self.last_acted_seq = 0
                    self.last_action = "NULL"
                    self.is_stale = False

                # Drop out-of-order or duplicate SEQ packets
                if seq <= self.last_seq and self.last_seq != 0:
                    return

                self.last_seq = seq
                self.last_update_time = time.time()
                self.is_stale = False

                state = int(parts[2])
                x, y, z = float(parts[3]), float(parts[4]), float(parts[5])
                qw, qx, qy, qz = float(parts[6]), float(parts[7]), float(parts[8]), float(parts[9])
                gesture_state = parts[10]
                raw_action = parts[11]

                # SEQ & State based ACTION de-duplication (TDD v4.2 §4.2)
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
                    "stale": False,
                }

                self.state_callback(parsed_data)

            except (ValueError, IndexError):
                # Drop frame if float/int conversion fails due to corrupted text
                return

        # Legacy JSON Fallback
        elif text.startswith("{"):
            try:
                data = json.loads(text)
                if not isinstance(data, dict):
                    return

                seq = int(data.get("seq", self.last_seq + 1))

                # Restart recovery: re-establish baseline when recovering from a stale gap
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
                self.is_stale = False

                state = int(data.get("state", 1))
                pos = data.get("pos", [0.0, 0.0, 0.0])
                rot = data.get("rot", [1.0, 0.0, 0.0, 0.0])
                gesture_state = str(data.get("gesture_state", "NULL"))
                raw_action = str(data.get("action", "NULL"))

                # SEQ & State based ACTION de-duplication
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
                    "pos": [float(pos[0]), float(pos[1]), float(pos[2])] if isinstance(pos, (list, tuple)) and len(pos) == 3 else [0.0, 0.0, 0.0],
                    "rot": [float(rot[0]), float(rot[1]), float(rot[2]), float(rot[3])] if isinstance(rot, (list, tuple)) and len(rot) == 4 else [1.0, 0.0, 0.0, 0.0],
                    "gesture_state": gesture_state,
                    "action": action_to_execute,
                    "stale": False,
                }

                self.state_callback(parsed_data)

            except (json.JSONDecodeError, ValueError, TypeError, IndexError):
                return