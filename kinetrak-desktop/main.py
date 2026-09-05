import sys
import time
import math
import pygame
from pygame.locals import *
from OpenGL.GL import *
from OpenGL.GLU import *
import numpy as np
from pyquaternion import Quaternion

from clipboard_hook import ClipboardWatcher

# --- Viewport Configuration ---
WINDOW_WIDTH = 1280
WINDOW_HEIGHT = 720
TARGET_FPS = 60

# --- Interpolation Smoothing Parameters ---
# Alpha blend factor per frame (60Hz loop absorbing 15Hz telemetry)
LERP_FACTOR = 0.25
SLERP_FACTOR = 0.25


class HostEngine:
    def __init__(self):
        pygame.init()
        pygame.font.init()
        pygame.display.set_caption("KineTrak Desktop Viewport — 6-DOF Spatial Copilot")
        self.screen = pygame.display.set_mode(
            (WINDOW_WIDTH, WINDOW_HEIGHT), DOUBLEBUF | OPENGL
        )
        self.clock = pygame.time.Clock()
        self.font = pygame.font.SysFont("Consolas", 18)

        # Telemetry State (Raw incoming target)
        self.target_pos = np.array([0.0, 0.0, -5.0], dtype=np.float32)
        self.target_rot = Quaternion(1.0, 0.0, 0.0, 0.0)  # [qw, qx, qy, qz]
        self.last_seq = 0
        self.is_stale = True
        self.gesture_state = "NULL"
        self.active_action = "NONE"
        self.action_display_timer = 0.0

        # Viewport Filtered State (Rendered smoothed coordinates)
        self.curr_pos = np.array([0.0, 0.0, -5.0], dtype=np.float32)
        self.curr_rot = Quaternion(1.0, 0.0, 0.0, 0.0)

        # CAD Visual Demonstration State (e.g., Explode offset)
        self.explode_factor = 0.0
        self.target_explode = 0.0

        # Start Clipboard Watcher
        self.watcher = ClipboardWatcher(state_callback=self.on_telemetry_packet)
        self.watcher.start()

    def on_telemetry_packet(self, data):
        """Callback invoked by ClipboardWatcher thread on valid packet arrival."""
        if data.get("state") == 0 or data.get("stale"):
            self.is_stale = True
            return

        self.is_stale = False
        self.last_seq = data.get("seq", self.last_seq)
        self.gesture_state = data.get("gesture_state", "NULL")

        # Ingestion coordinates
        pos = data.get("pos", [0.0, 0.0, 0.0])
        # Map phone translation to OpenGL units (scale factor adjusted for tabletop range)
        self.target_pos = np.array([pos[0] * 3.0, pos[1] * 3.0, -5.0 + (pos[2] * 3.0)], dtype=np.float32)

        rot = data.get("rot", [1.0, 0.0, 0.0, 0.0])
        try:
            # Android format: [qw, qx, qy, qz]
            self.target_rot = Quaternion(rot[0], rot[1], rot[2], rot[3]).normalised
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
            self.curr_pos = np.array([0.0, 0.0, -5.0], dtype=np.float32)
            self.curr_rot = Quaternion(1.0, 0.0, 0.0, 0.0)
            print("[ACTION TRIGGERED] RESET VIEWPORT")

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
        # Offset components outward based on explode_factor
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

        # Render 2D HUD overlays (Status banner, SEQ, Latch)
        # Note: Pygame 2D blitting over PyOpenGL window uses standard glDrawPixels/ortho or console output
        pygame.display.flip()

    def run(self):
        self.init_gl()
        running = True

        print("\n🚀 KineTrak Desktop Viewport Active.")
        print("   Listening on Office Kit Shared Clipboard @ 15Hz...")
        print("   Press ESC in window to exit.\n")

        try:
            while running:
                for event in pygame.event.get():
                    if event.type == QUIT or (event.type == KEYDOWN and event.key == K_ESCAPE):
                        running = False
                    elif event.type == KEYDOWN and event.key == K_SPACE:
                        # Desktop keyboard manual test trigger
                        self.trigger_action("EXPLODE")

                self.update_kinematics()
                self.render()
                self.clock.tick(TARGET_FPS)

        finally:
            self.watcher.stop()
            pygame.quit()
            sys.exit(0)


if __name__ == "__main__":
    engine = HostEngine()
    engine.run()