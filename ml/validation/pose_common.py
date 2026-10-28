"""Shared MediaPipe sampling for the offline fall replay.

Matches PoseReader: pose_landmarker_lite, IMAGE mode, num_poses=1,
detection / presence / tracking confidence 0.35, CPU. Frames are sampled
at 5 fps and fit inside 640x480 without stretching, unless a caller passes
an explicit ffmpeg scale (UR Fall preview mp4s are restored to 640x480).
"""

from __future__ import annotations

import csv
import hashlib
import json
import subprocess
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
    """Image landmarks, then world landmarks in meters (hip-centered, camera axes).

    World columns are extra. The Kotlin replay reads the image prefix and
    ignores the rest. World landmarks come from the same lite IMAGE detect
    call; they are not a second model.
    """
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


def extract_jpegs(video: Path, tmp: Path, scale: str | None = None) -> list[Path]:
    """scale is an ffmpeg scale expression. None fits inside 640x480."""
    pattern = tmp / "f_%05d.jpg"
    vf = (
        f"fps={SAMPLE_FPS},scale={ANALYSIS_MAX[0]}:{ANALYSIS_MAX[1]}:force_original_aspect_ratio=decrease"
        if scale is None
        else f"fps={SAMPLE_FPS},{scale}"
    )
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
            vf,
            "-start_number",
            "0",
            "-q:v",
            "3",
            str(pattern),
        ]
    )
    return sorted(tmp.glob("f_*.jpg"))


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


def landmark_row(timestamp_ms: int, source_frame: int, pose, world=None) -> list[str]:
    if pose is None or len(pose) != LANDMARKS:
        values = ["0", "0", "0"] + ["0"] * (LANDMARKS * 3)
        values[0] = str(timestamp_ms)
        values[1] = str(source_frame)
        _append_world(values, None)
        _append_image_z(values, None)
        return values
    values = [str(timestamp_ms), str(source_frame), "1"]
    for landmark in pose:
        visibility = getattr(landmark, "visibility", None)
        values.append(f"{float(landmark.x or 0.0):.6f}")
        values.append(f"{float(landmark.y or 0.0):.6f}")
        values.append(f"{0.0 if visibility is None else float(visibility):.6f}")
    _append_world(values, world)
    _append_image_z(values, pose)
    return values


def write_landmark_csv(video: Path, csv_path: Path, info: dict, landmarker, scale: str | None = None) -> tuple[int, int]:
    import mediapipe as mp

    csv_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="pose-"))
    try:
        frames = extract_jpegs(video, tmp, scale=scale)
        if not frames:
            raise RuntimeError("ffmpeg produced no frames")
        with PilImage.open(frames[0]) as first:
            analysis_w, analysis_h = first.size
        partial = csv_path.with_suffix(".csv.part")
        with partial.open("w", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(landmark_header())
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
        return analysis_w, analysis_h
    finally:
        import shutil

        shutil.rmtree(tmp, ignore_errors=True)


def write_manifest(path: Path, rows: list[dict]) -> None:
    rows = sorted(rows, key=lambda row: row["video_id"])
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=MANIFEST_FIELDS, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            full = {field: row.get(field, "") for field in MANIFEST_FIELDS}
            writer.writerow(full)


def blank_manifest_row() -> dict:
    return {field: "" for field in MANIFEST_FIELDS}
