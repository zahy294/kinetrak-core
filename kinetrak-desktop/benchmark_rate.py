"""
benchmark_rate.py — KineTrak Desktop Telemetry & Clipboard Rate Benchmark Tool

Measures Vivo Office Kit / Windows Clipboard bridge capacity, throughput, jitter,
and frame drop rates under real-time telemetry streaming conditions.

Key Capabilities:
  - High-frequency 120Hz polling (~8.3ms loop) via pywin32 / win32clipboard.
  - Exception-suppressed retry handling for OpenClipboard lock collisions.
  - Tracking of sequence progression, frame drops, inter-packet arrival jitter,
    and maximum stall gap durations.
  - Built-in synthetic telemetry generator (--synthetic) supporting Modes 1-3
    (15Hz, 20Hz, 30Hz) for standalone baseline and stress testing.
"""

import sys
import time
import math
import argparse
import threading
import numpy as np

# Ensure UTF-8 output on Windows
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass


_in_process_lock = threading.Lock()

def get_clipboard_text(max_retries=5, retry_delay=0.002):
    """
    Retrieves text from the Windows OS clipboard with retry logic and exception
    suppression for OpenClipboard lock contention.
    """
    for attempt in range(max_retries):
        clipboard_opened = False
        try:
            with _in_process_lock:
                import win32clipboard
                win32clipboard.OpenClipboard()
                clipboard_opened = True
                try:
                    if win32clipboard.IsClipboardFormatAvailable(win32clipboard.CF_UNICODETEXT):
                        return win32clipboard.GetClipboardData(win32clipboard.CF_UNICODETEXT)
                    elif win32clipboard.IsClipboardFormatAvailable(win32clipboard.CF_TEXT):
                        data = win32clipboard.GetClipboardData(win32clipboard.CF_TEXT)
                        if isinstance(data, bytes):
                            return data.decode("utf-8", errors="ignore")
                        return str(data)
                    return ""
                finally:
                    if clipboard_opened:
                        try:
                            win32clipboard.CloseClipboard()
                        except Exception:
                            pass
        except Exception:
            if attempt < max_retries - 1:
                time.sleep(retry_delay * (attempt + 1))
                continue
            break

    for attempt in range(max_retries):
        try:
            with _in_process_lock:
                import pyperclip
                return pyperclip.paste()
        except Exception:
            if attempt < max_retries - 1:
                time.sleep(retry_delay * (attempt + 1))
                continue
            return ""
    return ""


def set_clipboard_text(text: str, max_retries=5, retry_delay=0.002):
    """Copies text to the clipboard with lock retry handling."""
    for attempt in range(max_retries):
        clipboard_opened = False
        try:
            with _in_process_lock:
                import win32clipboard
                import win32con
                win32clipboard.OpenClipboard()
                clipboard_opened = True
                try:
                    win32clipboard.EmptyClipboard()
                    win32clipboard.SetClipboardText(text, win32con.CF_UNICODETEXT)
                    return True
                finally:
                    if clipboard_opened:
                        try:
                            win32clipboard.CloseClipboard()
                        except Exception:
                            pass
        except Exception:
            if attempt < max_retries - 1:
                time.sleep(retry_delay * (attempt + 1))
                continue
            break

    try:
        with _in_process_lock:
            import pyperclip
            pyperclip.copy(text)
            return True
    except Exception:
        return False


class SyntheticEmitter:
    """
    Background emitter simulating Android / Vivo Office Kit telemetry output.
    """
    def __init__(self, mode: int = 1, custom_hz: float = None):
        if custom_hz is not None:
            self.delay_sec = 1.0 / custom_hz
            self.target_hz = custom_hz
        elif mode == 2:
            self.delay_sec = 0.050  # 50ms (20Hz)
            self.target_hz = 20.0
        elif mode == 3:
            self.delay_sec = 0.0333 # ~33.3ms (30Hz)
            self.target_hz = 30.0
        else:
            self.delay_sec = 0.0666 # ~66.6ms (15Hz Baseline)
            self.target_hz = 15.0

        self.running = False
        self.seq = 1000
        self.thread = None

    def start(self):
        self.running = True
        self.thread = threading.Thread(target=self._emit_loop, daemon=True)
        self.thread.start()

    def stop(self):
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)

    def _emit_loop(self):
        t0 = time.time()
        while self.running:
            try:
                loop_start = time.perf_counter()
                self.seq += 1
                t = time.time() - t0
                
                # Simulated smooth orbital coordinates
                x = round(0.15 * math.sin(t * 1.5), 4)
                y = round(0.10 * math.cos(t * 1.2), 4)
                z = round(0.05 * math.sin(t * 0.8), 4)
                
                payload = f"KT|{self.seq}|1|{x}|{y}|{z}|1.0000|0.0000|0.0000|0.0000|NULL|NULL"
                set_clipboard_text(payload)

                elapsed = time.perf_counter() - loop_start
                sleep_time = max(0.001, self.delay_sec - elapsed)
                time.sleep(sleep_time)
            except Exception:
                time.sleep(0.01)


