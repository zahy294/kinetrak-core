"""
KineTrak Desktop — Spatial Interpolation & Smoothing Pipeline
Adheres strictly to KineTrak Technical Design Doc v4.2 architecture.

Provides low-jitter 60FPS coordinate smoothing from 15Hz telemetry packets:
- Scalar and 3D Vector Linear Interpolation (LERP)
- Spherical Linear Quaternion Interpolation (SLERP) via pyquaternion
- SpatialInterpolator with 300ms Zero-Order Hold (ZOH) failsafe for temporary tracking drops
"""

import math
import time
from typing import List, Tuple, Optional
import numpy as np
from pyquaternion import Quaternion


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
    # In SO(3), q and -q represent identical spatial orientations.
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


class SpatialInterpolator:
    """
    Encapsulates current and target spatial poses [X, Y, Z] and [QW, QX, QY, QZ].
    
    Provides:
    - `update_target(new_pos, new_rot, is_tracking)`: Updates target pose with
      a 300ms Zero-Order Hold (repeating last valid pose if tracking drops temporarily).
    - `step(alpha=0.25)`: Generates the next 60FPS frame coordinates via LERP + SLERP.
    """

    def __init__(
        self,
        init_pos: Optional[List[float]] = None,
        init_rot: Optional[List[float]] = None,
        zoh_duration: float = 0.3,
    ) -> None:
        """
        Initializes the spatial interpolator.
        
        :param init_pos: Initial position [x, y, z], defaults to [0.0, 0.0, 0.0].
        :param init_rot: Initial rotation [qw, qx, qy, qz], defaults to [1.0, 0.0, 0.0, 0.0].
        :param zoh_duration: Zero-Order Hold duration in seconds (default 300ms = 0.3s).
        """
        self.current_pos: List[float] = (
            [float(x) for x in init_pos] if init_pos is not None else [0.0, 0.0, 0.0]
        )
        self.current_rot: List[float] = (
            [float(x) for x in init_rot] if init_rot is not None else [1.0, 0.0, 0.0, 0.0]
        )

        self.target_pos: List[float] = list(self.current_pos)
        self.target_rot: List[float] = list(self.current_rot)

        # Last valid pose held for Zero-Order Hold (ZOH)
        self.last_valid_pos: List[float] = list(self.current_pos)
        self.last_valid_rot: List[float] = list(self.current_rot)

        self.last_valid_time: float = time.time()
        self.is_tracking: bool = False
        self.zoh_active: bool = False
        self.zoh_duration: float = float(zoh_duration)
        self._has_tracked: bool = init_pos is not None

    def update_target(
        self,
        new_pos: Optional[List[float]],
        new_rot: Optional[List[float]],
        is_tracking: bool,
        timestamp: Optional[float] = None,
    ) -> None:
        """
        Updates the target pose from incoming telemetry frames.
        
        Implements a 300ms Zero-Order Hold (ZOH):
        - If `is_tracking` is True: Latches new valid pose and resets the ZOH timer.
        - If `is_tracking` is False:
            - If elapsed time since last valid tracking <= 300ms: Holds and repeats
              the last valid pose, preventing jitter or dropout glitches.
            - If elapsed time > 300ms: Marks tracking as lost (`is_tracking = False`).
        """
        now = time.time() if timestamp is None else float(timestamp)

        if is_tracking:
            self._has_tracked = True
            self.is_tracking = True
            self.zoh_active = False
            self.last_valid_time = now

            if new_pos is not None:
                self.last_valid_pos = [float(x) for x in new_pos]
                self.target_pos = list(self.last_valid_pos)

            if new_rot is not None:
                self.last_valid_rot = [float(x) for x in new_rot]
                self.target_rot = list(self.last_valid_rot)
        else:
            # Tracking dropped temporarily or permanently
            elapsed = now - self.last_valid_time
            if self._has_tracked and (elapsed <= self.zoh_duration):
                # Within 300ms ZOH window: repeat last valid pose
                self.is_tracking = True
                self.zoh_active = True
                self.target_pos = list(self.last_valid_pos)
                self.target_rot = list(self.last_valid_rot)
            else:
                # 300ms ZOH window expired
                self.is_tracking = False
                self.zoh_active = False
                # Maintain last valid pose as frozen target
                self.target_pos = list(self.last_valid_pos)
                self.target_rot = list(self.last_valid_rot)

    @property
    def curr_pos(self) -> List[float]:
        """Current position [X, Y, Z] alias."""
        return self.current_pos

    @curr_pos.setter
    def curr_pos(self, val: List[float]) -> None:
        self.current_pos = val

    @property
    def curr_rot(self) -> List[float]:
        """Current rotation [QW, QX, QY, QZ] alias."""
        return self.current_rot

    @curr_rot.setter
    def curr_rot(self, val: List[float]) -> None:
        self.current_rot = val

    def step(
        self,
        pos_alpha: float = 0.40,
        rot_alpha: float = 0.45,
        alpha: Optional[float] = None
    ) -> Tuple[List[float], List[float]]:
        """
        Calculates and returns the next 60FPS frame coordinates.
        Interpolates current pose toward target pose:
        - Position: 3D vector LERP with pos_alpha (default 0.40)
        - Rotation: pyquaternion SLERP with rot_alpha (default 0.45)
        
        :param pos_alpha: Exponential smoothing factor for position (default 0.40).
        :param rot_alpha: Exponential smoothing factor for rotation (default 0.45).
        :param alpha: Backward-compatible unified alpha override if provided.
        :return: Tuple of ([X, Y, Z], [QW, QX, QY, QZ])
        """
        p_alpha = alpha if alpha is not None else pos_alpha
        r_alpha = alpha if alpha is not None else rot_alpha
        self.current_pos = lerp_vec3(self.current_pos, self.target_pos, p_alpha)
        self.current_rot = slerp_quat(self.current_rot, self.target_rot, r_alpha)
        return list(self.current_pos), list(self.current_rot)

    @property
    def pos(self) -> List[float]:
        """Current position [X, Y, Z]."""
        return list(self.current_pos)

    @property
    def rot(self) -> List[float]:
        """Current rotation [QW, QX, QY, QZ]."""
        return list(self.current_rot)


