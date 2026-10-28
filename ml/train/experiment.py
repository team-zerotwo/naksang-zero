#!/usr/bin/env python3
"""Train pose-window models without UR Fall and score the honest splits.

UR Fall clips are scored and never fitted. The threshold is the highest one
with zero ADL false alarms on the training clips, after the most falls. If the
test clips still false-alarm, the threshold only moves up. (a) scores the
commercial clips the weights saw. (b) is leave-one-commercial-dataset-out plus
UR Fall. Optional --rule-root ORs the frozen Kotlin rule_at_ms.
"""

from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "train"))

import features  # noqa: E402
from features import first_fire, score_model, window_features  # noqa: E402
from holdout import combine, fmt, load_clips, load_rule_times, raise_threshold, summarize  # noqa: E402
from train_pose_model import (  # noqa: E402
    NEGATIVE_BEFORE_MS,
    POSITIVE_POST_MS,
    POSITIVE_PRE_MS,
    fit_logreg,
    fit_mlp,
)


def plus_features(frames, times):
    """v6 features plus the speed of the torso going flat and whether it stays there.

    The extra numbers use the same smoothed window as `window_features`. A raw
    drop in image y is still not its own feature.
    """
    base = window_features(frames, times)
    if base is None:
        return None
    padded_f, padded_t = features.pad_window(list(frames), list(times))
    smoothed = features.smooth_window(padded_f)
    geoms = [features.frame_geometry(frame) for frame in smoothed]
    valid = [(index, geom) for index, geom in enumerate(geoms) if geom is not None]
    angles = [geom["angle"] for _, geom in valid]
    max_flat = 0.0
    steps = []
    for (i1, g1), (i2, g2) in zip(valid, valid[1:]):
        dt = max((padded_t[i2] - padded_t[i1]) / 1000.0, 0.05)
        flattening = (g1["angle"] - g2["angle"]) / dt
        if flattening > max_flat:
            max_flat = flattening
        steps.append(math.hypot(g2["cx"] - g1["cx"], g2["cy"] - g1["cy"]))
    still = sum(steps[-3:]) / len(steps[-3:]) if steps else 0.0
    min_angle = min(angles)
    end_angle = angles[-1]
    flat_frac = sum(1 for angle in angles if angle <= 45.0) / len(angles)
    return list(base) + [max_flat / 180.0, min_angle / 90.0, (end_angle - min_angle) / 90.0, still, flat_frac]


def as_legacy(clip: dict) -> dict:
    frames = []
    for frame in clip["frames"]:
        image = frame["image"]
        landmarks = None if image is None else [list(point) for point in image]
        frames.append({"t": frame["t"], "landmarks": landmarks})
    out = dict(clip)
    out["frames"] = frames
    return out


def cache_model(clips, model):
    cache = {}
    for clip in clips:
        hist_f = []
        hist_t = []
        scores = []
        times = []
        for frame in clip["frames"]:
            hist_f.append(frame["landmarks"])
            hist_t.append(frame["t"])
            times.append(frame["t"])
            feats = FEATURE_FN(hist_f, hist_t)
            scores.append(None if feats is None else score_model(feats, model))
        cache[clip["video_id"]] = (scores, times)
    return cache


def decide(cache, threshold, hold=2):
    return {video_id: first_fire(scores, times, threshold, hold) for video_id, (scores, times) in cache.items()}


FEATURE_FN = window_features
AUGMENT = False


def _scale_y(frames, factor: float):
    hips = []
    for frame in frames:
        if frame is None:
            continue
        for index in (23, 24):
            point = frame[index]
            if point[2] >= 0.35:
                hips.append(point[1])
    origin = sum(hips) / len(hips) if hips else 0.5
    out = []
    for frame in frames:
        if frame is None:
            out.append(None)
            continue
        out.append([[point[0], origin + (point[1] - origin) * factor, point[2]] for point in frame])
    return out


