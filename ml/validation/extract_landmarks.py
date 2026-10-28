#!/usr/bin/env python3
"""Sample CAUCAFall videos and write MediaPipe Pose Landmarker lite landmarks.

Mirrors the app's analysis path closely enough to replay the shipped rules:

- about 5 fps (FallCameraEngine's default)
- frames fit inside 640x480, aspect ratio kept (the app's analysis target)
- pose_landmarker_lite.task, IMAGE mode, num_poses=1, confidence 0.35, CPU

This does not train anything. The yes/no decision is made later by the Kotlin
FallDetector, not by this script.

Dataset credit: Eraso, Muñoz, Muñoz, Pinto, Universidad del Cauca /
Universidad de la Amazonia. DOI 10.17632/7w7fccy7ky.5. CC BY 4.0.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image as PilImage

SAMPLE_FPS = 5
ANALYSIS_MAX = (640, 480)
LANDMARKS = 33
MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    "pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
)
MODEL_SHA256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"
MIN_POSE_CONFIDENCE = 0.35

# Folder names from CAUCAFall v5. Fall sitting is a fall; Sit down is an ADL.
ACTIVITIES = {
    "Fall backwards": ("fall", "backward"),
    "Fall forward": ("fall", "forward"),
    "Fall left": ("fall", "left"),
    "Fall right": ("fall", "right"),
    "Fall sitting": ("fall", "from_sitting"),
    "Walk": ("adl", "walking"),
    "Hop": ("adl", "hopping"),
    "Pick up object": ("adl", "picking_up"),
    "Sit down": ("adl", "sitting"),
    "Kneel": ("adl", "kneeling"),
}

MANIFEST_FIELDS = [
    "video_id",
    "subject",
    "activity",
    "kind",
    "activity_type",
    "csv_file",
    "video_relpath",
    "source_fps",
    "video_frames",
    "duration_ms",
    "width",
    "height",
    "analysis_width",
    "analysis_height",
    "sample_fps",
    "label_frames",
    "onset_frame",
    "onset_ms",
    "fall_class_id",
    "notes",
    "dataset",
    "view",
    "camera",
    "direction",
]


def landmark_header() -> list[str]:
    header = ["timestamp_ms", "source_frame", "pose_found"]
    for index in range(LANDMARKS):
        header.extend([f"l{index:02d}_x", f"l{index:02d}_y", f"l{index:02d}_v"])
    for index in range(LANDMARKS):
        header.extend([f"w{index:02d}_x", f"w{index:02d}_y", f"w{index:02d}_z"])
    for index in range(LANDMARKS):
        header.append(f"n{index:02d}_z")
    return header


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_model(path: Path) -> Path:
    if path.is_file() and sha256_file(path) == MODEL_SHA256:
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".task.part")
    print(f"downloading pose landmarker lite -> {path}", flush=True)
    urllib.request.urlretrieve(MODEL_URL, tmp)
    got = sha256_file(tmp)
    if got != MODEL_SHA256:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"pose model sha256 mismatch: {got}")
    tmp.replace(path)
    return path


def parse_rate(value: str | None) -> float:
    if not value or value in {"0/0", "N/A"}:
        return 0.0
    if "/" in value:
        num, den = value.split("/", 1)
        den_f = float(den)
        return float(num) / den_f if den_f else 0.0
    return float(value)


def probe_video(path: Path) -> dict:
    raw = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height,avg_frame_rate,nb_frames,duration",
            "-show_entries",
            "format=duration",
            "-of",
            "json",
            str(path),
        ],
        text=True,
    )
    payload = json.loads(raw)
    stream = (payload.get("streams") or [{}])[0]
    duration = stream.get("duration") or (payload.get("format") or {}).get("duration") or 0
    fps = parse_rate(stream.get("avg_frame_rate"))
    nb = stream.get("nb_frames")
    frames = int(nb) if nb and str(nb).isdigit() else 0
    if frames <= 0 and fps and duration:
        frames = int(round(float(duration) * fps))
    return {
        "width": int(stream.get("width") or 0),
        "height": int(stream.get("height") or 0),
        "fps": fps,
        "frames": frames,
        "duration_s": float(duration or 0),
    }


def fit_inside(width: int, height: int) -> tuple[int, int]:
    if width <= 0 or height <= 0:
        return ANALYSIS_MAX
    scale = min(ANALYSIS_MAX[0] / width, ANALYSIS_MAX[1] / height, 1.0)
    return max(1, int(round(width * scale))), max(1, int(round(height * scale)))


def fall_class_id(folder: Path) -> int:
    """YOLO classes.txt is one name per line. The paper uses 0=nofall, 1=fall."""
    classes = folder / "classes.txt"
    if not classes.is_file():
        return 1
    names = [line.strip().lower().replace("-", "").replace("_", "") for line in classes.read_text(errors="replace").splitlines()]
    names = [name for name in names if name]
    for index, name in enumerate(names):
        if name == "fall":
            return index
    return 1


def frame_number(path: Path) -> int | None:
    """True when this txt is a frame label, not classes.txt.

    The numeric tail is not the frame index. `cas100059` is subject 1, frame 59,
    while `cfs1-00001` really does end at frame 1. Callers order the files by
    name instead of trusting the integer.
    """
    match = re.search(r"(\d+)$", path.stem)
    if not match:
        return None
    return int(match.group(1))


def file_is_fall(path: Path, fall_id: int) -> bool:
    text = path.read_text(errors="replace").strip()
    if not text:
        return False
    for line in text.splitlines():
        parts = line.split()
        if not parts:
            continue
        try:
            class_id = int(float(parts[0]))
        except ValueError:
            continue
        if class_id == fall_id:
            return True
    return False


def find_onset(folder: Path, fall_id: int) -> tuple[int | None, int]:
    """First 1-based frame whose box is labeled fall, and how many frame labels exist.

    CAUCAFall marks a frame as fall only when the body is on the ground because
    of a fall, not at the start of the descent (Eraso et al., Data in Brief 2022).
    Frame order is filename order. The digits at the end of the name mix the
    subject id and the frame index (`cas100059` is frame 59), so the integer
    itself is not the index.
    """
    labels = [path for path in folder.glob("*.txt") if frame_number(path) is not None]
    labels.sort(key=lambda path: path.name)
    onset = None
    for index, path in enumerate(labels, start=1):
        if file_is_fall(path, fall_id):
            onset = index
            break
    return onset, len(labels)


def video_id_for(subject: str, activity_type: str) -> str:
    match = re.search(r"(\d+)", subject)
    number = int(match.group(1)) if match else 0
    return f"s{number:02d}_{activity_type}"


def discover_videos(data_root: Path) -> list[Path]:
    root = data_root / "CAUCAFall" if (data_root / "CAUCAFall").is_dir() else data_root
    return sorted(root.rglob("*.avi"))


def _append_world(values: list[str], world) -> None:
    if world is None or len(world) != LANDMARKS:
        values.extend(["0"] * (LANDMARKS * 3))
        return
    for landmark in world:
        values.append(f"{float(landmark.x or 0.0):.6f}")
        values.append(f"{float(landmark.y or 0.0):.6f}")
        values.append(f"{float(getattr(landmark, 'z', 0.0) or 0.0):.6f}")


def _append_image_z(values: list[str], pose) -> None:
    if pose is None or len(pose) != LANDMARKS:
        values.extend(["0"] * LANDMARKS)
        return
    for landmark in pose:
        values.append(f"{float(getattr(landmark, 'z', 0.0) or 0.0):.6f}")


def blank_row() -> list[str]:
    return ["0", "0", "0"] + ["0"] * (LANDMARKS * 7)


def landmark_row(timestamp_ms: int, source_frame: int, pose, world=None) -> list[str]:
    if pose is None or len(pose) != LANDMARKS:
        row = blank_row()
        row[0] = str(timestamp_ms)
        row[1] = str(source_frame)
        return row
    values = [str(timestamp_ms), str(source_frame), "1"]
    for landmark in pose:
        visibility = getattr(landmark, "visibility", None)
        values.append(f"{float(landmark.x or 0.0):.6f}")
        values.append(f"{float(landmark.y or 0.0):.6f}")
        values.append(f"{0.0 if visibility is None else float(visibility):.6f}")
    _append_world(values, world)
    _append_image_z(values, pose)
    return values


def make_landmarker(model_path: Path):
    import mediapipe as mp
    from mediapipe.tasks.python.core.base_options import BaseOptions
    from mediapipe.tasks.python.vision import PoseLandmarker, PoseLandmarkerOptions, RunningMode

    options = PoseLandmarkerOptions(
        base_options=BaseOptions(
            model_asset_path=str(model_path),
            delegate=BaseOptions.Delegate.CPU,
        ),
        running_mode=RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=MIN_POSE_CONFIDENCE,
        min_pose_presence_confidence=MIN_POSE_CONFIDENCE,
        min_tracking_confidence=MIN_POSE_CONFIDENCE,
    )
    return PoseLandmarker.create_from_options(options)


def extract_jpegs(video: Path, tmp: Path) -> list[Path]:
    pattern = tmp / "f_%05d.jpg"
    subprocess.check_call(
        [
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(video),
            "-vf",
            f"fps={SAMPLE_FPS},scale={ANALYSIS_MAX[0]}:{ANALYSIS_MAX[1]}:force_original_aspect_ratio=decrease",
            "-start_number",
            "0",
            "-q:v",
            "3",
            str(pattern),
        ]
    )
    return sorted(tmp.glob("f_*.jpg"))


def process_video(video: Path, data_root: Path, out_dir: Path, landmarker, force: bool) -> dict:
    activity = video.parent.name
    subject = video.parent.parent.name
    if activity not in ACTIVITIES:
        raise RuntimeError(f"unknown activity folder {activity}")
    kind, activity_type = ACTIVITIES[activity]
    video_key = video_id_for(subject, activity_type)
    csv_name = f"{video_key}.csv"
    csv_path = out_dir / csv_name
    info = probe_video(video)
    fall_id = fall_class_id(video.parent)
    onset_frame, label_frames = find_onset(video.parent, fall_id)
    notes: list[str] = []
    if info["frames"] and label_frames and abs(info["frames"] - label_frames) > 3:
        notes.append(f"frame_count_mismatch video={info['frames']} labels={label_frames}")
    if kind == "fall" and onset_frame is None:
        notes.append("no_fall_label")
    if kind == "adl" and onset_frame is not None:
        notes.append("adl_folder_has_fall_label")
    onset_ms = ""
    if onset_frame is not None and info["fps"] > 0:
        onset_ms = str(int(round((onset_frame - 1) * 1000.0 / info["fps"])))
    analysis_w, analysis_h = fit_inside(info["width"], info["height"])
    root = data_root / "CAUCAFall" if (data_root / "CAUCAFall").is_dir() else data_root
    try:
        rel = str(video.relative_to(root))
    except ValueError:
        rel = str(video)

    if csv_path.is_file() and not force and csv_path.stat().st_size > 0:
        notes.append("landmarks_reused")
    else:
        if landmarker is None:
            raise RuntimeError("no landmarker and csv is missing")
        import mediapipe as mp

        tmp = Path(tempfile.mkdtemp(prefix=f"cauca-{video_key}-"))
        try:
            frames = extract_jpegs(video, tmp)
            if not frames:
                raise RuntimeError("ffmpeg produced no frames")
            with PilImage.open(frames[0]) as first:
                analysis_w, analysis_h = first.size
            header = landmark_header()
            partial = csv_path.with_suffix(".csv.part")
            with partial.open("w", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(header)
                for index, frame_path in enumerate(frames):
                    timestamp_ms = int(round(index * 1000.0 / SAMPLE_FPS))
                    source_frame = int(round(index * info["fps"] / SAMPLE_FPS)) if info["fps"] else index
                    with PilImage.open(frame_path) as image:
                        rgb = np.ascontiguousarray(np.asarray(image.convert("RGB"), dtype=np.uint8))
                    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                    result = landmarker.detect(mp_image)
                    pose = result.pose_landmarks[0] if result.pose_landmarks else None
                    world = result.pose_world_landmarks[0] if result.pose_world_landmarks else None
                    if pose is not None and len(pose) != LANDMARKS:
                        pose = None
                        world = None
                    if world is not None and len(world) != LANDMARKS:
                        world = None
                    writer.writerow(landmark_row(timestamp_ms, source_frame, pose, world))
            partial.replace(csv_path)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    return {
        "video_id": video_key,
        "subject": subject,
        "activity": activity,
        "kind": kind,
        "activity_type": activity_type,
        "csv_file": csv_name,
        "video_relpath": rel,
        "source_fps": f"{info['fps']:.4f}",
        "video_frames": str(info["frames"]),
        "duration_ms": str(int(round(info["duration_s"] * 1000))),
        "width": str(info["width"]),
        "height": str(info["height"]),
        "analysis_width": str(analysis_w),
        "analysis_height": str(analysis_h),
        "sample_fps": str(SAMPLE_FPS),
        "label_frames": str(label_frames),
        "onset_frame": "" if onset_frame is None else str(onset_frame),
        "onset_ms": onset_ms,
        "fall_class_id": str(fall_id),
        "notes": "; ".join(notes),
        "dataset": "caucafall",
        "view": "upper_corner",
        "camera": "hikvision_corner",
        "direction": activity_type,
    }


def write_manifest(path: Path, rows: list[dict]) -> None:
    rows = sorted(rows, key=lambda row: row["video_id"])
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=MANIFEST_FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=Path(__file__).resolve().parent / "data")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent / "out" / "landmarks")
    parser.add_argument("--model", type=Path, default=Path(__file__).resolve().parent / "models" / "pose_landmarker_lite.task")
    parser.add_argument("--limit", type=int, default=0, help="Process only the first N videos (0 = all)")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--shard", default="", help="k/n keeps videos whose index modulo n equals k")
    parser.add_argument("--skip-manifest", action="store_true")
    args = parser.parse_args()
    videos = discover_videos(args.data)
    if args.limit:
        videos = videos[: args.limit]
    if args.shard:
        part, total = (int(piece) for piece in args.shard.split("/"))
        videos = [video for index, video in enumerate(videos) if index % total == part]
    if not videos:
        print(f"no .avi files under {args.data}", file=sys.stderr)
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    model = ensure_model(args.model)
    print(f"processing {len(videos)} videos with {model.name}", flush=True)
    landmarker = make_landmarker(model)
    rows = []
    failed = 0
    try:
        for index, video in enumerate(videos, start=1):
            try:
                row = process_video(video, args.data, args.out, landmarker, args.force)
            except Exception as exc:  # noqa: BLE001 - one bad clip should not drop the rest
                failed += 1
                print(f"FAIL {video}: {exc}", flush=True)
                continue
            rows.append(row)
            print(
                f"[{index}/{len(videos)}] {row['video_id']} frames={row['label_frames']} "
                f"onset_ms={row['onset_ms'] or '-'} {row['notes']}",
                flush=True,
            )
    finally:
        landmarker.close()
    if args.skip_manifest:
        print(f"shard {args.shard} wrote {len(rows)} csvs, failed={failed}")
        return 1 if failed and not rows else 0
    write_manifest(args.out / "manifest.csv", rows)
    meta = {
        "model": "pose_landmarker_lite.task",
        "model_sha256": MODEL_SHA256,
        "running_mode": "IMAGE",
        "num_poses": 1,
        "min_pose_detection_confidence": MIN_POSE_CONFIDENCE,
        "min_pose_presence_confidence": MIN_POSE_CONFIDENCE,
        "min_tracking_confidence": MIN_POSE_CONFIDENCE,
        "delegate": "CPU",
        "sample_fps": SAMPLE_FPS,
        "analysis_fit": "inside 640x480, aspect preserved",
        "videos": len(rows),
        "failed": failed,
        "dataset": "CAUCAFall",
        "doi": "10.17632/7w7fccy7ky.5",
    }
    (args.out / "run_meta.json").write_text(json.dumps(meta, indent=2) + "\n")
    print(f"wrote {args.out / 'manifest.csv'} videos={len(rows)} failed={failed}")
    return 1 if failed and not rows else 0


if __name__ == "__main__":
    sys.exit(main())
