#!/bin/bash
export PATH=/home/zahy/.local/bin:
pip install --quiet --break-system-packages flatbuffers tflite
echo PACKAGES_DONE
pip show flatbuffers tflite 2>/dev/null | grep -E 'Name:|Version:'
