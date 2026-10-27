#!/usr/bin/env python3
"""Sample GMDCSA-24 videos and write the same landmark CSVs as CAUCAFall.

Videos and the class-timing CSVs are CC BY 4.0. Record 10.5281/zenodo.12921216
(v2.0, 1,107,543,412 bytes) was checked first. Its Zenodo note says class start
and end times were added in v2.1 (10.5281/zenodo.13354453, 1,107,545,615 bytes,
same CC BY 4.0 license, same clips). This script uses v2.1 so onset is the
labeled start of a Falling interval.

The falls are acted. A Falling label is the interval the authors marked, which
can begin at the loss of balance rather than the moment the body is on the ground.
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
import zipfile
from pathlib import Path

from pose_common import (
    MODEL_SHA256,
    SAMPLE_FPS,
    blank_manifest_row,
    ensure_model,
    fit_inside,
    make_landmarker,
    probe_video,
    write_landmark_csv,
    write_manifest,
)

# "to" is missing in a few v2.1 cells, e.g. Falling (BW)[2.3 6].
INTERVAL_RE = re.compile(
    r"([^\[\];]+?)\[\s*([0-9]*\.?[0-9]+)\s*(?:to\s+)?([0-9]*\.?[0-9]+)\s*\]",
    re.I,
)
BARE_FALL_RE = re.compile(r"\bfall(?:ing)?\b", re.I)

# Room camera heights from Alam et al., Data in Brief 57 (2024) 110892, Table 22.
CAMERA_HEIGHT = {
    "1": "70-90cm",
    "2": "135cm",
    "3": "135cm",
    "4": "64cm",
}


def unzip_dataset(zip_path: Path, dest: Path) -> Path:
    if any(dest.rglob("Fall.csv")) or any(dest.rglob("ADL.csv")):
        return dest
    if not zip_path.is_file():
        raise SystemExit(f"missing {zip_path}")
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(dest)
    return dest


def parse_intervals(text: str) -> list[tuple[str, float, float]]:
    found = []
    for match in INTERVAL_RE.finditer(text or ""):
        label = match.group(1).strip(" ;,")
        found.append((label, float(match.group(2)), float(match.group(3))))
    return found


def read_subject_csv(path: Path) -> list[dict]:
    """v2.1 rows. Semicolons separate clauses inside a field; commas separate fields."""
    raw = path.read_text(encoding="utf-8", errors="replace")
    # Some exports put a trailing comma on the header only.
    rows = list(csv.reader(raw.splitlines()))
    if not rows:
        return []
    header = [cell.strip().strip(",") for cell in rows[0]]
    header = [cell for cell in header if cell]
    parsed = []
    for cells in rows[1:]:
        cells = [cell.strip() for cell in cells]
        while cells and cells[-1] == "":
            cells.pop()
        if not cells or not cells[0].lower().endswith(".mp4"):
            continue
        record = {header[i] if i < len(header) else f"col{i}": cells[i] if i < len(cells) else "" for i in range(max(len(header), len(cells)))}
        # Fall back to positional fields when the header names vary.
        name = cells[0]
        description = ""
        classes = ""
        for key, value in record.items():
            lowered = key.lower()
            if "file" in lowered and value.lower().endswith(".mp4"):
                name = value
            elif "desc" in lowered:
                description = value
            elif "class" in lowered:
                classes = value
        if not classes and len(cells) >= 2 and "[" in cells[-1]:
            classes = cells[-1]
        if not description:
            for cell in cells:
                if cell.lower().endswith(".mp4") or re.fullmatch(r"[0-9.]+", cell):
                    continue
                if "[" in cell and "to" in cell:
                    continue
                if len(cell) > len(description):
                    description = cell
        parsed.append({"file": name, "description": description, "classes": classes, "raw": " | ".join(cells)})
    return parsed


def is_fall_label(label: str) -> bool:
    return "fall" in label.strip().lower()


def fall_onset(classes: str, description: str) -> tuple[float | None, str]:
    """Seconds when the Falling interval starts, plus a note when it was inferred.

    One Subject 4 cell says `Falling (FW)` with no times and `Sitting[0 to 1.7]`.
    The fall is taken to start when that adjacent class ends.
    """
    text = classes or description
    intervals = parse_intervals(text) or parse_intervals(description)
    fall_intervals = [span for span in intervals if is_fall_label(span[0])]
    if fall_intervals:
        return min(span[1] for span in fall_intervals), ""
    if BARE_FALL_RE.search(text) or BARE_FALL_RE.search(description):
        ends = [span[2] for span in intervals if not is_fall_label(span[0])]
        if ends:
            return max(ends), "onset_inferred_from_adjacent_class"
        return None, "no_fall_timing"
    return None, ""


def direction_of(text: str, fall_labels: list[str] | None = None) -> str:
    """Prefer an explicit left/right in the description, else the first fall code."""
    lowered = text.lower()
    if "left" in lowered and "right" not in lowered:
        return "left"
    if "right" in lowered and "left" not in lowered:
        return "right"
    blob = " ".join(fall_labels or []) + " " + lowered
    if "(bw)" in blob or "backward" in blob:
        return "backward"
    if "(fw)" in blob or "forward" in blob:
        return "forward"
    if "(sw)" in blob or "sideways" in blob or "side" in blob:
        return "sideways"
    return ""


def view_of(text: str) -> str:
    lowered = text.lower()
    if "side view" in lowered:
        return "side"
    if "front view" in lowered or "face towards the camera" in lowered or "face towards camera" in lowered:
        return "front"
    if "towards ceiling" in lowered or "toward ceiling" in lowered or "face towards ceiling" in lowered:
        return "face_ceiling"
    return "unspecified"


def adl_type(text: str) -> str:
    lowered = text.lower()
    if "push" in lowered or "exercise" in lowered:
        return "exercise"
    if "sleep" in lowered or "lying" in lowered or "lie " in lowered:
        return "lying_down"
    if "pick" in lowered:
        return "picking_up"
    if "kneel" in lowered:
        return "kneeling"
    if "sit" in lowered:
        return "sitting"
    if re.search(r"\b(walk|walking|run|running)\b", lowered):
        return "walking"
    return "other"


def subject_number(name: str) -> str:
    match = re.search(r"(\d+)", name)
    return match.group(1) if match else "0"


def discover(root: Path) -> list[dict]:
    videos = []
    for csv_path in sorted(root.rglob("*.csv")):
        if csv_path.name.lower() not in {"adl.csv", "fall.csv"}:
            continue
        kind = "fall" if csv_path.name.lower().startswith("fall") else "adl"
        subject = csv_path.parent.name
        table = {row["file"]: row for row in read_subject_csv(csv_path)}
        folder = csv_path.parent / ("Fall" if kind == "fall" else "ADL")
        if not folder.is_dir():
            # Some zips nest the clips next to the csv.
            folder = csv_path.parent
        for video in sorted(folder.glob("*.mp4")):
            meta = table.get(video.name, {})
            videos.append(
                {
                    "video": video,
                    "kind": kind,
                    "subject": subject,
                    "description": meta.get("description", ""),
                    "classes": meta.get("classes", ""),
                    "raw": meta.get("raw", ""),
                }
            )
    return videos


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parent
    parser.add_argument("--zip", type=Path, default=root / "data" / "gmdcsa-v2.1.zip")
    parser.add_argument("--data", type=Path, default=root / "data" / "gmdcsa")
    parser.add_argument("--out", type=Path, default=root / "out" / "gmdcsa" / "landmarks")
    parser.add_argument("--model", type=Path, default=root / "models" / "pose_landmarker_lite.task")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    unzip_dataset(args.zip, args.data)
    items = discover(args.data)
    if args.limit:
        items = items[: args.limit]
    if not items:
        print(f"no GMDCSA videos under {args.data}", file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    model = ensure_model(args.model)
    landmarker = make_landmarker(model)
    rows = []
    failed = 0
    try:
        for index, item in enumerate(items, start=1):
            video: Path = item["video"]
            number = subject_number(item["subject"])
            text = " ".join([item["description"], item["classes"]])
            intervals = parse_intervals(item["classes"]) or parse_intervals(item["description"])
            fall_intervals = [span for span in intervals if is_fall_label(span[0])]
            onset_s, onset_note = fall_onset(item["classes"], item["description"])
            end_s = max((span[2] for span in fall_intervals), default=None)
            direction = direction_of(text, [span[0] for span in fall_intervals])
            view = view_of(text)
            height = CAMERA_HEIGHT.get(number, "")
            if view == "unspecified" and height:
                view = f"height_{height}"
            if item["kind"] == "fall":
                activity_type = direction or "fall"
            else:
                activity_type = adl_type(text)
            video_id = f"gmdcsa_s{int(number):02d}_{item['kind']}_{video.stem}"
            csv_path = args.out / f"{video_id}.csv"
            try:
                info = probe_video(video)
                notes = []
                if csv_path.is_file() and not args.force and csv_path.stat().st_size > 0:
                    notes.append("landmarks_reused")
                    analysis_w, analysis_h = fit_inside(info["width"], info["height"])
                else:
                    analysis_w, analysis_h = write_landmark_csv(video, csv_path, info, landmarker)
                onset_ms = "" if onset_s is None else str(int(round(onset_s * 1000)))
                if onset_note:
                    notes.append(onset_note)
                if end_s is not None:
                    notes.append(f"fall_end_s={end_s:.2f}")
                if height:
                    notes.append(f"camera_height={height}")
                row = blank_manifest_row()
                row.update(
                    {
                        "video_id": video_id,
                        "subject": item["subject"],
                        "activity": (item["description"] or item["classes"] or video.stem)[:180],
                        "kind": item["kind"],
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
                        "onset_ms": onset_ms,
                        "notes": "; ".join(notes),
                        "dataset": "gmdcsa",
                        "view": view,
                        "camera": height or "unspecified",
                        "direction": direction,
                    }
                )
            except Exception as exc:  # noqa: BLE001
                failed += 1
                print(f"FAIL {video}: {exc}", flush=True)
                continue
            rows.append(row)
            print(f"[{index}/{len(items)}] {video_id} onset_ms={row['onset_ms'] or '-'} view={view}", flush=True)
    finally:
        landmarker.close()
    write_manifest(args.out / "manifest.csv", rows)
    print(f"wrote {args.out / 'manifest.csv'} videos={len(rows)} failed={failed} model={MODEL_SHA256[:12]}")
    return 1 if failed and not rows else 0


if __name__ == "__main__":
    sys.exit(main())
