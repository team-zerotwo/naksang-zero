# Fall-rule replay on three public datasets

The yes/no decision for the rule tables is the Kotlin `FallDetector` in the app, fed landmark CSVs. Pose landmarks were estimated offline with the same lite model, IMAGE mode, `num_poses=1`, and 0.35 confidence as `PoseReader`. The pose-window model is a separate section below. Frames are not uploaded. The 30-second countdown in `docs/api.md` is unchanged.

Every fall in these sets is acted. Young adults were asked to fall, often with a planned direction. An unwitnessed fall can look different. Counts below are not on-phone accuracy.

## What changed in the rule

The previous rule needed a near-horizontal shoulder–hip line after a large drop inside 800 ms. On the upper-corner camera that line stays steep when the person lies along the camera's line of sight, so those falls never started. The shipped rule keeps the flat-torso path and widens it (drop 0.10 inside 1600 ms, lie angle 45°). It adds a second path for that along-view fall: the torso stays at least 65° from horizontal, the center drops by 0.18 inside 800 ms, and the person then holds still within 0.035 of the frame. Walking drops the center too, but it does not hold still for the 2000 ms. Sitting and kneeling tip the torso below that steep gate or do it too slowly.

Torso-length ratio and full-body height ratio were measured and left off. Turning them on marked kneeling, stretching, and getting into bed as falls. Head-and-hip drop and torso-scaled drop did not add detections once the false-alarm constraint was met, so those gates are off too. After a detection the rule stays quiet until the person is upright and long again. An along-view landing also has to rise in the image, because that pose still looks like standing.

Stillness is still 2000 ms. That hold is inside the 30-second countdown the phone already shows; this change does not shorten or lengthen the countdown.

| Field | Before | Shipped |
| --- | --- | --- |
| minDrop | 0.18 | 0.10 |
| maxLieAngleDeg | 28 | 45 |
| stillMovement | 0.035 | 0.050 |
| dropWindowMs | 800 | 1600 |
| maxTorsoLenRatio | 0.00000 | 0.00000 |
| maxBodyHeightRatio | 0.00000 | 0.00000 |
| minDropInTorso | 0.00000 | 0.00000 |
| minLimbDrop | 0.00000 | 0.00000 |
| steepMinDrop | 0.00000 | 0.18000 |
| steepMinAngleDeg | 65.00000 | 65.00000 |

A ratio or a steep drop of 0 means that path is off. Lie-angle release sits 17° above `maxLieAngleDeg` (45° when the lie angle was 28°, 62° at the shipped 45°).

## Detection and false alarms

Before is the previous rule on the same landmarks. After is the shipped rule. Delay is milliseconds from the dataset's fall marker to the detection, so it includes the 2000 ms stillness hold. The marker is not the same instant in every set: CAUCAFall and UR use the on-ground label; GMDCSA uses the start of the authored Falling interval, which can be the loss of balance.

