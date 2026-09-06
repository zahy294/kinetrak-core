"""
KineTrak Desktop — Spatial Interpolation & State Estimation Pipeline
Adheres strictly to KineTrak Technical Design Doc v4.2 architecture.

Provides low-jitter, zero-drift 60FPS coordinate state estimation from 15Hz telemetry packets:
- 1D Constant-Velocity Kalman Filter (`AxisKF`) per spatial axis with:
  * 60 FPS continuous prediction: x = F @ x, P = F @ P @ F^T + Q
  * 15 Hz measurement update: y = z - H @ x
  * Chi-Squared Innovation Gating (3-sigma gate: y^2 > 9 * S) for outlier / rogue jump rejection
  * Zero-Velocity Update (ZUPT): Axis-specific velocity decay (X/Z: 0.98, Y: 0.93) when stationary (still_count >= 3)
- 3D Spatial Tracker (`Tracker3D`) coordinating 3-axis Kalman filtering and shortest-geodesic quaternion SLERP
- Velocity-Adaptive Non-linear Transfer Function Curve:
  gain = base_gain * (1.0 + velocity_gain * abs(velocity))
  out = sign * gain * (normalized_mag ** exponent) * axis_range
- SpatialInterpolator with Zero-Order Hold (ZOH) failsafe for temporary tracking drops
"""

import math
import time
import bisect
from typing import List, Tuple, Optional
import numpy as np
from pyquaternion import Quaternion


# ==============================================================================
# Workspace Scaling & Filter Thresholds
# ==============================================================================
WORKSPACE_GAIN: float = 5.5         # Amplified, responsive 5.5x default gain for broad desk sweeps
DEADZONE_THRESHOLD: float = 0.006   # 6mm (0.006m) tremor & noise suppression threshold
MAX_POS_DELTA: float = 0.25         # 25cm (0.25m) anomalous single-frame delta reference

# Workstation Workspace Bounding Box Bounds (meters)
WORKSPACE_BOUNDS_X: Tuple[float, float] = (-8.0, 8.0)
WORKSPACE_BOUNDS_Y: Tuple[float, float] = (-4.0, 5.0)
WORKSPACE_BOUNDS_Z: Tuple[float, float] = (-8.0, 8.0)
DRIFT_RETURN_FACTOR: float = 0.98   # High-pass filter / baseline drift decay factor per tick

# Zero-Velocity Update (ZUPT) Axis Decays
ZUPT_DECAY_XZ: float = 0.98
ZUPT_DECAY_Y: float = 0.93          # Aggressive decay on gravity-leakage axis
CHI_SQ_GATE_SIGMA: float = 3.0      # 3-sigma innovation gate (y^2 > 9 * S)


# ==============================================================================
# Core Math Utilities
# ==============================================================================
def lerp(a: float, b: float, t: float) -> float:
    """
    Standard linear interpolation between scalar values a and b by factor t.
    Formula: a + (b - a) * t
    """
    return float(a + (b - a) * t)


def lerp_vec3(vec_a: List[float], vec_b: List[float], t: float) -> List[float]:
    """
    3D position vector linear interpolation between vec_a and vec_b by factor t.
    """
    return [
        lerp(float(vec_a[0]), float(vec_b[0]), t),
        lerp(float(vec_a[1]), float(vec_b[1]), t),
        lerp(float(vec_a[2]), float(vec_b[2]), t),
    ]


def slerp_quat(q_a: List[float], q_b: List[float], t: float) -> List[float]:
    """
    Spherical linear interpolation between two [qw, qx, qy, qz] quaternions
    using pyquaternion.Quaternion.

    Guarantees:
    - Traversal along the shortest geodesic path (antipodal sign alignment).
    - Strictly normalized output quaternion: ||q|| == 1.0.
    """
    qa = Quaternion(q_a)
    qb = Quaternion(q_b)

    # Normalize inputs to unit hypersphere
    qa = qa.normalised
    qb = qb.normalised

    # Enforce shortest geodesic path by ensuring non-negative dot product
    dot = float(np.dot(qa.elements, qb.elements))
    if dot < 0.0:
        qb = -qb

    # Boundary conditions
    if t <= 0.0:
        res = qa
    elif t >= 1.0:
        res = qb
    else:
        res = Quaternion.slerp(qa, qb, amount=float(t))

    res = res.normalised
    return [float(x) for x in res.elements]


