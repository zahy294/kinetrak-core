#!/bin/bash
set -eo pipefail

SNPE_ROOT="/mnt/c/Users/ASUS/projects/kinktrack/Qualcomm_SNPE/qairt/2.50.0.260828"
WORK_DIR="${SNPE_ROOT}"
DATA_TXT="${WORK_DIR}/data.txt"
TFLITE_MODEL="${WORK_DIR}/gesture_model.tflite"
DLC_OUT="${WORK_DIR}/gesture_model.dlc"
DLC_QUANT_OUT="${WORK_DIR}/gesture_model_quantized.dlc"
ANDROID_ASSETS="/mnt/c/Users/ASUS/projects/kinktrack/kinetrak-android/app/src/main/assets"

echo "=== STEP 1: Verifying libLLVM-14 ==="
ldconfig -p | grep libLLVM-14 || { echo "ERROR: libLLVM-14 not in ldconfig!"; exit 1; }
echo "OK: libLLVM-14 confirmed"

echo ""
echo "=== STEP 2: Sourcing Python 3.10 and SNPE environment ==="
source /home/zahy/snpe_env/bin/activate
export PATH="/home/zahy/snpe_env/bin:${PATH}"
export LD_LIBRARY_PATH="/home/zahy/.local/share/uv/python/cpython-3.10.21-linux-x86_64-gnu/lib:${SNPE_ROOT}/lib/x86_64-linux-clang:${LD_LIBRARY_PATH:-}"
export PYTHONPATH="${PYTHONPATH:-}"

source "${SNPE_ROOT}/bin/envsetup.sh"
echo "SNPE_ROOT=${SNPE_ROOT}"
echo "Python: $(python3 --version)"

echo ""
echo "=== STEP 3: Generating/Locating gesture_model.tflite ==="
if [ ! -f "${TFLITE_MODEL}" ]; then
    echo "[INFO] Generating gesture_model.tflite with TensorFlow..."
    python3 /mnt/c/Users/ASUS/projects/kinktrack/generate_tflite_model.py
fi
echo "[INFO] TFLite model: ${TFLITE_MODEL}"
ls -lh "${TFLITE_MODEL}"

echo ""
echo "=== STEP 4: Creating calibration data for gesture shape (1, 45) ==="
INPUT_RAW="${WORK_DIR}/input_0.raw"
python3 -c "import numpy as np; arr = np.random.uniform(-1.0, 1.0, (1, 45)).astype(np.float32); arr.tofile('${INPUT_RAW}'); print('Generated raw input shape (1, 45) -> ${INPUT_RAW}')"
echo "${INPUT_RAW}" > "${DATA_TXT}"
echo "[INFO] Calibration list: ${DATA_TXT}"

echo ""
echo "=== STEP 5: Converting TFLite -> DLC ==="
cd "${WORK_DIR}"
rm -f "${DLC_OUT}" "${DLC_QUANT_OUT}"

"${SNPE_ROOT}/bin/x86_64-linux-clang/snpe-tflite-to-dlc" \
    --input_network "${TFLITE_MODEL}" \
    --output_path "${DLC_OUT}"

if [ -f "${DLC_OUT}" ]; then
    echo "[OK] gesture_model.dlc created successfully."
    ls -lh "${DLC_OUT}"
else
    echo "[ERROR] DLC conversion FAILED."
    exit 1
fi

echo ""
echo "=== STEP 6: NPU Quantization (INT8, enhanced, per-channel) ==="
"${SNPE_ROOT}/bin/x86_64-linux-clang/snpe-dlc-quantize" \
    --input_dlc "${DLC_OUT}" \
    --input_list "${DATA_TXT}" \
    --output_dlc "${DLC_QUANT_OUT}" \
    --use_enhanced_quantizer \
    --use_per_channel_quantization

if [ -f "${DLC_QUANT_OUT}" ]; then
    echo "[OK] gesture_model_quantized.dlc created successfully."
    ls -lh "${DLC_QUANT_OUT}"
else
    echo "[ERROR] Quantization FAILED."
    exit 1
fi

echo ""
echo "=== STEP 7: Copying to Android Assets ==="
mkdir -p "${ANDROID_ASSETS}"
cp -f "${DLC_QUANT_OUT}" "${ANDROID_ASSETS}/gesture_model_quantized.dlc"
cp -f "${DLC_OUT}" "${ANDROID_ASSETS}/gesture_model.dlc"

if [ -f "${ANDROID_ASSETS}/gesture_model_quantized.dlc" ]; then
    echo "[OK] Successfully deployed to Android assets:"
    ls -lh "${ANDROID_ASSETS}"
else
    echo "[ERROR] Failed to copy to Android assets."
    exit 1
fi

echo ""
echo "========================================================"
echo "  OFFLINE NPU PIPELINE COMPLETE & UNBLOCKED"
echo "  Quantized DLC: ${ANDROID_ASSETS}/gesture_model_quantized.dlc"
echo "========================================================"
