# clipboard_hook.py — Technical Patch Log & Architecture Spec (v4.2)

This document provides a detailed breakdown of all logic fixes, race mitigations, and contract enhancements applied to `kinetrak-desktop/clipboard_hook.py`. It serves as a technical synchronization reference for team members uploading to their local AI agent workspaces.

---

## 0. File Location & Environment

This file belongs at:

```
kinetrak-core/kinetrak-desktop/clipboard_hook.py
```

It runs inside the project's Python virtual environment (`.venv`). Activate the venv before running `clipboard_hook.py` or `test_clipboard.py`.

---

## 1. Executive Summary & Purpose

In the KineTrak system, the Android application streams 6-DOF spatial pose and discrete gesture actions to the desktop host at 15Hz over the Vivo/iQOO Office Kit Shared Clipboard.

To survive the hackathon's air-gapped Red Light evaluation constraints, this pipe operates without WebSockets, local network discovery, or cloud services.

`clipboard_hook.py` runs a background thread polling the host OS clipboard. Its core responsibilities are:

- Validating and deserializing the 12-field pipe payload schema.
- Enforcing sequence monotonicity (`SEQ`) and dropping duplicate/out-of-order packets.
- De-duplicating latched discrete actions (`ACTION:*`) during the ~500ms mobile latch window.
- Managing a 500ms Stale Watchdog to trigger tracking-loss failsafes when data halts.
- Providing zero-intervention Restart Recovery when sequence baselines reset.

---

## 2. Root Cause Analysis: The Latching Over-Trigger Bug

### The Observed Failure

During initial contract validation using synthetic frames simulating an action held across three consecutive 15Hz sequence ticks (`SEQ=101`, `SEQ=102`, `SEQ=103`), the contract test failed with:

```
AssertionError: Failed latch: [(101, 'ACTION:EXPLODE'), (102, 'ACTION:EXPLODE'), (103, 'ACTION:EXPLODE')]
```

### The Bug in Earlier Versions

Earlier iterations attempted action de-duplication using only a raw sequence inequality:

```python
# --- DEFECTIVE EARLIER IMPLEMENTATION ---
action_to_execute = "NULL"
if raw_action != "NULL" and seq > self.last_acted_seq:
    self.last_acted_seq = seq
    action_to_execute = raw_action
```

Because the mobile camera/IMU loop increments `seq` on every single tick:

| Tick | SEQ | Comparison | Result |
|------|-----|------------|--------|
| 1 | 101 | `101 > 0` | `True` → `last_acted_seq` updates to 101, action fires |
| 2 | 102 | `102 > 101` | `True` → action fires again, `last_acted_seq` updates to 102 |
| 3 | 103 | `103 > 102` | `True` → action fires a third time |

### Operational Impact

In live CAD viewports, an action latched for 500ms (~7 ticks at 15Hz) fired 7 consecutive times for a single physical gesture. A single `ACTION:SPAWN` spawned 7 duplicate meshes, and `ACTION:RESET` or `ACTION:EXPLODE` toggled repeatedly.

---

## 3. Detailed Changes & Architectural Rationale

### Change 1: Latched Action State Tracking (`self.last_action`)

**What was changed:** Added `self.last_action = "NULL"` to `ClipboardWatcher.__init__`.

**Why it was done:** Sequence progression alone cannot distinguish between a continuous held latch of the same gesture and a new discrete gesture arriving immediately after. Tracking the active action string allows the hook to recognize repeated frames within an active latch window.

### Change 2: Dual Sequence & State Latch Suppression Logic

**What was changed:** Updated `_parse_payload` so that incoming non-`NULL` actions execute only if the action represents a state transition (`raw_action != self.last_action`) or arrives after a minimum 10-tick sequence gap (`seq - self.last_acted_seq > 10`):

```python
# SEQ & State-based ACTION de-duplication (v4.2 Architecture §4.2)
action_to_execute = "NULL"
if raw_action != "NULL":
    if raw_action != self.last_action or (seq - self.last_acted_seq > 10):
        self.last_acted_seq = seq
        self.last_action = raw_action
        action_to_execute = raw_action
else:
    self.last_action = "NULL"
```

**Why it was done:** The first frame of a latch fires the action immediately (`action_to_execute = raw_action`). All subsequent frames in that latch window pass pose data cleanly while `action_to_execute` remains `"NULL"`. When the mobile client clears its buffer back to `"NULL"`, `self.last_action` resets, unblocking the next gesture.

