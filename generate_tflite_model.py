import os
import tensorflow as tf
import numpy as np

def generate_model():
    print(f"TensorFlow version: {tf.__version__}")
    
    # Input shape: (45,) for 45-frame gesture buffer (batch shape (1, 45))
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(45,), name="gesture_input"),
        tf.keras.layers.Dense(32, activation='relu', name="dense_1"),
        tf.keras.layers.Dense(16, activation='relu', name="dense_2"),
        tf.keras.layers.Dense(8, activation='softmax', name="gesture_output")
    ])
    
    model.summary()
    
    # Convert to TFLite
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    
    out_paths = [
        "/mnt/c/Users/ASUS/projects/kinktrack/gesture_model.tflite",
        "/mnt/c/Users/ASUS/projects/kinktrack/Qualcomm_SNPE/qairt/2.50.0.260828/gesture_model.tflite"
    ]
    
    for path in out_paths:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(tflite_model)
        print(f"Saved TFLite model to: {path} ({len(tflite_model)} bytes)")

if __name__ == "__main__":
    generate_model()
