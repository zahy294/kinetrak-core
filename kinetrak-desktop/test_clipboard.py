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
