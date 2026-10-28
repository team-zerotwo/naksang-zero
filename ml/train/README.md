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
# into ml/validation/data/urfall/. Do not commit them (CC BY-NC-SA 4.0).
# Extra sets (FallVision raw CC0, and the sets that were skipped) are in
# ml/validation/download_more.sh. Unpack each FallVision RAR alone; unar
# mis-reads sibling RARs as extra volumes.

ml/validation/.venv/bin/python ml/validation/extract_landmarks.py \
  --data ml/validation/data --out ml/validation/out/caucafall/landmarks
ml/validation/.venv/bin/python ml/validation/extract_gmdcsa.py
ml/validation/.venv/bin/python ml/validation/extract_urfall.py
# FallVision, after ml/validation/download_more.sh:
ml/validation/.venv/bin/python ml/validation/extract_fallvision.py
```

UR Fall side views are training rows. The ceiling camera is not: image y is not gravity there, and that camera has no ADL clips. Do not commit the UR files or any downloaded video.

| Dataset | License | Role |
| --- | --- | --- |
| CAUCAFall (Mendeley 10.17632/7w7fccy7ky.5) | CC BY 4.0 | train and leave-one-dataset-out |
| GMDCSA-24 v2.1 (Zenodo 10.5281/zenodo.13354453) | CC BY 4.0 | train and leave-one-dataset-out |
| UR Fall side views | CC BY-NC-SA 4.0 | train. Ceiling is test only |
| FallVision raw (Harvard Dataverse 10.7910/DVN/75QPKK) | CC0 | tried as extra train, not in the shipped weights |

The shipped weights are a derivative of the UR Fall side views, so they are **non-commercial only**. Le2i needs a login. The Montreal multiple-camera page sits behind a bot wall. UP-Fall is hundreds of gigabytes with no unauthenticated bulk file. Zenodo record 3886586 is depth frames, not RGB. OmniFall synthetic video was not downloaded. Details are in `ml/validation/EXPERIMENTS.md`.

## Fit

```bash
ml/validation/.venv/bin/python ml/train/export_shipped.py
```

`ml/train/train_pose_model.py` calls the same exporter. This writes:

- `ml/models/pose_fall_model.json` (logistic weights and both thresholds)
- `android/app/src/main/java/kr/zerotwo/naksang/detect/PoseFallWeights.kt` (logistic weights and the gradient-boosted trees)
- `android/app/src/test/resources/pose_fall_reference.json`

Training rows are CAUCAFall, GMDCSA-24, and UR Fall side views. The logistic threshold and the tree threshold are each the highest value with zero ADL false alarms on those same clips, after the most falls. That choice sees UR side ADL. Leave-one-dataset-out does not. `PoseFallModelTest` checks that Kotlin reproduces both Python scores on the reference windows.

The phone also runs a fast-flat rule (`FastFlattenDetector`): the torso angle falls from at least 58° to at most 20° inside 1600 ms, with a peak flattening speed of 140°/s, then stays at or below 30° for 2000 ms. That gate was the zero-false-alarm choice on the same two commercial sets. It does not change the 9 second lying-still default or the 30 second countdown. The trees are plain numbers evaluated in Kotlin. There is no TensorFlow Lite runtime; fifty depth-2 trees are cheap at 5 fps.

Honest scores, including the runs that were not shipped, are in `ml/validation/EXPERIMENTS.md`.

```bash
ml/validation/.venv/bin/python ml/train/experiment.py --kind logreg --features v6 --rule-root ml/validation/out
ml/validation/.venv/bin/python ml/train/ensemble.py
```

## Phone

The suspicion is the existing rule, or this score held for two frames, or the lying-still path. Settings can turn the model and the lying-still path off. The 30-second countdown is unchanged.