def training_matrix(clips):
    xs = []
    ys = []
    for clip in clips:
        if clip.get("ceiling") or clip.get("dataset") == "urfall":
            continue
        positives = []
        negatives = []
        raw_pos = []
        hist_f = []
        hist_t = []
        onset = clip["onset_ms"]
        for frame in clip["frames"]:
            hist_f.append(frame["landmarks"])
            hist_t.append(frame["t"])
            window_f = hist_f[-features.WINDOW :]
            window_t = hist_t[-features.WINDOW :]
            feats = FEATURE_FN(window_f, window_t)
            if feats is None:
                continue
            end_t = frame["t"]
            label = None
            if clip["kind"] != "fall":
                label = 0
            elif onset is not None and onset - POSITIVE_PRE_MS <= end_t <= onset + POSITIVE_POST_MS:
                label = 1
            elif onset is not None and end_t < onset - NEGATIVE_BEFORE_MS:
                label = 0
            if label is None:
                continue
            if label == 1:
                positives.append(feats)
                raw_pos.append((window_f, window_t))
            else:
                negatives.append(feats)
        if len(negatives) > 12:
            pick = np.linspace(0, len(negatives) - 1, 12).astype(int)
            negatives = [negatives[i] for i in pick]
        xs.extend(positives)
        ys.extend([1] * len(positives))
        xs.extend(negatives)
        ys.extend([0] * len(negatives))
        if AUGMENT:
            for window_f, window_t in raw_pos:
                for factor in (0.8, 1.2):
                    feats = FEATURE_FN(_scale_y(window_f, factor), window_t)
                    if feats is not None:
                        xs.append(feats)
                        ys.append(1)
    if not xs:
        raise RuntimeError("no training windows")
    return np.asarray(xs, dtype=np.float64), np.asarray(ys, dtype=np.float64)


def fit_kind(kind: str, clips, seed: int):
    x, y = training_matrix(clips)
    if kind == "mlp":
        return fit_mlp(x, y, seed=seed)
    if kind == "logreg":
        return fit_logreg(x, y)
    if kind == "gbdt":
        return fit_gbdt(x, y)
    raise ValueError(kind)


def fit_gbdt(x, y):
    from sklearn.ensemble import GradientBoostingClassifier

    mean = x.mean(axis=0)
    std = np.where(x.std(axis=0) < 1e-8, 1.0, x.std(axis=0))
    z = np.clip((x - mean) / std, -8.0, 8.0)
    clf = GradientBoostingClassifier(
        n_estimators=50,
        max_depth=2,
        learning_rate=0.08,
        subsample=0.9,
        min_samples_leaf=8,
        random_state=7,
    )
    pos = max(float(y.mean()), 1e-3)
    weight = np.where(y > 0.5, 0.5 / pos, 0.5 / (1.0 - pos))
    clf.fit(z, y, sample_weight=weight)
    return {"kind": "gbdt", "mean": mean.tolist(), "std": std.tolist(), "clf": clf}


def score_gbdt_matrix(rows, model) -> list[float]:
    if not rows:
        return []
    mean = np.asarray(model["mean"])
    std = np.asarray(model["std"])
    z = np.clip((np.asarray(rows) - mean) / std, -8.0, 8.0)
    return model["clf"].predict_proba(z)[:, 1].tolist()


def cache_any(clips, model):
    if model["kind"] != "gbdt":
        return cache_model(clips, model)
    cache = {}
    for clip in clips:
        hist_f = []
        hist_t = []
        pending = []
        pending_at = []
        scores = []
        times = []
        for frame in clip["frames"]:
            hist_f.append(frame["landmarks"])
            hist_t.append(frame["t"])
            times.append(frame["t"])
            feats = FEATURE_FN(hist_f, hist_t)
            if feats is None:
                scores.append(None)
            else:
                scores.append(0.0)
                pending.append(feats)
                pending_at.append(len(scores) - 1)
        if pending:
            probs = score_gbdt_matrix(pending, model)
            for index, prob in zip(pending_at, probs):
                scores[index] = prob
        cache[clip["video_id"]] = (scores, times)
    return cache


def gate_on(clips, model):
    cache = cache_any(clips, model)
    best = None
    best_thr = None
    grid = [round(float(v), 4) for v in np.linspace(0.5, 0.99, 50)]
    grid += [0.995, 0.997, 0.999, 0.9995]
    for threshold in grid:
        fired = decide(cache, threshold)
        stats = summarize(clips, fired)
        if stats["adl_false"] != 0:
            continue
        if best is None or stats["falls"] > best["falls"] or (
            stats["falls"] == best["falls"] and threshold > best_thr
        ):
            best = stats
            best_thr = threshold
    return best, best_thr, cache