class RateBenchmark:
    def __init__(self, duration: float = 30.0, poll_hz: float = 120.0):
        self.duration = duration
        self.poll_interval = 1.0 / poll_hz
        self.poll_hz = poll_hz

        self.timestamps = []
        self.sequences = []
        self.intervals = []
        self.dropped_frames = 0
        self.first_seq = None
        self.last_seq = None
        self.last_content = ""
        self.start_time = None
        self.end_time = None
        self.max_stall_gap = 0.0

    def run(self):
        print(f"\n==================================================================")
        print(f"       KineTrak Telemetry Bridge Rate & Jitter Benchmark          ")
        print(f"==================================================================")
        print(f"  • Polling Frequency : {self.poll_hz:.1f} Hz (~{self.poll_interval*1000:.2f} ms cycle)")
        print(f"  • Test Window       : {self.duration:.1f} seconds")
        print(f"  • Filter Contract   : KT|<SEQ>|<STATE>|<X>|<Y>|<Z>|... (12 fields)")
        print(f"------------------------------------------------------------------")
        print(f"Waiting for telemetry packets to arrive on OS clipboard...")

        # Initialize baseline clipboard state to avoid re-counting cold data
        self.last_content = get_clipboard_text().strip()

        # Wait up to 10s for the first new packet if no traffic yet
        wait_start = time.time()
        while True:
            raw = get_clipboard_text()
            if raw:
                text = raw.strip()
                if text != self.last_content and text.startswith("KT|"):
                    parts = text.split("|")
                    if len(parts) == 12:
                        try:
                            seq = int(parts[1])
                            self.last_content = text
                            self.first_seq = seq
                            self.last_seq = seq
                            t_now = time.perf_counter()
                            self.start_time = t_now
                            self.timestamps.append(t_now)
                            self.sequences.append(seq)
                            break
                        except ValueError:
                            pass
            time.sleep(self.poll_interval)
            if time.time() - wait_start > 10.0:
                print("\n[Timeout] No KT telemetry packets detected within 10 seconds.")
                print("Make sure either kinetrak-android is streaming or use --synthetic.")
                return False

        print(f"✅ Telemetry stream detected! Benchmark measuring for {self.duration:.0f} seconds...\n")

        last_packet_time = self.start_time
        next_print_time = time.time() + 0.5

        # Main high-speed polling loop
        while (time.perf_counter() - self.start_time) < self.duration:
            loop_start = time.perf_counter()
            try:
                raw = get_clipboard_text()
                if raw:
                    text = raw.strip()
                    if text != self.last_content and text.startswith("KT|"):
                        parts = text.split("|")
                        if len(parts) == 12:
                            try:
                                seq = int(parts[1])
                                t_now = time.perf_counter()
                                
                                # Valid new packet transition
                                if seq != self.last_seq:
                                    delta = t_now - last_packet_time
                                    self.intervals.append(delta)
                                    if delta > self.max_stall_gap:
                                        self.max_stall_gap = delta

                                    if seq > self.last_seq:
                                        gap = seq - self.last_seq - 1
                                        if gap > 0:
                                            self.dropped_frames += gap
                                    
                                    self.last_seq = seq
                                    self.last_content = text
                                    self.timestamps.append(t_now)
                                    self.sequences.append(seq)
                                    last_packet_time = t_now
                            except ValueError:
                                pass
            except Exception:
                pass

            # Update live terminal progress
            if time.time() >= next_print_time:
                try:
                    elapsed = time.perf_counter() - self.start_time
                    remain = max(0.0, self.duration - elapsed)
                    pkt_count = len(self.sequences)
                    current_rate = pkt_count / elapsed if elapsed > 0 else 0
                    sys.stdout.write(
                        f"\r  ⏱ Time: {elapsed:5.1f}s / {self.duration:4.1f}s | "
                        f"Pkts: {pkt_count:5d} | "
                        f"Rate: {current_rate:5.1f} Hz | "
                        f"Drops: {self.dropped_frames:3d} | "
                        f"SEQ: {self.last_seq:6d}  "
                    )
                    sys.stdout.flush()
                except Exception:
                    pass
                next_print_time = time.time() + 0.5

            elapsed_poll = time.perf_counter() - loop_start
            sleep_gap = self.poll_interval - elapsed_poll
            if sleep_gap > 0:
                time.sleep(sleep_gap)

        self.end_time = time.perf_counter()
        print("\n\n✅ Benchmark measurement window complete.")
        self.print_summary()
        return True

    def print_summary(self):
        total_time = self.end_time - self.start_time
        total_packets = len(self.sequences)
        arrival_rate = total_packets / total_time if total_time > 0 else 0.0
        
        expected_total = total_packets + self.dropped_frames
        drop_pct = (self.dropped_frames / expected_total * 100.0) if expected_total > 0 else 0.0

        if self.intervals:
            intervals_ms = np.array(self.intervals) * 1000.0
            mean_interval_ms = float(np.mean(intervals_ms))
            median_interval_ms = float(np.median(intervals_ms))
            std_jitter_ms = float(np.std(intervals_ms))
            min_interval_ms = float(np.min(intervals_ms))
            max_interval_ms = float(np.max(intervals_ms))
            p95_interval_ms = float(np.percentile(intervals_ms, 95))
            p99_interval_ms = float(np.percentile(intervals_ms, 99))
        else:
            mean_interval_ms = median_interval_ms = std_jitter_ms = min_interval_ms = max_interval_ms = p95_interval_ms = p99_interval_ms = 0.0

        max_stall_ms = self.max_stall_gap * 1000.0

        # Quality verdict assessment
        if arrival_rate >= 14.0 and drop_pct < 2.0 and std_jitter_ms < 15.0:
            verdict = "🟢 EXCELLENT — Ultra-smooth telemetry bridge performance."
        elif arrival_rate >= 10.0 and drop_pct < 5.0:
            verdict = "🟡 STABLE — Suitable for real-time 6-DOF tracking."
        elif arrival_rate >= 5.0:
            verdict = "🟠 DEGRADED — Noticeable packet latency or throttling detected."
        else:
            verdict = "🔴 UNSTABLE — High packet loss or driver stalling."

        print(f"\n==================================================================")
        print(f"                   BENCHMARK SUMMARY REPORT                       ")
        print(f"==================================================================")
        print(f"  • Total Duration          : {total_time:.2f} s")
        print(f"  • Polling Rate            : {self.poll_hz:.1f} Hz")
        print(f"  • Total Packets Received  : {total_packets} packets")
        print(f"  • Effective Arrival Rate  : {arrival_rate:.2f} Hz")
        print(f"  • Sequence Span           : {self.first_seq} → {self.last_seq} (Δ={self.last_seq - self.first_seq})")
        print(f"  • Dropped Frames Count    : {self.dropped_frames} frames")
        print(f"  • Frame Drop Percentage   : {drop_pct:.2f} %")
        print(f"------------------------------------------------------------------")
        print(f"  • Inter-Packet Interval   : Mean={mean_interval_ms:.2f} ms | Median={median_interval_ms:.2f} ms")
        print(f"  • Interval Bounds (Min/Max): {min_interval_ms:.2f} ms / {max_interval_ms:.2f} ms")
        print(f"  • 95th / 99th Percentile  : {p95_interval_ms:.2f} ms / {p99_interval_ms:.2f} ms")
        print(f"  • Arrival Jitter (StdDev) : ±{std_jitter_ms:.2f} ms")
        print(f"  • Maximum Stall Gap       : {max_stall_ms:.2f} ms")
        print(f"------------------------------------------------------------------")
        print(f"  Verdict: {verdict}")
        print(f"==================================================================\n")


