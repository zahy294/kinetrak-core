# KineTrak — Comprehensive System Architecture & Engineering Changelog

**Branch:** `feat/dev1-edge-ai-kinematics` (Integrated with `dev3-desktop-ui`)
**Merge Commit:** `5b67d14`
**Target Commit:** `5f186370923577c5ee524e3f2e9f826bd5d82e86`
**Authors:** Siddharth (Dev 1 — Edge-AI & Kinematics Lead), Jeston (Dev 3 — Desktop Viewport Lead)
**System Scope:** Android 6-DOF Sensor Fusion, Headless ARCore VIO, Edge NPU Kinematics, 15Hz Telemetry Pipe, Dual-Format Host Ingestion, and PyOpenGL Workstation Viewport

---

## 1. Executive Summary

This document is the consolidated, single source of truth detailing the technical architecture, mathematical models, cross-platform bridge contracts, and bug resolutions implemented across `kinetrak-android` and `kinetrak-desktop`.

### Core Problems Solved

1. **Stationary / Zero Translation Bug:** The desktop viewport was previously locked at (0, 0, 0) translational displacement because ARCore was not bound to an active OpenGL texture context in headless mode, preventing `session.update()` from advancing camera odometry.
2. **Double Sensor Contention:** `ClipboardBridgeService.kt` was running an uncalibrated raw `SensorEventListener` loop that concurrently stomped rotation matrices and ignored calibrated odometry produced by `SensorFusionHub.kt`.
3. **Watchdog Rubber-Banding & Coordinate Freezing:** The desktop viewport aggressively snapped model coordinates back to `[0.0, 0.0, -5.0]` whenever the telemetry packet flagged `state == 0` or experienced brief pipeline jitter, causing violent snapping between hand movements and the screen center.
4. **Missing Zero-Origin Baseline Calibration:** ARCore translations stream absolute metric coordinates relative to where the camera session initialized. Without baseline origin subtraction (`self.origin_pos`), the 3D model spawned meters outside the view frustum.
5. **Vivo Office Kit Driver Saturation (The 30Hz Trap):** Pushing clipboard writes at 30Hz (33ms) or on every camera frame choked the Windows Office Kit USB bridge driver, leading to packet stalls, bursty arrivals, and false-positive stale watchdog timeouts. The link was hard-throttled to a strict **15Hz (66ms)** loop.
6. **Foreground Service Crash on Android 14–16 (API 34–36):** The background telemetry service crashed on modern Android versions due to undeclared foreground service types. Resolved by adding `FOREGROUND_SERVICE_DATA_SYNC` flags across `AndroidManifest.xml` and runtime notification builders.
7. **Action Latch Over-Triggering:** The ~500ms action latch window emitted ~7 repeated sequential frames with the same action string. In the viewport, `seq > last_acted_seq` evaluated to `True` on every tick, firing commands 7 times per gesture. Resolved via discrete action state tracking (`self.last_action`) and gap thresholding.
8. **Over-Damped Viewport Lag:** Viewport interpolation factors were over-damped (0.10–0.25), causing the CAD model to lag noticeably behind the physical hand. Tuning alphas to 0.40 (position) and 0.45 (quaternion SLERP) restored crisp responsiveness.

---

## 2. Dev 3 Branch Merge & Integration Breakdown (Commit `5b67d14`)

The branch `origin/dev3-desktop-ui` was merged directly into `feat/dev1-edge-ai-kinematics` with the following component resolutions:

| Component / File | Conflict Origin | Resolution Rationale |
|---|---|---|
| `kinetrak-desktop/clipboard_hook.py` | Clean Merge | Integrated Dev 3's improvements: dual-format parser (Pipe `KT\|...` + JSON fallback), Win32 clipboard collision suppression, and edge-triggered 500ms watchdog. |
| `kinetrak-desktop/requirements.txt` | Clean Merge | Integrated Dev 3's additions: added `pyobjc-framework-Cocoa` (macOS compatibility) and `scipy>=1.14.0`. |
| `kinetrak-desktop/main.py` | Structural divergence between viewport loops | **Synthesized best of both:** Integrated Dev 3's 3D Trajectory Ribbon (`draw_trajectory_ribbon` with cyan-to-amber distance-based alpha fading) and Infinite Ground Grid (`draw_infinite_floor_grid`). Maintained Dev 1's first-frame zero-origin calibration, exploded CAD model, responsive LERP/SLERP (`pos_alpha=0.40`, `rot_alpha=0.45`), and Zero-Order Hold (eliminated rubber-banding snapping). Added `'C'` key hotkey to clear the trajectory ribbon alongside `'R'` to reset origin. |
| `ClipboardBridgeService.kt` | Sensor contention & thread dispatch | **Retained Dev 1 architecture:** Dev 3's branch had re-introduced `SensorEventListener` on an older commit. Dev 1's clean serialization loop pulls directly from atomic `BridgeState` at 15Hz with a 7-tick action latch window, preventing hardware sensor listener contention with `SensorFusionHub`. |
| `MainActivity.kt` | Direct pose manipulation vs. `SensorFusionHub` | **Integrated touch ergonomics while protecting VIO:** Preserved Dev 1's headless offscreen EGL PBuffer context (`initOffscreenGl()`) and `sensorFusionHub.onArCoreFrame(frame)` pipeline. Integrated Dev 3's `onTouchEvent` touch-to-record (`ACTION_DOWN` → `RECORDING`, `ACTION_UP` → `IDLE`) updating `BridgeState.currentState`. |

---

## 3. End-to-End System Architecture

```mermaid
flowchart TD
    subgraph Mobile ["kinetrak-android (Client)"]
        HW["Hardware Key (VOL_DOWN) / Touch UI"] -->|Record/Infer| MBM["MotionBufferManager\n(45x6 Rolling Tensor)"]
        MBM -->|Timeout Guard| NPU["Snapdragon NPU / Hexagon DSP\n(Heuristic Gesture Fallback)"]
        NPU -->|Latched ACTION| BS["BridgeState\n(Atomic Shared Memory)"]

        AR["ARCore Headless Engine\n(Offscreen EGL PBuffer)"] -->|displayOrientedPose| SFH["SensorFusionHub\n(2.5x Scale + ZOH)"]
        IMU["Hardware IMU\n(TYPE_ROTATION_VECTOR)"] -->|Fallback 3-DOF| SFH
        SFH -->|Vec3 & Quat| BS

        BS --> CBS["ClipboardBridgeService\n(Strict 15Hz Serialization)"]
    end

    CBS -->|KT Protocol String (66ms)| CLIP[("Vivo Office Kit\nAir-Gapped Clipboard Pipe")]

    subgraph Desktop ["kinetrak-desktop (Host Engine)"]
        CLIP --> CW["ClipboardWatcher (clipboard_hook.py)\n(Dual-Format Pipe/JSON Parser + Win32 Lock Guard)"]
        CW -->|Parsed & Deduplicated State| HE["HostEngine (main.py)\n(Zero-Origin Calibration + Watchdog Guard)"]
        HE -->|Relative Translation & Quat| SM["Spatial Smoothing Pipeline\n(LERP 0.40 / SLERP 0.45)"]
        SM --> GL["PyOpenGL 3D Viewport\n(Exploded CAD + Trajectory Ribbon + Infinite Grid + 2D HUD)"]
    end
```

### Telemetry Wire Protocol Specification

Packets are formatted as 12 pipe-delimited fields:

```text
KT|<seq>|<tracking_state>|<pos_x>|<pos_y>|<pos_z>|<rot_qw>|<rot_qx>|<rot_qy>|<rot_qz>|<gesture_state>|<active_action>
```

| Index | Field | Type | Description |
| --- | --- | --- | --- |
| 0 | `KT` | String | Protocol magic header |
| 1 | `seq` | Integer | Monotonically increasing sequence number |
| 2 | `state` | Integer | `1` = Tracking valid, `0` = Tracking dropped / stale |
| 3–5 | `pos.x, y, z` | Float | Scaled 3D spatial translation coordinates (meters) |
| 6–9 | `rot.w, x, y, z` | Float | Normalized orientation quaternion [w, x, y, z] |
| 10 | `gesture_state` | String | `IDLE`, `RECORDING`, `THINKING`, `ACTION_DISPATCHED` |
| 11 | `active_action` | String | `ACTION:SPAWN`, `ACTION:SELECT`, `ACTION:DELETE`, `ACTION:RESET`, or `NULL` |