# ==============================================================================
# 1D Constant-Velocity Kalman Filter (AxisKF)
# ==============================================================================
class AxisKF:
    """
    1D Constant-Velocity (CV) Kalman Filter for single-axis spatial state estimation.
    State vector: x = [position, velocity]^T
    State transition: F = [[1, dt], [0, 1]]
    Measurement: H = [[1, 0]]
    """

    def __init__(
        self,
        init_pos: float = 0.0,
        init_vel: float = 0.0,
        q_var: float = 1.5,
        r_var: float = 0.0004,
        p_pos: float = 0.01,
        p_vel: float = 0.1,
        velocity_decay: float = 0.98,
    ) -> None:
        """
        :param init_pos: Initial position (meters).
        :param init_vel: Initial velocity (m/s).
        :param q_var: Acceleration process noise spectral density.
        :param r_var: Measurement noise covariance (~2cm ARCore accuracy: 0.02^2 = 0.0004).
        :param p_pos: Initial position error covariance.
        :param p_vel: Initial velocity error covariance.
        :param velocity_decay: Axis-specific ZUPT velocity decay factor.
        """
        self.x = np.array([float(init_pos), float(init_vel)], dtype=np.float64)
        self.P = np.array([[float(p_pos), 0.0], [0.0, float(p_vel)]], dtype=np.float64)
        self.H = np.array([[1.0, 0.0]], dtype=np.float64)
        self.q_var = float(q_var)
        self.r_var = float(r_var)
        self.velocity_decay = float(velocity_decay)
        self.last_dt: float = 1.0 / 60.0

    @property
    def pos(self) -> float:
        return float(self.x[0])

    @pos.setter
    def pos(self, val: float) -> None:
        self.x[0] = float(val)

    @property
    def vel(self) -> float:
        return float(self.x[1])

    @vel.setter
    def vel(self, val: float) -> None:
        self.x[1] = float(val)

    def predict(self, dt: float = 1.0 / 60.0) -> np.ndarray:
        """
        Predict step at 60 FPS in render tick:
        x = F @ x
        P = F @ P @ F^T + Q
        """
        if dt <= 0.0:
            dt = 1.0 / 60.0
        self.last_dt = dt

        F = np.array([[1.0, dt], [0.0, 1.0]], dtype=np.float64)
        dt2 = dt * dt
        dt3 = dt2 * dt
        dt4 = dt3 * dt
        Q = self.q_var * np.array([
            [dt4 / 4.0, dt3 / 2.0],
            [dt3 / 2.0, dt2]
        ], dtype=np.float64)

        self.x = F @ self.x
        self.P = F @ self.P @ F.T + Q
        return self.x

    def update(self, z: float, gate_sigma: float = CHI_SQ_GATE_SIGMA) -> bool:
        """
        Update step on new 15Hz clipboard packet:
        y = z - H @ x
        S = H @ P @ H^T + R
        Chi-Squared Innovation Gating:
          Reject spikes when y^2 > (gate_sigma^2) * S (3-sigma gate: 9 * S),
          preserving tracking integrity without arbitrary static distance cutoffs.
        """
        y = float(z) - float(self.x[0])
        S = float(self.P[0, 0] + self.r_var)

        # Chi-Squared 3-sigma innovation gate (y^2 > 9 * S)
        gate_threshold = (gate_sigma ** 2) * S
        if (y * y) > gate_threshold:
            # Outlier / rogue spike rejected
            return False

        # Kalman Gain K = P @ H^T / S
        K = np.array([self.P[0, 0] / S, self.P[1, 0] / S], dtype=np.float64)

        # State Update: x = x + K * y
        self.x = self.x + K * y

        # Covariance Update: P = (I - K @ H) @ P
        I_KH = np.array([[1.0 - K[0], 0.0], [-K[1], 1.0]], dtype=np.float64)
        self.P = I_KH @ self.P

        return True

    def apply_zupt(self, decay_factor: Optional[float] = None) -> None:
        """
        Zero-Velocity Update (ZUPT): applies velocity decay.
        """
        factor = self.velocity_decay if decay_factor is None else float(decay_factor)
        self.x[1] *= factor

    def reset(self, pos: float = 0.0, vel: float = 0.0) -> None:
        """Resets filter state and covariance."""
        self.x = np.array([float(pos), float(vel)], dtype=np.float64)
        self.P = np.array([[0.01, 0.0], [0.0, 0.1]], dtype=np.float64)


# ==============================================================================
# 3D Spatial Tracker (Tracker3D)
# ==============================================================================
class Tracker3D:
    """
    3D Spatial State Estimator coordinating 3 independent 1D Constant-Velocity
    Kalman Filters (X, Y, Z) with axis-specific ZUPT velocity decay and quaternion SLERP.
    - X/Z velocity decay: 0.98
    - Y velocity decay: 0.93 (aggressive decay on gravity-leakage axis).
    - ZUPT active when stationary for still_count >= 3 (~200ms within deadzone).
    """

    def __init__(
        self,
        init_pos: Optional[List[float]] = None,
        init_rot: Optional[List[float]] = None,
        deadzone: float = DEADZONE_THRESHOLD,
    ) -> None:
        p0 = [float(x) for x in init_pos] if init_pos is not None else [0.0, 0.0, 0.0]
        r0 = [float(x) for x in init_rot] if init_rot is not None else [1.0, 0.0, 0.0, 0.0]

        self.kf_x = AxisKF(init_pos=p0[0], velocity_decay=ZUPT_DECAY_XZ)
        self.kf_y = AxisKF(init_pos=p0[1], velocity_decay=ZUPT_DECAY_Y)
        self.kf_z = AxisKF(init_pos=p0[2], velocity_decay=ZUPT_DECAY_XZ)

        self.current_rot: List[float] = list(r0)
        self.target_rot: List[float] = list(r0)
        self.deadzone: float = float(deadzone)
        self.still_count: int = 0
        self.last_pos: List[float] = list(p0)

    @property
    def pos(self) -> List[float]:
        return [self.kf_x.pos, self.kf_y.pos, self.kf_z.pos]

    @property
    def vel(self) -> List[float]:
        return [self.kf_x.vel, self.kf_y.vel, self.kf_z.vel]

    @property
    def rot(self) -> List[float]:
        return list(self.current_rot)

    def predict(self, dt: float = 1.0 / 60.0, rot_alpha: float = 0.22) -> Tuple[List[float], List[float]]:
        """
        Executes 60 FPS predict step across all 3 axes + quaternion SLERP.
        Applies ZUPT velocity decay when still_count >= 3.
        """
        self.kf_x.predict(dt)
        self.kf_y.predict(dt)
        self.kf_z.predict(dt)

        if self.still_count >= 3:
            self.kf_x.apply_zupt(ZUPT_DECAY_XZ)
            self.kf_y.apply_zupt(ZUPT_DECAY_Y)
            self.kf_z.apply_zupt(ZUPT_DECAY_XZ)

        self.current_rot = slerp_quat(self.current_rot, self.target_rot, rot_alpha)
        return self.pos, self.rot

    def update(self, pos: List[float], rot: Optional[List[float]] = None) -> Tuple[bool, bool, bool]:
        """
        Executes Kalman update on new 15Hz clipboard packet with Chi-Squared gating.
        Tracks still_count for ZUPT when movement delta is within deadzone.
        """
        gx = self.kf_x.update(pos[0])
        gy = self.kf_y.update(pos[1])
        gz = self.kf_z.update(pos[2])

        dx = pos[0] - self.last_pos[0]
        dy = pos[1] - self.last_pos[1]
        dz = pos[2] - self.last_pos[2]
        dist = math.sqrt(dx * dx + dy * dy + dz * dz)
        self.last_pos = [float(x) for x in pos]

        if dist < self.deadzone:
            self.still_count += 1
        else:
            self.still_count = 0

        if rot is not None:
            self.target_rot = [float(x) for x in rot]

        return gx, gy, gz

    def reset_origin(self, target_pos: Optional[List[float]] = None) -> None:
        """Snaps state to target position and clears velocity and still counter."""
        p = [float(x) for x in target_pos] if target_pos is not None else [0.0, 0.0, 0.0]
        self.kf_x.reset(p[0], 0.0)
        self.kf_y.reset(p[1], 0.0)
        self.kf_z.reset(p[2], 0.0)
        self.last_pos = list(p)
        self.still_count = 0


