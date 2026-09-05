#!/bin/bash
# =============================================================================
# KineTrak: LLVM 14 Fix + TFLite -> DLC Conversion + NPU Quantization
# =============================================================================
# WSL Ubuntu 26.04 "resolute" does not ship libllvm14 natively.
# Strategy:
#   1. Add apt.llvm.org jammy repo for libllvm14 (the .so installs fine cross-distro).
#   2. Install libllvm14 from that repo.
#   3. Source SNPE envsetup.sh.
#   4. Confirm gesture_model.tflite is present.
#   5. Run snpe-tflite-to-dlc to produce gesture_model.dlc.
#   6. Run snpe-dlc-quantize to produce gesture_model_quantized.dlc.
# =============================================================================

set -euo pipefail

SNPE_ROOT="/mnt/c/Users/ASUS/projects/kinktrack/Qualcomm_SNPE/qairt/2.50.0.260828"
LLVM_JAMMY_REPO="https://apt.llvm.org/jammy/"
LLVM_JAMMY_SUITE="llvm-toolchain-jammy-14"
LLVM_GPG_KEY="https://apt.llvm.org/llvm-snapshot.gpg.key"
LLVM_JAMMY_LIST="/etc/apt/sources.list.d/llvm-jammy-14.list"
WORK_DIR="${SNPE_ROOT}"
DATA_TXT="${WORK_DIR}/data.txt"
TFLITE_MODEL="${WORK_DIR}/gesture_model.tflite"
DLC_OUT="${WORK_DIR}/gesture_model.dlc"
DLC_QUANT_OUT="${WORK_DIR}/gesture_model_quantized.dlc"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# STEP 1: Resolve libLLVM-14.so.1
info "=== STEP 1: Resolving libLLVM-14.so.1 dependency ==="

if ldconfig -p | grep -q 'libLLVM-14'; then
    info "libLLVM-14.so.1 already present -- skipping installation."
else
    if dpkg -l libllvm14 2>/dev/null | grep -q '^ii'; then
        info "libllvm14 dpkg record found but ldconfig stale; refreshing..."
        sudo ldconfig
    else
        info "libllvm14 not installed. Fetching from apt.llvm.org/jammy ..."

        if [ ! -f /etc/apt/trusted.gpg.d/apt.llvm.org.asc ]; then
            info "Adding LLVM APT GPG key..."
            curl -fsSL "${LLVM_GPG_KEY}" | sudo tee /etc/apt/trusted.gpg.d/apt.llvm.org.asc > /dev/null
        fi

        if [ ! -f "${LLVM_JAMMY_LIST}" ]; then
            info "Adding apt.llvm.org/jammy source..."
            echo "deb ${LLVM_JAMMY_REPO} ${LLVM_JAMMY_SUITE} main" | sudo tee "${LLVM_JAMMY_LIST}" > /dev/null
        fi

        info "Running apt-get update (scoped to llvm-jammy list)..."
        sudo apt-get update \
            -o Dir::Etc::sourcelist="${LLVM_JAMMY_LIST}" \
            -o Dir::Etc::sourcelistd="" \
            -o APT::Get::List-Cleanup=0 2>&1 | tail -5

        info "Installing libllvm14..."
        sudo apt-get install -y --no-install-recommends libllvm14
    fi
fi

LLVM14_SO=$(ldconfig -p 2>/dev/null | grep 'libLLVM-14' | awk '{print $NF}' | head -1)
if [ -z "${LLVM14_SO}" ]; then
    LLVM14_FILE=$(find /usr/lib /usr/local/lib -name 'libLLVM-14*.so*' 2>/dev/null | head -1)
    if [ -n "${LLVM14_FILE}" ]; then
        LLVM14_DIR=$(dirname "${LLVM14_FILE}")
        sudo ln -sf "${LLVM14_FILE}" "${LLVM14_DIR}/libLLVM-14.so.1" 2>/dev/null || true
        sudo ldconfig
        LLVM14_SO="${LLVM14_DIR}/libLLVM-14.so.1"
    fi
fi

[ -n "${LLVM14_SO}" ] || error "libLLVM-14.so.1 still not found after install."
info "libLLVM-14.so.1 confirmed: ${LLVM14_SO}"

