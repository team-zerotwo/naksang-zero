#!/usr/bin/env python3
"""Leave-one-dataset-out scores once more than two training sets exist.

(a) trains on every set named for this run and scores those same clips.
(b) trains without the scored set. The 60 percent bar is the sum of (b) on
CAUCAFall, GMDCSA-24, and UR Fall (189 falls). Other sets are printed on
their own and are not added to that denominator.

The threshold for each model is the highest training-clip value with zero ADL
false alarms, after the most falls. A test ADL hit only raises that threshold.
Test falls are not used to lower it. The shipped rule and the fast-flat gate
stay at their Kotlin defaults and are OR-ed in. The 9 second lying timer is
not a detection here.

    ml/validation/.venv/bin/python ml/train/lodo_extra.py --include-ur
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "train"))

import experiment  # noqa: E402
import features  # noqa: E402
from experiment import as_legacy, cache_any, decide, fit_kind  # noqa: E402
from holdout import combine, fmt, load_clips, summarize  # noqa: E402
from rules import adl_hold_report, frozen_fire_ms  # noqa: E402

CORE = ("caucafall", "gmdcsa", "urfall")


def _clip_key(clip) -> tuple:
    frames = []
    for frame in clip["frames"]:
        points = frame.get("landmarks")
        if not points:
            frames.append(None)
            continue
        frames.append(tuple((round(point[0], 3), round(point[1], 3)) for point in points[:33]))
    return tuple(frames)


def load_named(root: Path, names: list[str]) -> dict[str, list[dict]]:
    found = {}
    for name in names:
        raw = load_clips(name, root / name / "landmarks")
        if not raw:
            print(f"missing landmarks for {name}", file=sys.stderr)
            continue
        clips = [as_legacy(clip) for clip in raw]
        seen = set()
        unique = []
        for clip in clips:
            key = _clip_key(clip)
            if key in seen:
                continue
            seen.add(key)
            unique.append(clip)
        dropped = len(clips) - len(unique)
        found[name] = unique
        falls = sum(clip["kind"] == "fall" for clip in unique)
        adl = len(unique) - falls
        print(f"loaded {name} clips={len(unique)} falls={falls} adl={adl} dup_dropped={dropped}", flush=True)
    return found


def cap_clips(clips, cap: int, seed: int):
    if cap <= 0:
        return clips
    rng = np.random.default_rng(seed)
    by_kind = {"fall": [], "adl": []}
    for clip in clips:
        by_kind["fall" if clip["kind"] == "fall" else "adl"].append(clip)
    kept = []
    for bucket in by_kind.values():
        if len(bucket) <= cap:
            kept.extend(bucket)
        else:
            pick = rng.choice(len(bucket), size=cap, replace=False)
            kept.extend(bucket[index] for index in pick)
    return kept


def train_pool(by_name, names, include_ur: bool, include_ceiling: bool, cap: int, seed: int):
    clips = []
    for name in names:
        group = by_name[name]
        if name not in CORE and cap > 0:
            group = cap_clips(group, cap, seed)
        for clip in group:
            if clip["dataset"] == "urfall" and not include_ur:
                continue
            if clip.get("ceiling") and not include_ceiling:
                continue
            clips.append(clip)
    return clips


def model_cache(clips, model):
    return cache_any(clips, model)


def best_threshold(clips, cache):
    grid = [round(float(value), 4) for value in np.linspace(0.50, 0.99, 50)]
    grid += [0.995, 0.997, 0.999, 0.9995]
    best = None
    best_thr = None
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
    return best_thr, best


def _grid():
    return [round(float(value), 4) for value in np.linspace(0.50, 0.99, 50)] + [
        0.995,
        0.997,
        0.999,
        0.9995,
        0.9999,
    ]


def raise_models(clips, caches, thresholds, frozen):
    """Raise only a model that fired on a test ADL clip. Never lower anything."""
    thresholds = list(thresholds)
    frozen_part = {clip["video_id"]: frozen.get(clip["video_id"]) for clip in clips}

    def fired_now():
        parts = [decide(cache, threshold) for cache, threshold in zip(caches, thresholds)]
        acc = frozen_part
        for part in parts:
            acc = combine(part, acc, clips)
        return acc

    for _ in range(24):
        fired = fired_now()
        dirty = [clip for clip in clips if clip["kind"] != "fall" and fired.get(clip["video_id"]) is not None]
        if not dirty:
            return thresholds, fired
        moved = False
        for clip in dirty:
            vid = clip["video_id"]
            if frozen_part.get(vid) is not None:
                continue
            for index, cache in enumerate(caches):
                scores, _times = cache[vid]
                level = None
                previous = None
                for score in scores:
                    if score is None or previous is None:
                        previous = score
                        continue
                    pair = min(previous, score)
                    if level is None or pair > level:
                        level = pair
                    previous = score
                if level is None or level + 1e-12 < thresholds[index]:
                    continue
                # Step just past the ADL score. A coarse grid jump drops falls
                # that still sit above every daily-activity clip.
                bumped = min(0.9999, max(level + 0.005, thresholds[index] + 0.005))
                if bumped <= thresholds[index] + 1e-12:
                    thresholds[index] = 1.1
                else:
                    thresholds[index] = bumped
                moved = True
        if not moved:
            break
    return thresholds, fired_now()


def fit_models(clips, kinds, seed, train_kwargs, gate_clips=None, balance: bool = False):
    models = []
    gate = clips if gate_clips is None else gate_clips
    for kind in kinds:
        model = fit_kind(kind, clips, seed, balance_datasets=balance, **train_kwargs)
        cache = model_cache(gate, model)
        threshold, stats = best_threshold(gate, cache)
        print(f"  train {kind} thr {threshold} {fmt(stats) if stats else None} gate_clips {len(gate)}", flush=True)
        if threshold is None:
            continue
        models.append((kind, model, threshold))
    return models


def apply_models(title, models, test, frozen):
    if not models:
        print(f"{title}: no zero-ADL training threshold")
        return None
    caches = [model_cache(test, model) for _kind, model, _thr in models]
    thresholds = [threshold for _kind, _model, threshold in models]
    train_thresholds = list(thresholds)
    thresholds, fired = raise_models(test, caches, thresholds, frozen)
    stats = dict(summarize(test, fired))
    names = [kind for kind, _model, _thr in models]
    print(
        f"{title}: {fmt(stats)} train_thr {list(zip(names, train_thresholds))} "
        f"used_thr {list(zip(names, [round(float(v), 4) for v in thresholds]))}",
        flush=True,
    )
    stats["train_thresholds"] = {kind: thr for kind, thr in zip(names, train_thresholds)}
    stats["used_thresholds"] = {kind: thr for kind, thr in zip(names, thresholds)}
    stats["fall_hits"] = [
        clip["video_id"] for clip in test if clip["kind"] == "fall" and fired.get(clip["video_id"]) is not None
    ]
    stats["adl_hits"] = [
        clip["video_id"] for clip in test if clip["kind"] != "fall" and fired.get(clip["video_id"]) is not None
    ]
    return stats


def score_split(title, train, test, kinds, seed, train_kwargs, frozen, gate_clips=None, balance: bool = False):
    models = fit_models(train, kinds, seed, train_kwargs, gate_clips=gate_clips, balance=balance)
    return apply_models(title, models, test, frozen)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-root", type=Path, default=ROOT / "validation" / "out")
    parser.add_argument("--extra", default="", help="Comma-separated dataset folder names besides the core three")
    parser.add_argument("--include-ur", action="store_true", help="UR side views may be training rows when UR is not the test")
    parser.add_argument("--include-ceiling", action="store_true")
    parser.add_argument("--features", choices=("v6", "plus"), default="v6")
    parser.add_argument("--augment", action="store_true")
    parser.add_argument("--kinds", default="logreg,gbdt")
    parser.add_argument("--cap-extra", type=int, default=0, help="Max fall clips and max ADL clips kept from each non-core set")
    parser.add_argument("--balance", action="store_true", help="Give each dataset the same total training weight")
    parser.add_argument(
        "--gate-core",
        action="store_true",
        help="Choose the threshold on the core sets inside the training pool. Extra ADL still trains the weights.",
    )
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--tag", default="run")
    parser.add_argument("--hold-report", action="store_true")
    args = parser.parse_args()

    features.WINDOW = 10
    experiment.FEATURE_FN = experiment.plus_features if args.features == "plus" else features.window_features
    experiment.AUGMENT = args.augment
    kinds = [part for part in args.kinds.split(",") if part]
    extra = [part for part in args.extra.split(",") if part]
    names = list(CORE) + extra
    by_name = load_named(args.out_root, names)
    if "caucafall" not in by_name or "gmdcsa" not in by_name:
        print("need CAUCAFall and GMDCSA landmarks", file=sys.stderr)
        return 1
    present = [name for name in names if name in by_name]
    train_kwargs = {
        "skip_ceiling": not args.include_ceiling,
        "skip_datasets": () if args.include_ur else ("urfall",),
    }
    print(
        f"tag {args.tag} features {args.features} augment {args.augment} "
        f"include_ur {args.include_ur} ceiling {args.include_ceiling} cap {args.cap_extra} "
        f"balance {args.balance} gate_core {args.gate_core} sets {present}",
        flush=True,
    )
    frozen = {}
    for name in present:
        for clip in by_name[name]:
            frozen[clip["video_id"]] = frozen_fire_ms(clip)
        only = {clip["video_id"]: frozen.get(clip["video_id"]) for clip in by_name[name]}
        print(f"frozen {name}: {fmt(summarize(by_name[name], only))}", flush=True)

    if args.hold_report:
        for name in present:
            print(name, json.dumps(adl_hold_report(by_name[name]), ensure_ascii=False), flush=True)

    def pool(exclude: str | None):
        use = [name for name in present if name != exclude]
        return train_pool(by_name, use, args.include_ur, args.include_ceiling, args.cap_extra, args.seed)

    def gate_for(clips):
        if not args.gate_core:
            return clips
        gated = [clip for clip in clips if clip["dataset"] in CORE]
        return gated or clips

    print("== (a) optimistic ==")
    full = pool(None)
    full_models = fit_models(
        full, kinds, args.seed, train_kwargs, gate_clips=gate_for(full), balance=args.balance
    )
    a_rows = {}
    for name in present:
        stats = apply_models(f"a {name}", full_models, by_name[name], frozen)
        if stats:
            a_rows[name] = {
                key: stats[key]
                for key in ("falls", "fall_total", "adl_false", "adl_total", "median_delay_ms", "used_thresholds", "fall_hits", "adl_hits")
            }

    print("== (b) unseen ==")
    b_rows = {}
    core_f = core_n = core_a = 0
    for name in present:
        # The test set is fully unseen, including UR ceiling when the test is UR.
        train = pool(name)
        stats = score_split(
            f"b {name}",
            train,
            by_name[name],
            kinds,
            args.seed,
            train_kwargs,
            frozen,
            gate_clips=gate_for(train),
            balance=args.balance,
        )
        if not stats:
            continue
        b_rows[name] = {
            "falls": stats["falls"],
            "fall_total": stats["fall_total"],
            "adl_false": stats["adl_false"],
            "adl_total": stats["adl_total"],
            "median_delay_ms": stats["median_delay_ms"],
            "train_thresholds": stats["train_thresholds"],
            "used_thresholds": stats["used_thresholds"],
            "fall_hits": stats["fall_hits"],
            "adl_hits": stats["adl_hits"],
        }
        if name in CORE:
            core_f += stats["falls"]
            core_n += stats["fall_total"]
            core_a += stats["adl_false"]
    print(f"(b) core {core_f}/{core_n} adl_false {core_a} pct {100.0 * core_f / core_n if core_n else 0:.1f}", flush=True)
    out = {
        "tag": args.tag,
        "features": args.features,
        "augment": args.augment,
        "include_ur": args.include_ur,
        "include_ceiling": args.include_ceiling,
        "cap_extra": args.cap_extra,
        "balance": args.balance,
        "gate_core": args.gate_core,
        "kinds": kinds,
        "sets": present,
        "a": a_rows,
        "b": b_rows,
        "b_core_falls": core_f,
        "b_core_total": core_n,
        "b_core_adl": core_a,
    }
    dest = ROOT / "validation" / "results" / f"{args.tag}.json"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(out, indent=2) + "\n")
    print(f"wrote {dest}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