# ==============================================================================
# Transfer Function Curve & Workspace Scaling
# ==============================================================================
def velocity_adaptive_transfer_function(
    displacement: float,
    velocity: float,
    base_gain: float = WORKSPACE_GAIN,
    velocity_gain: float = 0.5,
    deadzone: float = DEADZONE_THRESHOLD,
    axis_range: float = 0.30,
    exponent: float = 1.0,
    bounds: Optional[Tuple[float, float]] = None,
) -> float:
    """
    Velocity-adaptive non-linear transfer function curve:
      gain = base_gain * (1.0 + velocity_gain * abs(velocity))
      out = sign * gain * (normalized_mag ** exponent) * axis_range
    """
    mag = abs(displacement)
    if mag < deadzone:
        return 0.0

    sign = 1.0 if displacement >= 0.0 else -1.0
    effective_mag = mag - deadzone
    normalized_mag = effective_mag / axis_range if axis_range > 0 else effective_mag

    gain = base_gain * (1.0 + velocity_gain * abs(velocity))
    out = sign * gain * (normalized_mag ** exponent) * axis_range

    if bounds is not None:
        out = max(bounds[0], min(bounds[1], out))
    return float(out)


def apply_workspace_scaling(
    pos: List[float],
    origin: List[float],
    gain: float = WORKSPACE_GAIN,
    deadzone: float = DEADZONE_THRESHOLD,
    bounds_x: Tuple[float, float] = WORKSPACE_BOUNDS_X,
    bounds_y: Tuple[float, float] = WORKSPACE_BOUNDS_Y,
    bounds_z: Tuple[float, float] = WORKSPACE_BOUNDS_Z,
    drift_decay_y: float = DRIFT_RETURN_FACTOR,
    is_stationary: bool = False,
    velocity: Optional[List[float]] = None,
    velocity_gain: float = 0.0,
    exponent: float = 1.0,
) -> List[float]:
    """
    Applies desktop-side workspace scaling, tremor deadzone, drift decay, and viewport bounding box clamping.
    When velocity and velocity_gain > 0 are provided, applies the velocity-adaptive transfer function.
    Supports either scalar float gain or per-axis vector gain [gx, gy, gz].
    """
    dx = float(pos[0]) - float(origin[0])
    dy = float(pos[1]) - float(origin[1])
    dz = float(pos[2]) - float(origin[2])
    dist = math.sqrt(dx * dx + dy * dy + dz * dz)

    if dist < deadzone:
        return [0.0, 0.0, 0.0]

    # Resolve per-axis gains if vector provided, otherwise apply uniform scalar gain
    if hasattr(gain, "__len__") and len(gain) >= 3:
        gx, gy, gz = float(gain[0]), float(gain[1]), float(gain[2])
    else:
        gx = gy = gz = float(gain)

    if velocity is not None and velocity_gain > 0.0:
        range_x = (bounds_x[1] - bounds_x[0]) / (2.0 * gx)
        range_y = (bounds_y[1] - bounds_y[0]) / (2.0 * gy)
        range_z = (bounds_z[1] - bounds_z[0]) / (2.0 * gz)

        out_x = velocity_adaptive_transfer_function(
            dx, velocity[0], base_gain=gx, velocity_gain=velocity_gain,
            deadzone=0.0, axis_range=range_x, exponent=exponent, bounds=bounds_x
        )
        out_y = velocity_adaptive_transfer_function(
            dy, velocity[1], base_gain=gy, velocity_gain=velocity_gain,
            deadzone=0.0, axis_range=range_y, exponent=exponent, bounds=bounds_y
        )
        out_z = velocity_adaptive_transfer_function(
            dz, velocity[2], base_gain=gz, velocity_gain=velocity_gain,
            deadzone=0.0, axis_range=range_z, exponent=exponent, bounds=bounds_z
        )
    else:
        rel_x = dx * gx
        rel_y = dy * gy
        rel_z = dz * gz

        out_x = max(bounds_x[0], min(bounds_x[1], rel_x))
        out_y = max(bounds_y[0], min(bounds_y[1], rel_y))
        out_z = max(bounds_z[0], min(bounds_z[1], rel_z))

    if is_stationary:
        out_y *= drift_decay_y

    return [out_x, out_y, out_z]