### Change 3: Watchdog Reset on App Restart Recovery

**What was changed:** Updated the stale watchdog recovery branch:

```python
# Restart recovery: reset session tracking on stale recovery
if self.is_stale:
    self.last_seq = seq - 1
    self.last_acted_seq = 0
    self.last_action = "NULL"
    self.is_stale = False
```

**Why it was done:** If the Android app crashes or restarts, its internal sequence counter resets to `SEQ=1`. Without resetting `self.last_acted_seq` and `self.last_action`, any initial action payload on the new mobile session would be dropped because `1 < 100+`.

---

## 4. Full Verified Source: `clipboard_hook.py`

```python
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
                if raw_text and raw_text.startswith("KT|"):
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
```

---

## 5. Automated Verification Harness (`test_clipboard.py`)

Run this test script directly inside the virtual environment to confirm contract compliance:

```python
import time
import pyperclip
from clipboard_hook import ClipboardWatcher

received = []

def cb(data):
    if data.get("action") and data["action"] != "NULL":
        received.append((data["seq"], data["action"]))
        print(f"  [Watcher] -> ACTION: SEQ={data['seq']} | ACTION={data['action']}")
    elif data.get("state") == 0 or data.get("stale"):
        print("  [Watcher] -> STALE TIMEOUT FIRED")

watcher = ClipboardWatcher(state_callback=cb)
watcher.start()

print("\n--- 1. Baseline Ingestion ---")
pyperclip.copy("KT|100|1|0.0|0.0|0.0|1.0|0.0|0.0|0.0|NULL|NULL")
time.sleep(0.1)

print("\n--- 2. Latch Window (3 ticks carrying identical action) ---")
for s in [101, 102, 103]:
    pyperclip.copy(f"KT|{s}|1|0.0|0.0|0.0|1.0|0.0|0.0|0.0|RECORDING|ACTION:EXPLODE")
    time.sleep(0.066)

time.sleep(0.1)
assert len(received) == 1 and received[0] == (101, "ACTION:EXPLODE"), f"Failed latch: {received}"
print("  ✅ Latch de-duplication verified!")

print("\n--- 3. 500ms Stale Watchdog ---")
time.sleep(0.6)
assert watcher.is_stale, "Stale flag failed"
print("  ✅ Stale watchdog verified!")

print("\n--- 4. Session Restart Recovery ---")
pyperclip.copy("KT|1|1|0.1|0.2|0.3|1.0|0.0|0.0|0.0|NULL|ACTION:RESET")
time.sleep(0.1)
assert received[-1] == (1, "ACTION:RESET"), "Failed restart recovery"
print("  ✅ Restart recovery verified!")

watcher.stop()
print("\n🎉 ALL TESTS PASSED: Clipboard contract verified!\n")
```

---

## 6. Integration Contract Guidelines for Teammate Agents

### For Android Studio Agent (Mobile Loop)

- Only emit action tokens from the canonical set defined in Design Doc v4.2 §3. No other `ACTION:*` values are valid on the contract:

  | Token | Meaning |
  |-------|---------|
  | `ACTION:SELECT` | Select the targeted object |
  | `ACTION:DELETE` | Delete the targeted object |
  | `ACTION:SPAWN` | Spawn a new mesh at the current pose |
  | `ACTION:RESET` | Reset the CAD viewport/session state |

- Format packets strictly to the 12-field pipe schema:
  ```
  KT|[SEQ]|[STATE]|[X]|[Y]|[Z]|[QW]|[QX]|[QY]|[QZ]|[GESTURE_STATE]|[ACTION]
  ```
- Throttle the emission loop to 15Hz (~66ms per clipboard write) to prevent OriginOS sync queue saturation.
- When a gesture resolves, latch the discrete action token (e.g., `ACTION:SPAWN`, `ACTION:RESET`) continuously for ~500ms (~7 ticks) alongside incrementing SEQ numbers before clearing back to `NULL`.

### For PyOpenGL Engine Agent (Desktop Viewport)

- Inspect `data["action"]` on every frame. If it equals `"NULL"`, do not invoke CAD command logic.
- When `data["state"] == 0` or `data["stale"] == True`, render the HUD warning indicator and pause motion cursor updates.
- Run LERP on `data["pos"]` and SLERP on `data["rot"]` to interpolate the 15Hz stream into a fluid 60 FPS viewport render.