| Dataset | View | Falls before | Falls after | ADL false alarms before | ADL false alarms after | Median delay before (ms) | Median delay after (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CAUCAFall | all views | 4.0% (2/50) | 36.0% (18/50) | 0.0% (0/50) | 0.0% (0/50) | 2950 | 2800 |
| CAUCAFall | upper_corner | 4.0% (2/50) | 36.0% (18/50) | 0.0% (0/50) | 0.0% (0/50) | 2950 | 2800 |
| GMDCSA-24 | all views | 19.0% (15/79) | 34.2% (27/79) | 0.0% (0/81) | 0.0% (0/81) | 3500 | 3500 |
| GMDCSA-24 | front | — | — | 0.0% (0/4) | 0.0% (0/4) | — | — |
| GMDCSA-24 | height_135cm | 20.0% (9/45) | 40.0% (18/45) | 0.0% (0/45) | 0.0% (0/45) | 3600 | 3600 |
| GMDCSA-24 | height_64cm | 11.8% (2/17) | 23.5% (4/17) | 0.0% (0/20) | 0.0% (0/20) | 3400 | 3200 |
| GMDCSA-24 | height_70-90cm | 28.6% (4/14) | 21.4% (3/14) | 0.0% (0/9) | 0.0% (0/9) | 3550 | 3500 |
| GMDCSA-24 | side | 0.0% (0/3) | 66.7% (2/3) | 0.0% (0/3) | 0.0% (0/3) | — | 3950 |
| UR Fall | all views | 0.0% (0/60) | 0.0% (0/60) | 0.0% (0/40) | 0.0% (0/40) | — | — |
| UR Fall | ceiling | 0.0% (0/30) | 0.0% (0/30) | — | — | — | — |
| UR Fall | side | 0.0% (0/30) | 0.0% (0/30) | 0.0% (0/40) | 0.0% (0/40) | — | — |

## By fall direction and daily activity

Direction is the acted fall direction when the dataset marks one. An ADL false alarm is any non-fall clip the rule fires on. UR ADL clips are not labeled by activity;  clips whose depth features say the person is lying are `adl_lying` and the rest are `adl`.

### CAUCAFall

| Direction | Falls before | Falls after | Median min torso angle (°) | Median min torso-length ratio |
| --- | --- | --- | --- | --- |
| backward | 0.0% (0/10) | 30.0% (3/10) | 3.16 | 0.46 |
| forward | 0.0% (0/10) | 30.0% (3/10) | 24.19 | 0.58 |
| from_sitting | 10.0% (1/10) | 30.0% (3/10) | 3.12 | 0.61 |
| left | 10.0% (1/10) | 30.0% (3/10) | 18.01 | 0.54 |
| right | 0.0% (0/10) | 60.0% (6/10) | 6.00 | 0.52 |

| Daily activity | False alarms before | False alarms after |
| --- | --- | --- |
| hopping | 0.0% (0/10) | 0.0% (0/10) |
| kneeling | 0.0% (0/10) | 0.0% (0/10) |
| picking_up | 0.0% (0/10) | 0.0% (0/10) |
| sitting | 0.0% (0/10) | 0.0% (0/10) |
| walking | 0.0% (0/10) | 0.0% (0/10) |

### GMDCSA-24

| Direction | Falls before | Falls after | Median min torso angle (°) | Median min torso-length ratio |
| --- | --- | --- | --- | --- |
| backward | 14.8% (4/27) | 37.0% (10/27) | 9.22 | 0.52 |
| forward | 21.7% (5/23) | 34.8% (8/23) | 4.80 | 0.45 |
| sideways | 21.4% (6/28) | 32.1% (9/28) | 2.58 | 0.53 |

| Daily activity | False alarms before | False alarms after |
| --- | --- | --- |
| exercise | 0.0% (0/5) | 0.0% (0/5) |
| lying_down | 0.0% (0/14) | 0.0% (0/14) |
| other | 0.0% (0/5) | 0.0% (0/5) |
| picking_up | 0.0% (0/12) | 0.0% (0/12) |
| sitting | 0.0% (0/36) | 0.0% (0/36) |
| walking | 0.0% (0/9) | 0.0% (0/9) |

### UR Fall

| Daily activity | False alarms before | False alarms after |
| --- | --- | --- |
| adl | 0.0% (0/24) | 0.0% (0/24) |
| adl_lying | 0.0% (0/16) | 0.0% (0/16) |

## Camera-angle check on CAUCAFall

CAUCAFall's camera is fixed in an upper corner. Forward and backward falls lie more along that view; left and right falls lie across it. The angle below is the smallest raw shoulder–hip angle in the clip (0° is horizontal). The length ratio is the shortest smoothed-free torso length inside 1600 ms divided by the upright torso length. These two columns describe the pose. They are not a second detector.

Of 50 CAUCAFall falls, 5 never bring the raw torso within 45° of horizontal. 4 of those also shorten the torso to 0.70 of its upright length or less. The shipped rule misses 32 falls. Forward falls, the along-view direction, go from the previous rule's misses to 3/10 detected. On the ones it catches, the smoothed torso stays steep while the center drops. A short torso alone was not the gate: that ratio also fired on kneeling and stretching when it was turned on.

GMDCSA's 70–90 cm camera is the one bucket that does not improve (4/14 before, 3/14 after). The other heights and the upper-corner set gain, and no daily-activity clip becomes a false alarm.

## How the shipped values were chosen

The replay sweeps the same grid on every dataset. A candidate is kept only when it adds no ADL false alarm on any of the three sets. Among those, the shipped rule is the one in `FallConfig`. The search prefers more detected falls, then a higher minimum across the sets, and it does not trade a false alarm for recall.

Best zero-false-alarm setting in the sweep: `minDrop=0.10, maxLieAngleDeg=45, stillMovement=0.050, dropWindowMs=1600, maxTorsoLenRatio=0.00, maxBodyHeightRatio=0.00, minDropInTorso=0.00, minLimbDrop=0.00, steepMinDrop=0.18, steepMinAngleDeg=65` with 45 falls and 0 ADL false alarms across the sets that were joined.

Shipped setting: `minDrop=0.10, maxLieAngleDeg=45, stillMovement=0.050, dropWindowMs=1600, maxTorsoLenRatio=0.00, maxBodyHeightRatio=0.00, minDropInTorso=0.00, minLimbDrop=0.00, steepMinDrop=0.18, steepMinAngleDeg=65`.

The shipped setting is that zero-false-alarm choice.

## Datasets

CAUCAFall, Mendeley Data, V5, DOI 10.17632/7w7fccy7ky.5, CC BY 4.0. Eraso, Muñoz, Muñoz, Pinto. Upper-corner camera. Fall onset is the first frame whose YOLO label is `fall`, which the authors used for the body on the ground. Videos and labels stay in `data/` and are gitignored. The small sample frames already in `samples/` are from that CC BY set.

GMDCSA-24, CC BY 4.0. Zenodo record 12921216 (v2.0) is 1,107,543,412 bytes. Class start and end times were added in v2.1, record 13354453, 1,107,545,615 bytes, same license and the same clips, so the replay uses v2.1. Alam, Sufian, Dutta, Leo, Hameed, Data in Brief 57 (2024) 110892. A laptop webcam at 70 cm or 90 cm (subject 1), 135 cm (subjects 2 and 3), or 64 cm (subject 4). Descriptions mark front view, side view, or the subject facing the ceiling. That last phrase is the person's face, not an overhead camera.

UR Fall Detection, https://fenix.ur.edu.pl/~mkepski/ds/uf.html. Kwolek and Kępski, Computer Methods and Programs in Biomedicine 117 (2014). Rechecked on 2026-10-27, the page states CC BY-NC-SA 4.0 and asks for a citation. The videos, landmark CSVs, and frames stayed on the validation machine and are not in this repo. 70 sequences: 30 falls from camera 0 (parallel to the floor, a side view) and camera 1 (ceiling), plus 40 ADL sequences from camera 0 only. There is no ceiling ADL set, so a ceiling false-alarm rate was not measured. The page's mp4 previews are 640×240; the RGB PNG archives are 640×480 and about 50 MB each. Pose was run on the previews scaled to 640×480, which restores the Kinect aspect and is a compressed stand-in for the PNG sequence. Ceiling onset reuses camera 0's first lying frame. The two cameras are not strictly synchronized.

## Limits

Landmarks come from the Python MediaPipe tasks runtime, not from Android `PoseReader`. The model file, IMAGE mode, one pose, and 0.35 confidence match. Camera rotation, the front-camera mirror, and the FILL_CENTER crop do not run in this harness.

UR Fall stays at 0/60. Many of those clips end within a second of the on-ground label, so the 2000 ms stillness hold cannot finish inside the file. On the ceiling camera the body does not get shorter or horizontal; image y is not gravity, and a person walking under that camera would also move a long way in the frame. There is no ceiling ADL set, so a trigger for that view was not added. Full RGB PNGs are about 57 MB each (about 5.7 GB for 100 streams). Pose used the 640×240 previews scaled to 640×480.

A detection waits 2000 ms after the down pose. Clips that end sooner cannot confirm. GMDCSA delays are longer when the Falling label starts at the loss of balance, because the clock includes the descent plus the hold. CAUCAFall and UR delays start at the on-ground label, so a median near 2000 ms means the hold itself.

Not checked here: a physical phone, heat at 640×480, or whether a resident who stays on the floor matches these acted landings. Push-ups, sleeping, and lying down as a daily activity are in GMDCSA or UR and are counted as false alarms when the rule fires. They are not falls, and a false alarm was not accepted in exchange for more detections.

## Pose model and lying-still (v6)

The phone now alerts when the shipped rule fires, or a logistic regression on a 10-frame pose window stays above 0.99 for two frames, or the person is lying on the floor and almost still. The countdown is still 30 seconds. Settings can turn the model and the lying-still path off. The model weights are `PoseFallWeights`. Retrain with `ml/train/train_pose_model.py`.

The window is hip-centered and divided by torso length. Left and right are folded together, so a mirrored front camera does not change the vector. A raw move down the image is not a feature. Walking toward the upper-corner camera does that without falling, and it used to score above real falls. A drop counts only when the torso also flattens or shortens. Ceiling clips are scored and are not training rows, because image y is not gravity there.

The shipped threshold is the highest one with zero ADL false alarms on the clips the model was trained on, after taking the most falls. That operating point is in-distribution. Leave-one-dataset-out, below, picks a fresh threshold on the held-out set with the same zero-false-alarm rule. Those two tables are not the same number.

Lying still uses the shipped lie angle (45°) plus a low center or a drop from a recent upright pose. A fast descent (torso was at least 72° and reached lying within 1200 ms, after an upright or sitting pose in the last 20 s) must then hold within 0.05 of the frame for 9 seconds. A slow lie-down, or a person who was already lying when first seen, waits 45 seconds. Getting up rearms the detector. Shifting past the stillness gate restarts the clock and keeps the fast or slow choice. The 9 second path can still treat a fast flop onto a bed as a fall if the person then lies still. The 45 second path is the conservative one for a deliberate lie-down. Both durations are the defaults; the short one is a setting.

### Rules, model, and lying-still on the same clips

Before is the PR #7 rule. After is rule or model or lying-still, using the shipped weights and the 0.99 threshold. The model was fit on these three datasets, so the model column is in-distribution. Lying-still finishes inside none of the files. Delay is milliseconds from the dataset fall marker. A negative delay means the score fired during the descent, before the on-ground label.

| Dataset | View | Falls before | Falls after | ADL false alarms before | ADL false alarms after | Median delay before (ms) | Median delay after (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| CAUCAFall | all views | 36.0% (18/50) | 38.0% (19/50) | 0.0% (0/50) | 0.0% (0/50) | 2800 | 2750 |
| GMDCSA-24 | all views | 34.2% (27/79) | 54.4% (43/79) | 0.0% (0/81) | 0.0% (0/81) | 3500 | 1200 |
| GMDCSA-24 | height_135cm | 40.0% (18/45) | 60.0% (27/45) | 0.0% (0/45) | 0.0% (0/45) | 3600 | 1200 |
| GMDCSA-24 | height_64cm | 23.5% (4/17) | 29.4% (5/17) | 0.0% (0/20) | 0.0% (0/20) | 3200 | 800 |
| GMDCSA-24 | height_70-90cm | 21.4% (3/14) | 64.3% (9/14) | 0.0% (0/9) | 0.0% (0/9) | 3500 | 1200 |
| GMDCSA-24 | side | 66.7% (2/3) | 66.7% (2/3) | 0.0% (0/3) | 0.0% (0/3) | 3950 | 2550 |
| UR Fall | all views | 0.0% (0/60) | 13.3% (8/60) | 0.0% (0/40) | 0.0% (0/40) | — | 584 |
| UR Fall | side | 0.0% (0/30) | 23.3% (7/30) | 0.0% (0/40) | 0.0% (0/40) | — | 600 |
| UR Fall | ceiling | 0.0% (0/30) | 3.3% (1/30) | — | — | — | -1300 |

Across the three sets that is 45/189 falls before and 70/189 after, with 0 ADL false alarms both times. The model alone, at the same threshold, is 1/50, 37/79, and 8/60. On GMDCSA it adds 16 falls the rule misses and overlaps 21. On CAUCAFall it adds the one fall the rule misses. On UR Fall the rule still catches nothing, and all 8 are the model. The per-clip file is `per_video_signals.csv` from the Kotlin replay. UR rows stay out of git.

### Leave-one-dataset-out (model only)

Train on two datasets, test on the third, and choose the threshold so the held-out ADL false alarms are 0. This is the generalization number. It is not combined with the rule, and it is not the threshold shipped on the phone. The phone uses one model trained on all three sets and the single threshold 0.99.

| Held-out dataset | Threshold | Falls | ADL false alarms | Median delay (ms) |
| --- | --- | --- | --- | --- |
| CAUCAFall | 0.97 | 10.0% (5/50) | 0.0% (0/50) | 250 |
| GMDCSA-24 | 0.999 | 45.6% (36/79) | 0.0% (0/81) | 1400 |
| UR Fall | 0.98 | 16.7% (10/60) | 0.0% (0/40) | 584 |

A 12-unit MLP was trained the same way and kept fewer held-out falls (45/189 against 51/189), so it was not shipped. Full rows are in `results/model_lodo.md`.

### In-distribution model (labeled separately)

A model trained and scored on the same dataset, with its own zero-false-alarm threshold, is optimistic. CAUCAFall's own threshold has to sit at 0.9999 and then catches 0/50. GMDCSA catches 38/79 at 0.99. UR Fall catches 21/60 at 0.94, median delay 67 ms. The shared model at 0.99 is the phone: 1/50, 37/79, 8/60, all with 0 ADL false alarms. CAUCAFall walking and hopping still score close to falls, which is why that set barely moves.

### Lying still inside these files

The timer does not finish in any fall or ADL clip. The files end first. Of the falls the combined detector misses, 16 CAUCAFall, 25 GMDCSA, and 19 UR clips had already entered the fast floor-lying pose. The longest still stretch in those missed clips is 6000 ms, 5000 ms, and 1400 ms. None reach the 9 second default, so a longer default was not shortened to harvest extra dataset hits.

ADL clips do enter a flat pose. The longest still-lying stretch is 1600 ms on CAUCAFall (a pick-up), 5200 ms on GMDCSA (lying down; three lying-down clips hold at least 4000 ms), and 1000 ms on UR Fall. Kneeling, sitting, hopping, and exercise stay under 2400 ms. None reach 9 seconds, which is why the ADL false-alarm count above is 0. A person who flops onto a bed and stays still for 9 seconds can still alert. A slow lie-down waits 45 seconds. Someone already lying when the camera starts also waits 45 seconds.

### Limits of this section

Every fall is acted. Nothing here was run on the spare phone. Landmarks come from the Python MediaPipe runtime, not from Android `PoseReader`. The combined table trains on the clips it scores. The leave-one-dataset-out table is the one that does not. UR ceiling is 1/30, and there is no ceiling ADL set. Eight CAUCAFall hop-frame label files failed to download; hop is a daily activity, and fall onsets do not use those files.

UR Fall is CC BY-NC-SA 4.0. The videos and landmark CSVs are not in the repo. The v6 weights were fit with the UR side-view sequences included, so that older weight file was a derivative of that set. The weights shipped after that experiment are fit only on CAUCAFall and GMDCSA-24. See `ml/validation/EXPERIMENTS.md`.