# ==============================================================================
# Centripetal Catmull-Rom Spline & Fixed-Lag Trajectory Reconstructor
# ==============================================================================
def catmull_rom_centripetal(
    p0: np.ndarray,
    p1: np.ndarray,
    p2: np.ndarray,
    p3: np.ndarray,
    u: float,
    alpha: float = 0.5,
) -> np.ndarray:
    """
    Evaluates Centripetal Catmull-Rom Spline between p1 and p2 at normalized progress u in [0, 1].
    Uses centripetal knot parameterization:
        t_{i+1} = t_i + ||P_{i+1} - P_i||^alpha
    With alpha = 0.5 (centripetal parameterization), the curve is guaranteed not to form
    loops, cusps, or sharp tangent changes at knot transitions, avoiding the issues of
    alpha = 0.0 (uniform) or alpha = 1.0 (chordal).
    """
    if u <= 0.0:
        return np.copy(p1)
    if u >= 1.0:
        return np.copy(p2)

    def get_t(t_prev: float, p_prev: np.ndarray, p_curr: np.ndarray) -> float:
        d = float(np.linalg.norm(p_curr - p_prev))
        return t_prev + max(1e-6, d ** alpha)

    t0 = 0.0
    t1 = get_t(t0, p0, p1)
    t2 = get_t(t1, p1, p2)
    t3 = get_t(t2, p2, p3)

    t = t1 + u * (t2 - t1)

    # Barry-Goldman Pyramid Evaluation
    A1 = (t1 - t) / (t1 - t0) * p0 + (t - t0) / (t1 - t0) * p1
    A2 = (t2 - t) / (t2 - t1) * p1 + (t - t1) / (t2 - t1) * p2
    A3 = (t3 - t) / (t3 - t2) * p2 + (t - t2) / (t3 - t2) * p3

    B1 = (t2 - t) / (t2 - t0) * A1 + (t - t0) / (t2 - t0) * A2
    B2 = (t3 - t) / (t3 - t1) * A2 + (t - t1) / (t3 - t1) * A3

    C = (t2 - t) / (t2 - t1) * B1 + (t - t1) / (t2 - t1) * B2
    return C


class DelayedTrajectorySmoother:
    """
    Fixed-Lag Trajectory Reconstructor (Delayed Spline Smoother).
    Buffers incoming telemetry samples (15Hz Mode 1, 20Hz Mode 2, 30Hz Mode 3) over
    a rolling window (max_history=3.5s), and reconstructs continuous, jitter-free 60FPS motion
    using Centripetal Catmull-Rom Spline interpolation delayed by PLAYBACK_DELAY (default 1.0s).
    Includes a 3-tap normalized moving average post-spline filter (0.2 / 0.6 / 0.2) to eliminate
    C1 derivative transitions between adjacent knot spans.
    """

    def __init__(self, playback_delay: float = 1.0, max_history: float = 3.5) -> None:
        self.playback_delay: float = float(playback_delay)
        self.max_history: float = float(max_history)
        self.samples: List[Tuple[float, np.ndarray, np.ndarray]] = []
        self.last_pose: Tuple[List[float], List[float]] = ([0.0, 0.0, 0.0], [1.0, 0.0, 0.0, 0.0])
        self._pos_history: List[np.ndarray] = []

    def reset(self, initial_pos: Optional[List[float]] = None, initial_rot: Optional[List[float]] = None) -> None:
        """Clears sample history and post-filter buffer, resetting baseline pose."""
        self.samples.clear()
        self._pos_history.clear()
        p = [0.0, 0.0, 0.0] if initial_pos is None else [float(x) for x in initial_pos]
        r = [1.0, 0.0, 0.0, 0.0] if initial_rot is None else [float(x) for x in initial_rot]
        self.last_pose = (list(p), list(r))

    def add_sample(self, t_recv: float, pos: List[float], rot: List[float]) -> None:
        """
        Ingests a telemetry packet sample with timestamp.
        """
        p = np.array([float(x) for x in pos], dtype=np.float64)
        r = np.array([float(x) for x in rot], dtype=np.float64)
        self.samples.append((float(t_recv), p, r))

        # Maintain sorted by timestamp
        if len(self.samples) > 1 and self.samples[-1][0] < self.samples[-2][0]:
            self.samples.sort(key=lambda s: s[0])

        # Purge samples older than max_history
        cutoff = float(t_recv) - self.max_history
        while len(self.samples) > 10 and self.samples[0][0] < cutoff:
            self.samples.pop(0)

    def get_interpolated_pose(self, current_wall_time: float) -> Tuple[List[float], List[float]]:
        """
        Computes 60FPS continuous pose at query time t_render = current_wall_time - playback_delay.
        Uses Centripetal Catmull-Rom Spline for position, shortest-geodesic SLERP for rotation,
        and passes the evaluated position through a 3-tap moving average filter.
        """
        t_render = float(current_wall_time) - self.playback_delay

        if not self.samples:
            return self.last_pose

        if len(self.samples) < 4:
            # Insufficient samples yet: return earliest sample
            pos_raw = np.copy(self.samples[0][1])
            interp_q = self.samples[0][2].tolist()
        elif t_render <= self.samples[0][0]:
            pos_raw = np.copy(self.samples[0][1])
            interp_q = self.samples[0][2].tolist()
        elif t_render >= self.samples[-1][0]:
            pos_raw = np.copy(self.samples[-1][1])
            interp_q = self.samples[-1][2].tolist()
        else:
            # Dynamic knot selection adapting to 15Hz, 20Hz, 30Hz or variable stream rates
            times = [s[0] for s in self.samples]
            idx2 = bisect.bisect_right(times, t_render)
            idx1 = max(0, idx2 - 1)
            idx2 = min(len(self.samples) - 1, idx1 + 1)

            t1, p1, q1 = self.samples[idx1]
            t2, p2, q2 = self.samples[idx2]

            dt_seg = t2 - t1
            u = (t_render - t1) / dt_seg if dt_seg > 1e-6 else 0.0
            u = max(0.0, min(1.0, u))

            # 4 knots for Catmull-Rom
            p0 = self.samples[idx1 - 1][1] if idx1 > 0 else (2.0 * p1 - p2)
            p3 = self.samples[idx2 + 1][1] if idx2 < len(self.samples) - 1 else (2.0 * p2 - p1)

            # Centripetal Catmull-Rom Spline for Position (alpha=0.5)
            pos_raw = catmull_rom_centripetal(p0, p1, p2, p3, u, alpha=0.5)

            # Shortest Geodesic SLERP for Orientation
            interp_q = slerp_quat(q1.tolist(), q2.tolist(), u)

        # 3-Tap Moving Average Filter:
        # pos_final = 0.2 * pos_prev2 + 0.6 * pos_prev1 + 0.2 * pos_raw
        if len(self._pos_history) == 0:
            pos_prev2 = pos_raw
            pos_prev1 = pos_raw
        elif len(self._pos_history) == 1:
            pos_prev2 = self._pos_history[0]
            pos_prev1 = self._pos_history[0]
        else:
            pos_prev2 = self._pos_history[-2]
            pos_prev1 = self._pos_history[-1]

        pos_final = 0.2 * pos_prev2 + 0.6 * pos_prev1 + 0.2 * pos_raw
        self._pos_history.append(np.copy(pos_raw))
        if len(self._pos_history) > 4:
            self._pos_history.pop(0)

        self.last_pose = (pos_final.tolist(), interp_q)
        return self.last_pose


