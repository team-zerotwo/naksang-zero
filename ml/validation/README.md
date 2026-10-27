# Offline check of the shipped fall rules

This folder asks one question: if we run the shipped Kotlin rule in `FallDetector` on public fall videos, when does it fire?

The yes/no decision for the rule columns is the real `FallDetector`. Pose landmarks are estimated offline with the same lite model, IMAGE mode, `num_poses=1`, and 0.35 confidence as `PoseReader`. The 30-second countdown in `docs/api.md` is unchanged. The pose-window model trained beside that rule lives in `ml/train/`.

Every fall in these sets is acted. Counts in `RESULTS.md` are not on-phone accuracy.

## Datasets

Videos and landmark CSVs stay in `data/` and `out/` and are gitignored.

**CAUCAFall**, Mendeley Data, version 5. DOI [10.17632/7w7fccy7ky.5](https://doi.org/10.17632/7w7fccy7ky.5). CC BY 4.0. Ten subjects, five acted falls and five daily activities each. The camera is fixed in an upper corner. `download_caucafall.py` keeps the `.avi` files, the YOLO `.txt` labels, and `Dataset details.xlsx`, and skips the PNG frames. Fall onset is the first frame labeled `fall` (body on the ground).

```bash
python3 ml/validation/download_caucafall.py --dest ml/validation/data
```

**GMDCSA-24**, CC BY 4.0. Zenodo record [12921216](https://zenodo.org/records/12921216) (v2.0) is 1,107,543,412 bytes. Class start and end times were added in v2.1, record [13354453](https://zenodo.org/records/13354453), 1,107,545,615 bytes, same license and the same clips. The replay uses v2.1. A laptop webcam at 70 cm or 90 cm, 135 cm, or 64 cm. Onset is the start of the authored Falling interval. One Subject 4 cell has no fall times (`Falling (FW)` next to `Sitting[0 to 1.7]`); onset is the end of that sitting interval and the manifest says so.

**UR Fall Detection**, [fenix.ur.edu.pl/~mkepski/ds/uf.html](https://fenix.ur.edu.pl/~mkepski/ds/uf.html). Rechecked 2026-10-27, the page states CC BY-NC-SA 4.0. 70 sequences: 30 falls from a side camera and a ceiling camera, plus 40 daily activities from the side camera only. There is no ceiling daily-activity set. The page's mp4 previews are 640×240; each RGB PNG zip is about 57 MB, so 100 streams are about 5.7 GB. This run used every preview, scaled to 640×480, and did not download the RGB archives. Do not commit the videos, the landmark CSVs, or frames.

## Landmarks

Sample at 5 fps, fit inside 640×480 (UR previews are stretched to 640×480), MediaPipe Pose Landmarker lite, IMAGE, `num_poses=1`, confidence 0.35, CPU.

```bash
python3 -m venv ml/validation/.venv
ml/validation/.venv/bin/pip install -r ml/validation/requirements.txt
ml/validation/.venv/bin/python ml/validation/extract_landmarks.py \
  --data ml/validation/data --out ml/validation/out/landmarks
ml/validation/.venv/bin/python ml/validation/extract_gmdcsa.py
ml/validation/.venv/bin/python ml/validation/extract_urfall.py
```

The model is `pose_landmarker_lite.task`, SHA-256 `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a`. It is downloaded into `models/` and gitignored.

## Replay

```bash
cd android
./gradlew :app:replayCaucaFall \
  -PcsvDir=../ml/validation/out/landmarks \
  -PoutDir=../ml/validation/out/caucafall/replay
./gradlew :app:replayCaucaFall \
  -PcsvDir=../ml/validation/out/gmdcsa/landmarks \
  -PoutDir=../ml/validation/out/gmdcsa/replay
./gradlew :app:replayCaucaFall \
  -PcsvDir=../ml/validation/out/urfall/landmarks \
  -PoutDir=../ml/validation/out/urfall/replay
```

Then:

```bash
ml/validation/.venv/bin/python ml/validation/render_results.py
```

`RESULTS.md` and the CSVs under `results/` are the committed output. CAUCAFall and GMDCSA rows are copied there. UR Fall rows are not. `out/` is not committed.

`CaucaFallReplayTest` runs with the other unit tests and does not need the datasets. `CaucaFallDatasetReplayTest` skips unless `-PcsvDir` is set.
