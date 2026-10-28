#!/usr/bin/env bash
# Fetch the extra training videos. Nothing here is committed.
# Outputs stay under ml/validation/data (gitignored).
set -u
cd /workspace
DATA=ml/validation/data
mkdir -p "$DATA/fallvision" "$DATA/falldb" "$DATA/omnifall" "$DATA/urfall"

dl() {
  local url="$1" dest="$2"
  if [ -s "$dest" ]; then
    echo "have $dest"
    return 0
  fi
  echo "GET $dest"
  curl -fL --retry 8 --retry-delay 3 --retry-all-errors -C - \
    -A "naksang-zero-training/1.0" \
    -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
  ls -lh "$dest"
}

base_sets() {
echo "=== GMDCSA-24 v2.1 (CC BY 4.0) ==="
dl "https://zenodo.org/api/records/13354453/files/ekramalam/GMDCSA24-A-Dataset-for-Human-Fall-Detection-in-Videos-v2.1.zip/content" \
  "$DATA/gmdcsa-v2.1.zip"

echo "=== UR Fall previews (CC BY-NC-SA 4.0, training allowed, not committed) ==="
base="https://fenix.ur.edu.pl/~mkepski/ds/data"
pushd "$DATA/urfall" >/dev/null
dl "$base/urfall-cam0-falls.csv" urfall-cam0-falls.csv
dl "$base/urfall-cam0-adls.csv" urfall-cam0-adls.csv
for i in $(seq -w 1 30); do
  for cam in cam0 cam1; do
    f="fall-${i}-${cam}.mp4"
    dl "$base/$f" "$f" || echo "FAIL $f"
  done
done
for i in $(seq -w 1 40); do
  f="adl-${i}-cam0.mp4"
  dl "$base/$f" "$f" || echo "FAIL $f"
done
popd >/dev/null
echo "UR_COUNT $(ls "$DATA/urfall"/*.mp4 2>/dev/null | wc -l)"

echo "=== CAUCAFall (CC BY 4.0) ==="
python3 ml/validation/download_caucafall.py --dest "$DATA"

echo "BASE_DONE"
}

falldb_set() {
echo "=== Zenodo FALL DATABASE (record says CC BY 4.0; authors limit it to non-commercial research) ==="
dl "https://zenodo.org/api/records/3886586/files/FallDatabase.rar/content" \
  "$DATA/falldb/FallDatabase.rar"

echo "FALLDB_DONE"
}

fallvision_set() {
echo "=== FallVision raw only (Harvard Dataverse CC0). Masked copies and YOLO CSVs are skipped. ==="
# id filename
raws=(
  "8138607 f_raw_b_1.rar"
  "8138612 f_raw_b_2.rar"
  "8138614 f_raw_b_3.rar"
  "8138617 f_raw_b_4.rar"
  "8138622 f_raw_c_1.rar"
  "8138623 f_raw_c_2.rar"
  "8138624 f_raw_c_3.rar"
  "8138935 f_raw_s_1.rar"
  "8138937 f_raw_s_2.rar"
  "8138941 f_raw_s_3.rar"
  "8140287 nf_raw_b_1.rar"
  "8140288 nf_raw_b_2.rar"
  "8140291 nf_raw_b_3.rar"
  "8140295 nf_raw_b_4.rar"
  "8140307 nf_raw_c_1.rar"
  "8140309 nf_raw_c_2.rar"
  "8140310 nf_raw_c_3.rar"
  "8140315 nf_raw_s_1.rar"
  "8140316 nf_raw_s_2.rar"
  "8140317 nf_raw_s_3.rar"
)
for spec in "${raws[@]}"; do
  id="${spec%% *}"
  name="${spec##* }"
  dl "https://dataverse.harvard.edu/api/access/datafile/${id}" "$DATA/fallvision/${name}"
  dest="$DATA/fallvision/unpacked/${name%.rar}"
  if [ ! -d "$dest" ]; then
    mkdir -p "$dest"
    unar -q -o "$dest" "$DATA/fallvision/${name}" || echo "UNAR_FAIL $name"
  fi
  echo "UNPACKED $name"
done

echo "FALLVISION_DONE"
}

labels_set() {
echo "=== OmniFall labels (CC BY-NC-SA 4.0). Videos are a separate archive. ==="
dl "https://huggingface.co/datasets/simplexsigil2/omnifall/resolve/main/labels/of-syn.csv" \
  "$DATA/omnifall/of-syn.csv"
dl "https://huggingface.co/datasets/simplexsigil2/omnifall/raw/main/LABELS.md" \
  "$DATA/omnifall/LABELS.md" || true

echo "LABELS_DONE"
}

base_sets > ml/validation/data/base.log 2>&1 &
pid_base=$!
falldb_set > ml/validation/data/falldb.log 2>&1 &
pid_db=$!
fallvision_set > ml/validation/data/fallvision.log 2>&1 &
pid_fv=$!
labels_set > ml/validation/data/labels.log 2>&1 &
pid_labels=$!
echo "pids base=$pid_base falldb=$pid_db fallvision=$pid_fv labels=$pid_labels"
wait "$pid_base" "$pid_db" "$pid_fv" "$pid_labels"
echo "ALL_DOWNLOADS_DONE"