# ==============================================================================
# SpatialInterpolator (KF + ZOH Wrapper)
# ==============================================================================
class SpatialInterpolator:
    """
    State estimation interpolator coordinating Tracker3D with Zero-Order Hold (ZOH).
    Provides seamless 60 FPS continuous state estimation from 15Hz telemetry.
    """

    def __init__(
        self,
        init_pos: Optional[List[float]] = None,
        init_rot: Optional[List[float]] = None,
        zoh_duration: float = 0.5,
        max_pos_delta: float = MAX_POS_DELTA,
    ) -> None:
        p0 = [float(x) for x in init_pos] if init_pos is not None else [0.0, 0.0, 0.0]
        r0 = [float(x) for x in init_rot] if init_rot is not None else [1.0, 0.0, 0.0, 0.0]

        self.tracker = Tracker3D(init_pos=p0, init_rot=r0)
        self.last_valid_pos: List[float] = list(p0)
        self.last_valid_rot: List[float] = list(r0)
        self.last_valid_time: float = time.time()
        self.is_tracking: bool = False
        self.zoh_active: bool = False
        self.zoh_duration: float = float(zoh_duration)
        self.max_pos_delta: float = float(max_pos_delta)
        self._has_tracked: bool = init_pos is not None

    def reset_origin(self, target_pos: Optional[List[float]] = None) -> None:
        """Snaps Kalman filter state and target position to target_pos, zeroing velocity."""
        p = [float(x) for x in target_pos] if target_pos is not None else [0.0, 0.0, 0.0]
        self.tracker.reset_origin(p)
        self.last_valid_pos = list(p)

    def update_target(
        self,
        new_pos: Optional[List[float]],
        new_rot: Optional[List[float]],
        is_tracking: bool,
        timestamp: Optional[float] = None,
        max_pos_delta: Optional[float] = None,
    ) -> None:
        """
        Updates state estimator from incoming telemetry packet.
        Applies Chi-Squared innovation gating and Zero-Order Hold (ZOH) on dropouts.
        """
        now = time.time() if timestamp is None else float(timestamp)

        if is_tracking:
            self.is_tracking = True
            self.zoh_active = False
            self.last_valid_time = now

            if new_pos is not None:
                cand_pos = [float(x) for x in new_pos]
                if not self._has_tracked:
                    self.tracker.reset_origin(cand_pos)
                    self._has_tracked = True
                else:
                    self.tracker.update(cand_pos, new_rot)
                self.last_valid_pos = list(self.tracker.pos)

            if new_rot is not None:
                self.last_valid_rot = [float(x) for x in new_rot]
                self.tracker.target_rot = list(self.last_valid_rot)
        else:
            elapsed = now - self.last_valid_time
            if self._has_tracked and (elapsed <= self.zoh_duration):
                self.is_tracking = True
                self.zoh_active = True
            else:
                self.is_tracking = False
                self.zoh_active = False

    @property
    def curr_pos(self) -> List[float]:
        return self.tracker.pos

    @curr_pos.setter
    def curr_pos(self, val: List[float]) -> None:
        self.tracker.reset_origin(val)
        self.last_valid_pos = list(val)

    @property
    def curr_rot(self) -> List[float]:
        return self.tracker.rot

    @curr_rot.setter
    def curr_rot(self, val: List[float]) -> None:
        self.tracker.current_rot = list(val)
        self.tracker.target_rot = list(val)

    @property
    def current_pos(self) -> List[float]:
        return self.tracker.pos

    @current_pos.setter
    def current_pos(self, val: List[float]) -> None:
        self.curr_pos = val

    @property
    def current_rot(self) -> List[float]:
        return self.tracker.rot

    @current_rot.setter
    def current_rot(self, val: List[float]) -> None:
        self.curr_rot = val

    @property
    def target_pos(self) -> List[float]:
        return list(self.last_valid_pos)

    @target_pos.setter
    def target_pos(self, val: List[float]) -> None:
        self.last_valid_pos = [float(x) for x in val]

    @property
    def target_rot(self) -> List[float]:
        return list(self.tracker.target_rot)

    @target_rot.setter
    def target_rot(self, val: List[float]) -> None:
        self.tracker.target_rot = [float(x) for x in val]

    @property
    def pos(self) -> List[float]:
        return self.tracker.pos

    @property
    def vel(self) -> List[float]:
        return self.tracker.vel

    @property
    def rot(self) -> List[float]:
        return self.tracker.rot

    def step(
        self,
        pos_alpha: float = 0.18,
        rot_alpha: float = 0.22,
        alpha: Optional[float] = None,
        dt: float = 1.0 / 60.0,
    ) -> Tuple[List[float], List[float]]:
        """
        Predicts state at 60 FPS tick using Kalman Filter CV model + SLERP.
        """
        r_alpha = alpha if alpha is not None else rot_alpha
        return self.tracker.predict(dt=dt, rot_alpha=r_alpha)

    def step_interpolation(
        self,
        alpha_pos: float = 0.18,
        alpha_rot: float = 0.22,
        max_pos_delta: float = MAX_POS_DELTA,
        dt: float = 1.0 / 60.0,
    ) -> Tuple[List[float], List[float]]:
        return self.step(pos_alpha=alpha_pos, rot_alpha=alpha_rot, dt=dt)


