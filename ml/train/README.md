# Retrain the pose-window fall model

The phone scores a 10-frame pose window (about 2 seconds at 5 fps) with a tiny model. Weights are plain numbers. Kotlin evaluates them. There is no TensorFlow Lite dependency.

Features are hip-centered, divided by torso length, and mirror-invariant. A raw move down the image is not a feature, because walking toward an upper camera does that without falling. A drop is kept only when the torso also flattens or shortens. See `features.py`. The same math is in `android/.../detect/PoseFeatures.kt`.

## Data

Landmark CSVs come from the validation extractors. Videos and UR Fall outputs stay out of git.

```bash
python3 -m venv ml/validation/.venv
ml/validation/.venv/bin/pip install -r ml/validation/requirements.txt -r ml/train/requirements.txt
python3 ml/validation/download_caucafall.py --dest ml/validation/data
# GMDCSA-24 v2.1 zip from Zenodo record 13354453, saved as ml/validation/data/gmdcsa-v2.1.zip
# UR Fall mp4 previews and the two feature CSVs from https://fenix.ur.edu.pl/~mkepski/ds/uf.html
# into ml/validation/data/urfall/. Do not commit them (CC BY-NC-SA).

ml/validation/.venv/bin/python ml/validation/extract_landmarks.py \
  --data ml/validation/data --out ml/validation/out/caucafall/landmarks
ml/validation/.venv/bin/python ml/validation/extract_gmdcsa.py
ml/validation/.venv/bin/python ml/validation/extract_urfall.py
```

UR ceiling clips are scored and are not used as training rows. Image y is not gravity on that camera.

## Fit

```bash
ml/validation/.venv/bin/python ml/train/train_pose_model.py
```

This writes:

- `ml/models/pose_fall_model.json`
- `android/app/src/main/java/kr/zerotwo/naksang/detect/PoseFallWeights.kt`
- `android/app/src/test/resources/pose_fall_reference.json`
- `ml/validation/results/model_lodo.md`

The script trains logistic regression and a 12-unit MLP, keeps the one with more leave-one-dataset-out falls at zero held-out ADL false alarms, then refits that kind on every available dataset. The shipped threshold is chosen on those same clips, so it is in-distribution. The leave-one-dataset-out table is the generalization number.

`PoseFallModelTest` checks that Kotlin reproduces the Python score on the reference windows.

## Phone

The suspicion is the existing rule, or this score held for two frames, or the lying-still path. Settings can turn the model and the lying-still path off. The 30-second countdown is unchanged.
