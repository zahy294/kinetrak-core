#!/usr/bin/env python3
"""
Generates a minimal valid TFLite flatbuffer for KineTrak gesture model.
Architecture: Input(1,45) -> FullyConnected(32, ReLU) -> FullyConnected(8, NONE)
Pure flatbuffers builder -- no tensorflow or tflite-schema imports needed.
"""
import sys, os, struct
import numpy as np

sys.path.insert(0, '/home/zahy/.local/lib/python3.14/site-packages')
from flatbuffers import builder as fb_builder
from flatbuffers import encode

OUT_PATH = "/mnt/c/Users/ASUS/projects/kinktrack/Qualcomm_SNPE/qairt/2.50.0.260828/gesture_model.tflite"

np.random.seed(42)
W1 = (np.random.randn(32, 45) * 0.05).astype(np.float32)
B1 = np.zeros(32, dtype=np.float32)
W2 = (np.random.randn(8, 32) * 0.05).astype(np.float32)
B2 = np.zeros(8, dtype=np.float32)

b = fb_builder.Builder(1 << 20)

# ---- helpers ----
def vec_bytes(data: bytes):
    b.StartVector(1, len(data), 1)
    for byte in reversed(data):
        b.PrependByte(byte)
    return b.EndVector()

def vec_i32(items):
    b.StartVector(4, len(items), 4)
    for x in reversed(items):
        b.PrependInt32(x)
    return b.EndVector()

def vec_offsets(offsets):
    b.StartVector(4, len(offsets), 4)
    for o in reversed(offsets):
        b.PrependUOffsetTRelative(o)
    return b.EndVector()

# ==============================
# TFLite schema constants
# ==============================
# TensorType: FLOAT32 = 0
# BuiltinOperator: FULLY_CONNECTED = 9
# BuiltinOptions: FullyConnectedOptions = 9
# ActivationFunctionType: NONE=0, RELU=1

# ---- Buffers ----
# Buffer table fields: data (field 0)
def build_buffer(data: bytes = None):
    if data:
        bvec = vec_bytes(data)
    b.StartObject(2)  # Buffer has 2 fields
    if data:
        b.PrependUOffsetTRelativeSlot(0, bvec, 0)  # field 0: data
    return b.EndObject()

buf4 = build_buffer(B2.tobytes())
buf3 = build_buffer(W2.tobytes())
buf2 = build_buffer(B1.tobytes())
buf1 = build_buffer(W1.tobytes())
buf0 = build_buffer()

bufs = vec_offsets([buf0, buf1, buf2, buf3, buf4])

# ---- QuantizationParameters (empty) ----
def build_qparams():
    b.StartObject(7)
    return b.EndObject()

qp = [build_qparams() for _ in range(7)]

# ---- Tensor names ----
names = [b.CreateString(s) for s in [
    "serving_default_input:0",
    "dense/kernel",
    "dense/bias",
    "dense/Relu",
    "dense_1/kernel",
    "dense_1/bias",
    "StatefulPartitionedCall:0"
]]

# ---- Shapes ----
shapes = [
    vec_i32([1, 45]),
    vec_i32([32, 45]),
    vec_i32([32]),
    vec_i32([1, 32]),
    vec_i32([8, 32]),
    vec_i32([8]),
    vec_i32([1, 8]),
]

# Tensor: fields: name(0), shape(1), type(2), buffer(3), quantization(4)
def build_tensor(name_off, shape_off, buf_idx, qp_off):
    b.StartObject(9)
    b.PrependUOffsetTRelativeSlot(0, name_off, 0)   # name
    b.PrependUOffsetTRelativeSlot(1, shape_off, 0)  # shape
    b.PrependInt32Slot(2, 0, 0)                     # type: FLOAT32=0
    b.PrependInt32Slot(3, buf_idx, 0)               # buffer index
    b.PrependUOffsetTRelativeSlot(5, qp_off, 0)     # quantization
    return b.EndObject()

tensors = [
    build_tensor(names[0], shapes[0], 0, qp[0]),
    build_tensor(names[1], shapes[1], 1, qp[1]),
    build_tensor(names[2], shapes[2], 2, qp[2]),
    build_tensor(names[3], shapes[3], 0, qp[3]),
    build_tensor(names[4], shapes[4], 3, qp[4]),
    build_tensor(names[5], shapes[5], 4, qp[5]),
    build_tensor(names[6], shapes[6], 0, qp[6]),
]
tensors_vec = vec_offsets(tensors)

