import sys
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

import time
import pyperclip
from clipboard_hook import ClipboardWatcher

received = []

def cb(data):
    # Only record discrete actions when they are not NULL
    if data.get('action') and data['action'] != 'NULL':
        received.append((data['seq'], data['action']))
        print(f"  [Watcher] -> ACTION: SEQ={data['seq']} | ACTION={data['action']}")
    elif data.get('state') == 0:
        print("  [Watcher] -> STALE TIMEOUT FIRED")

watcher = ClipboardWatcher(state_callback=cb)
watcher.start()

print("\n--- 1. Baseline ---")
pyperclip.copy("KT|100|1|0.0|0.0|0.0|1.0|0.0|0.0|0.0|NULL|NULL")
time.sleep(0.1)

print("\n--- 2. Latch Window (3 ticks with same action) ---")
for s in [101, 102, 103]:
    pyperclip.copy(f"KT|{s}|1|0.0|0.0|0.0|1.0|0.0|0.0|0.0|RECORDING|EXPLODE")
    time.sleep(0.066)

time.sleep(0.1)
assert len(received) == 1 and received[0] == (101, 'EXPLODE'), f"Failed latch: {received}"
print("  ✅ Latch de-duplication verified!")

print("\n--- 3. Stale Watchdog ---")
time.sleep(0.6)
assert watcher.is_stale, "Stale flag failed"
print("  ✅ Stale watchdog verified!")

print("\n--- 4. Restart Recovery ---")
pyperclip.copy("KT|1|1|0.1|0.2|0.3|1.0|0.0|0.0|0.0|NULL|RESET")
time.sleep(0.1)
assert received[-1] == (1, 'RESET'), "Failed restart recovery"
print("  ✅ Restart recovery verified!")

print("\n--- 5. Legacy JSON Fallback ---")
pyperclip.copy('{"seq": 200, "state": 1, "pos": [1.0, 2.0, 3.0], "rot": [1.0, 0.0, 0.0, 0.0], "gesture_state": "GESTURE", "action": "ACTION:SPAWN"}')
time.sleep(0.1)
assert received[-1] == (200, 'ACTION:SPAWN'), f"Failed JSON fallback: {received}"
print("  ✅ Legacy JSON fallback verified!")

print("\n--- 6. Stale Jump Recovery (SEQ 8287 -> 9779+) ---")
pyperclip.copy("KT|8287|1|0.0|0.0|0.0|1.0|0.0|0.0|0.0|NULL|ACTION:GRAB")
time.sleep(0.1)
assert received[-1] == (8287, 'ACTION:GRAB'), f"Failed 8287: {received}"
time.sleep(0.6)
assert watcher.is_stale, "Watcher should be stale after 600ms gap"
pyperclip.copy("KT|9779|1|0.1|0.2|0.3|1.0|0.0|0.0|0.0|RECORDING|ACTION:RELEASE")
time.sleep(0.1)
assert received[-1] == (9779, 'ACTION:RELEASE'), f"Failed 9779 jump: {received}"
assert not watcher.is_stale, "Watcher should clear stale on SEQ 9779 arrival"
print("  ✅ Stale jump from SEQ 8287 to 9779+ verified!")

print("\n--- 7. Malformed Payload & Exception Resilience ---")
# Send malformed data to ensure thread doesn't crash
pyperclip.copy("KT|MALFORMED|1|0.0")
time.sleep(0.05)
pyperclip.copy("{ invalid: json }")
time.sleep(0.05)
pyperclip.copy("KT|9785|1|0.1|0.2|0.3|1.0|0.0|0.0|0.0|NULL|ACTION:SELECT")
time.sleep(0.1)
assert received[-1] == (9785, 'ACTION:SELECT'), f"Thread failed to recover from malformed payloads: {received}"
assert watcher.thread.is_alive(), "Watcher thread died!"
print("  ✅ Malformed frame exception resilience verified!")

print("\n--- 8. Thread Health Check & Auto-Restart ---")
watcher.thread = None  # Simulate dead thread
watcher.ensure_alive()
assert watcher.thread is not None and watcher.thread.is_alive(), "Watcher failed to auto-restart"
pyperclip.copy("KT|9999|1|0.0|0.0|0.0|1.0|0.0|0.0|0.0|NULL|ACTION:PINCH")
time.sleep(0.1)
assert received[-1] == (9999, 'ACTION:PINCH'), f"Auto-restarted watcher failed: {received}"
print("  ✅ Thread auto-restart verified!")

watcher.stop()
print("\n🎉 ALL TESTS PASSED: Clipboard contract verified!\n")