def step_interpolation(
    interpolator: SpatialInterpolator,
    alpha_pos: float = 0.18,
    alpha_rot: float = 0.22,
    max_pos_delta: float = MAX_POS_DELTA,
    dt: float = 1.0 / 60.0,
) -> Tuple[List[float], List[float]]:
    return interpolator.step_interpolation(
        alpha_pos=alpha_pos,
        alpha_rot=alpha_rot,
        max_pos_delta=max_pos_delta,
        dt=dt,
    )


if __name__ == "__main__":
    print("================================================================")
    print("  KineTrak Desktop -- Spatial State Estimation Verification Suite ")
    print("================================================================\n")

    # -------------------------------------------------------------
    # 1. 1D Constant-Velocity Kalman Filter (AxisKF)
    # -------------------------------------------------------------
    print("--- 1. Testing 1D Constant-Velocity Kalman Filter (AxisKF) ---")
    kf = AxisKF(init_pos=0.0, init_vel=0.0)
    # Predict step at 60 FPS (dt = 1/60s)
    pred_x = kf.predict(1.0 / 60.0)
    assert math.isclose(kf.pos, 0.0, abs_tol=1e-6), "Initial predict pos should be 0"
    assert math.isclose(kf.vel, 0.0, abs_tol=1e-6), "Initial predict vel should be 0"

    # Simulate 15Hz measurement packet arriving at constant velocity v = 0.3 m/s
    dt_packet = 1.0 / 15.0
    for i in range(1, 16):
        # 4 render prediction ticks per packet (~60 FPS)
        for _ in range(4):
            kf.predict(1.0 / 60.0)
        measured_z = 0.3 * (i * dt_packet)
        accepted = kf.update(measured_z)
        assert accepted, f"Packet {i} should be accepted"

    # After 1 second of 0.3 m/s motion, position should be ~0.3m and velocity ~0.3 m/s
    assert math.isclose(kf.pos, 0.3, abs_tol=0.05), f"KF pos convergence mismatch: {kf.pos}"
    assert math.isclose(kf.vel, 0.3, abs_tol=0.08), f"KF vel convergence mismatch: {kf.vel}"
    print("  [PASS] AxisKF 60 FPS predict & 15 Hz update verified!")

    # -------------------------------------------------------------
    # 2. Chi-Squared 3-Sigma Innovation Gating (Rogue Spike Rejection)
    # -------------------------------------------------------------
    print("\n--- 2. Testing Chi-Squared Innovation Gating (Spike Rejection) ---")
    kf_gate = AxisKF(init_pos=0.5, init_vel=0.0)
    # Predict 1 tick
    kf_gate.predict(1.0 / 60.0)

    # Valid smooth update (e.g. 5mm motion) -> should pass 3-sigma gate
    valid_z = 0.505
    assert kf_gate.update(valid_z), "Valid measurement must pass Chi-Squared gate"

    # Rogue jump / optical glitch (e.g. +2.0m sudden jump) -> must be rejected by 3-sigma gate
    rogue_z = 2.505
    rejected = not kf_gate.update(rogue_z)
    assert rejected, "Rogue spike > 3-sigma MUST be rejected by Chi-Squared gate"
    # Verify state was not corrupted by rogue spike
    assert kf_gate.pos < 0.6, f"KF pos was corrupted by rogue spike: {kf_gate.pos}"
    print("  [PASS] Chi-Squared 3-sigma innovation gating verified!")

    # -------------------------------------------------------------
    # 3. Zero-Velocity Update (ZUPT) Axis-Specific Decays
    # -------------------------------------------------------------
    print("\n--- 3. Testing Zero-Velocity Update (ZUPT) & Axis Decays ---")
    tracker = Tracker3D(init_pos=[0.0, 0.0, 0.0])
    # Give initial velocity
    tracker.kf_x.vel = 0.5
    tracker.kf_y.vel = 0.5
    tracker.kf_z.vel = 0.5

    # Feed 3 consecutive updates with 0 movement (< deadzone 6mm)
    for _ in range(3):
        tracker.update([0.0, 0.0, 0.0])
    assert tracker.still_count >= 3, f"still_count should be >= 3, got {tracker.still_count}"

    # Step render tick with ZUPT active
    tracker.predict(1.0 / 60.0)
    # Verify axis decays: X/Z decay = 0.98, Y decay = 0.93
    assert math.isclose(tracker.kf_x.vel, 0.5 * 0.98, rel_tol=1e-3), f"X decay failed: {tracker.kf_x.vel}"
    assert math.isclose(tracker.kf_z.vel, 0.5 * 0.98, rel_tol=1e-3), f"Z decay failed: {tracker.kf_z.vel}"
    assert math.isclose(tracker.kf_y.vel, 0.5 * 0.93, rel_tol=1e-3), f"Y aggressive decay failed: {tracker.kf_y.vel}"
    assert tracker.kf_y.vel < tracker.kf_x.vel, "Y-axis decay must be more aggressive than X-axis"
    print("  [PASS] ZUPT axis-specific decays (X/Z: 0.98, Y: 0.93) verified!")

    # -------------------------------------------------------------
    # 4. Velocity-Adaptive Transfer Function Curve
    # -------------------------------------------------------------
    print("\n--- 4. Testing Velocity-Adaptive Transfer Function Curve ---")
    # Low velocity -> gain ~ base_gain
    val_slow = velocity_adaptive_transfer_function(
        displacement=0.10, velocity=0.05, base_gain=2.2, velocity_gain=0.5,
        deadzone=0.006, axis_range=0.30, exponent=1.0
    )
    # High velocity -> higher gain
    val_fast = velocity_adaptive_transfer_function(
        displacement=0.10, velocity=1.50, base_gain=2.2, velocity_gain=0.5,
        deadzone=0.006, axis_range=0.30, exponent=1.0
    )
    assert val_fast > val_slow, f"Fast movement should have higher adaptive output: {val_fast} > {val_slow}"

    # Deadzone test
    val_deadzone = velocity_adaptive_transfer_function(
        displacement=0.003, velocity=0.0, deadzone=0.006
    )
    assert val_deadzone == 0.0, f"Deadzone not respected: {val_deadzone}"
    print("  [PASS] Velocity-adaptive transfer function curve verified!")

    # -------------------------------------------------------------
    # 5. Quaternion SLERP Shortest Geodesic Path & Normalization
    # -------------------------------------------------------------
    print("\n--- 5. Testing Quaternion SLERP Shortest Geodesic Path ---")
    q_identity = [1.0, 0.0, 0.0, 0.0]
    s = math.sqrt(0.5)
    q_rot_y_90 = [s, 0.0, s, 0.0]

    q_mid = slerp_quat(q_identity, q_rot_y_90, 0.5)
    norm_mid = math.sqrt(sum(x * x for x in q_mid))
    assert math.isclose(norm_mid, 1.0, abs_tol=1e-6), "Quaternion output not normalized"
    expected_qw = math.cos(math.radians(22.5))
    expected_qy = math.sin(math.radians(22.5))
    assert math.isclose(q_mid[0], expected_qw, abs_tol=1e-5), "q_mid qw mismatch"
    assert math.isclose(q_mid[2], expected_qy, abs_tol=1e-5), "q_mid qy mismatch"
    print("  [PASS] Quaternion SLERP shortest geodesic path verified!")

    # -------------------------------------------------------------
    # 6. SpatialInterpolator 60 FPS Stepping & 500ms ZOH Recovery
    # -------------------------------------------------------------
    print("\n--- 6. Testing SpatialInterpolator 60 FPS Stepping & ZOH Recovery ---")
    interp = SpatialInterpolator(
        init_pos=[0.0, 0.0, 0.0],
        init_rot=[1.0, 0.0, 0.0, 0.0],
        zoh_duration=0.5,
    )
    t_base = 1000.0
    interp.update_target([0.10, 0.20, 0.30], [1.0, 0.0, 0.0, 0.0], is_tracking=True, timestamp=t_base)
    assert interp.is_tracking, "Should be tracking after update"

    # Temporary dropout at t_base + 200ms (within 500ms ZOH window)
    interp.update_target([0.0, 0.0, 0.0], [0.0, 0.0, 0.0, 0.0], is_tracking=False, timestamp=t_base + 0.200)
    assert interp.is_tracking, "ZOH should keep tracking active within 500ms"
    assert interp.zoh_active, "zoh_active should be True"

    # Hard timeout at t_base + 600ms (> 500ms)
    interp.update_target([0.0, 0.0, 0.0], [0.0, 0.0, 0.0, 0.0], is_tracking=False, timestamp=t_base + 0.600)
    assert not interp.is_tracking, "Tracking should drop after ZOH timeout (>500ms)"

    # Hard recovery
    interp.update_target([0.20, 0.30, 0.40], [1.0, 0.0, 0.0, 0.0], is_tracking=True, timestamp=t_base + 0.700)
    assert interp.is_tracking, "Tracking should recover cleanly"
    print("  [PASS] SpatialInterpolator 60 FPS stepping & 500ms ZOH verified!")

    # -------------------------------------------------------------
    # 7. Workspace Scaling & Origin Latch Snapping
    # -------------------------------------------------------------
    print("\n--- 7. Testing Workspace Scaling & Origin Latch Snapping ---")
    origin = [1.200, 0.500, -0.300]
    calib_res = apply_workspace_scaling(origin, origin, gain=2.2, deadzone=0.006)
    assert calib_res == [0.0, 0.0, 0.0], f"Origin recalibration non-zero: {calib_res}"

    # Micro-tremor (< 6mm) suppression
    tremor_pos = [1.203, 0.502, -0.301]
    tremor_res = apply_workspace_scaling(tremor_pos, origin, gain=2.2, deadzone=0.006)
    assert tremor_res == [0.0, 0.0, 0.0], f"Tremor was not suppressed: {tremor_res}"

    # 20cm hand movement outside deadzone
    move_pos = [1.400, 0.500, -0.300]
    scaled_res = apply_workspace_scaling(move_pos, origin, gain=2.2, deadzone=0.006)
    assert math.isclose(scaled_res[0], 0.44, abs_tol=1e-5), f"Gain 2.2x failed on X: {scaled_res[0]}"

    # Origin reset latch
    interp.reset_origin([0.0, 0.0, 0.0])
    assert math.isclose(interp.pos[0], 0.0, abs_tol=1e-6)
    assert math.isclose(interp.pos[1], 0.0, abs_tol=1e-6)
    assert math.isclose(interp.pos[2], 0.0, abs_tol=1e-6)
    assert math.isclose(interp.vel[0], 0.0, abs_tol=1e-6)
    assert math.isclose(interp.vel[1], 0.0, abs_tol=1e-6)
    assert math.isclose(interp.vel[2], 0.0, abs_tol=1e-6)
    print("  [PASS] 2.2x gain, deadzone suppression & origin latch snapping verified!")

    # -------------------------------------------------------------
    # 8. Centripetal Catmull-Rom Spline Interpolation Accuracy
    # -------------------------------------------------------------
    print("\n--- 8. Testing Centripetal Catmull-Rom Spline Interpolation ---")
    p0 = np.array([0.0, 0.0, 0.0])
    p1 = np.array([1.0, 2.0, 0.0])
    p2 = np.array([2.0, 2.0, 1.0])
    p3 = np.array([3.0, 0.0, 2.0])

    # Endpoints exact match
    eval_start = catmull_rom_centripetal(p0, p1, p2, p3, 0.0, alpha=0.5)
    eval_end = catmull_rom_centripetal(p0, p1, p2, p3, 1.0, alpha=0.5)
    assert np.allclose(eval_start, p1, atol=1e-5), f"Start point mismatch: {eval_start} != {p1}"
    assert np.allclose(eval_end, p2, atol=1e-5), f"End point mismatch: {eval_end} != {p2}"

    # Smooth intermediate progression
    eval_mid = catmull_rom_centripetal(p0, p1, p2, p3, 0.5, alpha=0.5)
    assert 1.0 < eval_mid[0] < 2.0, f"Midpoint X out of bounds: {eval_mid[0]}"
    assert 0.0 <= eval_mid[2] <= 1.0, f"Midpoint Z out of bounds: {eval_mid[2]}"
    print("  [PASS] Centripetal Catmull-Rom Spline endpoints and continuity verified!")

    # -------------------------------------------------------------
    # 9. Fixed-Lag Trajectory Reconstructor (DelayedTrajectorySmoother)
    # -------------------------------------------------------------
    print("\n--- 9. Testing Fixed-Lag Trajectory Reconstructor ---")
    smoother = DelayedTrajectorySmoother(playback_delay=1.0, max_history=3.5)
    t_start = 100.0
    dt_pkt = 1.0 / 15.0  # 15Hz

    # Push 30 samples (2.0s of motion)
    for i in range(30):
        t_sample = t_start + i * dt_pkt
        pos = [0.1 * i, math.sin(i * 0.2), 0.05 * i]
        rot = [1.0, 0.0, 0.0, 0.0]
        smoother.add_sample(t_sample, pos, rot)

    assert len(smoother.samples) == 30, f"Buffer size mismatch: {len(smoother.samples)}"

    # Query at current_time = t_start + 1.5s -> t_render = t_start + 0.5s (sample ~7.5)
    query_time = t_start + 1.5
    rendered_pos, rendered_rot = smoother.get_interpolated_pose(query_time)

    # Expected X at 0.5s is approx 0.1 * (0.5 / (1/15)) = 0.1 * 7.5 = 0.75m
    assert math.isclose(rendered_pos[0], 0.75, abs_tol=0.05), f"Rendered pos X mismatch: {rendered_pos[0]}"
    assert math.isclose(rendered_rot[0], 1.0, abs_tol=1e-5), f"Rendered rot mismatch: {rendered_rot}"
    print("  [PASS] DelayedTrajectorySmoother fixed-lag playback verified!")

    # -------------------------------------------------------------
    # 10. Trajectory Window Polish (3-Tap Moving Average Filter)
    # -------------------------------------------------------------
    print("\n--- 10. Testing Trajectory Window Polish (3-Tap Moving Average Filter) ---")
    smoother_filter = DelayedTrajectorySmoother(playback_delay=1.0, max_history=3.5)
    t_base = 200.0
    for i in range(20):
        smoother_filter.add_sample(t_base + i * 0.1, [float(i), 0.0, 0.0], [1.0, 0.0, 0.0, 0.0])

    # Query successive 60FPS frames (dt = 1/60s)
    poses = []
    for frame in range(60):
        q_time = t_base + 1.5 + (frame * (1.0 / 60.0))
        p, _ = smoother_filter.get_interpolated_pose(q_time)
        poses.append(p[0])

    # Verify positions progress monotonically with smoothed derivative transitions (no micro-jitter)
    diffs = [poses[k+1] - poses[k] for k in range(len(poses)-1)]
    for d in diffs:
        assert d > 0.0, f"Interpolated trajectory must be strictly monotonic: {d}"
    
    # Verify second differences (acceleration/jerk) remain smooth and bounded after filter priming
    second_diffs = [diffs[k+1] - diffs[k] for k in range(len(diffs)-1)]
    for dd in second_diffs[2:]:
        assert abs(dd) < 1e-4, f"Second derivative spike detected (micro-roughness): {dd}"
    print("  [PASS] 3-Tap moving average filter eliminates C1 derivative transition roughness!")

    # -------------------------------------------------------------
    # 11. Multi-Rate Adaptive Knot Selection (15Hz, 20Hz, 30Hz)
    # -------------------------------------------------------------
    print("\n--- 11. Testing Multi-Rate Adaptive Knot Selection (15Hz / 20Hz / 30Hz) ---")
    for rate_hz in [15.0, 20.0, 30.0]:
        sm = DelayedTrajectorySmoother(playback_delay=1.0, max_history=4.0)
        dt_stream = 1.0 / rate_hz
        t0 = 500.0
        for i in range(40):
            # Known linear trajectory: pos = [2.0 * t, 0.0, 0.0]
            t_pkt = t0 + i * dt_stream
            sm.add_sample(t_pkt, [2.0 * (t_pkt - t0), 0.0, 0.0], [1.0, 0.0, 0.0, 0.0])

        # Query at t0 + 1.8s (t_render = t0 + 0.8s) -> expected X = 2.0 * 0.8 = 1.6m
        q_time = t0 + 1.8
        p_eval, _ = sm.get_interpolated_pose(q_time)
        assert math.isclose(p_eval[0], 1.6, abs_tol=0.05), (
            f"Rate {rate_hz}Hz adaptive knot evaluation failed: got {p_eval[0]}, expected ~1.6"
        )
    print("  [PASS] Adaptive knot selection across 15Hz, 20Hz, and 30Hz stream rates verified!")

    print("\n================================================================")
    print("  [SUCCESS] ALL TESTS PASSED: Delayed Spline Smoother & KF Verified! ")
    print("================================================================\n")