# ---- FullyConnectedOptions ----
# fields: fused_activation_function(0)
def build_fc_opts(activation):  # 0=NONE, 1=RELU
    b.StartObject(3)
    b.PrependInt8Slot(0, activation, 0)
    return b.EndObject()

fc2_opts = build_fc_opts(0)  # NONE
fc1_opts = build_fc_opts(1)  # RELU

# ---- Operators ----
# Operator fields: opcode_index(0), inputs(1), outputs(2), builtin_options_type(6), builtin_options(7)
def build_op(opcode_idx, inputs, outputs, opts_type, opts):
    inp_vec = vec_i32(inputs)
    out_vec = vec_i32(outputs)
    b.StartObject(10)
    b.PrependUint32Slot(0, opcode_idx, 0)           # opcode_index
    b.PrependUOffsetTRelativeSlot(1, inp_vec, 0)    # inputs
    b.PrependUOffsetTRelativeSlot(2, out_vec, 0)    # outputs
    b.PrependUint8Slot(6, opts_type, 0)             # builtin_options_type
    b.PrependUOffsetTRelativeSlot(7, opts, 0)       # builtin_options
    return b.EndObject()

op2 = build_op(0, [3, 4, 5], [6], 9, fc2_opts)
op1 = build_op(0, [0, 1, 2], [3], 9, fc1_opts)
ops_vec = vec_offsets([op1, op2])

# ---- SubGraph ----
sg_name = b.CreateString("main")
sg_in  = vec_i32([0])
sg_out = vec_i32([6])

# SubGraph fields: name(0), tensors(1), inputs(2), outputs(3), operators(4)
b.StartObject(6)
b.PrependUOffsetTRelativeSlot(0, sg_name, 0)
b.PrependUOffsetTRelativeSlot(1, tensors_vec, 0)
b.PrependUOffsetTRelativeSlot(2, sg_in, 0)
b.PrependUOffsetTRelativeSlot(3, sg_out, 0)
b.PrependUOffsetTRelativeSlot(4, ops_vec, 0)
sg = b.EndObject()
sgs_vec = vec_offsets([sg])

# ---- OperatorCode ----
# fields: builtin_code(0)
b.StartObject(4)
b.PrependInt8Slot(0, 9, 0)  # FULLY_CONNECTED = 9
opcode = b.EndObject()
opcodes_vec = vec_offsets([opcode])

# ---- Model description ----
desc = b.CreateString("KineTrak gesture model placeholder v1.0 -- Input(1,45)->FC(32,ReLU)->FC(8)")

# ---- Model ----
# fields: version(0), operator_codes(1), subgraphs(2), description(3), buffers(4)
b.StartObject(8)
b.PrependUint32Slot(0, 3, 0)                        # version = 3
b.PrependUOffsetTRelativeSlot(1, opcodes_vec, 0)    # operator_codes
b.PrependUOffsetTRelativeSlot(2, sgs_vec, 0)        # subgraphs
b.PrependUOffsetTRelativeSlot(3, desc, 0)           # description
b.PrependUOffsetTRelativeSlot(4, bufs, 0)           # buffers
model = b.EndObject()

b.Finish(model)
raw = bytes(b.Output())

# Inject TFLite file identifier at bytes 4-8
TFLITE_ID = b"TFL3"
out = raw[:4] + TFLITE_ID + raw[8:]

os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
with open(OUT_PATH, "wb") as f:
    f.write(out)

sz = len(out)
print(f"[OK] gesture_model.tflite written")
print(f"     Size     : {sz} bytes ({sz/1024:.1f} KB)")
print(f"     Path     : {OUT_PATH}")
print(f"     Input    : (1, 45) float32  [45-feature gesture buffer]")
print(f"     Layer 1  : FullyConnected(45 -> 32, ReLU)")
print(f"     Layer 2  : FullyConnected(32 -> 8,  NONE)")
print(f"     Output   : (1, 8)  [8 gesture classes]")
