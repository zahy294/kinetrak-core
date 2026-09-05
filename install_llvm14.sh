#!/bin/bash
set -e
DEB_URL="https://apt.llvm.org/jammy/pool/main/l/llvm-toolchain-14/libllvm14_14.0.6~++20230131082223+f28c006a5895-1~exp1~20230131082249.127_amd64.deb"
cd /tmp
echo "[INFO] Downloading libllvm14 deb..."
curl -fsSL -o libllvm14_dl.deb "${DEB_URL}"
ls -lh libllvm14_dl.deb
echo "[INFO] Force-installing libllvm14..."
printf '2941\n' | sudo -S dpkg -i --force-depends libllvm14_dl.deb
echo "[INFO] Refreshing ldconfig..."
printf '2941\n' | sudo -S ldconfig
echo "[INFO] Verifying libLLVM-14..."
ldconfig -p | grep libLLVM-14 && echo LLVM14_OK || echo LLVM14_MISSING