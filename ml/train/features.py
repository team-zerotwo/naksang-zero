"""Camera-tolerant pose-window features for the on-device fall model.

The same formulas are implemented in Kotlin `PoseFeatures`. A change here
has to be copied there, then `train_pose_model.py` regenerates the reference
samples the unit test checks.

Landmarks are MediaPipe Pose, normalized image coordinates, y growing down.
A window is the last 10 analysis frames (about 2 seconds at 5 fps). Features
are hip-centered where a length is involved, divided by torso length, and
mirror-invariant: left and right are averaged or differenced with abs, so the
front-camera mirror does not change the score. A raw drop in image y is not a
feature. Walking toward an upper camera moves the hips down the frame without
a fall, and that cue saturated the score above real falls. Drop is kept only
multiplied by flattening or by a shorter torso. That product is still wrong
for a ceiling camera; those clips are not used to fit the shipped weights.
"""

from __future__ import annotations

import math

WINDOW = 10
MIN_VALID = 6
MIN_VISIBILITY = 0.35
SMOOTH_ALPHA = 0.55
LANDMARKS = 33

NOSE = 0
LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_HIP = 23
RIGHT_HIP = 24
LEFT_KNEE = 25
RIGHT_KNEE = 26
LEFT_ANKLE = 27
RIGHT_ANKLE = 28

KEY_JOINTS = (
    NOSE,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE,
)

FEATURE_NAMES = (
    "angle_end",
    "angle_start",
    "angle_flattening",
    "angle_delta",
    "torso_ratio",
    "torso_min_ratio",
    "aspect_end",
    "aspect_start",
    "aspect_delta",
    "height_ratio",
    "pose_frac",
    "mean_vis",
    "leg_ext",
    "ankle_seen",
    "shoulder_w",
    "hip_w",
    "nose_above",
    "nose_seen",
    "upright_start",
    "flat_end",
    "flat_ever",
    "steep_end",
    "drop_x_flat",
    "drop_x_short",
    "peak_x_flat",
    "dropmax_x_upright",
    "knee_ext",
)

# Positive windows end near the dataset fall marker. The marker is not the
# same instant in every set (on the ground, or the start of a Falling label).
POSITIVE_PRE_MS = 600
POSITIVE_POST_MS = 1_200
# Clearly before the fall. The gap between this and POSITIVE_PRE_MS is skipped.
NEGATIVE_BEFORE_MS = 1_500


def _visible(point, min_visibility: float) -> bool:
    return point is not None and len(point) >= 3 and float(point[2]) >= min_visibility


def _mean_xy(frame, indexes, min_visibility: float):
    xs = []
    ys = []
    for index in indexes:
        point = frame[index] if index < len(frame) else None
        if not _visible(point, min_visibility):
            continue
        xs.append(float(point[0]))
        ys.append(float(point[1]))
    if not xs:
        return None
    return sum(xs) / len(xs), sum(ys) / len(ys)


def _mean_y(frame, indexes, min_visibility: float):
    ys = []
    for index in indexes:
        point = frame[index] if index < len(frame) else None
        if not _visible(point, min_visibility):
            continue
        ys.append(float(point[1]))
    if not ys:
        return None
    return sum(ys) / len(ys)


def smooth_window(frames, min_visibility: float = MIN_VISIBILITY, alpha: float = SMOOTH_ALPHA):
    """EMA inside this window only. A joint that was hidden on the previous
    frame starts again from the raw point, so a long gap does not smear."""
    tracked = {}
    seen = {index: False for index in KEY_JOINTS}
    smoothed = []
    for frame in frames:
        if frame is None:
            smoothed.append(None)
            seen = {index: False for index in KEY_JOINTS}
            continue
        out = [list(point) if point is not None else [0.0, 0.0, 0.0] for point in frame]
        if len(out) < LANDMARKS:
            out.extend([[0.0, 0.0, 0.0]] for _ in range(LANDMARKS - len(out)))
        for index in KEY_JOINTS:
            point = out[index]
            if not _visible(point, min_visibility):
                seen[index] = False
                continue
            x = float(point[0])
            y = float(point[1])
            if seen[index] and index in tracked:
                px, py = tracked[index]
                x = alpha * x + (1.0 - alpha) * px
                y = alpha * y + (1.0 - alpha) * py
            tracked[index] = (x, y)
            seen[index] = True
            point[0] = x
            point[1] = y
        smoothed.append(out)
    return smoothed