def apply_threshold(clips, model, threshold, rule_times):
    cache = cache_any(clips, model)
    fired = decide(cache, threshold)
    raw = summarize(clips, fired)
    used = threshold
    if raw["adl_false"]:
        # Reuse holdout's raiser, which expects the same cache shape.
        raised, _fired = raise_threshold(clips, cache, threshold)
        if raised is None:
            return None, used, raw["adl_false"]
        used = raised["threshold"]
        fired = decide(cache, used)
    if rule_times:
        fired = combine(fired, rule_times, clips)
    return summarize(clips, fired), used, raw["adl_false"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-root", type=Path, default=ROOT / "validation" / "out")
    parser.add_argument("--rule-root", type=Path, default=None)
    parser.add_argument("--kind", choices=("logreg", "mlp", "gbdt"), default="logreg")
    parser.add_argument("--window", type=int, default=10)
    parser.add_argument("--features", choices=("v6", "plus"), default="v6")
    parser.add_argument("--augment", action="store_true")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    global FEATURE_FN, AUGMENT
    features.WINDOW = args.window
    FEATURE_FN = plus_features if args.features == "plus" else window_features
    AUGMENT = args.augment
    by_name = {}
    for name in ("caucafall", "gmdcsa", "urfall"):
        raw = load_clips(name, args.out_root / name / "landmarks")
        if raw:
            by_name[name] = [as_legacy(clip) for clip in raw]
            print(f"loaded {name} {len(by_name[name])}", flush=True)
    if "caucafall" not in by_name or "gmdcsa" not in by_name:
        print("need both commercial sets", file=sys.stderr)
        return 1
    rule_times = load_rule_times(args.rule_root) if args.rule_root else {}
    print(
        f"kind {args.kind} window {args.window} features {args.features} augment {args.augment} rules {len(rule_times)}",
        flush=True,
    )
    commercial = by_name["caucafall"] + by_name["gmdcsa"]
    # training_rows skips ceiling. Drop UR explicitly so a stray clip cannot fit.
    train_pool = [clip for clip in commercial if clip["dataset"] != "urfall"]
    model = fit_kind(args.kind, train_pool, args.seed)
    best, thr, _ = gate_on(train_pool, model)
    print(f"train gate thr {thr} {fmt(best) if best else None}", flush=True)
    if best is None:
        return 1

    def report(title, clips, model_used, threshold):
        stats, used, raw_adl = apply_threshold(clips, model_used, threshold, rule_times)
        if stats is None:
            print(f"{title}: could not reach 0 ADL")
            return None
        print(f"{title}: {fmt(stats)} thr {used} train_thr {threshold} test_adl_before_raise {raw_adl}", flush=True)
        return stats

    print("== (a) ==")
    report("a caucafall", by_name["caucafall"], model, thr)
    report("a gmdcsa", by_name["gmdcsa"], model, thr)
    report("a both", commercial, model, thr)
    print("== (b) ==")
    total_f = total_n = total_a = 0
    for holdout, train_name in (("caucafall", "gmdcsa"), ("gmdcsa", "caucafall")):
        model_h = fit_kind(args.kind, by_name[train_name], args.seed)
        gate, gate_thr, _ = gate_on(by_name[train_name], model_h)
        print(f"gate from {train_name} thr {gate_thr} {fmt(gate) if gate else None}", flush=True)
        if gate is None:
            continue
        stats = report(f"b {holdout}", by_name[holdout], model_h, gate_thr)
        if stats:
            total_f += stats["falls"]
            total_n += stats["fall_total"]
            total_a += stats["adl_false"]
    if "urfall" in by_name:
        stats = report("b urfall", by_name["urfall"], model, thr)
        if stats:
            total_f += stats["falls"]
            total_n += stats["fall_total"]
            total_a += stats["adl_false"]
        side = [c for c in by_name["urfall"] if not c["ceiling"]]
        ceiling = [c for c in by_name["urfall"] if c["ceiling"]]
        report("b ur side", side, model, thr)
        report("b ur ceiling", ceiling, model, thr)
    print(f"(b) total {total_f}/{total_n} adl_false {total_a}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
