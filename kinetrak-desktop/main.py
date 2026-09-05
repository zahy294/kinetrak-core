"""
KineTrak Desktop — 6-DOF Spatial Viewport & Telemetry Client
Adheres strictly to KineTrak Technical Design Doc v4.2.

Features:
- Pygame-ce + PyOpenGL (GL/GLU) 3D Scene (1280x720 DOUBLEBUF | OPENGL).
- Thread-safe clipboard telemetry ingestion via ClipboardWatcher bridge.
- Frame-rate-independent 6-DOF pose interpolation (SpatialInterpolator) at 60 FPS.
- Infinite 3D floor grid along X-Z plane.
- 3D Motion Trajectory Ribbon recording and GL_LINE_STRIP alpha-fading rendering.
- Reactive central target object responding to ACTION:SPAWN, SELECT, DELETE, RESET, EXPLODE.
- 3D spatial phone cursor with local coordinate frame axes (RGB) and floor shadow.
- 2D HUD overlay (glOrtho) rendering SEQ, FPS, X/Y/Z coords, quaternion orientation,
  AI state indicator ring (Idle, Recording, Thinking, Execution), and stale warning.
- Origin recalibration on SPACEBAR, clean exit on ESC/close, and synthetic test generator (T key / --synthetic).
"""

import sys
import os
import time
import math
import argparse
import threading
from collections import deque
import numpy as np

# Ensure UTF-8 output on Windows
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

import pygame
from pygame.locals import *
from OpenGL.GL import *
from OpenGL.GLU import *
from pyquaternion import Quaternion

from clipboard_hook import ClipboardWatcher
from smoothing_math import SpatialInterpolator, lerp_vec3, slerp_quat


# ==============================================================================
# Configuration & Constants
# ==============================================================================
WINDOW_WIDTH = 1280
WINDOW_HEIGHT = 720
WINDOW_TITLE = "KineTrak v4.2 — 6-DOF Spatial Telemetry Viewport"
TARGET_FPS = 60

# Colors (RGBA float 0.0 - 1.0)
COLOR_BG = (0.06, 0.07, 0.09, 1.0)
COLOR_GRID_MAJOR = (0.28, 0.35, 0.48, 0.70)
COLOR_GRID_MINOR = (0.14, 0.18, 0.26, 0.40)
COLOR_AXIS_X = (0.95, 0.25, 0.25, 0.90)  # Red
COLOR_AXIS_Y = (0.25, 0.95, 0.35, 0.90)  # Green
COLOR_AXIS_Z = (0.25, 0.55, 0.95, 0.90)  # Blue

# AI State Machine Colors (TDD v4.2 §3)
COLOR_STATE_IDLE = (0.00, 0.90, 1.00, 1.00)       # Cyan
COLOR_STATE_RECORDING = (1.00, 0.84, 0.00, 1.00)  # Yellow
COLOR_STATE_THINKING = (0.83, 0.00, 0.98, 1.00)   # Purple
COLOR_STATE_EXECUTION = (0.00, 0.90, 0.46, 1.00)  # Green
COLOR_STATE_WARNING = (1.00, 0.25, 0.20, 1.00)    # Red / Amber


