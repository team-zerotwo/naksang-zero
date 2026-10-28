"""Replay of the shipped Kotlin fall rule, fast-flat gate, and lying-still hold.

Thresholds are the phone defaults. A float that Kotlin stores as Float is
rounded through float32. Lying-still is measured only so the longest ADL hold
can be reported. It is not a detection in the score tables: the 9 second and
45 second defaults are not shortened to fit a clip.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

NOSE, LS, RS, LH, RH, LK, RK, LA, RA = 0, 11, 12, 23, 24, 25, 26, 27, 28
TRACKED = (NOSE, LS, RS, LH, RH, LK, RK, LA, RA)
TORSO = (LS, RS, LH, RH)
MIN_VIS = np.float32(0.35)
SMOOTH = np.float32(0.55)
GRACE = 1_200
MIN_TORSO = np.float32(0.04)


def f32(value) -> float:
    return float(np.float32(value))


def _hypot32(dx, dy) -> float:
    return f32(math.hypot(float(dx), float(dy)))


@dataclass
class Sample:
    t: int
    cx: float
    cy: float
    angle: float
    torso: float
    shoulder_y: float
    hip_y: float
    nose_y: float | None
    knee_y: float | None
    ankle_y: float | None


def _point(frame, index):
    if frame is None or index >= len(frame) or frame[index] is None:
        return None
    point = frame[index]
    x = f32(point[0])
    y = f32(point[1])
    v = f32(point[2]) if len(point) > 2 else f32(1.0)
    return x, y, v


def _mean_xy(points):
    if not points:
        return None
    return f32(sum(p[0] for p in points) / len(points)), f32(sum(p[1] for p in points) / len(points))


def _mean_y(values):
    if not values:
        return None
    return f32(sum(values) / len(values))


def pose_measure(assembled, min_visibility: float = 0.5):
    """assembled maps landmark index -> (x, y, v) or missing."""
    shoulders = []
    hips = []
    for index, bucket in ((LS, shoulders), (RS, shoulders), (LH, hips), (RH, hips)):
        point = assembled.get(index)
        if point is None or point[2] < min_visibility:
            continue
        bucket.append(point)
    if not shoulders or not hips:
        return None
    sx, sy = _mean_xy(shoulders)
    hx, hy = _mean_xy(hips)
    angle = f32(math.degrees(math.atan2(abs(float(sy) - float(hy)), abs(float(sx) - float(hx)))))
    nose = assembled.get(NOSE)
    knees = []
    ankles = []
    for index, bucket in ((LK, knees), (RK, knees), (LA, ankles), (RA, ankles)):
        point = assembled.get(index)
        if point is None or point[2] < min_visibility:
            continue
        bucket.append(point[1])
    nose_y = f32(nose[1]) if nose is not None and nose[2] >= min_visibility else None
    return Sample(
        t=0,
        cx=f32((float(sx) + float(hx)) / 2.0),
        cy=f32((float(sy) + float(hy)) / 2.0),
        angle=angle,
        torso=_hypot32(float(sx) - float(hx), float(sy) - float(hy)),
        shoulder_y=sy,
        hip_y=hy,
        nose_y=nose_y,
        knee_y=_mean_y(knees),
        ankle_y=_mean_y(ankles),
    )


def _body_height_ratio(ref: Sample, cur: Sample):
    ref_ys = []
    cur_ys = []

    def add(a, b):
        if a is not None and b is not None:
            ref_ys.append(a)
            cur_ys.append(b)

    add(ref.nose_y, cur.nose_y)
    add(ref.shoulder_y, cur.shoulder_y)
    add(ref.hip_y, cur.hip_y)
    add(ref.knee_y, cur.knee_y)
    add(ref.ankle_y, cur.ankle_y)
    extra = (
        (ref.nose_y is not None and cur.nose_y is not None)
        or (ref.knee_y is not None and cur.knee_y is not None)
        or (ref.ankle_y is not None and cur.ankle_y is not None)
    )
    if not extra or len(ref_ys) < 2:
        return None
    ref_span = max(ref_ys) - min(ref_ys)
    cur_span = max(cur_ys) - min(cur_ys)
    if ref_span < f32(0.02):
        return None
    return f32(cur_span / ref_span)


class _Tracker:
    def __init__(self):
        self.xy = {}
        self.at = {}

    def track(self, slot, raw, live, t, grace):
        if live and raw is not None:
            prev = self.xy.get(slot)
            seen = self.at.get(slot)
            stale = prev is None or t - seen > grace
            alpha = float(SMOOTH)
            if stale:
                x, y = raw[0], raw[1]
            else:
                x = f32(alpha * float(raw[0]) + (1.0 - alpha) * float(prev[0]))
                y = f32(alpha * float(raw[1]) + (1.0 - alpha) * float(prev[1]))
            self.xy[slot] = (x, y)
            self.at[slot] = t
            return x, y, f32(1.0)
        seen = self.at.get(slot)
        if seen is not None and t - seen <= grace:
            x, y = self.xy[slot]
            return x, y, f32(1.0)
        return None


class FallRule:
    """FallDetector with the shipped FallConfig."""

    def __init__(self):
        self.history = []
        self.tracked = _Tracker()
        self.lie_since = None
        self.lie_ref = None
        self.lie_flat = False
        self.lie_steep = False
        self.lie_drop = f32(0)
        self.floor_cy = f32(0)
        self.anchor = (f32(0), f32(0))
        self.standing_len = f32(0)
        self.last_t = None
        self.armed = True

    def reset(self):
        self.__init__()

    def _measure(self, t, frame):
        live = {}
        raw = {}
        any_torso = False
        for index in TRACKED:
            point = _point(frame, index)
            raw[index] = point
            visible = point is not None and point[2] >= float(MIN_VIS)
            live[index] = visible
            if visible and index in TORSO:
                any_torso = True
        if not any_torso:
            return None
        assembled = {}
        for index in TRACKED:
            point = self.tracked.track(index, raw[index], live[index], t, GRACE)
            if point is not None:
                assembled[index] = point
        pose = pose_measure(assembled, 0.5)
        if pose is None:
            return None
        pose.t = t
        return pose

    def _remember_standing(self, sample):
        if sample.angle < f32(58) or sample.torso < float(MIN_TORSO):
            return
        if self.standing_len <= 0:
            self.standing_len = sample.torso
        elif sample.torso >= f32(self.standing_len * f32(0.85)):
            self.standing_len = f32(0.9 * float(self.standing_len) + 0.1 * float(sample.torso))

    def _standing_again(self, sample) -> bool:
        if self.floor_cy > 0 and sample.cy > f32(float(self.floor_cy) - 0.10):
            return False
        if sample.angle < f32(58):
            return False
        if self.standing_len > 0 and sample.torso < f32(float(self.standing_len) * 0.85):
            return False
        self.floor_cy = f32(0)
        return True

    def _clear_lie(self):
        self.lie_since = None
        self.lie_ref = None
        self.lie_flat = False
        self.lie_steep = False
        self.lie_drop = f32(0)

    def _ref_full(self, ref) -> bool:
        if self.standing_len <= 0:
            return ref.torso >= float(MIN_TORSO)
        return ref.torso >= f32(float(self.standing_len) * 0.80)

    def _flat_latch(self, sample):
        cutoff = sample.t - 1_600
        ref = None
        for item in self.history:
            if item.t <= cutoff:
                ref = item
        if ref is None or sample.t - ref.t > 1_850:
            return None
        if ref.angle < f32(58):
            return None
        drop = f32(float(sample.cy) - float(ref.cy))
        if drop < f32(0.10):
            return None
        flat = sample.angle <= f32(45)
        if not flat:
            return None
        return ref, drop, True

    def _steep_latch(self, sample):
        cutoff = sample.t - 800
        ref = None
        for item in self.history:
            if item.t <= cutoff:
                ref = item
        if ref is None or sample.t - ref.t > 1_050:
            return None
        if ref.angle < f32(58) or sample.angle < f32(65):
            return None
        drop = f32(float(sample.cy) - float(ref.cy))
        if drop < f32(0.18):
            return None
        return ref, drop, False

    def _still_down(self, sample) -> bool:
        ref = self.lie_ref
        if self.lie_steep:
            if sample.angle < f32(60):
                return False
            return ref is not None and sample.cy >= f32(float(ref.cy) + float(self.lie_drop) * 0.6)
        return self.lie_flat and sample.angle <= f32(62)

    def on_frame(self, t, frame) -> bool:
        sample = self._measure(t, frame)
        if sample is None:
            return False
        previous = self.last_t
        self.last_t = sample.t
        keep = sample.t - (1_600 + 2_000 + 3_000)
        self.history = [item for item in self.history if item.t >= keep]
        self.history.append(sample)
        self._remember_standing(sample)
        if not self.armed:
            if self._standing_again(sample):
                self.armed = True
                self._clear_lie()
            return False
        gap = None if previous is None else sample.t - previous
        if self.lie_since is not None and gap is not None and gap > GRACE:
            self.lie_since = sample.t
            self.anchor = (sample.cx, sample.cy)
        if self.lie_since is None:
            flat = self._flat_latch(sample)
            steep = self._steep_latch(sample)
            if flat is None and steep is None:
                return False
            use_steep = flat is None and steep is not None
            chosen = steep if use_steep else flat
            self.lie_since = sample.t
            self.lie_ref = chosen[0]
            self.lie_flat = (not use_steep) and chosen[2]
            self.lie_steep = use_steep
            self.lie_drop = chosen[1]
            self.anchor = (sample.cx, sample.cy)
            return False
        if self.lie_steep:
            flat = self._flat_latch(sample)
            if flat is not None:
                self.lie_steep = False
                self.lie_flat = True
                self.lie_ref = flat[0]
                self.lie_drop = flat[1]
        if not self._still_down(sample):
            self._clear_lie()
            return False
        moved = _hypot32(float(sample.cx) - float(self.anchor[0]), float(sample.cy) - float(self.anchor[1]))
        limit = f32(0.035) if self.lie_steep else f32(0.050)
        if moved > limit:
            self.lie_since = sample.t
            self.anchor = (sample.cx, sample.cy)
            return False
        if sample.t - self.lie_since >= 2_000:
            if self.lie_steep:
                self.floor_cy = sample.cy
            self.armed = False
            self._clear_lie()
            return True
        return False


class FastFlat:
    """FastFlattenDetector. Angle is the shoulder-hip line, unsmoothed."""

    def __init__(self):
        self.history = []
        self.confirm = None
        self.armed = True

    def _angle(self, frame):
        if frame is None:
            return None
        shoulders = []
        hips = []
        for index, bucket in ((LS, shoulders), (RS, shoulders), (LH, hips), (RH, hips)):
            point = _point(frame, index)
            if point is None or point[2] < 0.35:
                continue
            bucket.append((float(point[0]), float(point[1])))
        if not shoulders or not hips:
            return None
        sx = sum(p[0] for p in shoulders) / len(shoulders)
        sy = sum(p[1] for p in shoulders) / len(shoulders)
        hx = sum(p[0] for p in hips) / len(hips)
        hy = sum(p[1] for p in hips) / len(hips)
        return math.degrees(math.atan2(abs(sy - hy), abs(sx - hx)))

    def on_frame(self, t, frame) -> bool:
        angle = self._angle(frame)
        if not self.armed:
            if angle is not None and angle >= 58.0:
                self.armed = True
                self.confirm = None
            return False
        if angle is None:
            self.confirm = None
            return False
        self.history.append((t, angle))
        self.history = [(stamp, value) for stamp, value in self.history if stamp >= t - 4_000]
        fired = self._step(t, angle)
        if fired:
            self.armed = False
            self.confirm = None
        return fired

    def _step(self, now, angle) -> bool:
        if self.confirm is None:
            if angle > 20.0:
                return False
            opened = False
            peak = 0.0
            previous = None
            for stamp, value in self.history:
                if now - stamp > 1_600:
                    previous = (stamp, value)
                    continue
                if previous is not None:
                    dt = max((stamp - previous[0]) / 1000.0, 0.05)
                    flattening = (previous[1] - value) / dt
                    if flattening > peak:
                        peak = flattening
                if value >= 58.0:
                    opened = True
                previous = (stamp, value)
            if not opened or peak < 140.0:
                return False
            self.confirm = now
        if angle > 30.0:
            self.confirm = None
            return False
        return now - self.confirm >= 2_000


# Matches LyingStillDetector's along-camera supine path. The 9s/45s clock
# stays in Kotlin; this class only measures the longest still stretch.
SUPINE_LEN_RATIO = f32(0.50)
SUPINE_SHAPE_RATIO = f32(0.50)
SUPINE_ABS_SHAPE = f32(0.55)
SUPINE_LEG_BAND = f32(0.10)
SUPINE_LEG_SHOULDER = f32(0.80)
MIN_SHOULDER = f32(0.04)


def _shoulder_width(assembled):
    left = assembled.get(LS)
    right = assembled.get(RS)
    if left is None or right is None or left[2] < 0.5 or right[2] < 0.5:
        return None
    width = _hypot32(float(left[0]) - float(right[0]), float(left[1]) - float(right[1]))
    if width < float(MIN_SHOULDER):
        return None
    return width


def _legs_near_hips(pose: Sample, width: float) -> bool:
    if pose.knee_y is None or pose.ankle_y is None:
        return False
    band = max(float(SUPINE_LEG_BAND), float(width) * float(SUPINE_LEG_SHOULDER))
    return abs(float(pose.knee_y) - float(pose.hip_y)) <= band and abs(float(pose.ankle_y) - float(pose.hip_y)) <= band


class LyingHold:
    """Longest still floor-lying stretch. Does not fire the 9 second alert.

    Lying includes the flat-angle pose and a steep torso that has shortened
    against the recent upright torso (and against shoulder width) while the
    knees and ankles sit near the hips. The second case is someone lying
    straight at the camera. It does not treat walking away, sitting, or a
    squat as lying, and it does not skip the stillness clock.
    """

    def __init__(self):
        self.tracked = _Tracker()
        self.saw_upright = False
        self.upright_cy = f32(0)
        self.upright_torso = f32(0)
        self.upright_shoulder = f32(0)
        self.last_upright = None
        self.last_high = None
        self.lying_since = None
        self.anchor = (f32(0), f32(0))
        self.last_good = None
        self.max_ms = 0

    def _measure(self, t, frame):
        any_live = False
        assembled = {}
        for index in TRACKED:
            raw = _point(frame, index)
            live = raw is not None and raw[2] >= float(MIN_VIS)
            if live:
                any_live = True
            point = self.tracked.track(index, raw, live, t, GRACE)
            if point is not None:
                assembled[index] = point
        if not any_live:
            return None
        pose = pose_measure(assembled, 0.5)
        if pose is None:
            return None
        return pose, _shoulder_width(assembled)

    def _note_standing(self, pose: Sample, width):
        if width is None or width < float(MIN_SHOULDER) or pose.torso < float(MIN_TORSO):
            return
        if self.upright_torso <= 0 or self.upright_shoulder < float(MIN_SHOULDER):
            self.upright_torso = pose.torso
            self.upright_shoulder = width
            return
        if pose.torso < f32(float(self.upright_torso) * 0.85):
            return
        self.upright_torso = f32(0.9 * float(self.upright_torso) + 0.1 * float(pose.torso))
        self.upright_shoulder = f32(0.9 * float(self.upright_shoulder) + 0.1 * float(width))

    def _supine(self, pose: Sample, width) -> bool:
        if width is None or not _legs_near_hips(pose, width):
            return False
        shape = float(pose.torso) / float(width)
        if self.upright_torso >= float(MIN_TORSO) and self.upright_shoulder >= float(MIN_SHOULDER):
            upright_shape = float(self.upright_torso) / float(self.upright_shoulder)
            if upright_shape <= 0:
                return False
            len_ratio = float(pose.torso) / float(self.upright_torso)
            shape_ratio = shape / upright_shape
            return len_ratio <= float(SUPINE_LEN_RATIO) and shape_ratio <= float(SUPINE_SHAPE_RATIO)
        return shape <= float(SUPINE_ABS_SHAPE)

    def _note_hold(self, t):
        if self.lying_since is None:
            return
        self.max_ms = max(self.max_ms, t - self.lying_since)

    def on_frame(self, t, frame):
        measured = self._measure(t, frame) if frame is not None else None
        if measured is None:
            if self.last_good is not None and t - self.last_good > GRACE:
                self.lying_since = None
                if t - self.last_good > 20_000:
                    self.saw_upright = False
                    self.last_upright = None
                    self.last_high = None
                    self.upright_torso = f32(0)
                    self.upright_shoulder = f32(0)
            return
        pose, width = measured
        self.last_good = t
        supine = self._supine(pose, width)
        if not supine and pose.angle >= f32(72):
            self.last_high = t
        if not supine and pose.angle >= f32(50):
            self._note_standing(pose, width)
            self.saw_upright = True
            self.upright_cy = pose.cy
            self.last_upright = t
            self.lying_since = None
            return
        low = pose.cy >= f32(0.45)
        dropped = self.saw_upright and pose.cy - self.upright_cy >= f32(0.08)
        lying = (pose.angle <= f32(45) and (low or dropped)) or supine
        if not lying:
            self.lying_since = None
            return
        if self.lying_since is None:
            self.lying_since = t
            self.anchor = (pose.cx, pose.cy)
            return
        moved = _hypot32(float(pose.cx) - float(self.anchor[0]), float(pose.cy) - float(self.anchor[1]))
        if moved > f32(0.050):
            self._note_hold(t)
            self.lying_since = t
            self.anchor = (pose.cx, pose.cy)
            return
        self._note_hold(t)


def _frames_of(clip):
    frames = clip["frames"]
    if frames and "landmarks" in frames[0]:
        return [(int(frame["t"]), frame["landmarks"]) for frame in frames]
    return [(int(frame["t"]), frame.get("image")) for frame in frames]


def rule_fire_ms(clip) -> int | None:
    detector = FallRule()
    for t, frame in _frames_of(clip):
        if frame is None:
            continue
        if detector.on_frame(t, frame):
            return t
    return None


def fast_fire_ms(clip) -> int | None:
    detector = FastFlat()
    for t, frame in _frames_of(clip):
        if detector.on_frame(t, frame):
            return t
    return None


def frozen_fire_ms(clip) -> int | None:
    """Earliest of the shipped rule and the fast-flat gate. Lying-still is not included."""
    times = [stamp for stamp in (rule_fire_ms(clip), fast_fire_ms(clip)) if stamp is not None]
    return min(times) if times else None


def lying_hold_ms(clip) -> int:
    detector = LyingHold()
    for t, frame in _frames_of(clip):
        detector.on_frame(t, frame)
    return detector.max_ms


def adl_hold_report(clips) -> dict:
    longest = 0
    longest_id = ""
    by_type = {}
    for clip in clips:
        if clip.get("kind") == "fall":
            continue
        held = lying_hold_ms(clip)
        activity = clip.get("activity_type") or "adl"
        by_type[activity] = max(by_type.get(activity, 0), held)
        if held >= longest:
            longest = held
            longest_id = clip["video_id"]
    return {"max_ms": longest, "video_id": longest_id, "by_type": by_type}


def _pose(ls, rs, lh, rh):
    joints = [[0.0, 0.0, 0.0] for _ in range(33)]
    joints[LS] = [ls[0], ls[1], 1.0]
    joints[RS] = [rs[0], rs[1], 1.0]
    joints[LH] = [lh[0], lh[1], 1.0]
    joints[RH] = [rh[0], rh[1], 1.0]
    return joints


def _hold(start, end, lead, transition, until, step):
    frames = []
    t = 0
    while t <= until:
        if t <= lead:
            pose = start
        elif t >= transition:
            pose = end
        else:
            u = (t - lead) / (transition - lead)
            pose = [
                [a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2]]
                for a, b in zip(start, end)
            ]
        frames.append({"t": t, "landmarks": pose})
        t += step
    return frames


def _self_check() -> None:
    stand = _pose((0.42, 0.20), (0.58, 0.20), (0.44, 0.48), (0.56, 0.48))
    lying = _pose((0.28, 0.55), (0.36, 0.52), (0.62, 0.58), (0.72, 0.54))
    sitting = _pose((0.42, 0.38), (0.58, 0.38), (0.44, 0.70), (0.56, 0.70))
    squat = _pose((0.42, 0.36), (0.58, 0.36), (0.44, 0.60), (0.56, 0.60))
    short = _pose((0.48, 0.58), (0.52, 0.59), (0.49, 0.66), (0.53, 0.67))
    fall = {"frames": _hold(stand, lying, 1000, 1500, 5000, 100)}
    fired = rule_fire_ms(fall)
    if fired is None or fired < 1500 + 2000 - 200:
        raise SystemExit(f"rule missed a flat fall: {fired}")
    if rule_fire_ms({"frames": _hold(stand, sitting, 1000, 2200, 6000, 100)}) is not None:
        raise SystemExit("sitting fired the rule")
    if rule_fire_ms({"frames": _hold(stand, squat, 1000, 1600, 6000, 100)}) is not None:
        raise SystemExit("squat fired the rule")
    if rule_fire_ms({"frames": _hold(stand, lying, 1000, 9000, 13000, 100)}) is not None:
        raise SystemExit("slow lie fired the rule")
    steep = rule_fire_ms({"frames": _hold(stand, short, 1000, 1500, 5000, 100)})
    if steep is None:
        raise SystemExit("along-view fall did not fire")
    fast = fast_fire_ms({"frames": _hold(stand, lying, 1000, 1400, 5000, 200)})
    if fast is None or fast < 1400 + 2000 - 250:
        raise SystemExit(f"fast-flat missed: {fast}")
    if fast_fire_ms({"frames": _hold(stand, lying, 1000, 8000, 12000, 200)}) is not None:
        raise SystemExit("slow lie fired fast-flat")

    def _body(points):
        joints = [[0.0, 0.0, 0.0] for _ in range(33)]
        names = (LS, RS, LH, RH, LK, RK, LA, RA)
        for index, point in zip(names, points):
            joints[index] = [point[0], point[1], 1.0]
        return joints

    stand_full = _body((
        (0.42, 0.18), (0.58, 0.18), (0.44, 0.46), (0.56, 0.46),
        (0.43, 0.68), (0.57, 0.68), (0.42, 0.90), (0.58, 0.90),
    ))
    supine = _body((
        (0.40, 0.50), (0.60, 0.50), (0.46, 0.56), (0.54, 0.56),
        (0.48, 0.58), (0.52, 0.58), (0.47, 0.60), (0.53, 0.60),
    ))
    sitting = _body((
        (0.40, 0.36), (0.60, 0.36), (0.47, 0.44), (0.53, 0.44),
        (0.36, 0.70), (0.64, 0.70), (0.34, 0.88), (0.66, 0.88),
    ))
    supine_hold = lying_hold_ms({"frames": _hold(stand_full, supine, 1000, 1600, 8000, 200)})
    if supine_hold < 2_000:
        raise SystemExit(f"supine along the camera was not lying: {supine_hold}")
    if lying_hold_ms({"frames": _hold(stand_full, sitting, 1000, 2200, 8000, 200)}) != 0:
        raise SystemExit("sitting counted as lying")
    if lying_hold_ms({"frames": _hold(stand_full, stand_full, 1000, 1600, 4000, 200)}) != 0:
        raise SystemExit("standing counted as lying")
    print("rules self-check ok", flush=True)


if __name__ == "__main__":
    _self_check()
