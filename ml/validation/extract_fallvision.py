#!/usr/bin/env python3
"""MediaPipe landmarks for FallVision raw videos.

Harvard Dataverse 10.7910/DVN/75QPKK, CC0. This script reads the raw mp4
archives only (f_raw_*, nf_raw_*). The masked copies are a black backdrop with
YOLO-Pose drawn on, and the published CSVs are 17-point YOLO keypoints, so
neither is the phone's input. Landmarks here are pose_landmarker_lite, IMAGE,
num_poses=1, 5 fps, frames fit inside 640x480.

A FallVision file is a whole fall or a whole daily activity. There is no
authored onset frame. On fall clips the onset used for training windows is the
first time the torso goes from upright (>=58°) to flat (<=30°) inside 1.6 s.
If that never happens, it is the time of the minimum angle when that angle is
<=45°. ADL clips have no onset. The yes/no score still counts a fire anywhere
in the clip. Videos are not committed.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import zlib
from pathlib import Path

from pose_common import (
    MODEL_SHA256,
    SAMPLE_FPS,
    blank_manifest_row,
    ensure_model,
    make_landmarker,
    probe_video,
    write_landmark_csv,
    write_manifest,
)

LS, RS, LH, RH = 11, 12, 23, 24


def classify(path: Path) -> tuple[str, str]:
    blob = "/".join(path.parts).lower()
    if "nf_raw" in blob or "/nf_" in blob:
        kind = "adl"
    elif "f_raw" in blob or "/f_" in blob:
        kind = "fall"
    else:
        kind = "adl"
    if "_b_" in blob or "/bed" in blob:
        activity = "bed"
    elif "_c_" in blob or "/chair" in blob:
        activity = "chair"
    elif "_s_" in blob or "/stand" in blob:
        activity = "standing"
    else:
        activity = "other"
    return kind, activity


def _torso_angle(row) -> float | None:
    """row is timestamp plus image landmarks as flat floats, pose_found at [2]."""
    if row[2] < 0.5:
        return None
    shoulders = []
    hips = []
    for index, bucket in ((LS, shoulders), (RS, shoulders), (LH, hips), (RH, hips)):
        base = 3 + index * 3
        vis = row[base + 2]
        if vis < 0.35:
            continue
        bucket.append((row[base], row[base + 1]))
    if not shoulders or not hips:
        return None
    sx = sum(p[0] for p in shoulders) / len(shoulders)
    sy = sum(p[1] for p in shoulders) / len(shoulders)
    hx = sum(p[0] for p in hips) / len(hips)
    hy = sum(p[1] for p in hips) / len(hips)
    return math.degrees(math.atan2(abs(sy - hy), abs(sx - hx)))


def infer_onset_ms(csv_path: Path) -> str:
    """Fall clips have no authored frame. Locate the drop, do not invent one.

    Prefer the moment the torso reaches 30° after being upright inside 1.6 s.
    Otherwise the time of the minimum angle when the torso has dropped at least
    15° from its first visible sample. A clip that stays upright contributes no
    positive windows.
    """
    import csv

    times = []
    angles = []
    tracked = None
    with csv_path.open(newline="") as handle:
        reader = csv.reader(handle)
        next(reader)
        for cells in reader:
            if not cells or not cells[0]:
                continue
            values = [float(cell) for cell in cells[: 3 + 33 * 3]]
            angle = _torso_angle(values)
            if angle is None:
                tracked = None
            else:
                tracked = angle if tracked is None else 0.55 * angle + 0.45 * tracked
            times.append(int(float(cells[0])))
            angles.append(tracked)
    best_flat = None
    min_angle = None
    min_at = None
    first = None
    for index, (stamp, angle) in enumerate(zip(times, angles)):
        if angle is None:
            continue
        if first is None:
            first = angle
        if min_angle is None or angle < min_angle:
            min_angle = angle
            min_at = stamp
        if angle > 30.0:
            continue
        for prev_t, prev_a in zip(times[: index + 1], angles[: index + 1]):
            if stamp - prev_t > 1600:
                continue
            if prev_a is not None and prev_a >= 58.0:
                best_flat = stamp
                break
        if best_flat is not None:
            break
    if best_flat is not None:
        return str(best_flat)
    if (
        min_at is not None
        and min_angle is not None
        and first is not None
        and first - min_angle >= 15.0
    ):
        return str(min_at)
    return ""


def discover(root: Path) -> list[Path]:
    videos = []
    for pattern in ("*.mp4", "*.mov", "*.avi", "*.mkv", "*.MP4", "*.MOV"):
        videos.extend(root.rglob(pattern))
    return sorted({path.resolve() for path in videos})


def video_id_for(path: Path, root: Path) -> str:
    rel = path.resolve().relative_to(root.resolve())
    stem = "__".join(rel.with_suffix("").parts)
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in stem)
    return f"fv_{safe}"[:180]


def merge_rows(out: Path) -> list[dict]:
    rows = []
    seen = set()
    for path in sorted(out.glob("rows-*.jsonl")):
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if row["video_id"] in seen:
                continue
            seen.add(row["video_id"])
            rows.append(row)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parent
    parser.add_argument("--data", type=Path, default=root / "data" / "fallvision" / "unpacked")
    parser.add_argument("--out", type=Path, default=root / "out" / "fallvision" / "landmarks")
    parser.add_argument("--model", type=Path, default=root / "models" / "pose_landmarker_lite.task")
    parser.add_argument("--shard", default="")
    parser.add_argument("--merge-only", action="store_true")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    if args.merge_only:
        rows = merge_rows(args.out)
        write_manifest(args.out / "manifest.csv", rows)
        print(f"merged {len(rows)} -> {args.out / 'manifest.csv'}", flush=True)
        return 0 if rows else 1
    videos = discover(args.data)
    if args.shard:
        part, total = (int(piece) for piece in args.shard.split("/"))
        videos = [video for video in videos if zlib.crc32(str(video).encode()) % total == part]
    if args.limit:
        videos = videos[: args.limit]
    if not videos:
        print(f"no videos under {args.data}", file=sys.stderr)
        return 1
    model = ensure_model(args.model)
    landmarker = make_landmarker(model)
    rows = []
    failed = 0
    try:
        for index, video in enumerate(videos, start=1):
            vid = video_id_for(video, args.data)
            csv_path = args.out / f"{vid}.csv"
            kind, activity = classify(video)
            try:
                info = probe_video(video)
                if csv_path.is_file() and not args.force and csv_path.stat().st_size > 0:
                    analysis_w = analysis_h = 0
                    notes = "landmarks_reused"
                else:
                    analysis_w, analysis_h = write_landmark_csv(video, csv_path, info, landmarker)
                    notes = ""
                onset = infer_onset_ms(csv_path) if kind == "fall" else ""
            except Exception as exc:  # noqa: BLE001
                failed += 1
                print(f"FAIL {video}: {exc}", flush=True)
                continue
            row = blank_manifest_row()
            row.update(
                {
                    "video_id": vid,
                    "subject": "",
                    "activity": activity,
                    "kind": kind,
                    "activity_type": activity,
                    "csv_file": csv_path.name,
                    "video_relpath": str(video),
                    "source_fps": f"{info['fps']:.4f}",
                    "video_frames": str(info["frames"]),
                    "duration_ms": str(int(round(info["duration_s"] * 1000))),
                    "width": str(info["width"]),
                    "height": str(info["height"]),
                    "analysis_width": str(analysis_w),
                    "analysis_height": str(analysis_h),
                    "sample_fps": str(SAMPLE_FPS),
                    "onset_ms": onset,
                    "notes": notes,
                    "dataset": "fallvision",
                    "view": "handheld",
                    "camera": "phone_or_webcam",
                    "direction": activity,
                }
            )
            rows.append(row)
            if index % 25 == 0 or index == len(videos):
                print(f"[{index}/{len(videos)}] {vid} {kind} onset={onset or '-'}", flush=True)
    finally:
        landmarker.close()
    shard = args.shard.replace("/", "-") if args.shard else "all"
    path = args.out / f"rows-{shard}.jsonl"
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))
    print(f"wrote {path} videos={len(rows)} failed={failed} model={MODEL_SHA256[:12]}", flush=True)
    if not args.shard:
        write_manifest(args.out / "manifest.csv", rows)
    return 1 if failed and not rows else 0


if __name__ == "__main__":
    sys.exit(main())