def frame_geometry(frame, min_visibility: float = MIN_VISIBILITY):
    if frame is None:
        return None
    shoulders = _mean_xy(frame, (LEFT_SHOULDER, RIGHT_SHOULDER), min_visibility)
    hips = _mean_xy(frame, (LEFT_HIP, RIGHT_HIP), min_visibility)
    if shoulders is None or hips is None:
        return None
    sx, sy = shoulders
    hx, hy = hips
    angle = math.degrees(math.atan2(abs(sy - hy), abs(sx - hx)))
    torso = math.hypot(sx - hx, sy - hy)
    xs = []
    ys = []
    vis = 0
    for index in KEY_JOINTS:
        point = frame[index] if index < len(frame) else None
        if not _visible(point, min_visibility):
            continue
        vis += 1
        xs.append(float(point[0]))
        ys.append(float(point[1]))
    if not xs:
        return None
    both_shoulders = _visible(frame[LEFT_SHOULDER], min_visibility) and _visible(frame[RIGHT_SHOULDER], min_visibility)
    both_hips = _visible(frame[LEFT_HIP], min_visibility) and _visible(frame[RIGHT_HIP], min_visibility)
    shoulder_w = abs(float(frame[LEFT_SHOULDER][0]) - float(frame[RIGHT_SHOULDER][0])) if both_shoulders else 0.0
    hip_w = abs(float(frame[LEFT_HIP][0]) - float(frame[RIGHT_HIP][0])) if both_hips else 0.0
    nose = frame[NOSE] if NOSE < len(frame) else None
    return {
        "angle": angle,
        "torso": torso,
        "cx": (sx + hx) / 2.0,
        "cy": (sy + hy) / 2.0,
        "hy": hy,
        "bw": max(xs) - min(xs),
        "bh": max(ys) - min(ys),
        "nose_y": float(nose[1]) if _visible(nose, min_visibility) else None,
        "knee_y": _mean_y(frame, (LEFT_KNEE, RIGHT_KNEE), min_visibility),
        "ankle_y": _mean_y(frame, (LEFT_ANKLE, RIGHT_ANKLE), min_visibility),
        "shoulder_w": shoulder_w,
        "hip_w": hip_w,
        "vis": vis / float(len(KEY_JOINTS)),
    }


def features_from_geoms(geoms, times_ms):
    """geoms and times_ms are oldest-to-newest and the same length.

    Returns a list of floats, or None when fewer than MIN_VALID frames have a torso.
    """
    if len(geoms) != len(times_ms):
        raise ValueError("geoms and times must match")
    valid = [(index, geom) for index, geom in enumerate(geoms) if geom is not None]
    if len(valid) < MIN_VALID:
        return None
    first_i, first = valid[0]
    last_i, last = valid[-1]
    angles = [geom["angle"] for _, geom in valid]
    cys = [geom["cy"] for _, geom in valid]
    torsos = [geom["torso"] for _, geom in valid]
    max_down_speed = 0.0
    peak_disp = 0.0
    steps = []
    for (i1, g1), (i2, g2) in zip(valid, valid[1:]):
        dt = max((times_ms[i2] - times_ms[i1]) / 1000.0, 0.05)
        dcy = (g2["cy"] - g1["cy"]) / dt
        if dcy > max_down_speed:
            max_down_speed = dcy
        disp = math.hypot(g2["cx"] - g1["cx"], g2["cy"] - g1["cy"])
        if disp > peak_disp:
            peak_disp = disp
        steps.append(disp)
    still = sum(steps[-3:]) / len(steps[-3:]) if steps else 0.0
    eps = 1e-3
    torso_start = max(first["torso"], eps)
    bh_start = max(first["bh"], eps)
    angle_min = min(angles)
    angle_end = last["angle"]
    angle_start = first["angle"]
    cy_drop = last["cy"] - first["cy"]
    cy_drop_max = max(cys) - first["cy"]
    torso_ratio = last["torso"] / torso_start
    torso_min_ratio = min(torsos) / torso_start
    aspect_end = last["bw"] / max(last["bh"], eps)
    aspect_start = first["bw"] / max(first["bh"], eps)
    height_ratio = last["bh"] / bh_start
    ankle = last["ankle_y"]
    knee = last["knee_y"]
    nose = last["nose_y"]
    torso_end = max(last["torso"], eps)
    leg_ext = ((ankle - last["hy"]) / torso_end) if ankle is not None else 0.0
    knee_ext = ((knee - last["hy"]) / torso_end) if knee is not None else 0.0
    nose_above = ((last["hy"] - nose) / torso_end) if nose is not None else 0.0
    upright_start = 1.0 if angle_start >= 58.0 else 0.0
    flat_end = 1.0 if angle_end <= 45.0 else 0.0
    flat_ever = 1.0 if angle_min <= 45.0 else 0.0
    steep_end = 1.0 if angle_end >= 65.0 else 0.0
    # Raw image translation (cy drop, horizontal travel, bbox height) is computed
    # above and then left out of the vector. It fires when a person walks toward
    # an upper camera. The products below keep a drop only when the torso also
    # flattens, shortens, or was upright at the start of the window.
    values = [
        angle_end / 90.0,
        angle_start / 90.0,
        (angle_start - angle_min) / 90.0,
        (angle_end - angle_start) / 90.0,
        torso_ratio,
        torso_min_ratio,
        aspect_end,
        aspect_start,
        aspect_end - aspect_start,
        height_ratio,
        len(valid) / float(WINDOW),
        sum(geom["vis"] for _, geom in valid) / len(valid),
        leg_ext,
        1.0 if ankle is not None else 0.0,
        last["shoulder_w"] / torso_end,
        last["hip_w"] / torso_end,
        nose_above,
        1.0 if nose is not None else 0.0,
        upright_start,
        flat_end,
        flat_ever,
        steep_end,
        cy_drop * (1.0 - angle_end / 90.0),
        cy_drop * (1.0 - torso_ratio),
        peak_disp * flat_ever,
        cy_drop_max * upright_start,
        knee_ext,
    ]
    if len(values) != len(FEATURE_NAMES):
        raise RuntimeError(f"feature count {len(values)} != {len(FEATURE_NAMES)}")
    return values