# STEP 2: Setup SNPE/QAIRT environment
info ""
info "=== STEP 2: Initialising SNPE/QAIRT environment ==="
[ -d "${SNPE_ROOT}" ] || error "SNPE SDK not found at ${SNPE_ROOT}"
source "${SNPE_ROOT}/bin/envsetup.sh"
info "SNPE_ROOT=${SNPE_ROOT}"

# STEP 3: Locate gesture_model.tflite
info ""
info "=== STEP 3: Locating gesture_model.tflite ==="

FOUND_TFLITE=""
for d in "${WORK_DIR}" "/mnt/c/Users/ASUS/projects/kinktrack/kinetrak-android" "/mnt/c/Users/ASUS/projects/kinktrack"; do
    candidate=$(find "${d}" -maxdepth 4 -name "gesture_model.tflite" 2>/dev/null | head -1)
    if [ -n "${candidate}" ]; then FOUND_TFLITE="${candidate}"; break; fi
done

if [ -n "${FOUND_TFLITE}" ]; then
    info "Found: ${FOUND_TFLITE}"
    [ "${FOUND_TFLITE}" = "${TFLITE_MODEL}" ] || { cp "${FOUND_TFLITE}" "${TFLITE_MODEL}"; info "Copied to ${TFLITE_MODEL}"; }
else
    error "gesture_model.tflite not found. Place it at: ${TFLITE_MODEL} and re-run."
fi

# STEP 4: Ensure data.txt for quantization calibration
info ""
info "=== STEP 4: Checking calibration data (data.txt) ==="

if [ ! -f "${DATA_TXT}" ]; then
    FOUND_DATA=$(find /mnt/c/Users/ASUS/projects/kinktrack -maxdepth 5 -name "data.txt" 2>/dev/null | head -1)
    if [ -n "${FOUND_DATA}" ]; then
        cp "${FOUND_DATA}" "${DATA_TXT}"; info "Copied data.txt from: ${FOUND_DATA}"
    else
        warn "No data.txt found. Creating random placeholder inputs."
        INPUT_RAW="${WORK_DIR}/input_0.raw"
        python3 -c "
import numpy as np
arr = np.random.uniform(-1.0, 1.0, (1, 224, 224, 3)).astype(np.float32)
arr.tofile('${INPUT_RAW}')
print('Created placeholder raw input: ${INPUT_RAW}')
"
        echo "${INPUT_RAW}" > "${DATA_TXT}"
        info "Created data.txt -> ${INPUT_RAW}"
        warn "For production: replace data.txt with real representative gesture frames."
    fi
fi
info "Calibration list: ${DATA_TXT}"

# STEP 5: TFLite -> DLC Conversion
info ""
info "=== STEP 5: Converting TFLite -> DLC ==="
cd "${WORK_DIR}"
"${SNPE_ROOT}/bin/x86_64-linux-clang/snpe-tflite-to-dlc" \
    --input_network "${TFLITE_MODEL}" \
    --output_path "${DLC_OUT}"

echo ""
[ -f "${DLC_OUT}" ] && info "gesture_model.dlc OK ($(du -sh "${DLC_OUT}" | cut -f1))" \
    || error "DLC conversion failed -- gesture_model.dlc not created."

# STEP 6: NPU Quantization (INT8, enhanced, per-channel)
info ""
info "=== STEP 6: NPU Quantization (INT8) ==="
"${SNPE_ROOT}/bin/x86_64-linux-clang/snpe-dlc-quantize" \
    --input_dlc "${DLC_OUT}" \
    --input_list "${DATA_TXT}" \
    --output_dlc "${DLC_QUANT_OUT}" \
    --use_enhanced_quantizer \
    --use_per_channel_quantization

echo ""
if [ -f "${DLC_QUANT_OUT}" ]; then
    info "gesture_model_quantized.dlc OK ($(du -sh "${DLC_QUANT_OUT}" | cut -f1))"
    info "================================================================="
    info "  PIPELINE COMPLETE"
    info "  Unquantized DLC : ${DLC_OUT}"
    info "  Quantized DLC   : ${DLC_QUANT_OUT}"
    info "  Next: copy to kinetrak-android/app/src/main/assets/"
    info "================================================================="
else
    error "Quantization failed -- gesture_model_quantized.dlc not created."
fi