---

## 4. Subsystem Implementation & Integration Details

### A. Android Client (`kinetrak-android`)

#### 1. Foreground Service Compliance (API 34–36)

To prevent `ForegroundServiceDidNotStartInTimeException` on Android 14+ devices:

- **`AndroidManifest.xml`:**

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".ClipboardBridgeService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

- **`ClipboardBridgeService.kt`:** Added API-level checks passing `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` to `startForeground()`.

#### 2. Strict 15Hz Dispatch Throttling (Vivo Office Kit Bridge)

- **File:** `kinetrak-android/app/src/main/java/com/ggr/kinetrak/ClipboardBridgeService.kt`
- Removed `SensorEventListener` implementation from the service to eliminate write conflicts.
- Hard-locked emission delay to **66ms (~15Hz)** using `maxOf(10L, 66L - elapsed)`. This prevents driver buffer saturation on the Windows USB link while meeting the latency budget.
- Reads atomic position, rotation, and actions directly from `BridgeState`.
- Enforces an automated 7-tick (~462ms) latch window on active actions before reverting the broadcast token to `"NULL"`.

#### 3. Offscreen EGL Surface & ARCore Pose Extraction

- **File:** `kinetrak-android/app/src/main/java/com/ggr/kinetrak/MainActivity.kt`
- Initialized an offscreen EGL PBuffer context (1x1) bound to an external GL texture (`GL_TEXTURE_EXTERNAL_OES`). This allows `session.update()` to advance the camera odometry engine without requiring an active visible surface.
- Passes each live `Frame` to `sensorFusionHub.onArCoreFrame(frame)`.

#### 4. Sensor Fusion & Failover Logic

- **File:** `kinetrak-android/app/src/main/java/com/ggr/kinetrak/tracking/SensorFusionHub.kt`
- Uses `frame.camera.displayOrientedPose` to ensure correct axes regardless of device orientation.
- Applies a `2.5f` coordinate scaling factor to amplify physical hand movement for desktop CAD workflows.
- Correctly maps ARCore's [x, y, z, w] quaternion array into `Quat(w, x, y, z)`.
- **Zero-Order Hold Failover:** During visual tracking loss, `handleTrackingLoss()` retains `lastValidPosition` and maintains `BridgeState.isTrackingValid = true` over transient drops, falling back to Android's hardware `Sensor.TYPE_ROTATION_VECTOR` for continuous 3-DOF rotation.

#### 5. Edge Intelligence & Hardware Triggers

- **`MotionBufferManager.kt`:** Maintains a rolling 45-sample tensor of 6-channel sensor frames (`[ax, ay, az, gx, gy, gz]`).
- **Hardware Trigger Hook:** Bound to `KEYCODE_VOLUME_DOWN` (KeyDown → start buffering, KeyUp → trigger asynchronous classification).
- **Inference Pipeline:** Evaluates gestures against the Snapdragon NPU DLC model (`gesture_model_quantized.dlc`) with a 1500ms timeout guard, falling back to rule-based peak-acceleration heuristics (`HeuristicGestureFallback.kt`) if inference stalls.

### B. Desktop Host Engine (`kinetrak-desktop`)

#### 1. Ingestion & Latch De-duplication (`clipboard_hook.py`)

- **File:** `kinetrak-desktop/clipboard_hook.py`
- Runs a background thread reading OS clipboard contents via `pyperclip`.
- **Action De-duplication:** Tracks `self.last_action` alongside sequence numbers. When a gesture action arrives (`ACTION:SPAWN`, etc.), it executes once on the first sequence tick and suppresses repeated triggers across the remaining 500ms latch window.
- **Watchdog & Recovery:** A 500ms watchdog trips when clipboard updates halt. When incoming packets resume after an app restart, `last_acted_seq` and `last_action` reset to clear stale latches.

#### 2. Relative Origin Calibration & Watchdog Smoothing (`main.py`)