def main():
    parser = argparse.ArgumentParser(
        description="KineTrak Telemetry Clipboard Rate & Jitter Benchmark"
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=30.0,
        help="Measurement duration in seconds (default: 30.0)"
    )
    parser.add_argument(
        "--poll-rate",
        type=float,
        default=120.0,
        help="Polling frequency in Hz (default: 120.0)"
    )
    parser.add_argument(
        "--synthetic",
        action="store_true",
        help="Run background synthetic telemetry generator for local benchmark testing"
    )
    parser.add_argument(
        "--mode",
        type=int,
        choices=[1, 2, 3],
        default=1,
        help="Synthetic emitter mode: 1 (15Hz/66ms), 2 (20Hz/50ms), 3 (30Hz/33ms)"
    )
    parser.add_argument(
        "--hz",
        type=float,
        default=None,
        help="Custom target emission rate in Hz for synthetic generator"
    )

    args = parser.parse_args()

    emitter = None
    if args.synthetic:
        mode_names = {1: "15Hz (66ms Baseline)", 2: "20Hz (50ms)", 3: "30Hz (33ms)"}
        hz_label = f"{args.hz}Hz" if args.hz else mode_names.get(args.mode, f"Mode {args.mode}")
        print(f"[Synthetic Emitter] Initializing background generator ({hz_label})...")
        emitter = SyntheticEmitter(mode=args.mode, custom_hz=args.hz)
        emitter.start()
        time.sleep(0.1)

    try:
        benchmark = RateBenchmark(duration=args.duration, poll_hz=args.poll_rate)
        benchmark.run()
    except Exception as e:
        import traceback
        traceback.print_exc()
    finally:
        if emitter:
            try:
                emitter.stop()
                print("[Synthetic Emitter] Background generator stopped.")
            except Exception:
                pass


if __name__ == "__main__":
    main()
