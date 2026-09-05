"""
KineTrak Desktop — 6-DOF Spatial Viewport & Host Engine
"""

import sys
import os
import time
import math
import argparse
import threading
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

        # CAD Visual Demonstration State (e.g., Explode offset)
        self.explode_factor = 0.0
        self.target_explode = 0.0

        # Test and synthetic execution modes
        self.test_frames = test_frames
        self.frame_count = 0
        self.synthetic_mode = synthetic_mode
        self.synthetic_thread = None
        self.synthetic_running = False

        # Start Clipboard Watcher
        self.watcher = ClipboardWatcher(state_callback=self.on_telemetry_packet)
        self.watcher.start()

        if self.synthetic_mode:
            self.start_synthetic()

    def on_telemetry_packet(self, data):
        """Callback invoked by ClipboardWatcher thread on valid packet arrival."""
        if data.get("state") == 0 or data.get("stale"):
            # DO NOT reset self.curr_pos or self.target_pos! Simply set is_tracking_valid = False
            self.is_tracking_valid = False
            self.is_stale = True
            return

        # When valid data arrives (data.get("state") == 1 and not stale):
        self.is_tracking_valid = True
        self.is_stale = False
        self.last_seq = data.get("seq", self.last_seq)
        self.gesture_state = data.get("gesture_state", "IDLE")

        # Calibrate origin on the first valid frame:
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

        if action in ("EXPLODE", "ACTION:TEST", "ACTION:SPAWN"):
            # Toggle explode disassembly mode
            self.target_explode = 1.0 if self.target_explode == 0.0 else 0.0
            print(f"[ACTION TRIGGERED] {action} -> Target: {self.target_explode}")
        elif action in ("RESET", "ACTION:RESET"):
            self.target_explode = 0.0
            self.origin_pos = None
            self.calib_display_timer = time.time() + 2.0
            print("[ACTION TRIGGERED] RESET VIEWPORT & ORIGIN")

    def update_kinematics(self):
        """Smooths 15Hz discrete telemetry frames into continuous 60Hz viewport motion."""
        # 1. LERP Position: P_t = P_{t-1} + alpha * (P_target - P_{t-1})
        self.curr_pos += (self.target_pos - self.curr_pos) * LERP_FACTOR

        # 2. SLERP Orientation: Slerp between current quaternion and target
        try:
            self.curr_rot = Quaternion.slerp(self.curr_rot, self.target_rot, amount=SLERP_FACTOR)
        except Exception:
            self.curr_rot = self.target_rot

        # 3. Smooth Explode Factor
        self.explode_factor += (self.target_explode - self.explode_factor) * 0.1

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
        glLightfv(GL_LIGHT0, GL_AMBIENT, (0.2, 0.2, 0.2, 1.0))
        glLightfv(GL_LIGHT0, GL_DIFFUSE, (0.8, 0.8, 0.8, 1.0))

        glMatrixMode(GL_PROJECTION)
        glLoadIdentity()
        gluPerspective(45.0, (WINDOW_WIDTH / WINDOW_HEIGHT), 0.1, 100.0)
        glMatrixMode(GL_MODELVIEW)

    def draw_cube_face(self, v1, v2, v3, v4, normal, color):
        glColor3f(*color)
        glNormal3f(*normal)
        glVertex3f(*v1)
        glVertex3f(*v2)
        glVertex3f(*v3)
        glVertex3f(*v4)

    def draw_exploded_cad_model(self):
        """Renders a multi-part mechanical block showing spatial orientation and explode states."""
        offset = self.explode_factor * 1.2

        glBegin(GL_QUADS)
        # Part A: Center core (Dark Slate)
        c = (0.2, 0.6, 0.86)
        self.draw_cube_face((-0.5, -0.5, 0.5), (0.5, -0.5, 0.5), (0.5, 0.5, 0.5), (-0.5, 0.5, 0.5), (0, 0, 1), c)
        self.draw_cube_face((-0.5, -0.5, -0.5), (-0.5, 0.5, -0.5), (0.5, 0.5, -0.5), (0.5, -0.5, -0.5), (0, 0, -1), c)
        self.draw_cube_face((-0.5, 0.5, -0.5), (-0.5, 0.5, 0.5), (0.5, 0.5, 0.5), (0.5, 0.5, -0.5), (0, 1, 0), c)
        self.draw_cube_face((-0.5, -0.5, -0.5), (0.5, -0.5, -0.5), (0.5, -0.5, 0.5), (-0.5, -0.5, 0.5), (0, -1, 0), c)
        glEnd()

        # Part B: Top Plate (Explodes +Y)
        glPushMatrix()
        glTranslatef(0.0, offset, 0.0)
        glBegin(GL_QUADS)
        c_top = (0.95, 0.6, 0.1)
        self.draw_cube_face((-0.6, 0.55, 0.6), (0.6, 0.55, 0.6), (0.6, 0.7, 0.6), (-0.6, 0.7, 0.6), (0, 0, 1), c_top)
        self.draw_cube_face((-0.6, 0.55, -0.6), (-0.6, 0.7, -0.6), (0.6, 0.7, -0.6), (0.6, 0.55, -0.6), (0, 0, -1), c_top)
        self.draw_cube_face((-0.6, 0.7, -0.6), (-0.6, 0.7, 0.6), (0.6, 0.7, 0.6), (0.6, 0.7, -0.6), (0, 1, 0), c_top)
        glEnd()
        glPopMatrix()

        # Part C: Front Shield (Explodes +Z)
        glPushMatrix()
        glTranslatef(0.0, 0.0, offset)
        glBegin(GL_QUADS)
        c_front = (0.1, 0.8, 0.4)
        self.draw_cube_face((-0.5, -0.5, 0.55), (0.5, -0.5, 0.55), (0.5, 0.5, 0.55), (-0.5, 0.5, 0.55), (0, 0, 1), c_front)
        glEnd()
        glPopMatrix()

    def render_hud(self):
        """Renders 2D HUD text overlay displaying telemetry and state."""
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

        # Draw semi-transparent panel background
        glBegin(GL_QUADS)
        glColor4f(0.04, 0.06, 0.09, 0.80)
        glVertex2f(20, 20); glVertex2f(420, 20); glVertex2f(420, 230); glVertex2f(20, 230)
        glEnd()

        # Panel border
        glLineWidth(1.5)
        glBegin(GL_LINE_LOOP)
        glColor4f(0.18, 0.28, 0.42, 0.90)
        glVertex2f(20, 20); glVertex2f(420, 20); glVertex2f(420, 230); glVertex2f(20, 230)
        glEnd()

        # Render text lines
        fps = self.clock.get_fps()
        lines = [
            ("KINETRAK 6-DOF VIEWPORT", (0, 229, 255)),
            (f"SEQ: {self.last_seq:<8d} | FPS: {fps:>5.1f}", (200, 210, 225)),
            (f"POS: X:{self.curr_pos[0]:+6.2f} Y:{self.curr_pos[1]:+6.2f} Z:{self.curr_pos[2]:+6.2f}", (230, 235, 245)),
            (f"ROT: W:{self.curr_rot[0]:+5.2f} X:{self.curr_rot[1]:+5.2f} Y:{self.curr_rot[2]:+5.2f} Z:{self.curr_rot[3]:+5.2f}", (180, 195, 215)),
            (f"ACTION: {self.active_action}", (0, 230, 118) if self.active_action != "NONE" else (140, 150, 170)),
            (f"GESTURE: {self.gesture_state}", (200, 200, 255)),
            (f"ORIGIN: {'CALIBRATED' if self.origin_pos is not None else 'UNSET'}", (255, 214, 0) if self.origin_pos is not None else (255, 100, 100)),
        ]

        y = 30
        for text_str, color in lines:
            text_surface = self.font.render(text_str, True, color)
            text_data = pygame.image.tostring(text_surface, "RGBA", True)
            w, h = text_surface.get_size()
            glRasterPos2i(35, y + h)
            glDrawPixels(w, h, GL_RGBA, GL_UNSIGNED_BYTE, text_data)
            y += 26

        # Warning banner if tracking lost or stale
        if not self.is_tracking_valid or self.is_stale:
            warn_str = "⚠ WARNING: TELEMETRY STALE / TRACKING LOST (HOLDING POSE)"
            warn_surface = self.font_title.render(warn_str, True, (255, 60, 50))
            w, h = warn_surface.get_size()
            warn_x = (WINDOW_WIDTH - w) // 2
            warn_y = 25
            glBegin(GL_QUADS)
            glColor4f(0.3, 0.05, 0.05, 0.85)
            glVertex2f(warn_x - 10, warn_y - 5)
            glVertex2f(warn_x + w + 10, warn_y - 5)
            glVertex2f(warn_x + w + 10, warn_y + h + 5)
            glVertex2f(warn_x - 10, warn_y + h + 5)
            glEnd()
            text_data = pygame.image.tostring(warn_surface, "RGBA", True)
            glRasterPos2i(warn_x, warn_y + h)
            glDrawPixels(w, h, GL_RGBA, GL_UNSIGNED_BYTE, text_data)

        # Calibrated notice
        if time.time() < self.calib_display_timer:
            calib_str = "✓ ORIGIN RECALIBRATED (R KEY)"
            calib_surface = self.font.render(calib_str, True, (0, 230, 118))
            w, h = calib_surface.get_size()
            cx = (WINDOW_WIDTH - w) // 2
            cy = WINDOW_HEIGHT - 60
            text_data = pygame.image.tostring(calib_surface, "RGBA", True)
            glRasterPos2i(cx, cy + h)
            glDrawPixels(w, h, GL_RGBA, GL_UNSIGNED_BYTE, text_data)

        # Footer instructions
        footer_str = "[R] Recalibrate Origin | [SPACE] Trigger Explode | [T] Synthetic Mode | [ESC] Exit"
        footer_surface = self.font.render(footer_str, True, (120, 135, 155))
        fw, fh = footer_surface.get_size()
        text_data = pygame.image.tostring(footer_surface, "RGBA", True)
        glRasterPos2i((WINDOW_WIDTH - fw) // 2, WINDOW_HEIGHT - 20)
        glDrawPixels(fw, fh, GL_RGBA, GL_UNSIGNED_BYTE, text_data)

        glEnable(GL_DEPTH_TEST)
        glEnable(GL_LIGHTING)
        glMatrixMode(GL_PROJECTION)
        glPopMatrix()
        glMatrixMode(GL_MODELVIEW)
        glPopMatrix()

    def render(self):
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT)
        glClearColor(0.08, 0.09, 0.11, 1.0)
        glLoadIdentity()

        # Apply smoothed 6-DOF translation
        glTranslatef(self.curr_pos[0], self.curr_pos[1], self.curr_pos[2])

        # Apply smoothed orientation from Quaternion
        rot_matrix = self.curr_rot.rotation_matrix
        gl_matrix = [
            rot_matrix[0][0], rot_matrix[1][0], rot_matrix[2][0], 0.0,
            rot_matrix[0][1], rot_matrix[1][1], rot_matrix[2][1], 0.0,
            rot_matrix[0][2], rot_matrix[1][2], rot_matrix[2][2], 0.0,
            0.0,              0.0,              0.0,              1.0
        ]
        glMultMatrixf(gl_matrix)

        # Draw CAD geometry
        self.draw_exploded_cad_model()

        # Render 2D HUD overlays
        self.render_hud()

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
                "action": "ACTION:SPAWN" if int(elapsed) % 10 == 0 and (elapsed - int(elapsed) < 0.1) else "NULL",
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
                        elif event.key == pygame.K_SPACE:
                            self.trigger_action("EXPLODE")
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