def pad_window(frames, times_ms):
    """Oldest-to-newest, padded on the left to WINDOW slots."""
    frames = list(frames)[-WINDOW:]
    times_ms = list(times_ms)[-WINDOW:]
    while len(frames) < WINDOW:
        frames.insert(0, None)
        times_ms.insert(0, (times_ms[0] - 200) if times_ms else 0)
    return frames, times_ms


def window_features(frames, times_ms, min_visibility: float = MIN_VISIBILITY):
    frames, times_ms = pad_window(frames, times_ms)
    smoothed = smooth_window(frames, min_visibility=min_visibility)
    geoms = [frame_geometry(frame, min_visibility=min_visibility) for frame in smoothed]
    return features_from_geoms(geoms, times_ms)


def stable_sigmoid(z: float) -> float:
    if z >= 0.0:
        ez = math.exp(-z)
        return 1.0 / (1.0 + ez)
    ez = math.exp(z)
    return ez / (1.0 + ez)


def standardize(features, mean, std, clip: float = 8.0):
    out = []
    for value, mu, sigma in zip(features, mean, std):
        scale = sigma if sigma >= 1e-8 else 1.0
        z = (value - mu) / scale
        if z > clip:
            z = clip
        elif z < -clip:
            z = -clip
        out.append(z)
    return out


def score_logreg(features, mean, std, coef, intercept) -> float:
    z = standardize(features, mean, std)
    logit = intercept
    for value, weight in zip(z, coef):
        logit += value * weight
    return stable_sigmoid(logit)


def score_mlp(features, mean, std, w1, b1, w2, b2) -> float:
    z = standardize(features, mean, std)
    hidden = []
    for col in range(len(b1)):
        acc = b1[col]
        for row, value in enumerate(z):
            acc += value * w1[row][col]
        hidden.append(acc if acc > 0.0 else 0.0)
    logit = b2
    for value, weight in zip(hidden, w2):
        logit += value * weight
    return stable_sigmoid(logit)


def score_model(features, model) -> float:
    kind = model["kind"]
    if kind == "logreg":
        return score_logreg(features, model["mean"], model["std"], model["coef"], model["intercept"])
    if kind == "mlp":
        return score_mlp(features, model["mean"], model["std"], model["w1"], model["b1"], model["w2"], model["b2"])
    raise ValueError(kind)


def clip_scores(frames, model):
    """Per-frame probabilities. None when the window is not ready.

    frames: list of {t, landmarks or None}. landmarks is a list of [x, y, v].
    """
    history_f = []
    history_t = []
    scores = []
    for frame in frames:
        history_f.append(frame["landmarks"])
        history_t.append(int(frame["t"]))
        feats = window_features(history_f, history_t)
        if feats is None:
            scores.append(None)
        else:
            scores.append(score_model(feats, model))
    return scores


def first_fire(scores, times, threshold: float, hold: int):
    streak = 0
    for score, t in zip(scores, times):
        if score is None or score < threshold:
            streak = 0
            continue
        streak += 1
        if streak >= hold:
            return int(t)
    return None
