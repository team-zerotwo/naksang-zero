#!/usr/bin/env python3
"""Honest scores for pose fall detectors.

UR Fall is never a training row. Thresholds and rule gates are chosen on the
training clips only: zero ADL false alarms, then the most falls, then the
stricter gate. If a test set still has an ADL false alarm, the gate is only
tightened until that count is zero. That lower recall is the number reported.

(a) trains on CAUCAFall + GMDCSA-24 and scores those same clips (optimistic).
(b) holds each of those out in turn, and scores UR Fall with the model trained
on both. The decision target is the sum of (b) fall detections over 189.
Pass --rule-root to OR the frozen Kotlin rule (per_video_signals.csv rule_at_ms).
The 9 second lying-still default is not part of this search.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "train"))

from features import FEATURE_NAMES, first_fire, score_model, window_features  # noqa: E402

IMAGE_FLOATS = 33 * 3
WORLD_FLOATS = 33 * 3
NZ_FLOATS = 33
PREFIX = 3
WORLD_AT = PREFIX + IMAGE_FLOATS
NZ_AT = WORLD_AT + WORLD_FLOATS

LS, RS = 11, 12
LH, RH = 23, 24
LK, RK = 25, 26
LA, RA = 27, 28
MIN_VIS = 0.35


def read_landmark_csv(path: Path) -> list[dict]:
    frames = []
    with path.open(newline="") as handle:
        reader = csv.reader(handle)
        header = next(reader)
        if not header or header[0] != "timestamp_ms":
            raise RuntimeError(f"unexpected landmark header in {path}")
        has_world = len(header) >= NZ_AT
        has_nz = len(header) >= NZ_AT + NZ_FLOATS
        for row in reader:
            if not row or not row[0]:
                continue
            found = row[2] == "1"
            image = world = nz = None
            if found and len(row) >= WORLD_AT:
                image = []
                for index in range(33):
                    base = PREFIX + index * 3
                    image.append((float(row[base]), float(row[base + 1]), float(row[base + 2])))
                if has_world and len(row) >= NZ_AT:
                    world = []
                    for index in range(33):
                        base = WORLD_AT + index * 3
                        world.append((float(row[base]), float(row[base + 1]), float(row[base + 2])))
                if has_nz and len(row) >= NZ_AT + NZ_FLOATS:
                    nz = [float(row[NZ_AT + index]) for index in range(33)]
            frames.append({"t": int(float(row[0])), "image": image, "world": world, "nz": nz})
    return frames


def load_clips(name: str, out_dir: Path) -> list[dict]:
    manifest = out_dir / "manifest.csv"
    if not manifest.is_file():
        print(f"skip {name}: no {manifest}", file=sys.stderr)
        return []
    clips = []
    with manifest.open(newline="") as handle:
        for row in csv.DictReader(handle):
            csv_path = out_dir / row["csv_file"]
            if not csv_path.is_file():
                print(f"missing {csv_path}", file=sys.stderr)
                continue
            onset = row.get("onset_ms") or ""
            camera = (row.get("camera") or "").strip()
            view = (row.get("view") or "").strip()
            clips.append(
                {
                    "dataset": name,
                    "video_id": row["video_id"],
                    "subject": row.get("subject") or "",
                    "kind": row.get("kind") or "",
                    "activity_type": row.get("activity_type") or "",
                    "view": view,
                    "camera": camera,
                    "ceiling": camera == "cam1" or view == "ceiling",
                    "onset_ms": int(float(onset)) if onset else None,
                    "frames": read_landmark_csv(csv_path),
                }
            )
    return clips


def _mean_pts(points, visible, indexes, min_vis: float):
    acc = []
    for index in indexes:
        if visible[index] < min_vis:
            continue
        point = points[index]
        acc.append(point)
    if not acc:
        return None
    return (
        sum(p[0] for p in acc) / len(acc),
        sum(p[1] for p in acc) / len(acc),
        sum(p[2] for p in acc) / len(acc),
    )


def _unit(vec):
    norm = math.sqrt(vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2])
    if norm < 1e-6:
        return None
    return (vec[0] / norm, vec[1] / norm, vec[2] / norm, norm)


def _angle(a, b) -> float:
    dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    dot = max(-1.0, min(1.0, dot))
    return math.degrees(math.acos(dot))


def _torso_from_image(image, nz):
    """Camera-space torso. z is the normalized landmark depth, hips at 0."""
    if image is None or nz is None:
        return None
    points = [(image[i][0], image[i][1], nz[i]) for i in range(33)]
    visible = [image[i][2] for i in range(33)]
    shoulders = _mean_pts(points, visible, (LS, RS), MIN_VIS)
    hips = _mean_pts(points, visible, (LH, RH), MIN_VIS)
    if shoulders is None or hips is None:
        return None
    return _unit((shoulders[0] - hips[0], shoulders[1] - hips[1], shoulders[2] - hips[2]))


def _torso_from_world(world, image):
    if world is None or image is None:
        return None
    visible = [image[i][2] for i in range(33)]
    shoulders = _mean_pts(world, visible, (LS, RS), MIN_VIS)
    hips = _mean_pts(world, visible, (LH, RH), MIN_VIS)
    if shoulders is None or hips is None:
        return None
    direction = _unit((shoulders[0] - hips[0], shoulders[1] - hips[1], shoulders[2] - hips[2]))
    if direction is None:
        return None
    # A real torso is about half a meter. Tiny or huge lengths are a bad solve.
    if direction[3] < 0.15 or direction[3] > 1.2:
        return None
    return direction


def _knee_flexion(world, image) -> float | None:
    """0 is a straight leg, larger is more bent. Mean of the visible legs."""
    if world is None or image is None:
        return None
    bends = []
    for hip_i, knee_i, ankle_i in ((LH, LK, LA), (RH, RK, RA)):
        if min(image[hip_i][2], image[knee_i][2], image[ankle_i][2]) < MIN_VIS:
            continue
        hip = world[hip_i]
        knee = world[knee_i]
        ankle = world[ankle_i]
        thigh = _unit((hip[0] - knee[0], hip[1] - knee[1], hip[2] - knee[2]))
        shin = _unit((ankle[0] - knee[0], ankle[1] - knee[1], ankle[2] - knee[2]))
        if thigh is None or shin is None:
            continue
        interior = _angle(thigh, shin)
        bends.append(180.0 - interior)
    if not bends:
        return None
    return sum(bends) / len(bends)


def _image_geom(image):
    if image is None:
        return None
    shoulders = []
    hips = []
    for index, bucket in ((LS, shoulders), (RS, shoulders), (LH, hips), (RH, hips)):
        point = image[index]
        if point[2] < MIN_VIS:
            continue
        bucket.append(point)
    if not shoulders or not hips:
        return None
    sx = sum(p[0] for p in shoulders) / len(shoulders)
    sy = sum(p[1] for p in shoulders) / len(shoulders)
    hx = sum(p[0] for p in hips) / len(hips)
    hy = sum(p[1] for p in hips) / len(hips)
    angle = math.degrees(math.atan2(abs(sy - hy), abs(sx - hx)))
    torso = math.hypot(sx - hx, sy - hy)
    both_s = image[LS][2] >= MIN_VIS and image[RS][2] >= MIN_VIS
    shoulder_w = abs(image[LS][0] - image[RS][0]) if both_s else 0.0
    ankles_x = []
    for index in (LA, RA):
        if image[index][2] >= MIN_VIS:
            ankles_x.append(image[index][0])
    return {
        "angle": angle,
        "torso": torso,
        "cx": (sx + hx) / 2.0,
        "cy": (sy + hy) / 2.0,
        "shoulder_w": shoulder_w,
        "ankle_span": (max(ankles_x) - min(ankles_x)) if len(ankles_x) == 2 else None,
    }


def prepare(clip: dict, source: str) -> dict:
    """Per-frame angle away from the standing torso axis, in degrees."""
    times = []
    directions = []
    knees = []
    images = []
    for frame in clip["frames"]:
        times.append(frame["t"])
        image = frame["image"]
        images.append(_image_geom(image))
        knees.append(_knee_flexion(frame["world"], image))
        if source == "world":
            torso = _torso_from_world(frame["world"], image)
        elif source == "cam":
            torso = _torso_from_image(image, frame["nz"])
        else:
            torso = None
        directions.append(None if torso is None else torso[:3])
    angles = [None] * len(times)
    speeds = [None] * len(times)
    if source == "image":
        prev_angle = None
        prev_t = None
        for index, geom in enumerate(images):
            if geom is None:
                prev_angle = None
                prev_t = None
                continue
            # 0 when the shoulder–hip line is vertical in the image, 90 when it is flat.
            angle = 90.0 - geom["angle"]
            angles[index] = angle
            if prev_angle is not None and prev_t is not None:
                dt = max((times[index] - prev_t) / 1000.0, 0.05)
                speeds[index] = (angle - prev_angle) / dt
            else:
                speeds[index] = 0.0
            prev_angle = angle
            prev_t = times[index]
        prepared = dict(clip)
        prepared["sig"] = {"t": times, "angle": angles, "speed": speeds, "knee": knees, "image": images}
        return prepared
    up = None
    samples = 0
    prev_angle = None
    prev_t = None
    for index, direction in enumerate(directions):
        if direction is None:
            prev_angle = None
            prev_t = None
            continue
        if up is None:
            up = direction
            samples = 1
            angles[index] = 0.0
            speeds[index] = 0.0
            prev_angle = 0.0
            prev_t = times[index]
            continue
        align = _angle(direction, up)
        dt = 0.2
        if prev_t is not None:
            dt = max((times[index] - prev_t) / 1000.0, 0.05)
        inst = 0.0 if prev_angle is None else (align - prev_angle) / dt
        if samples < 8 and align < 30.0 and inst < 50.0:
            up = (
                up[0] * samples + direction[0],
                up[1] * samples + direction[1],
                up[2] * samples + direction[2],
            )
            up = _unit(up)[:3]
            samples += 1
            align = _angle(direction, up)
            inst = 0.0
        elif samples >= 8 and align < 18.0 and inst < 30.0:
            up = (
                0.97 * up[0] + 0.03 * direction[0],
                0.97 * up[1] + 0.03 * direction[1],
                0.97 * up[2] + 0.03 * direction[2],
            )
            up = _unit(up)[:3]
        angles[index] = align
        speeds[index] = inst
        prev_angle = align
        prev_t = times[index]
    clip = dict(clip)
    clip["sig"] = {"t": times, "angle": angles, "speed": speeds, "knee": knees, "image": images}
    return clip


def _fire_angles(sig: dict, lie: float, upright: float, window_ms: int, min_speed: float, confirm_ms: int):
    times = sig["t"]
    angles = sig["angle"]
    speeds = sig["speed"]
    confirm_since = None
    for index, angle in enumerate(angles):
        if angle is None:
            confirm_since = None
            continue
        if confirm_since is None:
            if angle < lie:
                continue
            opened = None
            peak = 0.0
            for prev in range(index - 1, -1, -1):
                if times[index] - times[prev] > window_ms:
                    break
                if angles[prev] is None:
                    continue
                if speeds[prev] is not None and speeds[prev] > peak:
                    peak = speeds[prev]
                if angles[prev] <= upright:
                    opened = prev
                    break
            if speeds[index] is not None and speeds[index] > peak:
                peak = speeds[index]
            if opened is None or peak < min_speed:
                continue
            confirm_since = times[index]
        if angle < lie - 10.0:
            confirm_since = None
            continue
        if times[index] - confirm_since >= confirm_ms:
            return times[index]
    return None


def gravity_grid():
    for lie in (50.0, 60.0, 70.0):
        for window_ms in (800, 1200, 1600):
            for min_speed in (30.0, 50.0, 70.0, 100.0, 140.0):
                for confirm_ms in (600, 1000, 1500, 2000):
                    yield {
                        "lie": lie,
                        "upright": 32.0,
                        "window_ms": window_ms,
                        "min_speed": min_speed,
                        "confirm_ms": confirm_ms,
                    }


def apply_gravity(clips, params):
    fired = {}
    for clip in clips:
        fired[clip["video_id"]] = _fire_angles(
            clip["sig"],
            params["lie"],
            params["upright"],
            params["window_ms"],
            params["min_speed"],
            params["confirm_ms"],
        )
    return fired


def choose_gravity(clips):
    """Strictest gate among those with the most training falls and zero ADL hits."""
    best = None
    best_fired = None
    for params in gravity_grid():
        fired = apply_gravity(clips, params)
        stats = summarize(clips, fired)
        if stats["adl_false"] != 0:
            continue
        rank = (
            stats["falls"],
            params["confirm_ms"],
            params["min_speed"],
            params["lie"],
            -params["window_ms"],
        )
        if best is None or rank > best[0]:
            best = (rank, params, stats)
            best_fired = fired
    if best is None:
        return None, None, None
    return best[1], best[2], best_fired


def tighten_gravity(train_params, test_clips):
    """Walk strictly upward from the training gate. Keep the first point with zero test ADL false alarms.

    The test labels are not used to pick a higher recall. They only reject a
    gate that still false-alarms.
    """
    speeds = [s for s in (30.0, 50.0, 70.0, 100.0, 140.0, 180.0) if s + 1e-6 >= train_params["min_speed"]]
    confirms = [c for c in (600, 1000, 1500, 2000, 2500) if c >= train_params["confirm_ms"]]
    lies = [v for v in (50.0, 60.0, 70.0, 80.0) if v + 1e-6 >= train_params["lie"]]
    candidates = []
    for lie in lies:
        for min_speed in speeds:
            for confirm_ms in confirms:
                params = dict(train_params)
                params.update({"lie": lie, "min_speed": min_speed, "confirm_ms": confirm_ms})
                steps = lies.index(lie) + speeds.index(min_speed) + confirms.index(confirm_ms)
                candidates.append((steps, confirm_ms, min_speed, lie, params))
    candidates.sort()
    for _steps, _confirm, _speed, _lie, params in candidates:
        fired = apply_gravity(test_clips, params)
        stats = summarize(test_clips, fired)
        if stats["adl_false"] == 0:
            return (None, params, stats, fired)
    return None


def summarize(clips, fired) -> dict:
    fall_hit = fall_n = adl_hit = adl_n = 0
    delays = []
    by_view = {}
    for clip in clips:
        at = fired.get(clip["video_id"])
        hit = at is not None
        view = clip["view"] or "all"
        bucket = by_view.setdefault(view, [0, 0, 0, 0])
        if clip["kind"] == "fall":
            fall_n += 1
            bucket[1] += 1
            if hit:
                fall_hit += 1
                bucket[0] += 1
                if clip["onset_ms"] is not None:
                    delays.append(at - clip["onset_ms"])
        else:
            adl_n += 1
            bucket[3] += 1
            if hit:
                adl_hit += 1
                bucket[2] += 1
    delays.sort()
    median = None
    if delays:
        mid = len(delays) // 2
        median = delays[mid] if len(delays) % 2 else int(round((delays[mid - 1] + delays[mid]) / 2))
    return {
        "falls": fall_hit,
        "fall_total": fall_n,
        "adl_false": adl_hit,
        "adl_total": adl_n,
        "median_delay_ms": median,
        "by_view": by_view,
    }


def adl_angle_hold(clips, lie: float) -> dict:
    """Longest stretch an ADL clip stays rotated away from its standing axis."""
    longest = 0
    longest_id = ""
    by_type = {}
    for clip in clips:
        if clip["kind"] == "fall":
            continue
        times = clip["sig"]["t"]
        angles = clip["sig"]["angle"]
        start = None
        best = 0
        for time, angle in zip(times, angles):
            if angle is not None and angle >= lie:
                if start is None:
                    start = time
                best = max(best, time - start)
            else:
                start = None
        activity = clip["activity_type"] or "adl"
        prev = by_type.get(activity, 0)
        by_type[activity] = max(prev, best)
        if best >= longest:
            longest = best
            longest_id = clip["video_id"]
    return {"max_ms": longest, "video_id": longest_id, "by_type": by_type}


def load_rule_times(root: Path) -> dict[str, int]:
    found = {}
    if root is None:
        return found
    for path in root.glob("*/replay/per_video_signals.csv"):
        with path.open(newline="") as handle:
            for row in csv.DictReader(handle):
                raw = row.get("rule_at_ms") or ""
                if raw:
                    found[row["video_id"]] = int(float(raw))
    return found


def combine(model_fired, rule_times, clips):
    out = {}
    for clip in clips:
        times = []
        model_at = model_fired.get(clip["video_id"])
        rule_at = rule_times.get(clip["video_id"])
        if model_at is not None:
            times.append(model_at)
        if rule_at is not None:
            times.append(rule_at)
        out[clip["video_id"]] = min(times) if times else None
    return out


def fmt(stats) -> str:
    if stats["fall_total"] <= 0:
        return "—"
    return (
        f"{stats['falls']}/{stats['fall_total']} "
        f"adl {stats['adl_false']}/{stats['adl_total']} "
        f"delay {stats['median_delay_ms']}"
    )


def orient_report(clips) -> str:
    """How far the torso axis actually moves, so a bad 3D frame is obvious."""
    lines = []
    for source_name in ("world", "cam", "image"):
        prepared = [prepare(clip, source_name) for clip in clips]
        fall_peak = []
        adl_peak = []
        valid = 0
        total = 0
        for clip in prepared:
            peaks = [a for a in clip["sig"]["angle"] if a is not None]
            total += len(clip["sig"]["angle"])
            valid += len(peaks)
            if not peaks:
                continue
            peak = max(peaks)
            if clip["kind"] == "fall":
                fall_peak.append(peak)
            else:
                adl_peak.append(peak)
        def med(values):
            if not values:
                return None
            values = sorted(values)
            return values[len(values) // 2]
        lines.append(
            f"{source_name}: valid {valid}/{total} "
            f"fall median peak {med(fall_peak)} adl median peak {med(adl_peak)} "
            f"falls with peak>=50 {sum(1 for v in fall_peak if v >= 50)}/{len(fall_peak)} "
            f"adl with peak>=50 {sum(1 for v in adl_peak if v >= 50)}/{len(adl_peak)}"
        )
    return "\n".join(lines)


RICH_FEATURES = (
    "up_end",
    "up_start",
    "up_delta",
    "up_max",
    "up_speed_max",
    "up_settled",
    "knee_end",
    "knee_delta",
    "img_angle_end",
    "img_angle_delta",
    "torso_ratio",
    "width_ratio_delta",
    "cy_drop_x_up",
    "step_std",
    "valid_frac",
    "rise_s",
)
RICH_WINDOW = 15
POSITIVE_PRE_MS = 800
POSITIVE_POST_MS = 1_500
NEGATIVE_BEFORE_MS = 1_500


def rich_vector(sig: dict, end: int, window: int = RICH_WINDOW):
    start = max(0, end - window + 1)
    angles = []
    speeds = []
    knees = []
    images = []
    times = sig["t"][start : end + 1]
    for index in range(start, end + 1):
        angle = sig["angle"][index]
        if angle is None:
            continue
        angles.append(angle)
        speed = sig["speed"][index]
        speeds.append(0.0 if speed is None else speed)
        knee = sig["knee"][index]
        if knee is not None:
            knees.append(knee)
        image = sig["image"][index]
        if image is not None:
            images.append(image)
    if len(angles) < 6:
        return None
    img_angles = [geom["angle"] for geom in images]
    spans = [geom["ankle_span"] for geom in images if geom["ankle_span"] is not None]
    rise = 0.0
    opened = None
    for index in range(start, end + 1):
        angle = sig["angle"][index]
        if angle is None:
            continue
        if angle <= 32.0:
            opened = sig["t"][index]
        elif opened is not None and angle >= 50.0:
            rise = max(0.0, (sig["t"][index] - opened) / 1000.0)
            break
    torso_ratio = 1.0
    width_delta = 0.0
    cy_drop = 0.0
    if len(images) >= 2 and images[0]["torso"] > 1e-3:
        torso_ratio = images[-1]["torso"] / images[0]["torso"]
        w0 = images[0]["shoulder_w"]
        w1 = images[-1]["shoulder_w"]
        if w0 > 1e-3 and w1 > 1e-3:
            width_delta = (images[-1]["torso"] / w1) - (images[0]["torso"] / w0)
        cy_drop = images[-1]["cy"] - images[0]["cy"]
    step = 0.0
    if len(spans) >= 3:
        mean = sum(spans) / len(spans)
        step = math.sqrt(sum((value - mean) ** 2 for value in spans) / len(spans))
    knee_end = knees[-1] if knees else 0.0
    knee_start = knees[0] if knees else 0.0
    settled = angles[-3:] if len(angles) >= 3 else angles
    return [
        angles[-1] / 90.0,
        angles[0] / 90.0,
        (angles[-1] - angles[0]) / 90.0,
        max(angles) / 90.0,
        max(speeds) / 180.0,
        (sum(settled) / len(settled)) / 90.0,
        knee_end / 180.0,
        (knee_end - knee_start) / 180.0,
        (img_angles[-1] / 90.0) if img_angles else 0.0,
        ((img_angles[-1] - img_angles[0]) / 90.0) if len(img_angles) >= 2 else 0.0,
        torso_ratio,
        width_delta,
        cy_drop * (angles[-1] / 90.0),
        step,
        len(angles) / float(window),
        rise,
    ]


def rich_label(clip, end_t: int):
    if clip["ceiling"]:
        return None
    if clip["kind"] != "fall":
        return 0
    onset = clip["onset_ms"]
    if onset is None:
        return None
    if onset - POSITIVE_PRE_MS <= end_t <= onset + POSITIVE_POST_MS:
        return 1
    if end_t < onset - NEGATIVE_BEFORE_MS:
        return 0
    return None


def rich_matrix(clips):
    xs = []
    ys = []
    for clip in clips:
        if clip["ceiling"] or clip["dataset"] == "urfall":
            continue
        positives = []
        negatives = []
        sig = clip["sig"]
        for end, end_t in enumerate(sig["t"]):
            label = rich_label(clip, end_t)
            if label is None:
                continue
            vec = rich_vector(sig, end)
            if vec is None:
                continue
            (positives if label == 1 else negatives).append(vec)
        if len(negatives) > 12:
            pick = np.linspace(0, len(negatives) - 1, 12).astype(int)
            negatives = [negatives[i] for i in pick]
        xs.extend(positives)
        ys.extend([1] * len(positives))
        xs.extend(negatives)
        ys.extend([0] * len(negatives))
    if not xs:
        raise RuntimeError("no rich training rows")
    return np.asarray(xs, dtype=np.float64), np.asarray(ys, dtype=np.float64)


def fit_gbdt(x, y):
    from sklearn.ensemble import GradientBoostingClassifier

    model = GradientBoostingClassifier(
        n_estimators=40,
        max_depth=2,
        learning_rate=0.08,
        subsample=0.9,
        min_samples_leaf=8,
        random_state=7,
    )
    pos = max(float(y.mean()), 1e-3)
    weight = np.where(y > 0.5, 0.5 / pos, 0.5 / (1.0 - pos))
    model.fit(x, y, sample_weight=weight)
    return model


def score_rich(clips, model, hold: int = 2):
    cache = {}
    for clip in clips:
        scores = []
        sig = clip["sig"]
        streak_scores = []
        for end in range(len(sig["t"])):
            vec = rich_vector(sig, end)
            if vec is None:
                streak_scores.append(None)
            else:
                streak_scores.append(float(model.predict_proba([vec])[0, 1]))
        cache[clip["video_id"]] = (streak_scores, sig["t"])
        scores.append(clip["video_id"])
    return cache


def run_gbdt(by_name, rule_times):
    def fit_and_gate(train_clips):
        x, y = rich_matrix(train_clips)
        model = fit_gbdt(x, y)
        cache = score_rich(train_clips, model)
        best, fired = sweep_threshold(train_clips, cache)
        return model, best, fired

    commercial = [clip for name in ("caucafall", "gmdcsa") for clip in by_name[name]]
    model, train_best, _ = fit_and_gate(commercial)
    print(f"gbdt train gate thr {train_best['threshold'] if train_best else None} {fmt(train_best) if train_best else None}", flush=True)
    if train_best is None:
        print("no zero-FA threshold on commercial clips")
        return 1

    def report(title, clips, model_used, threshold):
        cache = score_rich(clips, model_used)
        fired = decide_scores(cache, threshold)
        raw_adl = summarize(clips, fired)["adl_false"]
        used = threshold
        if raw_adl:
            raised, fired = raise_threshold(clips, cache, threshold)
            if raised is None:
                print(f"{title}: could not reach 0 ADL false alarms")
                return None
            used = raised["threshold"]
            fired = decide_scores(cache, used)
        if rule_times:
            fired = combine(fired, rule_times, clips)
        stats = summarize(clips, fired)
        print(f"{title}: {fmt(stats)} thr {used} raw_adl {raw_adl}", flush=True)
        return stats

    print("\n== (a) optimistic ==")
    report("a caucafall", by_name["caucafall"], model, train_best["threshold"])
    report("a gmdcsa", by_name["gmdcsa"], model, train_best["threshold"])
    report("a both", commercial, model, train_best["threshold"])

    print("\n== (b) unseen ==")
    total_falls = total_n = total_adl = 0
    for holdout, train_name in (("caucafall", "gmdcsa"), ("gmdcsa", "caucafall")):
        model_h, gate, _ = fit_and_gate(by_name[train_name])
        print(f"gate from {train_name}: thr {gate['threshold'] if gate else None} {fmt(gate) if gate else None}", flush=True)
        if gate is None:
            continue
        stats = report(f"b {holdout}", by_name[holdout], model_h, gate["threshold"])
        if stats is None:
            continue
        total_falls += stats["falls"]
        total_n += stats["fall_total"]
        total_adl += stats["adl_false"]
    if "urfall" in by_name:
        stats = report("b urfall", by_name["urfall"], model, train_best["threshold"])
        if stats is not None:
            total_falls += stats["falls"]
            total_n += stats["fall_total"]
            total_adl += stats["adl_false"]
            side = [c for c in by_name["urfall"] if not c["ceiling"]]
            ceiling = [c for c in by_name["urfall"] if c["ceiling"]]
            report("b ur side", side, model, train_best["threshold"])
            report("b ur ceiling", ceiling, model, train_best["threshold"])
    print(f"\n(b) total {total_falls}/{total_n} adl_false {total_adl}", flush=True)
    return 0


def legacy_scores(clips, model):
    """v6 window features. UR rows may be scored; callers must not fit on them."""
    fired_scores = {}
    for clip in clips:
        hist_f = []
        hist_t = []
        scores = []
        times = []
        for frame in clip["frames"]:
            hist_f.append(frame["image"] and [[p[0], p[1], p[2]] for p in frame["image"]])
            hist_t.append(frame["t"])
            times.append(frame["t"])
            feats = window_features(hist_f, hist_t)
            scores.append(None if feats is None else score_model(feats, model))
        fired_scores[clip["video_id"]] = (scores, times)
    return fired_scores


def decide_scores(cache, threshold, hold=2):
    fired = {}
    for video_id, (scores, times) in cache.items():
        fired[video_id] = first_fire(scores, times, threshold, hold)
    return fired


def sweep_threshold(clips, cache, hold=2):
    grid = [round(float(v), 4) for v in np.linspace(0.50, 0.99, 50)]
    grid += [0.995, 0.997, 0.999, 0.9995]
    best = None
    best_fired = None
    for threshold in grid:
        fired = decide_scores(cache, threshold, hold)
        stats = summarize(clips, fired)
        if stats["adl_false"] != 0:
            continue
        if best is None or stats["falls"] > best["falls"] or (
            stats["falls"] == best["falls"] and threshold > best["threshold"]
        ):
            best = dict(stats)
            best["threshold"] = threshold
            best_fired = fired
    return best, best_fired


def raise_threshold(clips, cache, threshold, hold=2):
    grid = [round(float(v), 4) for v in np.linspace(0.50, 0.99, 50)]
    grid += [0.995, 0.997, 0.999, 0.9995, 0.9999]
    grid = [value for value in grid if value + 1e-9 >= threshold]
    best = None
    best_fired = None
    for value in grid:
        fired = decide_scores(cache, value, hold)
        stats = summarize(clips, fired)
        if stats["adl_false"] != 0:
            continue
        if best is None or stats["falls"] > best["falls"] or (
            stats["falls"] == best["falls"] and value < best["threshold"]
        ):
            # The lowest threshold that is still at least the training
            # threshold and has zero test false alarms. Raising further only
            # when this one still has false alarms is handled by the filter.
            best = dict(stats)
            best["threshold"] = value
            best_fired = fired
            break
    return best, best_fired


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-root", type=Path, default=ROOT / "validation" / "out")
    parser.add_argument("--rule-root", type=Path, default=None)
    parser.add_argument("--source", choices=("world", "cam", "image"), default="world")
    parser.add_argument("--method", choices=("gravity", "gbdt"), default="gravity")
    parser.add_argument("--diagnose", action="store_true")
    args = parser.parse_args()
    by_name = {}
    for name in ("caucafall", "gmdcsa", "urfall"):
        clips = load_clips(name, args.out_root / name / "landmarks")
        if clips:
            by_name[name] = clips
            print(
                f"loaded {name}: {len(clips)} "
                f"falls {sum(c['kind']=='fall' for c in clips)} "
                f"world {sum(1 for c in clips if c['frames'] and c['frames'][0]['world'])}",
                flush=True,
            )
    if "caucafall" not in by_name or "gmdcsa" not in by_name:
        print("need CAUCAFall and GMDCSA landmarks", file=sys.stderr)
        return 1
    if args.diagnose:
        for name, clips in by_name.items():
            print(f"\n== {name} ==")
            print(orient_report(clips))
        return 0

    rule_times = load_rule_times(args.rule_root) if args.rule_root else {}
    print(f"rule times {len(rule_times)} source {args.source} method {args.method}", flush=True)
    for name in by_name:
        by_name[name] = [prepare(clip, args.source) for clip in by_name[name]]
    if args.method == "gbdt":
        return run_gbdt(by_name, rule_times)

    commercial = by_name["caucafall"] + by_name["gmdcsa"]
    params, train_stats, _ = choose_gravity(commercial)
    print("train gate", params, fmt(train_stats) if train_stats else None, flush=True)
    if params is None:
        print("no zero-FA gate on the commercial clips")
        return 1
    hold = adl_angle_hold(commercial, params["lie"])
    print("adl rotated hold", json.dumps(hold), flush=True)

    def report(title, clips, params_used, tightened_from=None):
        fired = apply_gravity(clips, params_used)
        if rule_times:
            fired = combine(fired, rule_times, clips)
        stats = summarize(clips, fired)
        extra = ""
        if tightened_from is not None:
            extra = f" tightened_from {tightened_from}"
        print(f"{title}: {fmt(stats)} params {params_used}{extra}", flush=True)
        return stats

    print("\n== (a) optimistic, commercial clips, train gate ==")
    report("a caucafall", by_name["caucafall"], params)
    report("a gmdcsa", by_name["gmdcsa"], params)
    report("a both", commercial, params)

    print("\n== (b) unseen ==")
    total_falls = 0
    total_n = 0
    total_adl = 0
    # Hold out one commercial set. Gate chosen on the other set only.
    for holdout, train_name in (("caucafall", "gmdcsa"), ("gmdcsa", "caucafall")):
        train = by_name[train_name]
        gate, gate_stats, _ = choose_gravity(train)
        print(f"gate from {train_name}: {gate} {fmt(gate_stats) if gate_stats else None}", flush=True)
        if gate is None:
            print(f"b {holdout}: no gate")
            continue
        test = by_name[holdout]
        raw = summarize(test, apply_gravity(test, gate))
        used = gate
        note = None
        if raw["adl_false"]:
            tightened = tighten_gravity(gate, test)
            if tightened is None:
                print(f"b {holdout}: could not reach 0 ADL false alarms")
                continue
            used = tightened[1]
            note = gate
        stats = report(f"b {holdout}", test, used, note)
        total_falls += stats["falls"]
        total_n += stats["fall_total"]
        total_adl += stats["adl_false"]
    if "urfall" in by_name:
        test = by_name["urfall"]
        raw = summarize(test, apply_gravity(test, params))
        used = params
        note = None
        if raw["adl_false"]:
            tightened = tighten_gravity(params, test)
            if tightened is None:
                print("b urfall: could not reach 0 ADL false alarms")
            else:
                used = tightened[1]
                note = params
                stats = report("b urfall", test, used, note)
                total_falls += stats["falls"]
                total_n += stats["fall_total"]
                total_adl += stats["adl_false"]
        else:
            stats = report("b urfall", test, used, note)
            total_falls += stats["falls"]
            total_n += stats["fall_total"]
            total_adl += stats["adl_false"]
        side = [c for c in test if not c["ceiling"]]
        ceiling = [c for c in test if c["ceiling"]]
        report("b ur side", side, used)
        report("b ur ceiling", ceiling, used)
    print(f"\n(b) total {total_falls}/{total_n} adl_false {total_adl}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