- **File:** `kinetrak-desktop/main.py`
- **Zero-Origin Anchoring:** The viewport subtracts the first valid room position from subsequent translation vectors:

```python
if self.origin_pos is None and np.linalg.norm(raw_pos) > 0.001:
    self.origin_pos = raw_pos.copy()
delta = raw_pos - self.origin_pos
self.target_pos = np.array([delta[0] * 3.0, delta[1] * 3.0, -5.0 + (delta[2] * 2.0)], dtype=np.float32)
```

- **Hold-Last-Position Policy:** When `data["state"] == 0` or stale conditions occur, the viewport displays a warning HUD but holds its current coordinates instead of snapping back to default.
- **Interactive Hotkeys:**
  - `R`: Reset and recalibrate zero origin to the phone's current position.
  - `SPACE`: Trigger CAD component explosion / disassembly test.
  - `T`: Toggle synthetic motion telemetry generator.
  - `C`: Clear the trajectory ribbon.
  - `ESC`: Clean shutdown.

#### 3. Mathematical Interpolation (`smoothing_math.py`)

- **File:** `kinetrak-desktop/smoothing_math.py`
- Position interpolation uses a tuned LERP factor: `self.curr_pos += (self.target_pos - self.curr_pos) * 0.40`.
- Rotation uses unit quaternion SLERP: `Quaternion.slerp(self.curr_rot, self.target_rot, amount=0.45)` with shortest geodesic path enforcement.

---

## 5. Problem & Resolution Matrix

| Symptom | Root Cause | Resolution |
| --- | --- | --- |
| X, Y, Z translation frozen at 0.0 | ARCore ran without an active OpenGL texture context, causing frame updates to stall. | Bound an offscreen EGL PBuffer surface (1x1) to `session.setCameraTextureName()` in `MainActivity.kt`. |
| Model snapping to screen center | Host watchdog forcibly reset `curr_pos` to `[0, 0, -5]` on dropped frames. | Removed coordinate snapping in `main.py`; replaced with Zero-Order Hold on last valid position. |
| Model spawning out of view | Absolute room-scale metric coordinates streamed directly into the camera frustum. | Implemented relative zero-origin subtraction (`raw_pos - origin_pos`) with `R` key recalibration. |
| Single gesture triggering 7 times | Sequential packet delivery during the 500ms latch window kept evaluating `seq > last_acted_seq` as `True`. | Implemented state de-duplication in `clipboard_hook.py` using `self.last_action`. |
| Telemetry stalling & packet bursts | Writing to clipboard at 30Hz or per-frame saturated the Vivo Office Kit driver buffer. | Enforced a strict 66ms (~15Hz) delay in `ClipboardBridgeService.kt`. |
| Foreground service crash on startup | Missing foreground service permissions and type declarations on API 34–36. | Added `FOREGROUND_SERVICE_DATA_SYNC` across manifest and service notification setup. |
| Visual lag behind hand motion | LERP and SLERP damping factors (0.10–0.25) were too stiff for 15Hz discrete updates. | Adjusted position alpha to `0.40` and quaternion SLERP alpha to `0.45`. |

---

## 6. Build, Deployment & Verification

### Running the Desktop Workstation

```powershell
cd d:\KineTrak\kinetrak-core
.\.venv\Scripts\Activate.ps1
python kinetrak-desktop\main.py
```

### Building & Deploying the Android Client

```powershell
cd d:\KineTrak\kinetrak-core\kinetrak-android
$env:JAVA_HOME = "C:\Users\Siddharth\.jdks\ms-17.0.20.1"
.\gradlew installDebug
```

### Live Validation Checklist

- [ ] Connect the Android device to the PC via USB tethering with Vivo/iQOO Office Kit Shared Clipboard enabled.
- [ ] Verify the persistent foreground service notification: "Streaming 6-DOF telemetry at 15Hz".
- [ ] Run `python kinetrak-desktop\main.py` and press `R` with the phone held in front of the screen to calibrate the zero origin.
- [ ] Move the device along X, Y, Z and verify smooth 60FPS tracking in the PyOpenGL viewport.
- [ ] Hold Volume Down, perform an outward thrust gesture, and release: verify that the CAD assembly triggers the exploded view.
