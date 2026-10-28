#!/usr/bin/env bash
# Local-only dataset fetch. Outputs stay under ml/validation/data (gitignored).
set -u
cd /workspace
mkdir -p ml/validation/data/urfall

echo "=== GMDCSA-24 v2.1 ==="
zip=ml/validation/data/gmdcsa-v2.1.zip
if [ ! -s "$zip" ]; then
  curl -L --retry 5 --retry-delay 2 -o "$zip" \
    "https://zenodo.org/api/records/13354453/files/ekramalam/GMDCSA24-A-Dataset-for-Human-Fall-Detection-in-Videos-v2.1.zip/content"
fi
ls -lh "$zip"
echo "GMDCSADONE"

echo "=== UR Fall previews (test only, CC BY-NC-SA, not committed) ==="
base="https://fenix.ur.edu.pl/~mkepski/ds/data"
(
  cd ml/validation/data/urfall
  curl -fsSL --retry 4 -O "$base/urfall-cam0-falls.csv"
  curl -fsSL --retry 4 -O "$base/urfall-cam0-adls.csv"
  for i in $(seq -w 1 30); do
    for cam in cam0 cam1; do
      f="fall-${i}-${cam}.mp4"
      if [ ! -s "$f" ]; then
        curl -fsSL --retry 4 -O "$base/$f" || echo "FAIL $f"
      fi
    done
    echo "ur fall $i"
  done
  for i in $(seq -w 1 40); do
    f="adl-${i}-cam0.mp4"
    if [ ! -s "$f" ]; then
      curl -fsSL --retry 4 -O "$base/$f" || echo "FAIL $f"
    fi
  done
)
echo "UR_COUNT $(ls ml/validation/data/urfall/*.mp4 2>/dev/null | wc -l)"
echo "URDONE"

echo "=== CAUCAFall ==="
python3 ml/validation/download_caucafall.py --dest ml/validation/data
echo "CAUCADONE"
echo "ALL_DOWNLOADS_DONE"
