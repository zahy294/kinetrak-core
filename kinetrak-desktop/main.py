"""
KineTrak Desktop — 6-DOF Spatial Viewport & Host Engine
"""

import sys
import os
import time
import math
import argparse
import threading
from collections import deque
import pygame
from pygame.locals import *
from OpenGL.GL import *
from OpenGL.GLU import *
import numpy as np
from pyquaternion import Quaternion

from clipboard_hook import ClipboardWatcher

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

# --- Viewport Configuration ---
WINDOW_WIDTH = 1280
WINDOW_HEIGHT = 720
TARGET_FPS = 60

# --- Interpolation Smoothing Parameters ---
# Alpha blend factor per frame (60Hz loop absorbing 15Hz telemetry)
LERP_FACTOR = 0.40
SLERP_FACTOR = 0.45


class HostEngine:
    def __init__(self, synthetic_mode=False, test_frames=0):
        pygame.init()
        pygame.font.init()
        pygame.display.set_caption("KineTrak Desktop Viewport — 6-DOF Spatial Copilot")
        self.screen = pygame.display.set_mode(
            (WINDOW_WIDTH, WINDOW_HEIGHT), DOUBLEBUF | OPENGL
        )
        self.clock = pygame.time.Clock()
        self.font = pygame.font.SysFont("Consolas", 18)
        self.font_title = pygame.font.SysFont("Consolas", 22, bold=True)

        # Origin calibration & tracking valid state
        self.origin_pos = None
        self.is_tracking_valid = True
        self.last_packet_time = time.time() if synthetic_mode else 0.0

        # Telemetry State (Raw incoming target)
        self.target_pos = np.array([0.0, 0.0, -5.0], dtype=np.float32)
        self.target_rot = Quaternion(1.0, 0.0, 0.0, 0.0)  # [qw, qx, qy, qz]
        self.last_seq = 0
        self.is_stale = False
        self.gesture_state = "IDLE"
        self.active_action = "NONE"
        self.action_display_timer = 0.0
        self.calib_display_timer = 0.0

        # Viewport Filtered State (Rendered smoothed coordinates)
        self.curr_pos = np.array([0.0, 0.0, -5.0], dtype=np.float32)
        self.curr_rot = Quaternion(1.0, 0.0, 0.0, 0.0)

        # Trajectory Ribbon History (Dev 3 feature)
        self.trajectory_history = deque(maxlen=240)
        self.show_trajectory = True

        # Test and synthetic execution modes
        self.test_frames = test_frames
        self.frame_count = 0
        self.synthetic_mode = synthetic_mode
        self.synthetic_thread = None
        self.synthetic_running = False

        # Precompute rounded chassis 2D perimeter points
        self._chassis_pts = self._generate_rounded_rect_points(
            hw=0.375, hh=0.75, cr=0.045, segments_per_corner=8
        )

        # Start Clipboard Watcher
        self.watcher = ClipboardWatcher(state_callback=self.on_telemetry_packet)
        self.watcher.start()

        if self.synthetic_mode:
            self.start_synthetic()

    @staticmethod
    def _generate_rounded_rect_points(hw, hh, cr, segments_per_corner=8):
        """Generates 2D (x, y) vertices for a rounded rectangle centered at origin."""
        pts = []
        # Top-right corner (0 to pi/2)
        cx, cy = hw - cr, hh - cr
        for i in range(segments_per_corner + 1):
            th = 0.5 * math.pi * i / segments_per_corner
            pts.append((cx + cr * math.cos(th), cy + cr * math.sin(th)))
        # Top-left corner (pi/2 to pi)
        cx, cy = -hw + cr, hh - cr
        for i in range(segments_per_corner + 1):
            th = 0.5 * math.pi + 0.5 * math.pi * i / segments_per_corner
            pts.append((cx + cr * math.cos(th), cy + cr * math.sin(th)))
        # Bottom-left corner (pi to 3*pi/2)
        cx, cy = -hw + cr, -hh + cr
        for i in range(segments_per_corner + 1):
            th = math.pi + 0.5 * math.pi * i / segments_per_corner
            pts.append((cx + cr * math.cos(th), cy + cr * math.sin(th)))
        # Bottom-right corner (3*pi/2 to 2*pi)
        cx, cy = hw - cr, -hh + cr
        for i in range(segments_per_corner + 1):
            th = 1.5 * math.pi + 0.5 * math.pi * i / segments_per_corner
            pts.append((cx + cr * math.cos(th), cy + cr * math.sin(th)))
        return pts

    def on_telemetry_packet(self, data):
        """Callback invoked by ClipboardWatcher thread on valid packet arrival."""
        if data.get("state") == 0 or data.get("stale"):
            self.is_tracking_valid = False
            self.is_stale = True
            return

        # When valid data arrives (state == 1 and not stale):
        self.is_tracking_valid = True
        self.is_stale = False
        self.last_packet_time = time.time()
        self.last_seq = data.get("seq", self.last_seq)
        self.gesture_state = data.get("gesture_state", "IDLE")

        # Calibrate origin on first valid frame:
        raw_pos = np.array(data["pos"], dtype=np.float32)
        if self.origin_pos is None:
            self.origin_pos = raw_pos.copy()
            self.calib_display_timer = time.time() + 2.0
            print(f"[KineTrak] Calibrated zero origin at: {self.origin_pos}")

        # Compute relative movement delta:
        delta_pos = raw_pos - self.origin_pos
        self.target_pos = np.array([
            delta_pos[0] * 3.0,
            delta_pos[1] * 3.0,
            -5.0 + (delta_pos[2] * 2.0)
        ], dtype=np.float32)

        # Update target rotation from unit quaternion:
        q = data["rot"]
        try:
            self.target_rot = Quaternion(q[0], q[1], q[2], q[3]).normalised
        except Exception:
            pass

        # Discrete Action triggers
        action = data.get("action", "NULL")
        if action and action != "NULL":
            self.trigger_action(action)

    def trigger_action(self, action):
        """Executes discrete high-level commands decoded from gestures."""
        self.active_action = action
        self.action_display_timer = time.time() + 2.0

        if action in ("RESET", "ACTION:RESET"):
            self.origin_pos = None
            self.trajectory_history.clear()
            self.calib_display_timer = time.time() + 2.0
            print("[ACTION TRIGGERED] RESET VIEWPORT & ORIGIN")
        else:
            print(f"[ACTION TRIGGERED] {action}")

    def update_kinematics(self):
        """Smooths 15Hz discrete telemetry frames into continuous 60Hz viewport motion."""
        # Thread health check for clipboard watcher
        if hasattr(self, "watcher") and hasattr(self.watcher, "ensure_alive"):
            self.watcher.ensure_alive()

        # Check staleness based on time elapsed since last valid packet
        if self.last_packet_time > 0 and (time.time() - self.last_packet_time > 0.5):
            self.is_stale = True

        # 1. LERP Position: P_t = P_{t-1} + alpha * (P_target - P_{t-1})
        self.curr_pos += (self.target_pos - self.curr_pos) * LERP_FACTOR

        # 2. SLERP Orientation: Slerp between current quaternion and target
        try:
            self.curr_rot = Quaternion.slerp(self.curr_rot, self.target_rot, amount=SLERP_FACTOR)
        except Exception:
            self.curr_rot = self.target_rot

        # 3. Record 3D Trajectory Ribbon during RECORDING gesture
        if self.is_tracking_valid and not self.is_stale and self.gesture_state == "RECORDING":
            self.trajectory_history.append(self.curr_pos.copy())

    def init_gl(self):
        """Sets up 3D perspective projection and depth testing."""
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LESS)
        glShadeModel(GL_SMOOTH)
        glEnable(GL_COLOR_MATERIAL)
        glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE)

        # Enable lighting
        glEnable(GL_LIGHTING)
        glEnable(GL_LIGHT0)
        glLightfv(GL_LIGHT0, GL_POSITION, (5.0, 10.0, 5.0, 1.0))
        glLightfv(GL_LIGHT0, GL_AMBIENT, (0.25, 0.25, 0.28, 1.0))
        glLightfv(GL_LIGHT0, GL_DIFFUSE, (0.85, 0.85, 0.90, 1.0))

        glMatrixMode(GL_PROJECTION)
        glLoadIdentity()
        gluPerspective(45.0, (WINDOW_WIDTH / WINDOW_HEIGHT), 0.1, 100.0)
        glMatrixMode(GL_MODELVIEW)

    def draw_phone_model(self):
        """
        Renders the clean wireframe smartphone model:
        - Outer Chassis: Rectangular rounded wireframe box (Cyan #00E5FF, W: 0.75m, H: 1.5m, D: 0.08m)
        - Dark glass semi-transparent body slab with proper depth occlusion
        - Screen Face (+Z): Cyan wireframe grid overlay across the front active display
        - 3-Axis Orientation Gizmo (RGB Crosshair): Anchored at phone center (+X Red, +Y Green, +Z Blue/Cyan)
        - Coordinate alignment: Local screen normal is +Z (faces viewer under identity quaternion)
        """
        hw = 0.375  # Width = 0.75m
        hh = 0.750  # Height = 1.50m
        hd = 0.040  # Thickness = 0.08m

        pts = self._chassis_pts

        # -------------------------------------------------------------
        # 1. DARK GLASS OBSIDIAN BODY SLAB (Depth occlusion & body fill)
        # -------------------------------------------------------------
        glDisable(GL_LIGHTING)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        # Dark Obsidian Body Fill
        glColor4f(0.04, 0.06, 0.09, 0.85)

        # Front Face (+Z)
        glBegin(GL_POLYGON)
        for x, y in pts:
            glVertex3f(x, y, hd)
        glEnd()

        # Back Face (-Z)
        glBegin(GL_POLYGON)
        for x, y in reversed(pts):
            glVertex3f(x, y, -hd)
        glEnd()

        # Side Band Strip
        glBegin(GL_QUAD_STRIP)
        for x, y in pts:
            glVertex3f(x, y, hd)
            glVertex3f(x, y, -hd)
        # Close strip loop
        glVertex3f(pts[0][0], pts[0][1], hd)
        glVertex3f(pts[0][0], pts[0][1], -hd)
        glEnd()

        # -------------------------------------------------------------
        # 2. OUTER CHASSIS WIREFRAME (Cyan #00E5FF)
        # -------------------------------------------------------------
        glLineWidth(2.2)
        # Vibrant Cyan #00E5FF outline
        glColor4f(0.0, 229 / 255.0, 1.0, 0.95)

        # Front Rim (+Z)
        glBegin(GL_LINE_LOOP)
        for x, y in pts:
            glVertex3f(x, y, hd + 0.0005)
        glEnd()

        # Back Rim (-Z)
        glBegin(GL_LINE_LOOP)
        for x, y in pts:
            glVertex3f(x, y, -hd - 0.0005)
        glEnd()

        # Corner / Edge Struts connecting front and back
        glLineWidth(1.6)
        glBegin(GL_LINES)
        for x, y in pts[::2]:  # Connect key perimeter vertices
            glVertex3f(x, y, hd)
            glVertex3f(x, y, -hd)
        glEnd()

        # Midframe accent seam
        glColor4f(0.0, 229 / 255.0, 1.0, 0.35)
        glLineWidth(1.0)
        glBegin(GL_LINE_LOOP)
        for x, y in pts:
            glVertex3f(x, y, 0.0)
        glEnd()

        # -------------------------------------------------------------
        # 3. SCREEN FACE (+Z Active Panel)
        # -------------------------------------------------------------
        screen_z = hd + 0.001
        sw = hw - 0.035  # Screen active width
        sh = hh - 0.045  # Screen active height

        # Screen dark OLED face
        glColor4f(0.02, 0.03, 0.06, 0.95)
        glBegin(GL_QUADS)
        glVertex3f(-sw, -sh, screen_z)
        glVertex3f(sw, -sh, screen_z)
        glVertex3f(sw, sh, screen_z)
        glVertex3f(-sw, sh, screen_z)
        glEnd()

        # Screen inner border
        glColor4f(0.0, 229 / 255.0, 1.0, 0.60)
        glLineWidth(1.4)
        glBegin(GL_LINE_LOOP)
        glVertex3f(-sw, -sh, screen_z + 0.0005)
        glVertex3f(sw, -sh, screen_z + 0.0005)
        glVertex3f(sw, sh, screen_z + 0.0005)
        glVertex3f(-sw, sh, screen_z + 0.0005)
        glEnd()

        # Cyan wireframe grid overlay across the front panel
        glColor4f(0.0, 229 / 255.0, 1.0, 0.25)
        glLineWidth(1.0)
        glBegin(GL_LINES)
        # Horizontal grid lines
        for gy in np.linspace(-sh + 0.10, sh - 0.10, 9):
            glVertex3f(-sw + 0.02, gy, screen_z + 0.001)
            glVertex3f(sw - 0.02, gy, screen_z + 0.001)
        # Vertical grid lines
        for gx in np.linspace(-sw + 0.05, sw - 0.05, 5):
            glVertex3f(gx, -sh + 0.05, screen_z + 0.001)
            glVertex3f(gx, sh - 0.05, screen_z + 0.001)
        glEnd()

        # Top speaker punch hole / earpiece slit (+Y)
        glColor4f(0.08, 0.10, 0.14, 0.95)
        glBegin(GL_QUADS)
        glVertex3f(-0.045, sh + 0.015, screen_z + 0.001)
        glVertex3f(0.045, sh + 0.015, screen_z + 0.001)
        glVertex3f(0.045, sh + 0.022, screen_z + 0.001)
        glVertex3f(-0.045, sh + 0.022, screen_z + 0.001)
        glEnd()

        # -------------------------------------------------------------
        # 4. 3-AXIS ORIENTATION GIZMO (RGB Crosshair anchored at phone center)
        # -------------------------------------------------------------
        axis_len = 0.55
        cube_sz = 0.025  # Half-size of tip marker cube

        glLineWidth(3.0)
        glBegin(GL_LINES)
        # +X Axis (Right): Solid Red (#FF3366)
        glColor4f(255 / 255.0, 51 / 255.0, 102 / 255.0, 1.0)
        glVertex3f(0.0, 0.0, 0.0)
        glVertex3f(axis_len, 0.0, 0.0)

        # +Y Axis (Up / Top of phone): Solid Green (#00FF66)
        glColor4f(0.0, 255 / 255.0, 102 / 255.0, 1.0)
        glVertex3f(0.0, 0.0, 0.0)
        glVertex3f(0.0, axis_len, 0.0)

        # +Z Axis (Forward / Screen Normal): Solid Blue/Cyan (#00E5FF)
        glColor4f(0.0, 229 / 255.0, 1.0, 1.0)
        glVertex3f(0.0, 0.0, 0.0)
        glVertex3f(0.0, 0.0, axis_len)
        glEnd()

        # Marker cubes at axis tips
        self._draw_marker_cube(axis_len, 0.0, 0.0, cube_sz, (255 / 255.0, 51 / 255.0, 102 / 255.0, 1.0))
        self._draw_marker_cube(0.0, axis_len, 0.0, cube_sz, (0.0, 255 / 255.0, 102 / 255.0, 1.0))
        self._draw_marker_cube(0.0, 0.0, axis_len, cube_sz, (0.0, 229 / 255.0, 1.0, 1.0))

        glEnable(GL_LIGHTING)

    @staticmethod
    def _draw_marker_cube(cx, cy, cz, s, color):
        """Draws a solid marker cube centered at (cx, cy, cz) with half-size s."""
        glColor4f(*color)
        glBegin(GL_QUADS)
        # Front (+Z)
        glVertex3f(cx - s, cy - s, cz + s)
        glVertex3f(cx + s, cy - s, cz + s)
        glVertex3f(cx + s, cy + s, cz + s)
        glVertex3f(cx - s, cy + s, cz + s)
        # Back (-Z)
        glVertex3f(cx - s, cy - s, cz - s)
        glVertex3f(cx - s, cy + s, cz - s)
        glVertex3f(cx + s, cy + s, cz - s)
        glVertex3f(cx + s, cy - s, cz - s)
        # Right (+X)
        glVertex3f(cx + s, cy - s, cz - s)
        glVertex3f(cx + s, cy + s, cz - s)
        glVertex3f(cx + s, cy + s, cz + s)
        glVertex3f(cx + s, cy - s, cz + s)
        # Left (-X)
        glVertex3f(cx - s, cy - s, cz - s)
        glVertex3f(cx - s, cy - s, cz + s)
        glVertex3f(cx - s, cy + s, cz + s)
        glVertex3f(cx - s, cy + s, cz - s)
        # Top (+Y)
        glVertex3f(cx - s, cy + s, cz - s)
        glVertex3f(cx - s, cy + s, cz + s)
        glVertex3f(cx + s, cy + s, cz + s)
        glVertex3f(cx + s, cy + s, cz - s)
        # Bottom (-Y)
        glVertex3f(cx - s, cy - s, cz - s)
        glVertex3f(cx + s, cy - s, cz - s)
        glVertex3f(cx + s, cy - s, cz + s)
        glVertex3f(cx - s, cy - s, cz + s)
        glEnd()

    # Aliases for compatibility
    draw_3d_phone_model = draw_phone_model
    draw_scene = draw_phone_model

    def draw_infinite_floor_grid(self, extent=25.0, step=1.0):
        """Renders an infinite-styled 3D ground grid along the X-Z plane."""
        glDisable(GL_LIGHTING)
        glLineWidth(1.0)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glBegin(GL_LINES)

        i = -extent
        while i <= extent + 0.001:
            dist_factor = 1.0 - (abs(i) / extent) * 0.6
            if abs(i) < 0.001:
                glColor4f(0.25, 0.55, 0.95, 0.85)  # Blue axis
            elif abs(i % 5.0) < 0.001:
                glColor4f(0.28, 0.35, 0.48, 0.70 * dist_factor)
            else:
                glColor4f(0.14, 0.18, 0.26, 0.40 * dist_factor)

            # Lines parallel to Z
            glVertex3f(i, -2.0, -extent)
            glVertex3f(i, -2.0, extent)

            # Lines parallel to X
            glVertex3f(-extent, -2.0, i)
            glVertex3f(extent, -2.0, i)
            i += step

        glEnd()
        glEnable(GL_LIGHTING)

    def draw_trajectory_ribbon(self):
        """
        Renders 3D trajectory ribbon using GL_LINE_STRIP with distance-based alpha fading
        and continuous Cyan -> Amber gradient (Dev 3 feature).
        """
        if not self.show_trajectory or len(self.trajectory_history) < 2:
            return

        glDisable(GL_LIGHTING)
        glLineWidth(3.5)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glBegin(GL_LINE_STRIP)

        n_pts = len(self.trajectory_history)
        for i, pt in enumerate(self.trajectory_history):
            t = i / max(1, n_pts - 1)
            alpha = 0.15 + 0.85 * (t ** 1.5)

            # Continuous Cyan (0.0, 0.9, 1.0) -> Amber (1.0, 0.75, 0.0) color gradient
            r = (1.0 - t) * 0.0 + t * 1.0
            g = (1.0 - t) * 0.9 + t * 0.75
            b = (1.0 - t) * 1.0 + t * 0.0
            glColor4f(r, g, b, alpha)
            glVertex3f(pt[0], pt[1], pt[2])

        glEnd()
        glEnable(GL_LIGHTING)

    @staticmethod
    def _render_surface_gl(surface, x, y):
        """Draws a pygame surface to OpenGL viewport at top-left screen coordinates (x, y)."""
        w, h = surface.get_size()
        if hasattr(pygame.image, "tobytes"):
            raw_bytes = pygame.image.tobytes(surface, "RGBA", True)
        else:
            raw_bytes = pygame.image.tostring(surface, "RGBA", True)
        glRasterPos2i(int(x), int(y + h))
        glDrawPixels(w, h, GL_RGBA, GL_UNSIGNED_BYTE, raw_bytes)

    def draw_hud(self):
        """
        Renders the clean 2D HUD:
        - Compact Dark-Glass Telemetry Card (#0B0E14 @ 85% alpha with subtle cyan border)
        - Header: 'KINETRAK 6-DOF' (Bold Cyan #00E5FF) and status pill ([STREAMING 15Hz] / [HOLDING POSE])
        - Monospace Telemetry Readouts (SEQ, FPS, POS, ROT, GESTURE, ACTION, ORIGIN)
        - Minimalist Centered Bottom Navigation Bar Hotkey Prompt
        """
        glMatrixMode(GL_PROJECTION)
        glPushMatrix()
        glLoadIdentity()
        glOrtho(0.0, WINDOW_WIDTH, WINDOW_HEIGHT, 0.0, -1.0, 1.0)
        glMatrixMode(GL_MODELVIEW)
        glPushMatrix()
        glLoadIdentity()
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_LIGHTING)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        # -------------------------------------------------------------
        # 1. COMPACT DARK-GLASS TELEMETRY CARD (#0B0E14 with subtle cyan border)
        # -------------------------------------------------------------
        card_x = 24
        card_y = 24
        card_w = 440
        card_h = 230

        # Background: Semi-transparent dark card #0B0E14 @ 85% alpha
        glBegin(GL_QUADS)
        glColor4f(11 / 255.0, 14 / 255.0, 20 / 255.0, 0.85)  # #0B0E14
        glVertex2f(card_x, card_y)
        glVertex2f(card_x + card_w, card_y)
        glVertex2f(card_x + card_w, card_y + card_h)
        glVertex2f(card_x, card_y + card_h)
        glEnd()

        # Subtle Cyan Border (#00E5FF @ 35% alpha)
        glLineWidth(1.5)
        glBegin(GL_LINE_LOOP)
        glColor4f(0.0, 229 / 255.0, 1.0, 0.35)
        glVertex2f(card_x, card_y)
        glVertex2f(card_x + card_w, card_y)
        glVertex2f(card_x + card_w, card_y + card_h)
        glVertex2f(card_x, card_y + card_h)
        glEnd()

        # Cyan Brand Accent Bar (Left Edge)
        glBegin(GL_QUADS)
        glColor4f(0.0, 229 / 255.0, 1.0, 0.90)
        glVertex2f(card_x, card_y)
        glVertex2f(card_x + 4, card_y)
        glVertex2f(card_x + 4, card_y + card_h)
        glVertex2f(card_x, card_y + card_h)
        glEnd()

        # -------------------------------------------------------------
        # 2. STATUS PILLS & TELEMETRY READOUTS
        # -------------------------------------------------------------
        # Determine tracking freshness: Fresh when state == 1, not stale, within 500ms
        is_fresh = (not self.is_stale) and self.is_tracking_valid and (time.time() - self.last_packet_time <= 0.5)

        # Header: "KINETRAK 6-DOF" (Bold Cyan #00E5FF)
        title_surf = self.font_title.render("KINETRAK 6-DOF", True, (0, 229, 255))
        self._render_surface_gl(title_surf, card_x + 16, card_y + 14)

        # Status Pill: [STREAMING 15Hz] (Neon Green #00FF66) vs [HOLDING POSE] (Amber #FFB000)
        pill_x = card_x + card_w - 180
        pill_y = card_y + 14
        pill_w = 165
        pill_h = 24

        if is_fresh:
            pill_bg = (0.02, 0.20, 0.08, 0.85)
            pill_border = (0.0, 1.0, 0.40, 0.90)
            status_text = "[STREAMING 15Hz]"
            status_color = (0, 255, 102)  # Neon Green #00FF66
        else:
            pill_bg = (0.30, 0.18, 0.02, 0.85)
            pill_border = (1.0, 0.69, 0.0, 0.90)
            status_text = "[HOLDING POSE]"
            status_color = (255, 176, 0)  # Amber #FFB000

        glBegin(GL_QUADS)
        glColor4f(*pill_bg)
        glVertex2f(pill_x, pill_y); glVertex2f(pill_x + pill_w, pill_y)
        glVertex2f(pill_x + pill_w, pill_y + pill_h); glVertex2f(pill_x, pill_y + pill_h)
        glEnd()

        glLineWidth(1.2)
        glBegin(GL_LINE_LOOP)
        glColor4f(*pill_border)
        glVertex2f(pill_x, pill_y); glVertex2f(pill_x + pill_w, pill_y)
        glVertex2f(pill_x + pill_w, pill_y + pill_h); glVertex2f(pill_x, pill_y + pill_h)
        glEnd()

        pill_surf = self.font.render(status_text, True, status_color)
        pw, ph = pill_surf.get_size()
        self._render_surface_gl(pill_surf, pill_x + (pill_w - pw) // 2, pill_y + (pill_h - ph) // 2)

        # Telemetry Monospace Readouts
        fps = self.clock.get_fps()
        y_cursor = card_y + 48

        # SEQ & FPS Row
        seq_fps_str = f"SEQ: {self.last_seq:<8d} | FPS: {fps:>5.1f}"
        seq_fps_surf = self.font.render(seq_fps_str, True, (170, 185, 205))
        self._render_surface_gl(seq_fps_surf, card_x + 16, y_cursor)
        y_cursor += 26

        # POS (X, Y, Z)
        pos_str = f"POS: X:{self.curr_pos[0]:+6.2f} Y:{self.curr_pos[1]:+6.2f} Z:{self.curr_pos[2]:+6.2f}"
        pos_surf = self.font.render(pos_str, True, (235, 240, 250))
        self._render_surface_gl(pos_surf, card_x + 16, y_cursor)
        y_cursor += 24

        # ROT (W, X, Y, Z)
        rot_str = f"ROT: W:{self.curr_rot[0]:+5.2f} X:{self.curr_rot[1]:+5.2f} Y:{self.curr_rot[2]:+5.2f} Z:{self.curr_rot[3]:+5.2f}"
        rot_surf = self.font.render(rot_str, True, (185, 200, 220))
        self._render_surface_gl(rot_surf, card_x + 16, y_cursor)
        y_cursor += 24

        # GESTURE Row
        gesture_str = f"GESTURE: {self.gesture_state}"
        gesture_surf = self.font.render(gesture_str, True, (180, 210, 255))
        self._render_surface_gl(gesture_surf, card_x + 16, y_cursor)
        y_cursor += 26

        # ACTION Row (Green when active, otherwise dim)
        action_active = (self.active_action != "NONE") and (time.time() < self.action_display_timer)
        act_text = f"ACTION: {self.active_action}"
        if action_active:
            act_surf = self.font.render(act_text, True, (0, 255, 102))  # Green #00FF66
        else:
            act_surf = self.font.render(act_text, True, (110, 125, 145))  # Dim
        self._render_surface_gl(act_surf, card_x + 16, y_cursor)

        # ORIGIN Row: ZEROED / CALIBRATED (Yellow #FFD700)
        origin_status = "ORIGIN: ZEROED" if self.origin_pos is not None else "ORIGIN: UNSET"
        origin_color = (255, 215, 0) if self.origin_pos is not None else (255, 110, 110)  # Yellow #FFD700 vs Dim Red
        origin_surf = self.font.render(origin_status, True, origin_color)
        self._render_surface_gl(origin_surf, card_x + card_w - 180, y_cursor)

        # -------------------------------------------------------------
        # 3. ORIGIN ZEROED NOTIFICATION (Top Center Toast)
        # -------------------------------------------------------------
        if time.time() < self.calib_display_timer:
            calib_str = "✓ ORIGIN ZEROED"
            calib_surface = self.font.render(calib_str, True, (0, 255, 102))
            cw, ch = calib_surface.get_size()
            cx = (WINDOW_WIDTH - cw) // 2
            cy = 25
            glBegin(GL_QUADS)
            glColor4f(11 / 255.0, 14 / 255.0, 20 / 255.0, 0.85)
            glVertex2f(cx - 14, cy - 5); glVertex2f(cx + cw + 14, cy - 5)
            glVertex2f(cx + cw + 14, cy + ch + 5); glVertex2f(cx - 14, cy + ch + 5)
            glEnd()
            glLineWidth(1.2)
            glBegin(GL_LINE_LOOP)
            glColor4f(0.0, 1.0, 0.40, 0.80)
            glVertex2f(cx - 14, cy - 5); glVertex2f(cx + cw + 14, cy - 5)
            glVertex2f(cx + cw + 14, cy + ch + 5); glVertex2f(cx - 14, cy + ch + 5)
            glEnd()
            self._render_surface_gl(calib_surface, cx, cy)

        # -------------------------------------------------------------
        # 4. BOTTOM NAVIGATION BAR: CLEAN CENTERED HOTKEY PROMPT
        # -------------------------------------------------------------
        footer_str = "[R] Calibrate Zero | [C] Clear Ribbon | [T] Synthetic Mode | [ESC] Exit"
        footer_surface = self.font.render(footer_str, True, (140, 155, 175))
        fw, fh = footer_surface.get_size()
        bar_x = (WINDOW_WIDTH - fw) // 2 - 16
        bar_y = WINDOW_HEIGHT - 38
        bar_w = fw + 32
        bar_h = fh + 8

        # Floating minimalist dark glass pill for footer (#0B0E14)
        glBegin(GL_QUADS)
        glColor4f(11 / 255.0, 14 / 255.0, 20 / 255.0, 0.85)
        glVertex2f(bar_x, bar_y); glVertex2f(bar_x + bar_w, bar_y)
        glVertex2f(bar_x + bar_w, bar_y + bar_h); glVertex2f(bar_x, bar_y + bar_h)
        glEnd()

        glLineWidth(1.0)
        glBegin(GL_LINE_LOOP)
        glColor4f(0.18, 0.28, 0.40, 0.60)
        glVertex2f(bar_x, bar_y); glVertex2f(bar_x + bar_w, bar_y)
        glVertex2f(bar_x + bar_w, bar_y + bar_h); glVertex2f(bar_x, bar_y + bar_h)
        glEnd()

        self._render_surface_gl(footer_surface, bar_x + 16, bar_y + 4)

        glEnable(GL_DEPTH_TEST)
        glEnable(GL_LIGHTING)
        glMatrixMode(GL_PROJECTION)
        glPopMatrix()
        glMatrixMode(GL_MODELVIEW)
        glPopMatrix()

    # Alias for compatibility
    render_hud = draw_hud

    def render(self):
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT)
        glClearColor(0.06, 0.07, 0.09, 1.0)
        glLoadIdentity()

        # Render infinite ground grid in world space
        self.draw_infinite_floor_grid(extent=25.0, step=1.0)

        # Render 3D trajectory ribbon in world space
        self.draw_trajectory_ribbon()

        # Apply smoothed 6-DOF translation and rotation to 3D phone model
        glPushMatrix()
        glTranslatef(self.curr_pos[0], self.curr_pos[1], self.curr_pos[2])

        rot_matrix = self.curr_rot.rotation_matrix
        gl_matrix = [
            rot_matrix[0][0], rot_matrix[1][0], rot_matrix[2][0], 0.0,
            rot_matrix[0][1], rot_matrix[1][1], rot_matrix[2][1], 0.0,
            rot_matrix[0][2], rot_matrix[1][2], rot_matrix[2][2], 0.0,
            0.0,              0.0,              0.0,              1.0
        ]
        glMultMatrixf(gl_matrix)

        # Draw wireframe smartphone model
        self.draw_phone_model()
        glPopMatrix()

        # Render 2D HUD overlays
        self.draw_hud()

        pygame.display.flip()

    def start_synthetic(self):
        if self.synthetic_running:
            return
        self.synthetic_running = True
        self.synthetic_thread = threading.Thread(target=self._synthetic_loop, daemon=True)
        self.synthetic_thread.start()
        print("[KineTrak] Synthetic telemetry generator started.")

    def stop_synthetic(self):
        self.synthetic_running = False
        if self.synthetic_thread and self.synthetic_thread.is_alive():
            self.synthetic_thread.join(timeout=1.0)
        print("[KineTrak] Synthetic telemetry generator stopped.")

    def _synthetic_loop(self):
        t0 = time.time()
        seq = 1000
        while self.synthetic_running:
            now = time.time()
            elapsed = now - t0
            seq += 1
            x = 0.3 * math.sin(elapsed * 1.5)
            y = 0.2 * math.sin(elapsed * 2.0)
            z = 0.2 * math.cos(elapsed * 1.5)
            yaw = elapsed * 0.5
            pitch = 0.2 * math.sin(elapsed * 1.2)
            q = Quaternion(axis=[0, 1, 0], radians=yaw) * Quaternion(axis=[1, 0, 0], radians=pitch)
            qw, qx, qy, qz = q.elements
            packet = {
                "seq": seq,
                "state": 1,
                "pos": [x, y, z],
                "rot": [qw, qx, qy, qz],
                "gesture_state": "RECORDING" if (elapsed % 6.0) < 3.0 else "IDLE",
                "action": "ACTION:SELECT" if int(elapsed) % 8 == 0 and (elapsed - int(elapsed) < 0.1) else "NULL",
                "stale": False,
            }
            self.on_telemetry_packet(packet)
            time.sleep(0.0667)

    def run(self):
        self.init_gl()
        running = True

        print("\n[KineTrak] Desktop Viewport Active.")
        print("   Listening on Office Kit Shared Clipboard @ 15Hz...")
        print("   Press 'R' to recalibrate origin, ESC to exit.\n")

        try:
            while running:
                for event in pygame.event.get():
                    if event.type == QUIT or (event.type == KEYDOWN and event.key == K_ESCAPE):
                        running = False
                    elif event.type == KEYDOWN:
                        if event.key == pygame.K_r:
                            self.origin_pos = None
                            self.calib_display_timer = time.time() + 2.0
                            print("[KineTrak] Origin reset. Recalibrating to current position.")
                        elif event.key == pygame.K_c:
                            self.trajectory_history.clear()
                            print("[KineTrak] Trajectory ribbon cleared.")
                        elif event.key == pygame.K_t:
                            if self.synthetic_running:
                                self.stop_synthetic()
                            else:
                                self.start_synthetic()

                self.update_kinematics()
                self.render()
                self.clock.tick(TARGET_FPS)
                self.frame_count += 1
                if self.test_frames > 0 and self.frame_count >= self.test_frames:
                    print(f"[KineTrak] Completed test run of {self.test_frames} frames. Exiting cleanly.")
                    break

        finally:
            self.stop_synthetic()
            self.watcher.stop()
            pygame.quit()


def main():
    parser = argparse.ArgumentParser(description="KineTrak Desktop Client v4.2")
    parser.add_argument("--synthetic", action="store_true", help="Start in synthetic test mode")
    parser.add_argument("--test-frames", type=int, default=0, help="Run for N frames and exit (for automated testing)")
    args = parser.parse_args()

    engine = HostEngine(synthetic_mode=args.synthetic, test_frames=args.test_frames)
    engine.run()


if __name__ == "__main__":
    main()

