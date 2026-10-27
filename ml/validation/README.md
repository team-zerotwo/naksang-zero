# Offline check of the shipped fall rules

This folder asks one question: if we run the **shipped** Kotlin rule in `FallDetector` on the public CAUCAFall videos, when does it fire?

It does not train a model, and it does not change app thresholds. A threshold sweep is reported beside the shipped settings. The app keeps the current defaults.

## Dataset

CAUCAFall, Mendeley Data, version 5.

Eraso, Muñoz, Muñoz, Pinto, Universidad del Cauca / Universidad de la Amazonia. DOI [10.17632/7w7fccy7ky.5](https://doi.org/10.17632/7w7fccy7ky.5). License CC BY 4.0.

Ten subjects, each with five acted falls (forward, backward, left, right, from sitting) and five daily activities (walk, hop, pick up an object, sit down, kneel). The camera is fixed in an upper corner of the room, about 23 fps, 1080×960. Each activity folder has an `.avi`, PNG frames, and a YOLO `.txt` label per frame. Class `fall` means the body is on the ground because of a fall, not the start of the descent.

The published zip is about 7.8 GB because of the PNG frames. `download_caucafall.py` uses the public Mendeley API and keeps the videos, the `.txt` labels, and `Dataset details.xlsx`. Those files stay in `data/` and are gitignored.

```bash
python3 ml/validation/download_caucafall.py --dest ml/validation/data
```

## Landmarks

`extract_landmarks.py` samples each video at 5 fps, scales it to fit inside 640×480 without changing the aspect ratio, and runs MediaPipe Pose Landmarker lite in IMAGE mode (`num_poses=1`, detection / presence / tracking confidence 0.35, CPU). That is the same model file and the same options as `PoseReader`. The Python tasks runtime is not the Android `PoseReader` class.

```bash
python3 -m venv ml/validation/.venv
ml/validation/.venv/bin/pip install -r ml/validation/requirements.txt
ml/validation/.venv/bin/python ml/validation/extract_landmarks.py \
  --data ml/validation/data \
  --out ml/validation/out/landmarks
```

The model is `pose_landmarker_lite.task`, SHA-256 `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`. It is downloaded into `models/` and gitignored. Landmark CSVs are gitignored too.

## Replay

The yes/no decision is the real `FallDetector` (sharp drop, upright start, near-horizontal torso, stillness, smoothing, person-lost grace). A unit test feeds it synthetic CSVs. The dataset run is a separate Gradle task so `testDebugUnitTest` stays a skip when no CSV directory is passed.

```bash
cd android
./gradlew :app:replayCaucaFall \
  -PcsvDir=../ml/validation/out/landmarks \
  -PoutDir=../ml/validation/out/replay
```

Then:

```bash
ml/validation/.venv/bin/python ml/validation/render_report.py
```

`RESULTS.md` and the small CSVs under `results/` are the committed output. `out/` is not.

`CaucaFallReplayTest` runs with the other unit tests and does not need the dataset. `CaucaFallDatasetReplayTest` skips unless `-PcsvDir` is set.
