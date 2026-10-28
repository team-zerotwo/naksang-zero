#!/usr/bin/env python3
"""Fit the shipped pose models on CAUCAFall, GMDCSA-24, and UR Fall side views.

UR ceiling clips stay out of the fit: image y is not gravity there, and there
is no ceiling ADL set. The thresholds are the highest ones with zero ADL false
alarms on the clips that were fit, after the most falls. That choice sees UR
side ADL. The honest unseen number is leave-one-dataset-out in
ml/validation/EXPERIMENTS.md, which does not. Kotlin evaluates both scores.

    ml/validation/.venv/bin/python ml/train/export_shipped.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "train"))

import experiment  # noqa: E402
import features  # noqa: E402
from experiment import as_legacy, cache_any, decide, fit_kind  # noqa: E402
from features import FEATURE_NAMES, score_model, window_features  # noqa: E402
from holdout import load_clips, summarize  # noqa: E402
from train_pose_model import emit_kotlin, reference_samples  # noqa: E402


def best_threshold(clips, cache):
    grid = [round(float(v), 4) for v in np.linspace(0.50, 0.99, 50)]
    grid += [0.995, 0.997, 0.999, 0.9995]
    best = None
    best_thr = None
    best_fired = None
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
            best_fired = fired
    return best, best_thr, best_fired


def flatten_trees(model) -> dict:
    """Walk that matches sklearn predict_proba on the standardized features."""
    clf = model["clf"]
    feature = []
    threshold = []
    left = []
    right = []
    value = []
    offsets = []
    for estimator in clf.estimators_.ravel():
        tree = estimator.tree_
        offsets.append(len(feature))
        for node in range(tree.node_count):
            feature.append(int(tree.feature[node]))
            threshold.append(float(tree.threshold[node]))
            left.append(int(tree.children_left[node]))
            right.append(int(tree.children_right[node]))
            value.append(float(tree.value[node][0][0]))
    return {
        "feature": feature,
        "threshold": threshold,
        "left": left,
        "right": right,
        "value": value,
        "offsets": offsets,
        "learning_rate": float(clf.learning_rate),
        "n_trees": len(offsets),
    }


def gbdt_raw(z, flat, init: float) -> float:
    raw = init
    lr = flat["learning_rate"]
    offsets = flat["offsets"] + [len(flat["feature"])]
    for tree in range(flat["n_trees"]):
        node = 0
        start = offsets[tree]
        while flat["left"][start + node] != -1:
            feat = flat["feature"][start + node]
            if z[feat] <= flat["threshold"][start + node]:
                node = flat["left"][start + node]
            else:
                node = flat["right"][start + node]
        raw += lr * flat["value"][start + node]
    return raw


def gbdt_init(clf) -> float:
    """Log-odds of the prior, before any tree."""
    prior = getattr(clf.init_, "class_prior_", None)
    if prior is not None:
        p = float(np.ravel(prior)[-1])
        p = min(max(p, 1e-6), 1.0 - 1e-6)
        return float(np.log(p / (1.0 - p)))
    if hasattr(clf.init_, "constant_"):
        return float(np.ravel(clf.init_.constant_)[0])
    raise RuntimeError(f"cannot read gbdt init from {type(clf.init_)}")


def check_gbdt(model, flat, init: float, x: np.ndarray) -> None:
    mean = np.asarray(model["mean"])
    std = np.asarray(model["std"])
    z = np.clip((x - mean) / std, -8.0, 8.0)
    got = np.array([features.stable_sigmoid(gbdt_raw(row, flat, init)) for row in z[:8]])
    expect = model["clf"].predict_proba(z[:8])[:, 1]
    if np.max(np.abs(got - expect)) > 1e-6:
        raise RuntimeError(f"gbdt walk drifted max {np.max(np.abs(got - expect))}")


def kotlin_gbdt(flat, init: float, threshold: float) -> str:
    def arr(name, values, fmt):
        lines = [f"    val {name} = {fmt}("]
        row = []
        for value in values:
            row.append(value)
            if len(row) == 8:
                lines.append("        " + ", ".join(row) + ",")
                row = []
        if row:
            lines.append("        " + ", ".join(row) + ",")
        lines.append("    )")
        return "\n".join(lines)

    ints = lambda xs: [str(int(v)) for v in xs]
    def doubles(xs):
        out = []
        for value in xs:
            text = f"{float(value):.10g}"
            if "." not in text and "e" not in text.lower():
                text += ".0"
            out.append(text)
        return out
    return "\n".join(
        [
            f"    const val GBDT_THRESHOLD = {float(threshold)}",
            f"    const val GBDT_LEARNING_RATE = {flat['learning_rate']}",
            f"    const val GBDT_INIT = {init}",
            f"    const val GBDT_TREES = {flat['n_trees']}",
            arr("GBDT_OFFSET", ints(flat["offsets"]), "intArrayOf"),
            arr("GBDT_FEATURE", ints(flat["feature"]), "intArrayOf"),
            arr("GBDT_LEFT", ints(flat["left"]), "intArrayOf"),
            arr("GBDT_RIGHT", ints(flat["right"]), "intArrayOf"),
            arr("GBDT_NODE_THRESHOLD", doubles(flat["threshold"]), "doubleArrayOf"),
            arr("GBDT_VALUE", doubles(flat["value"]), "doubleArrayOf"),
        ]
    )


def emit_both(logreg: dict, gbdt_kotlin: str, path: Path) -> None:
    # Reuse the logistic emitter, then append the trees before the closing brace.
    from train_pose_model import emit_kotlin

    emit_kotlin(logreg, path)
    text = path.read_text()
    if not text.rstrip().endswith("}"):
        raise RuntimeError("unexpected kotlin weights")
    body = text.rstrip()[:-1].rstrip()
    path.write_text(body + "\n" + gbdt_kotlin + "\n}\n")


def main() -> int:
    features.WINDOW = 10
    experiment.FEATURE_FN = features.window_features
    experiment.AUGMENT = False
    by_name = {}
    for name in ("caucafall", "gmdcsa", "urfall"):
        raw = load_clips(name, ROOT / "validation" / "out" / name / "landmarks")
        if raw:
            by_name[name] = [as_legacy(clip) for clip in raw]
            print(f"loaded {name} {len(by_name[name])}", flush=True)
    if "caucafall" not in by_name or "gmdcsa" not in by_name:
        print("need CAUCAFall and GMDCSA landmarks", file=sys.stderr)
        return 1
    train = by_name["caucafall"] + by_name["gmdcsa"]
    if "urfall" in by_name:
        side = [clip for clip in by_name["urfall"] if not clip.get("ceiling")]
        train.extend(side)
        print(f"UR side clips in the fit {len(side)}", flush=True)
    fit_kwargs = {"skip_datasets": (), "skip_ceiling": True}
    logreg = fit_kind("logreg", train, 7, **fit_kwargs)
    gbdt = fit_kind("gbdt", train, 7, **fit_kwargs)
    lr_stats, lr_thr, _ = best_threshold(train, cache_any(train, logreg))
    gb_stats, gb_thr, _ = best_threshold(train, cache_any(train, gbdt))
    print(f"logreg thr {lr_thr} {lr_stats['falls']}/{lr_stats['fall_total']} adl {lr_stats['adl_false']}")
    print(f"gbdt thr {gb_thr} {gb_stats['falls']}/{gb_stats['fall_total']} adl {gb_stats['adl_false']}")
    if lr_thr is None or gb_thr is None:
        print("no zero-ADL threshold", file=sys.stderr)
        return 1
    x, _y = experiment.training_matrix(train, **fit_kwargs)
    init = gbdt_init(gbdt["clf"])
    flat = flatten_trees(gbdt)
    check_gbdt(gbdt, flat, init, x)
    print("gbdt walk matches sklearn")
    logreg["threshold"] = lr_thr
    logreg["hold_frames"] = 2
    logreg["trained_on"] = ["caucafall", "gmdcsa", "urfall-side"]
    logreg["feature_names"] = list(FEATURE_NAMES)
    logreg["gbdt_threshold"] = gb_thr
    kotlin_path = (
        ROOT.parent
        / "android"
        / "app"
        / "src"
        / "main"
        / "java"
        / "kr"
        / "zerotwo"
        / "naksang"
        / "detect"
        / "PoseFallWeights.kt"
    )
    emit_both(logreg, kotlin_gbdt(flat, init, gb_thr), kotlin_path)
    print(f"wrote {kotlin_path}")
    model_path = ROOT / "models" / "pose_fall_model.json"
    public = {key: value for key, value in logreg.items() if key != "clf"}
    public["notes"] = (
        "Trained on CAUCAFall, GMDCSA-24, and UR Fall side views. "
        "UR Fall is CC BY-NC-SA 4.0, so these weights are for non-commercial use. "
        "Ceiling clips are not training rows. "
        "A second gradient-boosted tree uses the same features; its threshold is gbdt_threshold. "
        "The thresholds were chosen on these same clips. Leave-one-dataset-out is the unseen score."
    )
    model_path.write_text(json.dumps(public, indent=2))
    reference_samples(train, logreg, ROOT.parent / "android" / "app" / "src" / "test" / "resources" / "pose_fall_reference.json")
    # Attach gbdt scores to the reference so the unit test can check the trees.
    ref_path = ROOT.parent / "android" / "app" / "src" / "test" / "resources" / "pose_fall_reference.json"
    payload = json.loads(ref_path.read_text())
    for sample in payload["samples"]:
        z = features.standardize(sample["features"], gbdt["mean"], gbdt["std"])
        sample["gbdt_score"] = features.stable_sigmoid(gbdt_raw(z, flat, init))
    ref_path.write_text(json.dumps(payload, indent=2))
    print(f"wrote {ref_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