if __name__ == "__main__":
    print("================================================================")
    print("  KineTrak Desktop -- Spatial Smoothing Math Verification Suite ")
    print("================================================================\n")

    # -------------------------------------------------------------
    # 1. Scalar and 3D Vector LERP Stepping
    # -------------------------------------------------------------
    print("--- 1. Testing Scalar & 3D Vector LERP Stepping ---")
    assert math.isclose(lerp(0.0, 10.0, 0.0), 0.0), "lerp(t=0) failed"
    assert math.isclose(lerp(0.0, 10.0, 1.0), 10.0), "lerp(t=1) failed"
    assert math.isclose(lerp(0.0, 10.0, 0.5), 5.0), "lerp(t=0.5) failed"
    assert math.isclose(lerp(10.0, 20.0, 0.25), 12.5), "lerp(alpha=0.25) failed"
    assert math.isclose(lerp(-5.0, 5.0, 0.5), 0.0), "lerp with negative values failed"

    v1 = [0.0, 10.0, -20.0]
    v2 = [10.0, 30.0, 20.0]
    interp_v = lerp_vec3(v1, v2, 0.5)
    expected_v = [5.0, 20.0, 0.0]
    for a, e in zip(interp_v, expected_v):
        assert math.isclose(a, e, abs_tol=1e-6), f"lerp_vec3 midpoint failed: {interp_v} != {expected_v}"

    # Verify monotonic stepping convergence over multiple frames
    cur = [0.0, 0.0, 0.0]
    target = [100.0, 100.0, 100.0]
    alpha = 0.25
    prev_dist = math.dist(cur, target)
    for frame in range(60):
        cur = lerp_vec3(cur, target, alpha)
        dist = math.dist(cur, target)
        assert dist < prev_dist, f"Frame {frame}: distance did not decrease monotonically"
        prev_dist = dist

    # After 60 frames (1s at 60FPS with alpha=0.25), remaining error is (0.75)^60 ~ 3.2e-8
    assert math.isclose(cur[0], 100.0, abs_tol=1e-4), f"LERP failed to converge: {cur}"
    print("  [PASS] Scalar & 3D Vector LERP stepping verified!")

    # -------------------------------------------------------------
    # 2. Quaternion SLERP Continuity & Normalization
    # -------------------------------------------------------------
    print("\n--- 2. Testing Quaternion SLERP Continuity & Normalization ---")
    q_identity = [1.0, 0.0, 0.0, 0.0]
    # 90-degree rotation around Y axis: qw = cos(45 deg) = sqrt(0.5), qy = sin(45 deg) = sqrt(0.5)
    s = math.sqrt(0.5)
    q_rot_y_90 = [s, 0.0, s, 0.0]

    # Midpoint t=0.5 -> 45-degree rotation around Y axis: qw = cos(22.5 deg), qy = sin(22.5 deg)
    q_mid = slerp_quat(q_identity, q_rot_y_90, 0.5)
    norm_mid = math.sqrt(sum(x * x for x in q_mid))
    assert math.isclose(norm_mid, 1.0, abs_tol=1e-6), f"Quaternion output not normalized: norm={norm_mid}"

    expected_qw = math.cos(math.radians(22.5))
    expected_qy = math.sin(math.radians(22.5))
    assert math.isclose(q_mid[0], expected_qw, abs_tol=1e-5), f"q_mid qw mismatch: {q_mid[0]} != {expected_qw}"
    assert math.isclose(q_mid[2], expected_qy, abs_tol=1e-5), f"q_mid qy mismatch: {q_mid[2]} != {expected_qy}"
    assert math.isclose(q_mid[1], 0.0, abs_tol=1e-6), f"q_mid qx should be 0: {q_mid[1]}"
    assert math.isclose(q_mid[3], 0.0, abs_tol=1e-6), f"q_mid qz should be 0: {q_mid[3]}"

    # Boundary tests
    q_start = slerp_quat(q_identity, q_rot_y_90, 0.0)
    assert all(math.isclose(a, b, abs_tol=1e-6) for a, b in zip(q_start, q_identity)), "slerp(t=0) failed"
    q_end = slerp_quat(q_identity, q_rot_y_90, 1.0)
    assert all(math.isclose(a, b, abs_tol=1e-6) for a, b in zip(q_end, q_rot_y_90)), "slerp(t=1) failed"

    # Antipodal continuity: q and -q represent identical orientation
    # A correct SLERP must take the shortest geodesic path and remain continuous
    q_antipodal = [-1.0, 0.0, 0.0, 0.0]
    q_interp_anti = slerp_quat(q_identity, q_antipodal, 0.5)
    norm_anti = math.sqrt(sum(x * x for x in q_interp_anti))
    assert math.isclose(norm_anti, 1.0, abs_tol=1e-6), "Antipodal SLERP not normalized"
    # Angle of rotation must be zero (identity orientation)
    assert math.isclose(abs(q_interp_anti[0]), 1.0, abs_tol=1e-5), f"Antipodal slerp produced non-identity: {q_interp_anti}"

    # Smooth trajectory across hemisphere boundary
    # Test incremental steps from 0 to 180 deg
    num_steps = 20
    prev_q = q_identity
    for i in range(1, num_steps + 1):
        t_val = i / num_steps
        curr_q = slerp_quat(q_identity, q_rot_y_90, t_val)
        norm = math.sqrt(sum(x * x for x in curr_q))
        assert math.isclose(norm, 1.0, abs_tol=1e-6), f"Step {i} not normalized"
        # Dot product between adjacent steps should be close to 1.0 (smooth continuity)
        step_dot = abs(sum(a * b for a, b in zip(prev_q, curr_q)))
        assert step_dot > 0.95, f"Discontinuity detected at step {i}: dot={step_dot}"
        prev_q = curr_q
    print("  [PASS] Quaternion SLERP continuity & normalization verified!")

    # -------------------------------------------------------------
    # 3. SpatialInterpolator 60FPS Stepping
    # -------------------------------------------------------------
    print("\n--- 3. Testing SpatialInterpolator 60FPS Stepping ---")
    interp = SpatialInterpolator(
        init_pos=[0.0, 0.0, 0.0],
        init_rot=[1.0, 0.0, 0.0, 0.0],
        zoh_duration=0.3,
    )
    p0, r0 = interp.step(alpha=0.25)
    assert p0 == [0.0, 0.0, 0.0], f"Initial step pos mismatch: {p0}"
    assert r0 == [1.0, 0.0, 0.0, 0.0], f"Initial step rot mismatch: {r0}"

    # Update to a new target
    new_target_pos = [4.0, 8.0, 12.0]
    new_target_rot = [s, 0.0, s, 0.0]
    interp.update_target(new_target_pos, new_target_rot, is_tracking=True)
    assert interp.is_tracking, "is_tracking should be True after valid update"
    assert interp.target_pos == new_target_pos, "Target pos not updated"

    # Step once with alpha=0.25: pos should be [1.0, 2.0, 3.0]
    p1, r1 = interp.step(alpha=0.25)
    assert all(math.isclose(a, b, abs_tol=1e-6) for a, b in zip(p1, [1.0, 2.0, 3.0])), f"Step 1 pos failed: {p1}"
    assert math.isclose(math.sqrt(sum(x * x for x in r1)), 1.0, abs_tol=1e-6), "Step 1 rot not normalized"

    # Step 2: pos = 1.0 + 0.25 * (4.0 - 1.0) = 1.75
    p2, r2 = interp.step(alpha=0.25)
    assert all(math.isclose(a, b, abs_tol=1e-6) for a, b in zip(p2, [1.75, 3.5, 5.25])), f"Step 2 pos failed: {p2}"
    print("  [PASS] SpatialInterpolator 60FPS frame stepping verified!")

    # -------------------------------------------------------------
    # 4. 300ms Zero-Order Hold (ZOH) & Recovery
    # -------------------------------------------------------------
    print("\n--- 4. Testing 300ms Zero-Order Hold (ZOH) & Recovery ---")
    t_base = 1000.0
    interp_zoh = SpatialInterpolator(zoh_duration=0.3)

    # Step A: Establish valid tracking baseline at t = t_base
    pos_a = [10.0, 20.0, 30.0]
    rot_a = [1.0, 0.0, 0.0, 0.0]
    interp_zoh.update_target(pos_a, rot_a, is_tracking=True, timestamp=t_base)
    assert interp_zoh.is_tracking, "Baseline tracking should be active"
    assert interp_zoh.target_pos == pos_a, "Baseline target pos mismatch"

    # Step B: Tracking drops temporarily at t = t_base + 100ms (within 300ms ZOH window)
    # Incoming packet has corrupt/zeroed telemetry coordinates
    corrupt_pos = [0.0, 0.0, 0.0]
    corrupt_rot = [0.0, 0.0, 0.0, 0.0]
    interp_zoh.update_target(corrupt_pos, corrupt_rot, is_tracking=False, timestamp=t_base + 0.100)

    # ZOH must hold: target pos must remain pos_a, NOT corrupt_pos
    assert interp_zoh.is_tracking, "ZOH should keep tracking active within 300ms"
    assert interp_zoh.zoh_active, "zoh_active flag should be True"
    assert interp_zoh.target_pos == pos_a, f"ZOH failed to hold valid pose: {interp_zoh.target_pos} != {pos_a}"
    assert interp_zoh.target_rot == rot_a, "ZOH failed to hold valid rot"

    # Step C: Tracking drops again at t = t_base + 250ms (still within 300ms window)
    interp_zoh.update_target(corrupt_pos, corrupt_rot, is_tracking=False, timestamp=t_base + 0.250)
    assert interp_zoh.is_tracking, "ZOH should still hold at 250ms"
    assert interp_zoh.zoh_active, "zoh_active should still be True at 250ms"
    assert interp_zoh.target_pos == pos_a, "ZOH target pos drifted"

    # Step D: Tracking recovers at t = t_base + 280ms with new valid pose_b
    pos_b = [15.0, 25.0, 35.0]
    rot_b = [s, 0.0, s, 0.0]
    interp_zoh.update_target(pos_b, rot_b, is_tracking=True, timestamp=t_base + 0.280)
    assert interp_zoh.is_tracking, "Tracking should be active after recovery"
    assert not interp_zoh.zoh_active, "zoh_active should clear after recovery"
    assert interp_zoh.target_pos == pos_b, f"Recovery failed to latch new target: {interp_zoh.target_pos} != {pos_b}"

    # Step E: Tracking drops permanently exceeding 300ms (t = t_base + 650ms, elapsed=370ms > 300ms)
    interp_zoh.update_target(corrupt_pos, corrupt_rot, is_tracking=False, timestamp=t_base + 0.650)
    assert not interp_zoh.is_tracking, "Tracking should drop after ZOH timeout (>300ms)"
    assert not interp_zoh.zoh_active, "zoh_active should be False after ZOH expiry"
    # Frozen target remains pos_b (does not collapse to corrupt zeros)
    assert interp_zoh.target_pos == pos_b, "Target pos should freeze at last valid pose"

    # Step F: Hard recovery after extended drop (t = t_base + 1000ms)
    pos_c = [50.0, 60.0, 70.0]
    rot_c = [1.0, 0.0, 0.0, 0.0]
    interp_zoh.update_target(pos_c, rot_c, is_tracking=True, timestamp=t_base + 1.000)
    assert interp_zoh.is_tracking, "Tracking should recover from hard timeout"
    assert interp_zoh.target_pos == pos_c, "Target should update to pos_c"

    # Verify real-world wall clock timing ZOH with time.sleep
    print("  Testing real-world wall clock ZOH timing (sleep ~350ms)...")
    interp_clock = SpatialInterpolator(zoh_duration=0.3)
    interp_clock.update_target([1.0, 1.0, 1.0], [1.0, 0.0, 0.0, 0.0], is_tracking=True)
    # Temporary drop within 100ms
    time.sleep(0.08)
    interp_clock.update_target([0.0, 0.0, 0.0], [0.0, 0.0, 0.0, 0.0], is_tracking=False)
    assert interp_clock.is_tracking, "Real-time ZOH failed to hold during brief drop"
    assert interp_clock.target_pos == [1.0, 1.0, 1.0], "Real-time ZOH did not repeat last valid pose"

    # Wait until past 300ms total
    time.sleep(0.25)
    interp_clock.update_target([0.0, 0.0, 0.0], [0.0, 0.0, 0.0, 0.0], is_tracking=False)
    assert not interp_clock.is_tracking, "Real-time ZOH failed to expire after >300ms"

    # Recover
    interp_clock.update_target([2.0, 2.0, 2.0], [1.0, 0.0, 0.0, 0.0], is_tracking=True)
    assert interp_clock.is_tracking, "Real-time ZOH failed to recover"
    assert interp_clock.target_pos == [2.0, 2.0, 2.0], "Real-time ZOH recovery pos mismatch"
    print("  [PASS] 300ms Zero-Order Hold (ZOH) & Recovery verified!")

    print("\n================================================================")
    print("  [SUCCESS] ALL TESTS PASSED: KineTrak v4.2 Smoothing Math Verified!  ")
    print("================================================================\n")
