#!/usr/bin/env python3
"""Sample UR Fall Detection sequences through the same pose path.

License on https://fenix.ur.edu.pl/~mkepski/ds/uf.html is not stated. The page
asks for a citation and publishes 70 sequences (30 falls + 40 ADLs). Use the
output only for this internal replay. Do not commit the videos, the landmark
CSVs, or frames.

The page's mp4 previews are 640x240. The RGB PNG archives are 640x480 Kinect
frames and are about 50MB each, so the full RGB set is several gigabytes.
This script restores the preview aspect with scale=640:480 before pose. That is
a compressed stand-in for the PNG sequence, not the original pixels.

Camera 0 is parallel to the floor (a side view). Camera 1, falls only, is
ceiling mounted. ADL has no ceiling camera, so a ceiling false-alarm rate
cannot be measured here. Cameras are not strictly synchronized; ceiling onset
reuses the camera-0 "lying" frame index.

Fall onset is the first feature-CSV frame whose label is 1 (person lying on
the ground). Label 0 is the temporary in-between pose and is not the onset.
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

from pose_common import (
    SAMPLE_FPS,
    blank_manifest_row,
    ensure_model,
    make_landmarker,
    probe_video,
    write_landmark_csv,
    write_manifest,
)


def load_labels(path: Path) -> dict[str, list[int]]:
    groups: dict[str, list[tuple[int, int]]] = defaultdict(list)
    with path.open(newline="") as handle:
        for row in csv.reader(handle):
            if len(row) < 3:
                continue
            frame = int(float(row[1]))
            label = int(float(row[2]))
            groups[row[0]].append((frame, label))
    ordered = {}
    for name, pairs in groups.items():
        pairs.sort(key=lambda item: item[0])
        ordered[name] = [label for _, label in pairs]
    return ordered


def onset_frame(labels: list[int]) -> int | None:
    for index, label in enumerate(labels, start=1):
        if label == 1:
            return index
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parent
    parser.add_argument("--data", type=Path, default=root / "data" / "urfall")
    parser.add_argument("--out", type=Path, default=root / "out" / "urfall" / "landmarks")
    parser.add_argument("--model", type=Path, default=root / "models" / "pose_landmarker_lite.task")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--shard", default="", help="k/n keeps videos whose index modulo n equals k")
    parser.add_argument("--skip-manifest", action="store_true")
    args = parser.parse_args()
    fall_labels = load_labels(args.data / "urfall-cam0-falls.csv")
    adl_labels = load_labels(args.data / "urfall-cam0-adls.csv")
    videos = sorted(args.data.glob("*.mp4"))
    if args.limit:
        videos = videos[: args.limit]
    if args.shard:
        part, total = (int(piece) for piece in args.shard.split("/"))
        videos = [video for index, video in enumerate(videos) if index % total == part]
    if not videos:
        print(f"no mp4 files in {args.data}", file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    model = ensure_model(args.model)
    landmarker = make_landmarker(model)
    rows = []
    failed = 0
    try:
        for index, video in enumerate(videos, start=1):
            stem = video.stem  # fall-01-cam0
            parts = stem.split("-")
            kind = "fall" if stem.startswith("fall") else "adl"
            seq = f"{parts[0]}-{parts[1]}"
            camera = parts[2]
            labels = (fall_labels if kind == "fall" else adl_labels).get(seq, [])
            lying = onset_frame(labels)
            has_lying = any(label == 1 for label in labels)
            video_id = f"ur_{stem.replace('-', '_')}"
            csv_path = args.out / f"{video_id}.csv"
            try:
                info = probe_video(video)
                notes = [
                    "license_unspecified_internal_only",
                    "preview_mp4_640x240_scaled_to_640x480",
                ]
                if camera == "cam1":
                    notes.append("onset_from_cam0_lying_label_cameras_not_strictly_synced")
                if labels and info["frames"] and abs(info["frames"] - len(labels)) > 2:
                    notes.append(f"frame_count_mismatch video={info['frames']} labels={len(labels)}")
                if csv_path.is_file() and not args.force and csv_path.stat().st_size > 0:
                    notes.append("landmarks_reused")
                    analysis_w, analysis_h = info["width"], info["height"]
                else:
                    analysis_w, analysis_h = write_landmark_csv(
                        video,
                        csv_path,
                        info,
                        landmarker,
                        scale="scale=640:480",
                    )
                onset_ms = ""
                if lying is not None and info["fps"] > 0:
                    if info["frames"] and labels and abs(info["frames"] - len(labels)) > 2:
                        duration_ms = info["duration_s"] * 1000
                        onset_ms = str(int(round((lying - 1) * duration_ms / max(len(labels) - 1, 1))))
                    else:
                        onset_ms = str(int(round((lying - 1) * 1000.0 / info["fps"])))
                if kind == "adl" and has_lying:
                    activity_type = "adl_lying"
                elif kind == "adl":
                    activity_type = "adl"
                else:
                    activity_type = "fall"
                view = "ceiling" if camera == "cam1" else "side"
                row = blank_manifest_row()
                row.update(
                    {
                        "video_id": video_id,
                        "subject": seq,
                        "activity": stem,
                        "kind": kind,
                        "activity_type": activity_type,
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
                        "label_frames": str(len(labels)),
                        "onset_frame": "" if lying is None else str(lying),
                        "onset_ms": onset_ms,
                        "notes": "; ".join(notes),
                        "dataset": "urfall",
                        "view": view,
                        "camera": camera,
                        "direction": "",
                    }
                )
            except Exception as exc:  # noqa: BLE001
                failed += 1
                print(f"FAIL {video}: {exc}", flush=True)
                continue
            rows.append(row)
            print(
                f"[{index}/{len(videos)}] {video_id} view={view} onset_ms={row['onset_ms'] or '-'}",
                flush=True,
            )
    finally:
        landmarker.close()
    if args.skip_manifest:
        print(f"shard {args.shard} wrote {len(rows)} csvs, failed={failed}")
        return 1 if failed and not rows else 0
    write_manifest(args.out / "manifest.csv", rows)
    print(f"wrote {args.out / 'manifest.csv'} videos={len(rows)} failed={failed}")
    return 1 if failed and not rows else 0


if __name__ == "__main__":
    sys.exit(main())
