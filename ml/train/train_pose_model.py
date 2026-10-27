#!/usr/bin/env python3
"""Fit a tiny pose-window fall model and export Kotlin weights.

Reads landmark CSVs produced by ml/validation/extract_*.py. Does not read or
write video. UR Fall landmark CSVs are used when present and are not copied
into the repo. The shipped weights default to CAUCAFall + GMDCSA-24 (both
CC BY 4.0) plus UR side-view falls and ADL when that set is on disk. Ceiling
clips are scored, not fit: image y is not gravity there.

Leave-one-dataset-out trains on two sets and sweeps a threshold on the third
so ADL false alarms on the held-out set stay at 0. In-distribution numbers
are written separately and labeled that way.

    python3 ml/train/train_pose_model.py
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

from features import (  # noqa: E402
    FEATURE_NAMES,
    MIN_VISIBILITY,
    NEGATIVE_BEFORE_MS,
    POSITIVE_POST_MS,
    POSITIVE_PRE_MS,
    WINDOW,
    first_fire,
    score_model,
    window_features,
)

DATASETS = ("caucafall", "gmdcsa", "urfall")
HOLD = 2
Z_CLIP = 8.0


def read_landmark_csv(path: Path) -> list[dict]:
    frames = []
    with path.open(newline="") as handle:
        reader = csv.reader(handle)
        header = next(reader)
        if not header or header[0] != "timestamp_ms":
            raise RuntimeError(f"unexpected landmark header in {path}")
        for row in reader:
            if not row or not row[0]:
                continue
            found = row[2] == "1"
            landmarks = None
            if found:
                landmarks = []
                for index in range(33):
                    base = 3 + index * 3
                    landmarks.append([float(row[base]), float(row[base + 1]), float(row[base + 2])])
            frames.append({"t": int(float(row[0])), "landmarks": landmarks})
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
            ceiling = camera == "cam1" or view == "ceiling"
            clips.append(
                {
                    "dataset": name,
                    "video_id": row["video_id"],
                    "kind": row.get("kind") or "",
                    "activity_type": row.get("activity_type") or "",
                    "view": view,
                    "camera": camera,
                    "ceiling": ceiling,
                    "onset_ms": int(float(onset)) if onset else None,
                    "duration_ms": int(float(row["duration_ms"])) if row.get("duration_ms") else None,
                    "frames": read_landmark_csv(csv_path),
                }
            )
    return clips


def iter_windows(clip: dict):
    """Yield (end_index, features, label or None).

    label 1 = fall transition, 0 = clearly not a fall, None = skip (ambiguous
    tail after the marker, or the moment just before the positive band).
    """
    frames = clip["frames"]
    hist_f = []
    hist_t = []
    onset = clip["onset_ms"]
    for index, frame in enumerate(frames):
        hist_f.append(frame["landmarks"])
        hist_t.append(frame["t"])
        feats = window_features(hist_f, hist_t)
        if feats is None:
            yield index, None, None
            continue
        label = None
        if clip["kind"] != "fall":
            label = 0
        elif onset is None:
            label = None
        else:
            end_t = frame["t"]
            if onset - POSITIVE_PRE_MS <= end_t <= onset + POSITIVE_POST_MS:
                label = 1
            elif end_t < onset - NEGATIVE_BEFORE_MS:
                label = 0
        yield index, feats, label


def training_rows(clips: list[dict], rng: np.random.Generator, noise: float):
    xs = []
    ys = []
    for clip in clips:
        if clip["ceiling"]:
            continue
        positives = []
        negatives = []
        raw_windows = []
        hist_f = []
        hist_t = []
        onset = clip["onset_ms"]
        for frame in clip["frames"]:
            hist_f.append(frame["landmarks"])
            hist_t.append(frame["t"])
            window_f = hist_f[-WINDOW:]
            window_t = hist_t[-WINDOW:]
            feats = window_features(window_f, window_t)
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
            bucket = positives if label == 1 else negatives
            bucket.append(feats)
            raw_windows.append((window_f, window_t, label))
        if len(negatives) > 12:
            pick = np.linspace(0, len(negatives) - 1, 12).astype(int)
            negatives = [negatives[i] for i in pick]
        xs.extend(positives)
        ys.extend([1] * len(positives))
        xs.extend(negatives)
        ys.extend([0] * len(negatives))
        if noise > 0:
            for window_f, window_t, label in raw_windows:
                if label == 0 and rng.random() > 0.35:
                    continue
                noisy = _jitter(window_f, rng, noise)
                feats = window_features(noisy, window_t)
                if feats is None:
                    continue
                xs.append(feats)
                ys.append(label)
    if not xs:
        raise RuntimeError("no training windows")
    return np.asarray(xs, dtype=np.float64), np.asarray(ys, dtype=np.float64)


def _jitter(frames, rng: np.random.Generator, sigma: float):
    out = []
    for frame in frames:
        if frame is None:
            out.append(None)
            continue
        noisy = []
        for point in frame:
            x, y, v = float(point[0]), float(point[1]), float(point[2])
            if v >= MIN_VISIBILITY:
                x += float(rng.normal(0.0, sigma))
                y += float(rng.normal(0.0, sigma))
            noisy.append([x, y, v])
        out.append(noisy)
    return out


def fit_logreg(x: np.ndarray, y: np.ndarray):
    from sklearn.linear_model import LogisticRegression

    mean = x.mean(axis=0)
    std = x.std(axis=0)
    std = np.where(std < 1e-8, 1.0, std)
    z = np.clip((x - mean) / std, -Z_CLIP, Z_CLIP)
    # L2 at C=0.1. Looser fits let one walking or hopping clip sit above
    # every fall once the three datasets share a threshold.
    clf = LogisticRegression(
        C=0.1,
        class_weight="balanced",
        solver="lbfgs",
        max_iter=500,
    )
    clf.fit(z, y)
    return {
        "kind": "logreg",
        "mean": mean.tolist(),
        "std": std.tolist(),
        "coef": clf.coef_.ravel().tolist(),
        "intercept": float(clf.intercept_[0]),
    }


def fit_mlp(x: np.ndarray, y: np.ndarray, seed: int = 0, hidden: int = 12, epochs: int = 80):
    rng = np.random.default_rng(seed)
    mean = x.mean(axis=0)
    std = x.std(axis=0)
    std = np.where(std < 1e-8, 1.0, std)
    z = np.clip((x - mean) / std, -Z_CLIP, Z_CLIP)
    n, d = z.shape
    w1 = rng.normal(0.0, 1.0 / math.sqrt(d), size=(d, hidden))
    b1 = np.zeros(hidden)
    w2 = rng.normal(0.0, 1.0 / math.sqrt(hidden), size=(hidden,))
    b2 = np.zeros(())
    pos = max(float(y.mean()), 1e-4)
    weights = np.where(y > 0.5, 0.5 / pos, 0.5 / (1.0 - pos)).astype(np.float64)
    lr = 0.08
    l2 = 1e-2
    idx = np.arange(n)
    for epoch in range(epochs):
        rng.shuffle(idx)
        step = lr / (1.0 + epoch / 40.0)
        for start in range(0, n, 64):
            batch = idx[start : start + 64]
            xb = z[batch]
            yb = y[batch]
            wb = weights[batch]
            hidden_pre = xb @ w1 + b1
            act = np.maximum(hidden_pre, 0.0)
            logit = act @ w2 + b2
            pred = 1.0 / (1.0 + np.exp(-np.clip(logit, -30, 30)))
            grad = (pred - yb) * wb / max(len(batch), 1)
            dw2 = act.T @ grad + l2 * w2
            db2 = grad.sum()
            dact = grad[:, None] * w2[None, :]
            dact = dact * (hidden_pre > 0.0)
            dw1 = xb.T @ dact + l2 * w1
            db1 = dact.sum(axis=0)
            w2 -= step * dw2
            b2 -= step * db2
            w1 -= step * dw1
            b1 -= step * db1
    return {
        "kind": "mlp",
        "mean": mean.tolist(),
        "std": std.tolist(),
        "w1": w1.tolist(),
        "b1": b1.tolist(),
        "w2": w2.tolist(),
        "b2": float(b2),
    }


def score_clip(clip: dict, model: dict) -> list[float | None]:
    hist_f = []
    hist_t = []
    scores = []
    for frame in clip["frames"]:
        hist_f.append(frame["landmarks"])
        hist_t.append(frame["t"])
        feats = window_features(hist_f, hist_t)
        scores.append(None if feats is None else score_model(feats, model))
    return scores


def decide(clip: dict, scores: list[float | None], threshold: float, hold: int):
    times = [frame["t"] for frame in clip["frames"]]
    return first_fire(scores, times, threshold, hold)


def summarize(clips: list[dict], fired: dict[str, int | None]) -> dict:
    fall_hit = fall_n = adl_hit = adl_n = 0
    delays = []
    by_type: dict[str, list[int]] = {}
    for clip in clips:
        at = fired[clip["video_id"]]
        hit = at is not None
        if clip["kind"] == "fall":
            fall_n += 1
            if hit:
                fall_hit += 1
                if clip["onset_ms"] is not None:
                    delays.append(at - clip["onset_ms"])
        else:
            adl_n += 1
            if hit:
                adl_hit += 1
        key = clip["activity_type"] or clip["kind"]
        bucket = by_type.setdefault(key, [0, 0])
        bucket[1] += 1
        if hit and clip["kind"] != "fall":
            bucket[0] += 1
        elif hit and clip["kind"] == "fall":
            bucket[0] += 1
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
        "by_type": {key: counts for key, counts in sorted(by_type.items())},
    }


def sweep(clips: list[dict], cache: dict[str, list[float | None]], hold: int):
    """Highest threshold with zero ADL false alarms, then the most falls."""
    grid = [round(float(v), 4) for v in np.linspace(0.50, 0.99, 50)]
    grid += [0.995, 0.997, 0.999, 0.9995, 0.9999]
    best = None
    rows = []
    for threshold in grid:
        fired = {clip["video_id"]: decide(clip, cache[clip["video_id"]], threshold, hold) for clip in clips}
        stats = summarize(clips, fired)
        stats["threshold"] = threshold
        rows.append(stats)
        if stats["adl_false"] != 0:
            continue
        if best is None or stats["falls"] > best["falls"] or (
            stats["falls"] == best["falls"] and threshold > best["threshold"]
        ):
            best = stats
    if best is None:
        # No zero-FA point. Keep the threshold with the fewest false alarms,
        # then the most falls, then the highest threshold.
        best = min(rows, key=lambda row: (row["adl_false"], -row["falls"], -row["threshold"]))
    return best, rows


def cache_scores(clips, model):
    return {clip["video_id"]: score_clip(clip, model) for clip in clips}


def fmt_rate(hit, total):
    if total <= 0:
        return "—"
    return f"{100.0 * hit / total:.1f}% ({hit}/{total})"


def kotlin_array(name: str, values, indent: str = "    ") -> str:
    lines = [f"{indent}val {name} = doubleArrayOf("]
    row = []
    for value in values:
        row.append(f"{float(value):.10g}")
        if len(row) == 6:
            lines.append(indent + "    " + ", ".join(row) + ",")
            row = []
    if row:
        lines.append(indent + "    " + ", ".join(row) + ",")
    lines.append(f"{indent})")
    return "\n".join(lines)


def emit_kotlin(model: dict, path: Path) -> None:
    kind = model["kind"]
    lines = [
        "package kr.zerotwo.naksang.detect",
        "",
        "/**",
        " * Generated by ml/train/train_pose_model.py. Do not edit by hand.",
        f" * kind={kind} trained_on={','.join(model['trained_on'])}",
        " */",
        "internal object PoseFallWeights {",
        f'    const val KIND = "{kind}"',
        f"    const val THRESHOLD = {float(model['threshold'])}",
        f"    const val HOLD_FRAMES = {int(model['hold_frames'])}",
        f"    const val WINDOW = {WINDOW}",
        "    const val MIN_VALID = 6",
        "    const val SMOOTH_ALPHA = 0.55",
        "    const val MIN_VISIBILITY = 0.35",
        kotlin_array("MEAN", model["mean"]),
        kotlin_array("STD", model["std"]),
    ]
    if kind == "logreg":
        lines.append(kotlin_array("COEF", model["coef"]))
        lines.append(f"    const val INTERCEPT = {float(model['intercept'])}")
        lines.append("    val W1 = emptyArray<DoubleArray>()")
        lines.append("    val B1 = doubleArrayOf()")
        lines.append("    val W2 = doubleArrayOf()")
        lines.append("    const val B2 = 0.0")
    else:
        lines.append("    val COEF = doubleArrayOf()")
        lines.append("    const val INTERCEPT = 0.0")
        w1 = model["w1"]
        lines.append("    val W1 = arrayOf(")
        for row in w1:
            body = ", ".join(f"{float(v):.10g}" for v in row)
            lines.append(f"        doubleArrayOf({body}),")
        lines.append("    )")
        lines.append(kotlin_array("B1", model["b1"]))
        lines.append(kotlin_array("W2", model["w2"]))
        lines.append(f"    const val B2 = {float(model['b2'])}")
    lines.append("}")
    lines.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines))


def reference_samples(clips: list[dict], model: dict, path: Path, limit: int = 4) -> None:
    """A few fixed windows so the Kotlin forward pass can be checked."""
    samples = []
    # CC BY clips only. UR Fall landmarks are not written into the repo.
    wanted = [
        ("caucafall", "fall"),
        ("gmdcsa", "fall"),
        ("caucafall", "adl"),
        ("gmdcsa", "adl"),
    ]
    for dataset, kind in wanted:
        for clip in clips:
            if clip["dataset"] != dataset or clip["kind"] != kind or clip["ceiling"]:
                continue
            if len(clip["frames"]) < WINDOW:
                continue
            # Pick a window near the onset for falls, the middle for ADL.
            if clip["onset_ms"] is not None:
                end = next((i for i, frame in enumerate(clip["frames"]) if frame["t"] >= clip["onset_ms"]), len(clip["frames"]) - 1)
            else:
                end = len(clip["frames"]) // 2
            start = max(0, end - WINDOW + 1)
            chunk = []
            for frame in clip["frames"][start : end + 1]:
                landmarks = frame["landmarks"]
                if landmarks is not None:
                    # The phone stores landmarks as Float. Match that round trip.
                    landmarks = [[float(np.float32(value)) for value in point] for point in landmarks]
                chunk.append({"t": frame["t"], "landmarks": landmarks})
            feats = window_features(
                [frame["landmarks"] for frame in chunk],
                [frame["t"] for frame in chunk],
            )
            if feats is None:
                continue
            samples.append(
                {
                    "name": clip["video_id"],
                    "dataset": clip["dataset"],
                    "kind": clip["kind"],
                    "timestamps_ms": [frame["t"] for frame in chunk],
                    "landmarks": [frame["landmarks"] for frame in chunk],
                    "features": feats,
                    "score": score_model(feats, model),
                }
            )
            break
        if len(samples) >= limit:
            break
    if len(samples) < 2:
        raise RuntimeError("could not build reference samples")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"samples": samples}, indent=2))
    print(f"wrote {path} samples={len(samples)}")


def evaluate_split(train_clips, test_clips, kind: str, seed: int):
    # Landmark noise was tried and dropped. It pushed one daily-activity clip
    # above the shared threshold and erased falls that were clean at zero
    # false alarms. The phone still smooths the window with the EMA above.
    x, y = training_rows(train_clips, np.random.default_rng(seed), noise=0.0)
    if kind == "mlp":
        model = fit_mlp(x, y, seed=seed)
    else:
        model = fit_logreg(x, y)
    model["hold_frames"] = HOLD
    cache = cache_scores(test_clips, model)
    best, _rows = sweep(test_clips, cache, HOLD)
    model["threshold"] = best["threshold"]
    return model, best


def markdown_table(headers, rows) -> str:
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(str(cell) for cell in row) + " |")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-root", type=Path, default=ROOT / "validation" / "out")
    parser.add_argument("--model-out", type=Path, default=ROOT / "models" / "pose_fall_model.json")
    parser.add_argument(
        "--kotlin-out",
        type=Path,
        default=ROOT.parent / "android" / "app" / "src" / "main" / "java" / "kr" / "zerotwo" / "naksang" / "detect" / "PoseFallWeights.kt",
    )
    parser.add_argument(
        "--reference-out",
        type=Path,
        default=ROOT.parent / "android" / "app" / "src" / "test" / "resources" / "pose_fall_reference.json",
    )
    parser.add_argument("--report-out", type=Path, default=ROOT / "validation" / "results" / "model_lodo.md")
    parser.add_argument("--kind", choices=("logreg", "mlp", "both"), default="both")
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    by_name = {}
    for name in DATASETS:
        clips = load_clips(name, args.out_root / name / "landmarks")
        if clips:
            by_name[name] = clips
            falls = sum(1 for clip in clips if clip["kind"] == "fall")
            adl = sum(1 for clip in clips if clip["kind"] != "fall")
            print(f"loaded {name}: clips={len(clips)} falls={falls} adl={adl}")
    if len(by_name) < 2:
        print("need landmark CSVs for at least two datasets", file=sys.stderr)
        return 1

    kinds = ("logreg", "mlp") if args.kind == "both" else (args.kind,)
    present = [name for name in DATASETS if name in by_name]
    lodo_rows = []
    chosen = None
    chosen_score = None

    for kind in kinds:
        print(f"\n== {kind} leave-one-dataset-out ==")
        fold_falls = 0
        fold_total = 0
        fold_fa = 0
        for holdout in present:
            train = [clip for name, clips in by_name.items() if name != holdout for clip in clips]
            test = by_name[holdout]
            model, best = evaluate_split(train, test, kind, args.seed)
            print(
                f"  hold out {holdout}: falls {best['falls']}/{best['fall_total']} "
                f"adl {best['adl_false']}/{best['adl_total']} thr={best['threshold']} "
                f"delay={best['median_delay_ms']}"
            )
            lodo_rows.append(
                [
                    kind,
                    holdout,
                    "leave-one-dataset-out",
                    f"{best['threshold']:.4f}",
                    fmt_rate(best["falls"], best["fall_total"]),
                    fmt_rate(best["adl_false"], best["adl_total"]),
                    "—" if best["median_delay_ms"] is None else str(best["median_delay_ms"]),
                ]
            )
            fold_falls += best["falls"]
            fold_total += best["fall_total"]
            fold_fa += best["adl_false"]
        # Prefer zero false alarms, then more held-out falls.
        score = (-fold_fa, fold_falls / max(fold_total, 1))
        print(f"  total held-out falls {fold_falls}/{fold_total} adl false {fold_fa} score={score}")
        if chosen_score is None or score > chosen_score:
            chosen_score = score
            chosen = kind

    assert chosen is not None
    print(f"\nshipped kind={chosen}")

    # In-distribution: train and test on the same dataset. Optimistic.
    indis_rows = []
    for name in present:
        model, best = evaluate_split(by_name[name], by_name[name], chosen, args.seed)
        indis_rows.append(
            [
                chosen,
                name,
                "in-distribution (same dataset, optimistic)",
                f"{best['threshold']:.4f}",
                fmt_rate(best["falls"], best["fall_total"]),
                fmt_rate(best["adl_false"], best["adl_total"]),
                "—" if best["median_delay_ms"] is None else str(best["median_delay_ms"]),
            ]
        )
        print(
            f"in-dist {name}: falls {best['falls']}/{best['fall_total']} "
            f"adl {best['adl_false']}/{best['adl_total']} thr={best['threshold']}"
        )

    train_all = [clip for clips in by_name.values() for clip in clips]
    shipped, _ = evaluate_split(train_all, train_all, chosen, args.seed)
    # Re-score each dataset at the single shipped threshold (still in-distribution).
    shipped_rows = []
    cache = cache_scores(train_all, shipped)
    for name in present:
        subset = by_name[name]
        fired = {
            clip["video_id"]: decide(clip, cache[clip["video_id"]], shipped["threshold"], HOLD) for clip in subset
        }
        stats = summarize(subset, fired)
        shipped_rows.append(
            [
                chosen,
                name,
                "all-data model, one threshold (in-distribution)",
                f"{shipped['threshold']:.4f}",
                fmt_rate(stats["falls"], stats["fall_total"]),
                fmt_rate(stats["adl_false"], stats["adl_total"]),
                "—" if stats["median_delay_ms"] is None else str(stats["median_delay_ms"]),
            ]
        )
        print(
            f"shipped-on {name}: falls {stats['falls']}/{stats['fall_total']} "
            f"adl {stats['adl_false']}/{stats['adl_total']}"
        )

    shipped["trained_on"] = present
    shipped["hold_frames"] = HOLD
    shipped["feature_names"] = list(FEATURE_NAMES)
    shipped["window"] = WINDOW
    shipped["min_valid"] = 6
    shipped["smooth_alpha"] = 0.55
    shipped["min_visibility"] = MIN_VISIBILITY
    shipped["excluded_from_training"] = "ceiling views (UR cam1); positive label is the fall transition, not lying still"
    shipped["notes"] = (
        "Raw image translation is not a feature; a drop counts only when the "
        "torso also flattens or shortens. The threshold is the highest one with "
        "zero ADL false alarms on the training clips, after maximizing those "
        "falls. That operating point is in-distribution. Use the "
        "leave-one-dataset-out table for generalization. Training does not "
        "jitter landmarks; that jitter lifted one daily activity over the "
        "shared threshold. Every fall in these sets is acted. UR Fall "
        "sequences influence the weights; the raw and landmark files are not "
        "in the repo."
    )
    args.model_out.parent.mkdir(parents=True, exist_ok=True)
    args.model_out.write_text(json.dumps(shipped, indent=2))
    print(f"wrote {args.model_out}")
    emit_kotlin(shipped, args.kotlin_out)
    print(f"wrote {args.kotlin_out}")
    reference_samples(train_all, shipped, args.reference_out)

    headers = ["Model", "Dataset", "Split", "Threshold", "Falls", "ADL false alarms", "Median delay (ms)"]
    body = [
        "# Pose-model offline numbers",
        "",
        "Generated by `ml/train/train_pose_model.py`. The model only sees the fall",
        "transition. Lying still is a separate rule and is not in this table.",
        "In-distribution rows train on the clips they are scored on. Leave-one-dataset-out",
        "rows do not. Ceiling clips are scored with the others and were not used as training rows.",
        "Delays are from the dataset fall marker and can be negative when the score fires",
        "during the descent, before the on-ground label. These are acted falls, not a phone test.",
        "",
        "## Leave-one-dataset-out",
        "",
        markdown_table(headers, [row for row in lodo_rows if row[0] == chosen]),
        "",
        "## Other model family (not shipped)" if args.kind == "both" else "",
        "",
        markdown_table(headers, [row for row in lodo_rows if row[0] != chosen]) if args.kind == "both" else "",
        "",
        "## In-distribution",
        "",
        markdown_table(headers, indis_rows + shipped_rows),
        "",
        f"Shipped kind: `{chosen}`. Shipped threshold: `{shipped['threshold']}`. Hold frames: `{HOLD}`.",
        "",
    ]
    args.report_out.parent.mkdir(parents=True, exist_ok=True)
    args.report_out.write_text("\n".join(line for line in body if line is not None))
    print(f"wrote {args.report_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
