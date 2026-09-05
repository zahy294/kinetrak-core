"""
KineTrak Desktop — Low-Latency Shared Clipboard Telemetry Bridge
Ingests 15Hz 6-DOF spatial pose and latched discrete actions via Vivo Office Kit.
Adheres strictly to KineTrak Technical Design Doc v4.2.
"""

import time
import threading
import pyperclip


class ClipboardWatcher:
    def __init__(self, state_callback):
        self.state_callback = state_callback
        self.running = False
        self.last_seq = 0
        self.last_acted_seq = 0
        self.last_action = "NULL"  # Tracks active latched action token
        self.last_update_time = time.time()
        self.is_stale = False
        self.thread = None
        try:
            self.last_raw_text = pyperclip.paste()
        except Exception:
            self.last_raw_text = ""

    def start(self):
        """Starts the high-speed polling daemon thread."""
        self.running = True
        self.thread = threading.Thread(target=self._poll_loop, daemon=True)
        self.thread.start()
        print("[KineTrak] Clipboard watcher thread started.")

    def stop(self):
        """Stops clipboard polling cleanly."""
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
        print("[KineTrak] Clipboard watcher thread stopped.")

    def _poll_loop(self):
        """Polls the OS clipboard at ~60Hz to catch 15Hz mobile updates with minimal jitter."""
        while self.running:
            try:
                raw_text = pyperclip.paste()
                # Edge-triggered ingestion: only evaluate when clipboard content changes
                if raw_text and raw_text != self.last_raw_text:
                    self.last_raw_text = raw_text
                    if raw_text.startswith("KT|"):
                        self._parse_payload(raw_text.strip())
            except Exception:
                pass  # Suppress temporary clipboard access collisions

            # 500ms Stale Watchdog
            if not self.is_stale and (time.time() - self.last_update_time > 0.5):
                self.is_stale = True
                self.state_callback({"state": 0, "stale": True})

            time.sleep(0.016)  # ~60Hz polling interval

    def _parse_payload(self, text):
        parts = text.split("|")
        if len(parts) != 12:
            return  # Drop malformed packets

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

            state = int(parts[2])
            x, y, z = float(parts[3]), float(parts[4]), float(parts[5])
            qw, qx, qy, qz = float(parts[6]), float(parts[7]), float(parts[8]), float(parts[9])
            gesture_state = parts[10]
            raw_action = parts[11]

            # SEQ & State-based ACTION de-duplication (TDD v4.2 §4.2)
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
            pass  # Drop corrupted numbers