# ==============================================================================
# Thread-Safe Telemetry Bridge
# ==============================================================================
class TelemetryBridge:
    """
    Thread-safe bridge between ClipboardWatcher background thread
    and the main OpenGL rendering loop.
    NO OpenGL calls may be executed inside the watcher callback.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._latest_packet = {
            "seq": 0,
            "state": 1,
            "pos": [0.0, 0.0, 0.0],
            "rot": [1.0, 0.0, 0.0, 0.0],
            "gesture_state": "IDLE",
            "action": "NULL",
            "stale": False,
        }
        self._has_new_data = False
        self._last_received_time = time.time()
        self._last_action = "NULL"
        self._last_action_time = 0.0

    def on_packet(self, data):
        """Watcher callback invoked from background thread."""
        with self._lock:
            if not self._latest_packet:
                self._latest_packet = {}

            # Update fields safely
            for k, v in data.items():
                self._latest_packet[k] = v

            action = data.get("action", "NULL")
            if action and action != "NULL":
                self._last_action = action
                self._last_action_time = time.time()

            self._has_new_data = True
            self._last_received_time = time.time()

    def snapshot(self):
        """Atomically retrieves the current telemetry state on the main render thread."""
        with self._lock:
            packet_copy = dict(self._latest_packet)
            has_new = self._has_new_data
            self._has_new_data = False
            return packet_copy, has_new, self._last_action, self._last_action_time


# ==============================================================================
# Synthetic Telemetry Generator (Standalone Test Mode)
# ==============================================================================
class SyntheticGenerator:
    """
    Generates realistic 15Hz mobile motion telemetry with simulated AI states
    and action triggers for standalone verification without phone connection.
    """

    def __init__(self, bridge):
        self.bridge = bridge
        self.running = False
        self.thread = None
        self.seq = 1000

    def start(self):
        self.running = True
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        print("[KineTrak] Synthetic telemetry generator started.")

    def stop(self):
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
        print("[KineTrak] Synthetic telemetry generator stopped.")

    def _run(self):
        t0 = time.time()
        actions = ["ACTION:SPAWN", "ACTION:SELECT", "ACTION:DELETE", "ACTION:RESET"]
        action_idx = 0
        last_action_time = 0.0

        while self.running:
            now = time.time()
            elapsed = now - t0
            self.seq += 1

            # Smooth Lissajous 3D motion path
            x = 1.2 * math.sin(elapsed * 0.8)
            y = 0.8 + 0.4 * math.sin(elapsed * 1.6)
            z = 0.9 * math.cos(elapsed * 0.8)

            # Realistic phone pitch and roll
            pitch = 0.25 * math.sin(elapsed * 1.2)
            yaw = elapsed * 0.4
            roll = 0.20 * math.cos(elapsed * 1.0)

            q_yaw = Quaternion(axis=[0, 1, 0], radians=yaw)
            q_pitch = Quaternion(axis=[1, 0, 0], radians=pitch)
            q_roll = Quaternion(axis=[0, 0, 1], radians=roll)
            q_total = q_yaw * q_pitch * q_roll
            qw, qx, qy, qz = q_total.elements

            # State Machine cycle: IDLE (3s) -> RECORDING (2s) -> THINKING (1.5s) -> EXECUTION (0.5s)
            cycle_time = elapsed % 7.0
            if cycle_time < 3.0:
                state_str = "IDLE"
                action_to_send = "NULL"
            elif cycle_time < 5.0:
                state_str = "RECORDING"
                action_to_send = "NULL"
            elif cycle_time < 6.5:
                state_str = "THINKING"
                action_to_send = "NULL"
            else:
                state_str = "EXECUTION"
                # Trigger action once per cycle
                if now - last_action_time > 4.0:
                    action_to_send = actions[action_idx % len(actions)]
                    action_idx += 1
                    last_action_time = now
                else:
                    action_to_send = "NULL"

            packet = {
                "seq": self.seq,
                "state": 1,
                "pos": [x, y, z],
                "rot": [qw, qx, qy, qz],
                "gesture_state": state_str,
                "action": action_to_send,
                "stale": False,
            }
            self.bridge.on_packet(packet)

            # 15Hz interval (~66.7ms)
            time.sleep(0.0667)


# ==============================================================================
# Reactive Central Target Object (Cube)
# ==============================================================================
class ReactiveTargetObject:
    """
    Central 3D target object reacting to KineTrak action triggers:
    - SPAWN / EXPLODE: Scale pop-in / outward geometric expansion
    - SELECT: Continuous or spin burst + highlight
    - DELETE: Visibility toggle / dissolve
    - RESET: Restores initial state
    """

    def __init__(self):
        self.base_pos = [0.0, 0.85, 0.0]
        self.scale = 1.0
        self.target_scale = 1.0
        self.rotation_y = 0.0
        self.spin_velocity = 0.0
        self.visible = True
        self.selected = False
        self.highlight_timer = 0.0
        self.explosion_factor = 0.0

    def trigger_action(self, action_name: str):
        if not action_name or action_name == "NULL":
            return
        act = action_name.strip().upper()
        if act.startswith("ACTION:"):
            act = act[7:]

        if act == "SPAWN":
            self.visible = True
            self.scale = 0.2
            self.target_scale = 1.3
            self.highlight_timer = 1.0
            print(f"[TargetObject] Action SPAWN: scaling object")
        elif act == "EXPLODE":
            self.explosion_factor = 1.5
            self.highlight_timer = 1.2
            print(f"[TargetObject] Action EXPLODE: triggered")
        elif act == "SELECT":
            self.selected = not self.selected
            self.spin_velocity = 720.0  # Fast spin burst
            self.highlight_timer = 1.5
            print(f"[TargetObject] Action SELECT: selected={self.selected}")
        elif act == "DELETE":
            self.visible = not self.visible
            self.highlight_timer = 1.0
            print(f"[TargetObject] Action DELETE: visible={self.visible}")
        elif act == "RESET":
            self.scale = 1.0
            self.target_scale = 1.0
            self.rotation_y = 0.0
            self.spin_velocity = 0.0
            self.visible = True
            self.selected = False
            self.highlight_timer = 1.0
            self.explosion_factor = 0.0
            print(f"[TargetObject] Action RESET: restored defaults")

    def update(self, dt):
        # Smooth scale return
        self.scale += (self.target_scale - self.scale) * min(1.0, 8.0 * dt)
        if abs(self.target_scale - 1.0) > 0.01:
            self.target_scale += (1.0 - self.target_scale) * min(1.0, 4.0 * dt)

        # Decay spin velocity and explosion factor
        if self.spin_velocity > 0.0:
            self.rotation_y += self.spin_velocity * dt
            self.spin_velocity = max(0.0, self.spin_velocity - 600.0 * dt)
        else:
            self.rotation_y += 15.0 * dt  # Gentle idle ambient rotation

        if self.explosion_factor > 0.0:
            self.explosion_factor = max(0.0, self.explosion_factor - 2.0 * dt)

        if self.highlight_timer > 0.0:
            self.highlight_timer = max(0.0, self.highlight_timer - dt)

    def draw(self):
        if not self.visible:
            return

        glPushMatrix()
        glTranslatef(*self.base_pos)
        glRotatef(self.rotation_y, 0.0, 1.0, 0.0)
        s = self.scale * (1.0 + 0.3 * self.explosion_factor)
        glScalef(s, s, s)

        half = 0.3
        exp_offset = 0.15 * self.explosion_factor

        # Solid Mesh Faces
        glBegin(GL_QUADS)
        # Front face (+Z)
        if self.selected:
            glColor4f(1.0, 0.84, 0.0, 0.95)  # Gold highlight if selected
        else:
            glColor4f(0.0, 0.75, 0.95, 0.90)  # Cyan core
        glVertex3f(-half, -half,  half + exp_offset)
        glVertex3f( half, -half,  half + exp_offset)
        glVertex3f( half,  half,  half + exp_offset)
        glVertex3f(-half,  half,  half + exp_offset)

        # Back face (-Z)
        glColor4f(0.0, 0.50, 0.80, 0.90)
        glVertex3f(-half, -half, -half - exp_offset)
        glVertex3f(-half,  half, -half - exp_offset)
        glVertex3f( half,  half, -half - exp_offset)
        glVertex3f( half, -half, -half - exp_offset)

        # Top face (+Y)
        glColor4f(0.20, 0.85, 1.00, 0.90)
        glVertex3f(-half,  half + exp_offset, -half)
        glVertex3f(-half,  half + exp_offset,  half)
        glVertex3f( half,  half + exp_offset,  half)
        glVertex3f( half,  half + exp_offset, -half)

        # Bottom face (-Y)
        glColor4f(0.0, 0.35, 0.65, 0.90)
        glVertex3f(-half, -half - exp_offset, -half)
        glVertex3f( half, -half - exp_offset, -half)
        glVertex3f( half, -half - exp_offset,  half)
        glVertex3f(-half, -half - exp_offset,  half)

        # Right face (+X)
        glColor4f(0.10, 0.65, 0.90, 0.90)
        glVertex3f( half + exp_offset, -half, -half)
        glVertex3f( half + exp_offset,  half, -half)
        glVertex3f( half + exp_offset,  half,  half)
        glVertex3f( half + exp_offset, -half,  half)

        # Left face (-X)
        glColor4f(0.05, 0.45, 0.75, 0.90)
        glVertex3f(-half - exp_offset, -half, -half)
        glVertex3f(-half - exp_offset, -half,  half)
        glVertex3f(-half - exp_offset,  half,  half)
        glVertex3f(-half - exp_offset,  half, -half)
        glEnd()

        # Glowing Wireframe Bounding Box Outline
        glLineWidth(2.5 if self.selected else 1.5)
        if self.highlight_timer > 0.0:
            glColor4f(1.0, 1.0, 1.0, 1.0)
        elif self.selected:
            glColor4f(1.0, 0.9, 0.2, 0.95)
        else:
            glColor4f(0.0, 1.0, 0.8, 0.8)

        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
        glBegin(GL_QUADS)
        glVertex3f(-half, -half,  half); glVertex3f( half, -half,  half); glVertex3f( half,  half,  half); glVertex3f(-half,  half,  half)
        glVertex3f(-half, -half, -half); glVertex3f(-half,  half, -half); glVertex3f( half,  half, -half); glVertex3f( half, -half, -half)
        glEnd()
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)

        glPopMatrix()


# ==============================================================================
# Text & Font Rendering Module (Pygame to OpenGL Texture)
# ==============================================================================
class TextRenderer:
    """
    Renders high-crispness 2D UI text overlays via Pygame surface texture mapping.
    """

    def __init__(self):
        pygame.font.init()
        # Fallback font stack
        self.font_main = pygame.font.SysFont("Consolas, Courier New, monospace", 14, bold=True)
        self.font_large = pygame.font.SysFont("Consolas, Courier New, monospace", 18, bold=True)
        self.font_title = pygame.font.SysFont("Consolas, Courier New, monospace", 22, bold=True)
        self._texture_cache = {}

    def get_texture(self, text, color, font):
        key = (text, color, id(font))
        if key in self._texture_cache:
            return self._texture_cache[key]

        # Manage texture cache limit
        if len(self._texture_cache) > 100:
            for old_key, (old_tex, _, _) in list(self._texture_cache.items())[:50]:
                glDeleteTextures(1, [old_tex])
                del self._texture_cache[old_key]

        surface = font.render(text, True, color)
        w, h = surface.get_size()
        data = pygame.image.tobytes(surface, "RGBA", False)

        tex = glGenTextures(1)
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, data)

        self._texture_cache[key] = (tex, w, h)
        return tex, w, h

    def draw_text(self, text, x, y, color=(255, 255, 255), font_type="main"):
        font = self.font_main
        if font_type == "large":
            font = self.font_large
        elif font_type == "title":
            font = self.font_title

        tex, w, h = self.get_texture(text, color, font)

        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, tex)
        glColor4f(1.0, 1.0, 1.0, 1.0)

        glBegin(GL_QUADS)
        glTexCoord2f(0.0, 0.0); glVertex2f(x, y)
        glTexCoord2f(1.0, 0.0); glVertex2f(x + w, y)
        glTexCoord2f(1.0, 1.0); glVertex2f(x + w, y + h)
        glTexCoord2f(0.0, 1.0); glVertex2f(x, y + h)
        glEnd()

        glDisable(GL_TEXTURE_2D)
        return w, h


# ==============================================================================
# Scene Drawing Routines (3D & 2D HUD)
# ==============================================================================
def draw_infinite_floor_grid(extent=25.0, step=1.0):
    """
    Renders an infinite-styled 3D ground grid along the X-Z plane.
    Fades gracefully toward the horizons.
    """
    glLineWidth(1.0)
    glBegin(GL_LINES)

    i = -extent
    while i <= extent + 0.001:
        dist_factor = 1.0 - (abs(i) / extent) * 0.6
        if abs(i) < 0.001:
            # Highlight main axes
            glColor4f(COLOR_AXIS_Z[0], COLOR_AXIS_Z[1], COLOR_AXIS_Z[2], 0.85)
        elif abs(i % 5.0) < 0.001:
            glColor4f(COLOR_GRID_MAJOR[0], COLOR_GRID_MAJOR[1], COLOR_GRID_MAJOR[2], COLOR_GRID_MAJOR[3] * dist_factor)
        else:
            glColor4f(COLOR_GRID_MINOR[0], COLOR_GRID_MINOR[1], COLOR_GRID_MINOR[2], COLOR_GRID_MINOR[3] * dist_factor)

        # Lines parallel to Z
        glVertex3f(i, 0.0, -extent)
        glVertex3f(i, 0.0, extent)

        if abs(i) < 0.001:
            glColor4f(COLOR_AXIS_X[0], COLOR_AXIS_X[1], COLOR_AXIS_X[2], 0.85)
        elif abs(i % 5.0) < 0.001:
            glColor4f(COLOR_GRID_MAJOR[0], COLOR_GRID_MAJOR[1], COLOR_GRID_MAJOR[2], COLOR_GRID_MAJOR[3] * dist_factor)
        else:
            glColor4f(COLOR_GRID_MINOR[0], COLOR_GRID_MINOR[1], COLOR_GRID_MINOR[2], COLOR_GRID_MINOR[3] * dist_factor)

        # Lines parallel to X
        glVertex3f(-extent, 0.0, i)
        glVertex3f(extent, 0.0, i)

        i += step

    glEnd()


def draw_phone_spatial_cursor(pos, rot_quat):
    """
    Renders a 3D spatial cursor reflecting the phone's smoothed 6-DOF pose.
    Includes:
    - Smartphone-form-factor body (Vivo aspect ratio)
    - Glowing screen face
    - Protruding RGB coordinate frame axes
    - Forward pointing targeting ray
    - Ground projection shadow
    """
    x, y, z = pos

    # 1. Ground shadow on X-Z floor plane
    glDisable(GL_LIGHTING)
    glBegin(GL_TRIANGLE_FAN)
    glColor4f(0.0, 0.8, 1.0, 0.25)
    glVertex3f(x, 0.005, z)
    segments = 24
    radius = 0.22
    for i in range(segments + 1):
        angle = 2.0 * math.pi * i / segments
        glColor4f(0.0, 0.5, 0.8, 0.0)
        glVertex3f(x + radius * math.cos(angle), 0.005, z + radius * math.sin(angle))
    glEnd()

    # Vertical tether line from floor to phone
    glLineWidth(1.0)
    glBegin(GL_LINES)
    glColor4f(0.0, 0.8, 1.0, 0.30)
    glVertex3f(x, 0.0, z)
    glVertex3f(x, y, z)
    glEnd()

    # 2. 6-DOF Phone Cursor Mesh
    glPushMatrix()
    glTranslatef(x, y, z)

    # Apply quaternion rotation matrix
    q = Quaternion(rot_quat)
    M = np.eye(4, dtype=np.float32)
    M[:3, :3] = q.rotation_matrix
    glMultMatrixf(M.flatten('F'))

    # Phone dimensions (width, height/length, thickness)
    pw, ph, pd = 0.18, 0.36, 0.02

    # Solid phone body
    glBegin(GL_QUADS)
    # Back face
    glColor4f(0.12, 0.14, 0.18, 0.95)
    glVertex3f(-pw, -ph, -pd); glVertex3f( pw, -ph, -pd); glVertex3f( pw,  ph, -pd); glVertex3f(-pw,  ph, -pd)
    # Screen face (+Z)
    glColor4f(0.04, 0.30, 0.45, 0.95)
    glVertex3f(-pw * 0.9, -ph * 0.9,  pd); glVertex3f( pw * 0.9, -ph * 0.9,  pd);
    glVertex3f( pw * 0.9,  ph * 0.9,  pd); glVertex3f(-pw * 0.9,  ph * 0.9,  pd)
    # Screen bezel (+Z)
    glColor4f(0.18, 0.20, 0.25, 0.95)
    glVertex3f(-pw, -ph,  pd * 0.9); glVertex3f( pw, -ph,  pd * 0.9);
    glVertex3f( pw,  ph,  pd * 0.9); glVertex3f(-pw,  ph,  pd * 0.9)
    # Sides
    glColor4f(0.30, 0.34, 0.42, 0.95)
    glVertex3f(-pw, -ph, -pd); glVertex3f(-pw, -ph,  pd); glVertex3f(-pw,  ph,  pd); glVertex3f(-pw,  ph, -pd)
    glVertex3f( pw, -ph, -pd); glVertex3f( pw,  ph, -pd); glVertex3f( pw,  ph,  pd); glVertex3f( pw, -ph,  pd)
    glVertex3f(-pw,  ph, -pd); glVertex3f(-pw,  ph,  pd); glVertex3f( pw,  ph,  pd); glVertex3f( pw,  ph, -pd)
    glVertex3f(-pw, -ph, -pd); glVertex3f( pw, -ph, -pd); glVertex3f( pw, -ph,  pd); glVertex3f(-pw, -ph,  pd)
    glEnd()

    # Phone wireframe outline
    glLineWidth(2.0)
    glColor4f(0.0, 0.9, 1.0, 0.8)
    glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
    glBegin(GL_QUADS)
    glVertex3f(-pw, -ph,  pd); glVertex3f( pw, -ph,  pd); glVertex3f( pw,  ph,  pd); glVertex3f(-pw,  ph,  pd)
    glEnd()
    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)

    # 3. Local Coordinate Axes (Red = +X, Green = +Y, Blue = +Z)
    axis_len = 0.35
    glLineWidth(3.0)
    glBegin(GL_LINES)
    # X Axis (Red)
    glColor4fv(COLOR_AXIS_X)
    glVertex3f(0.0, 0.0, 0.0); glVertex3f(axis_len, 0.0, 0.0)
    # Y Axis (Green)
    glColor4fv(COLOR_AXIS_Y)
    glVertex3f(0.0, 0.0, 0.0); glVertex3f(0.0, axis_len, 0.0)
    # Z Axis (Blue - screen normal)
    glColor4fv(COLOR_AXIS_Z)
    glVertex3f(0.0, 0.0, 0.0); glVertex3f(0.0, 0.0, axis_len)
    # Forward pointing pointer / laser ray (+Y phone forward)
    glColor4f(0.0, 1.0, 0.6, 0.7)
    glVertex3f(0.0, ph, 0.0); glVertex3f(0.0, ph + 1.2, 0.0)
    glEnd()

    glPopMatrix()


# ==============================================================================
# 2D HUD Rendering
# ==============================================================================
def draw_2d_panel(x, y, w, h, bg_color=(0.04, 0.06, 0.09, 0.85), border_color=(0.18, 0.28, 0.42, 0.90)):
    """Draws a semi-transparent panel with a crisp bordered outline."""
    glBegin(GL_QUADS)
    glColor4fv(bg_color)
    glVertex2f(x, y)
    glVertex2f(x + w, y)
    glVertex2f(x + w, y + h)
    glVertex2f(x, y + h)
    glEnd()

    glLineWidth(1.5)
    glBegin(GL_LINE_LOOP)
    glColor4fv(border_color)
    glVertex2f(x, y)
    glVertex2f(x + w, y)
    glVertex2f(x + w, y + h)
    glVertex2f(x, y + h)
    glEnd()


def draw_ai_state_ring(cx, cy, radius, state_str, anim_time, execution_flash):
    """
    Renders the AI State Machine visual indicator:
    - Idle (Cyan): Standard 1:1 cursor tracking
    - Recording (Yellow): Visual cue that motion is buffering
    - Thinking (Purple): Spinning/pulsing cue that NPU inference is active
    - Execution (Green Flash): Visual feedback when an ACTION resolves
    """
    st = state_str.upper()

    if execution_flash > 0.0:
        base_col = COLOR_STATE_EXECUTION
        label = "EXECUTION"
    elif "RECORD" in st:
        base_col = COLOR_STATE_RECORDING
        label = "RECORDING"
    elif "THINK" in st:
        base_col = COLOR_STATE_THINKING
        label = "THINKING (NPU)"
    else:
        base_col = COLOR_STATE_IDLE
        label = "IDLE (1:1 TRACK)"

    # Draw Outer Ring
    glLineWidth(3.0)
    num_segments = 48

    if "THINK" in st and execution_flash <= 0.0:
        # Spinning segmented ring for Thinking state
        spin_offset = anim_time * 6.0
        glBegin(GL_LINES)
        for i in range(num_segments):
            if i % 4 < 2:  # Dashed/segmented effect
                a1 = spin_offset + 2.0 * math.pi * i / num_segments
                a2 = spin_offset + 2.0 * math.pi * (i + 1) / num_segments
                glColor4fv(base_col)
                glVertex2f(cx + radius * math.cos(a1), cy + radius * math.sin(a1))
                glVertex2f(cx + radius * math.cos(a2), cy + radius * math.sin(a2))
        glEnd()

        # Inner pulsing core
        pulse = 0.5 + 0.5 * math.sin(anim_time * 8.0)
        glBegin(GL_TRIANGLE_FAN)
        glColor4f(base_col[0], base_col[1], base_col[2], 0.7 * pulse)
        glVertex2f(cx, cy)
        for i in range(num_segments + 1):
            ang = 2.0 * math.pi * i / num_segments
            r_inner = (radius * 0.45) * (0.8 + 0.2 * pulse)
            glVertex2f(cx + r_inner * math.cos(ang), cy + r_inner * math.sin(ang))
        glEnd()

    else:
        # Continuous Ring
        glBegin(GL_LINE_LOOP)
        pulse = 1.0
        if "RECORD" in st:
            pulse = 0.8 + 0.2 * math.sin(anim_time * 6.0)
        elif execution_flash > 0.0:
            pulse = 1.0 + 0.4 * execution_flash

        r_eff = radius * pulse
        for i in range(num_segments):
            ang = 2.0 * math.pi * i / num_segments
            glColor4f(base_col[0], base_col[1], base_col[2], base_col[3])
            glVertex2f(cx + r_eff * math.cos(ang), cy + r_eff * math.sin(ang))
        glEnd()

        # Center indicator dot
        glPointSize(6.0)
        glBegin(GL_POINTS)
        glColor4fv(base_col)
        glVertex2f(cx, cy)
        glEnd()

    return label, base_col


def draw_hud(text_renderer, telemetry, fps, calib_notice_time, anim_time, execution_flash, synthetic_mode):
    """
    Renders the complete 2D Orthographic HUD layer.
    """
    # Switch to 2D Orthographic Projection
    glMatrixMode(GL_PROJECTION)
    glPushMatrix()
    glLoadIdentity()
    glOrtho(0.0, WINDOW_WIDTH, WINDOW_HEIGHT, 0.0, -1.0, 1.0)
    glMatrixMode(GL_MODELVIEW)
    glPushMatrix()
    glLoadIdentity()
    glDisable(GL_DEPTH_TEST)
    glEnable(GL_BLEND)
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

    # 1. Top-Left Telemetry Dashboard Panel
    panel_x, panel_y, panel_w, panel_h = 24, 24, 380, 220
    draw_2d_panel(panel_x, panel_y, panel_w, panel_h)

    text_renderer.draw_text("KINETRAK v4.2 TELEMETRY", panel_x + 16, panel_y + 14, (0, 229, 255), "large")

    seq = telemetry.get("seq", 0)
    state = telemetry.get("state", 1)
    stale = telemetry.get("stale", False)
    pos = telemetry.get("pos", [0.0, 0.0, 0.0])
    rot = telemetry.get("rot", [1.0, 0.0, 0.0, 0.0])
    action = telemetry.get("action", "NULL")
    gesture_state = telemetry.get("gesture_state", "IDLE")

    mode_str = "SYNTHETIC [T]" if synthetic_mode else "VIVO CLIPBOARD"
    mode_col = (255, 214, 0) if synthetic_mode else (0, 230, 118)

    y_off = panel_y + 48
    text_renderer.draw_text(f"SOURCE : {mode_str}", panel_x + 16, y_off, mode_col)
    y_off += 22
    text_renderer.draw_text(f"SEQ    : {seq:<8d} | FPS: {fps:>5.1f}", panel_x + 16, y_off, (200, 210, 225))
    y_off += 22
    text_renderer.draw_text(f"POS (M): X:{pos[0]:+6.3f} Y:{pos[1]:+6.3f} Z:{pos[2]:+6.3f}", panel_x + 16, y_off, (230, 235, 245))
    y_off += 22
    text_renderer.draw_text(f"QUAT   : W:{rot[0]:+5.2f} X:{rot[1]:+5.2f} Y:{rot[2]:+5.2f} Z:{rot[3]:+5.2f}", panel_x + 16, y_off, (180, 195, 215))
    y_off += 22
    act_col = (0, 230, 118) if action != "NULL" else (140, 150, 170)
    text_renderer.draw_text(f"ACTION : {action}", panel_x + 16, y_off, act_col)
    y_off += 22
    text_renderer.draw_text(f"NPU ST : {gesture_state}", panel_x + 16, y_off, (200, 200, 255))

    # 2. Top-Right AI State Machine Indicator Ring
    ring_cx, ring_cy, ring_r = WINDOW_WIDTH - 140, 68, 28
    ring_panel_x = ring_cx - 140
    draw_2d_panel(ring_panel_x, 24, 250, 88)

    state_label, state_col_float = draw_ai_state_ring(ring_cx + 70, ring_cy, ring_r, gesture_state, anim_time, execution_flash)
    col_255 = (int(state_col_float[0] * 255), int(state_col_float[1] * 255), int(state_col_float[2] * 255))
    text_renderer.draw_text("AI STATE ENGINE", ring_panel_x + 16, 36, (170, 185, 200))
    text_renderer.draw_text(state_label, ring_panel_x + 16, 60, col_255, "large")

    # 3. Center Warning Indicator if Stale or Tracking Lost (STATE == 0)
    if stale or state == 0:
        warn_w, warn_h = 560, 50
        warn_x = (WINDOW_WIDTH - warn_w) // 2
        warn_y = 30
        flash_alpha = 0.5 + 0.5 * math.sin(anim_time * 8.0)
        draw_2d_panel(
            warn_x, warn_y, warn_w, warn_h,
            bg_color=(0.4 * flash_alpha, 0.05, 0.05, 0.90),
            border_color=(1.0, 0.25, 0.2, 0.95)
        )
        text_renderer.draw_text(
            "⚠ WARNING: TELEMETRY STALE / TRACKING LOST (STATE == 0)",
            warn_x + 20, warn_y + 14, (255, 60, 50), "large"
        )

    # 4. Calibration Flash Notification
    if calib_notice_time > 0.0:
        alpha = min(1.0, calib_notice_time)
        notif_w, notif_h = 420, 42
        notif_x = (WINDOW_WIDTH - notif_w) // 2
        notif_y = WINDOW_HEIGHT - 130
        draw_2d_panel(
            notif_x, notif_y, notif_w, notif_h,
            bg_color=(0.05, 0.25 * alpha, 0.15 * alpha, 0.90),
            border_color=(0.0, 0.9, 0.46, alpha)
        )
        text_renderer.draw_text("✓ ORIGIN RECALIBRATED TO (0, 0, 0)", notif_x + 36, notif_y + 11, (0, 230, 118), "large")

    # 5. Bottom Instructions Bar
    footer_w = 780
    footer_h = 36
    footer_x = (WINDOW_WIDTH - footer_w) // 2
    footer_y = WINDOW_HEIGHT - 54
    draw_2d_panel(footer_x, footer_y, footer_w, footer_h)
    text_renderer.draw_text(
        "[SPACE] Recalibrate Origin   |   [T] Toggle Synthetic Test   |   [ESC] Exit",
        footer_x + 30, footer_y + 9, (180, 195, 215)
    )

    # Restore 3D Projection
    glEnable(GL_DEPTH_TEST)
    glMatrixMode(GL_PROJECTION)
    glPopMatrix()
    glMatrixMode(GL_MODELVIEW)
    glPopMatrix()


# ==============================================================================
# Host Desktop Viewport Application Engine (Task 3.2 & 3.3 Architecture)
# ==============================================================================
class HostEngine:
    """
    Main desktop viewport application engine encapsulating Pygame/PyOpenGL render loop,
    telemetry bridge, spatial interpolation, 3D trajectory recording, and action routing.
    Adheres strictly to Tasks 3.2 and 3.3 of KineTrak Technical Architecture Spec v4.2.
    """

    def __init__(self, synthetic_mode=False, test_frames=0):
        self.synthetic_mode = synthetic_mode
        self.test_frames = test_frames

        # Task 3.2 & 3.3: Trajectory recording history & rendering state
        self.trajectory_history = deque(maxlen=60)
        self.show_trajectory = True
        self.curr_pos = [0.0, 0.0, 0.0]
        self.curr_rot = [1.0, 0.0, 0.0, 0.0]

        self.running = True
        self.origin_offset = [0.0, 0.0, 0.0]
        self.calib_notice_time = 0.0
        self.execution_flash = 0.0
        self.last_processed_action = "NULL"
        self.frame_count = 0

        # Sub-modules
        self.text_renderer = TextRenderer()
        self.telemetry_bridge = TelemetryBridge()
        self.interpolator = SpatialInterpolator(init_pos=[0.0, 0.0, 0.0], init_rot=[1.0, 0.0, 0.0, 0.0], zoh_duration=0.3)
        self.target_object = ReactiveTargetObject()

        # Telemetry ingestion bridge
        self.watcher = ClipboardWatcher(state_callback=self.telemetry_bridge.on_packet)
        self.synthetic_gen = None

    def trigger_action(self, action: str):
        """
        Task 3.3: Routes discrete actions (e.g., ACTION:SPAWN / EXPLODE, ACTION:RESET / RESET,
        ACTION:SELECT, ACTION:DELETE) cleanly to the central reactive target object.
        Clears trajectory history on RESET.
        """
        if not action or action == "NULL":
            return
        act = action.strip().upper()
        if act.startswith("ACTION:"):
            act = act[7:]

        if act == "RESET":
            self.trajectory_history.clear()

        self.target_object.trigger_action(action)

    def draw_trajectory_ribbon(self):
        """
        Task 3.3: Renders 3D trajectory ribbon using GL_LINE_STRIP with distance-based alpha fading
        for the cyan/amber ribbon line strip (TDD v4.2 §3.3).
        """
        if not self.show_trajectory or len(self.trajectory_history) < 2:
            return

        glLineWidth(3.5)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glBegin(GL_LINE_STRIP)

        n_pts = len(self.trajectory_history)
        for i, pt in enumerate(self.trajectory_history):
            # Distance-based alpha fading (tail = semi-transparent 0.15, head = opaque 1.0)
            t = i / max(1, n_pts - 1)
            alpha = 0.15 + 0.85 * (t ** 1.5)

            # Continuous Cyan (0.0, 0.9, 1.0) -> Amber (1.0, 0.75, 0.0) color gradient along ribbon
            r = (1.0 - t) * 0.0 + t * 1.0
            g = (1.0 - t) * 0.9 + t * 0.75
            b = (1.0 - t) * 1.0 + t * 0.0
            glColor4f(r, g, b, alpha)
            glVertex3f(pt[0], pt[1], pt[2])

        glEnd()

    def run(self):
        """
        Runs the main Pygame/PyOpenGL 60FPS application loop.
        """
        # 1. Initialize Pygame & PyOpenGL Window
        pygame.init()
        pygame.display.set_caption(WINDOW_TITLE)

        pygame.display.gl_set_attribute(pygame.GL_MULTISAMPLEBUFFERS, 1)
        pygame.display.gl_set_attribute(pygame.GL_MULTISAMPLESAMPLES, 4)
        pygame.display.gl_set_attribute(pygame.GL_DEPTH_SIZE, 24)
        pygame.display.gl_set_attribute(pygame.GL_DOUBLEBUFFER, 1)

        screen = pygame.display.set_mode(
            (WINDOW_WIDTH, WINDOW_HEIGHT),
            pygame.DOUBLEBUF | pygame.OPENGL
        )

        glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT)
        glMatrixMode(GL_PROJECTION)
        glLoadIdentity()
        gluPerspective(45.0, WINDOW_WIDTH / WINDOW_HEIGHT, 0.1, 50.0)
        glMatrixMode(GL_MODELVIEW)

        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glClearColor(*COLOR_BG)

        # 2. Start Background Telemetry Threads
        self.watcher.start()

        if self.synthetic_mode:
            self.synthetic_gen = SyntheticGenerator(self.telemetry_bridge)
            self.synthetic_gen.start()

        clock = pygame.time.Clock()

        print("[KineTrak] 3D Viewport initialized successfully.")

        try:
            while self.running:
                dt = clock.tick(TARGET_FPS) / 1000.0  # seconds
                fps = clock.get_fps()
                anim_time = time.time()
                self.frame_count += 1

                if self.test_frames > 0 and self.frame_count >= self.test_frames:
                    print(f"[KineTrak] Completed test run of {self.test_frames} frames. Exiting cleanly.")
                    break

                # ------------------------------------------------------------------
                # Event Handling
                # ------------------------------------------------------------------
                for event in pygame.event.get():
                    if event.type == QUIT:
                        self.running = False
                    elif event.type == KEYDOWN:
                        if event.key == K_ESCAPE:
                            self.running = False
                        elif event.key == K_SPACE:
                            # Recalibrate Origin
                            self.origin_offset = list(self.interpolator.pos)
                            self.calib_notice_time = 2.0
                            print(f"[KineTrak] Origin Recalibrated: offset={self.origin_offset}")
                        elif event.key == K_t:
                            # Toggle synthetic test mode
                            self.synthetic_mode = not self.synthetic_mode
                            if self.synthetic_mode:
                                if not self.synthetic_gen:
                                    self.synthetic_gen = SyntheticGenerator(self.telemetry_bridge)
                                self.synthetic_gen.start()
                            else:
                                if self.synthetic_gen:
                                    self.synthetic_gen.stop()

                # ------------------------------------------------------------------
                # Telemetry Ingestion & Spatial Interpolation (60FPS Main Thread)
                # ------------------------------------------------------------------
                latest_packet, has_new, last_act, last_act_time = self.telemetry_bridge.snapshot()

                if has_new:
                    is_tracking = (latest_packet.get("state", 1) == 1) and not latest_packet.get("stale", False)
                    self.interpolator.update_target(
                        latest_packet.get("pos"),
                        latest_packet.get("rot"),
                        is_tracking=is_tracking
                    )

                smoothed_pos, smoothed_rot = self.interpolator.step(alpha=0.25)

                calibrated_pos = [
                    smoothed_pos[0] - self.origin_offset[0],
                    smoothed_pos[1] - self.origin_offset[1],
                    smoothed_pos[2] - self.origin_offset[2],
                ]

                self.curr_pos = calibrated_pos
                self.curr_rot = smoothed_rot

                # Task 3.2: Record trajectory history when state == 1 and gesture_state == "RECORDING"
                is_tracking = (latest_packet.get("state", 1) == 1) and not latest_packet.get("stale", False)
                gesture_state = latest_packet.get("gesture_state", "IDLE")
                if is_tracking and gesture_state == "RECORDING":
                    self.trajectory_history.append(self.curr_pos.copy())

                # Task 3.3: Detect and route new Action Triggers
                current_action = latest_packet.get("action", "NULL")
                if current_action and current_action != "NULL" and current_action != self.last_processed_action:
                    self.trigger_action(current_action)
                    self.last_processed_action = current_action
                    self.execution_flash = 1.0
                elif current_action == "NULL":
                    self.last_processed_action = "NULL"

                # Decay flash / notice timers
                if self.execution_flash > 0.0:
                    self.execution_flash = max(0.0, self.execution_flash - 2.5 * dt)
                if self.calib_notice_time > 0.0:
                    self.calib_notice_time = max(0.0, self.calib_notice_time - dt)

                self.target_object.update(dt)

                # ------------------------------------------------------------------
                # 3D Viewport Rendering
                # ------------------------------------------------------------------
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT)
                glMatrixMode(GL_MODELVIEW)
                glLoadIdentity()

                gluLookAt(
                    0.0, 3.2, 6.2,  # Eye position
                    0.0, 0.6, 0.0,  # Look-at target
                    0.0, 1.0, 0.0   # Up vector
                )

                # Draw infinite 3D floor grid along X-Z plane
                draw_infinite_floor_grid(extent=24.0, step=1.0)

                # Draw central reactive target object (cube)
                self.target_object.draw()

                # Task 3.3: Draw 3D trajectory ribbon line strip
                self.draw_trajectory_ribbon()

                # Draw 3D spatial cursor reflecting smoothed 6-DOF phone pose
                draw_phone_spatial_cursor(self.curr_pos, self.curr_rot)

                # ------------------------------------------------------------------
                # 2D HUD Rendering (Orthographic Mode)
                # ------------------------------------------------------------------
                display_telemetry = dict(latest_packet)
                display_telemetry["pos"] = self.curr_pos
                display_telemetry["rot"] = self.curr_rot

                draw_hud(
                    text_renderer=self.text_renderer,
                    telemetry=display_telemetry,
                    fps=fps,
                    calib_notice_time=self.calib_notice_time,
                    anim_time=anim_time,
                    execution_flash=self.execution_flash,
                    synthetic_mode=self.synthetic_mode
                )

                pygame.display.flip()

        finally:
            print("[KineTrak] Shutting down cleanly...")
            if self.synthetic_gen:
                self.synthetic_gen.stop()
            self.watcher.stop()
            pygame.quit()
            print("[KineTrak] Cleanup complete.")


# ==============================================================================
# Main Entry Point
# ==============================================================================
def main():
    parser = argparse.ArgumentParser(description="KineTrak Desktop Client v4.2")
    parser.add_argument("--synthetic", action="store_true", help="Start in synthetic test mode")
    parser.add_argument("--test-frames", type=int, default=0, help="Run for N frames and exit (for automated testing)")
    args = parser.parse_args()

    engine = HostEngine(synthetic_mode=args.synthetic, test_frames=args.test_frames)
    engine.run()


if __name__ == "__main__":
    main()
