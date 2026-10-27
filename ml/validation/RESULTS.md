# Fall-rule replay on three public datasets

The yes/no decision is the Kotlin `FallDetector` in the app, fed landmark CSVs. Pose landmarks were estimated offline with the same lite model, IMAGE mode, `num_poses=1`, and 0.35 confidence as `PoseReader`. Nothing here trains a model, and frames are not uploaded. The 30-second countdown in `docs/api.md` is unchanged.

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
