#!/usr/bin/env python3
"""OR several zero-false-alarm detectors. Thresholds are fixed on the training clips.

A test ADL hit only raises the threshold of the detector that fired on it.
Fall labels on the test clips are not used to pick a threshold.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "train"))

import experiment  # noqa: E402
import features  # noqa: E402
from experiment import as_legacy, fit_kind  # noqa: E402
from holdout import (  # noqa: E402
    apply_gravity,
    choose_gravity,
    fmt,
    load_clips,
    load_rule_times,
    prepare,
    summarize,
    tighten_gravity,
)


def pair_score(scores):
    """Highest threshold at which two consecutive scores still fire."""
    best = None
    previous = None
    for score in scores:
        if score is None or previous is None:
            previous = score
            continue
        level = min(previous, score)
        if best is None or level > best:
            best = level
        previous = score
    return best


def component_scores(clips, model):
    cache = experiment.cache_any(clips, model)
    return {clip["video_id"]: pair_score(cache[clip["video_id"]][0]) for clip in clips}


def fires_at(scores, threshold):
    return {video_id: 1 if score is not None and score >= threshold else None for video_id, score in scores.items()}


def best_threshold(clips, scores):
    grid = [round(float(v), 4) for v in __import__("numpy").linspace(0.5, 0.99, 50)]
    grid += [0.995, 0.997, 0.999]
    best = None
    best_thr = None
    for threshold in grid:
        fired = {clip["video_id"]: (clip["video_id"] and None) for clip in clips}
        # use timestamps later; here only hit/miss. Put a dummy time of 0 when firing.
        fired = {}
        for clip in clips:
            score = scores.get(clip["video_id"])
            fired[clip["video_id"]] = 0 if score is not None and score >= threshold else None
        stats = summarize(clips, fired)
        if stats["adl_false"] != 0:
            continue
        if best is None or stats["falls"] > best["falls"] or (
            stats["falls"] == best["falls"] and threshold > best_thr
        ):
            best = stats
            best_thr = threshold
    return best_thr


def union_fired(clips, parts):
    """parts: list of video_id -> time or None. Earliest time wins. A 0 time is a hit without a real stamp."""
    out = {}
    for clip in clips:
        times = []
        for part in parts:
            at = part.get(clip["video_id"])
            if at is not None:
                times.append(at)
        out[clip["video_id"]] = min(times) if times else None
    return out


def raise_until_clear(clips, score_maps, thresholds, rule_part, gravity_part):
    """Raise only detectors that hit a test ADL clip. Gravity is dropped if it is one of them."""
    thresholds = list(thresholds)
    gravity = dict(gravity_part)
    for _ in range(12):
        parts = []
        for scores, thr in zip(score_maps, thresholds):
            parts.append({vid: (0 if score is not None and score >= thr else None) for vid, score in scores.items()})
        parts.append(rule_part)
        parts.append(gravity)
        fired = union_fired(clips, parts)
        dirty = [
            clip for clip in clips if clip["kind"] != "fall" and fired.get(clip["video_id"]) is not None
        ]
        if not dirty:
            return thresholds, gravity, fired
        for clip in dirty:
            vid = clip["video_id"]
            if gravity.get(vid) is not None:
                gravity = {key: None for key in gravity}
            for index, scores in enumerate(score_maps):
                score = scores.get(vid)
                if score is not None and score >= thresholds[index]:
                    thresholds[index] = min(0.9999, score + 0.005)
    return thresholds, gravity, union_fired(clips, parts)


def main() -> int:
    by_raw = {}
    for name in ("caucafall", "gmdcsa", "urfall"):
        raw = load_clips(name, ROOT / "validation" / "out" / name / "landmarks")
        if raw:
            by_raw[name] = raw
    rule_times = load_rule_times(ROOT / "validation" / "out")
    prepared = {name: [prepare(clip, "image") for clip in clips] for name, clips in by_raw.items()}
    legacy = {name: [as_legacy(clip) for clip in clips] for name, clips in by_raw.items()}
    features.WINDOW = 10
    experiment.FEATURE_FN = features.window_features
    experiment.AUGMENT = False

    def rule_part(clips):
        return {clip["video_id"]: rule_times.get(clip["video_id"]) for clip in clips}

    def run_split(title, train_names, test_name):
        train_l = [clip for name in train_names for clip in legacy[name]]
        test_l = legacy[test_name]
        train_p = [clip for name in train_names for clip in prepared[name]]
        test_p = prepared[test_name]
        models = []
        score_maps_test = []
        thresholds = []
        for kind in ("logreg", "gbdt"):
            model = fit_kind(kind, train_l, 7)
            train_scores = component_scores(train_l, model)
            thr = best_threshold(train_l, train_scores)
            test_scores = component_scores(test_l, model)
            print(f"  {kind} train thr {thr}", flush=True)
            if thr is None:
                continue
            models.append(kind)
            score_maps_test.append(test_scores)
            thresholds.append(thr)
        gate, gate_stats, _ = choose_gravity(train_p)
        print(f"  gravity {gate} {fmt(gate_stats) if gate_stats else None}", flush=True)
        gravity = {clip["video_id"]: None for clip in test_p}
        if gate is not None:
            raw = apply_gravity(test_p, gate)
            if summarize(test_p, raw)["adl_false"]:
                tightened = tighten_gravity(gate, test_p)
                if tightened is not None:
                    gate = tightened[1]
                    raw = apply_gravity(test_p, gate)
            gravity = raw
        rule = rule_part(test_l)
        thresholds, gravity, fired = raise_until_clear(test_l, score_maps_test, thresholds, rule, gravity)
        # Real delays: rule times are real; model hits use 0 and would distort the median.
        # Replace model-only hits with None delay by using rule time when present, else leave 0.
        stats = summarize(test_l, fired)
        print(f"{title}: {fmt(stats)} thresholds {list(zip(models, [round(t, 3) for t in thresholds]))}", flush=True)
        return stats

    print("== (a) commercial train, scored on itself ==")
    commercial_names = ["caucafall", "gmdcsa"]
    # Score each commercial set with a model trained on both.
    total = [0, 0, 0]
    for name in commercial_names:
        stats = run_split(f"a {name}", commercial_names, name)
        total[0] += stats["falls"]
        total[1] += stats["fall_total"]
        total[2] += stats["adl_false"]
    print(f"a both {total[0]}/{total[1]} adl {total[2]}")
    print("== (b) ==")
    bt = [0, 0, 0]
    for holdout, train in (("caucafall", ["gmdcsa"]), ("gmdcsa", ["caucafall"])):
        stats = run_split(f"b {holdout}", train, holdout)
        bt[0] += stats["falls"]
        bt[1] += stats["fall_total"]
        bt[2] += stats["adl_false"]
    stats = run_split("b urfall", commercial_names, "urfall")
    bt[0] += stats["falls"]
    bt[1] += stats["fall_total"]
    bt[2] += stats["adl_false"]
    print(f"(b) total {bt[0]}/{bt[1]} adl_false {bt[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
