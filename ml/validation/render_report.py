#!/usr/bin/env python3
"""Older CAUCAFall-only writer.

`render_results.py` is what writes `RESULTS.md` for the three-dataset replay.
This script still formats a single CAUCAFall replay and can save example frames.
"""

from __future__ import annotations

import argparse
import csv
import math
import re
import shutil
import subprocess
import zipfile
from collections import defaultdict
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parent

FALL_TYPES = ["forward", "backward", "left", "right", "from_sitting"]
ADL_TYPES = ["walking", "hopping", "picking_up", "sitting", "kneeling"]
FALL_LABEL = {
    "forward": "Forward",
    "backward": "Backward",
    "left": "Left",
    "right": "Right",
    "from_sitting": "From sitting",
}
ADL_LABEL = {
    "walking": "Walking",
    "hopping": "Hopping",
    "picking_up": "Picking up an object",
    "sitting": "Sitting",
    "kneeling": "Kneeling",
}
BLOCKER_LABEL = {
    "no_pose": "No usable torso pose",
    "min_drop": "Drop smaller than 0.18",
    "drop_window": "Drop slower than the 800 ms window",
    "torso_angle": "Torso never within 28° of horizontal",
    "upright": "Not upright at the start of the drop window",
    "still_movement": "Moved more than the stillness tolerance",
    "still_duration": "Did not stay still for 2000 ms",
    "person_lost_grace": "Torso gap longer than the 1200 ms grace",
    "clip_shorter_than_stillness": "Clip ends before the 2000 ms stillness hold",
    "combined_thresholds": "No single threshold; a looser combination would fire",
    "no_fall_pattern": "Loose thresholds still do not see a fall",
}


def read_csv(path: Path) -> list[dict]:
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


def num(value: str | None, cast=float):
    if value is None or value == "":
        return None
    return cast(value)


def pct(part: int, whole: int) -> str:
    if whole <= 0:
        return "n/a"
    return f"{100.0 * part / whole:.1f}% ({part}/{whole})"


def subject_number(name: str) -> int:
    digits = "".join(ch for ch in name if ch.isdigit())
    return int(digits) if digits else 0


def load_dataset_details(path: Path) -> dict:
    """Per-subject light and per-activity distance / occlusion from Dataset details.xlsx."""
    if not path.is_file():
        return {}
    ns = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    with zipfile.ZipFile(path) as workbook:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in workbook.namelist():
            root = ET.fromstring(workbook.read("xl/sharedStrings.xml"))
            for item in root.findall(f"{ns}si"):
                shared.append("".join(text.text or "" for text in item.iter(f"{ns}t")))
        sheet = ET.fromstring(workbook.read("xl/worksheets/sheet1.xml"))

    def text_of(cell) -> str:
        kind = cell.attrib.get("t")
        value = cell.find(f"{ns}v")
        if value is None or value.text is None:
            return ""
        if kind == "s":
            return shared[int(value.text)]
        return value.text

    def column(ref: str) -> str:
        return "".join(ch for ch in ref if ch.isalpha())

    subjects: dict[str, dict] = {}
    left_subject = right_subject = None

    def ensure(name: str | None) -> dict | None:
        if not name:
            return None
        return subjects.setdefault(name, {"lux": None, "light": "", "activities": {}})

    for row in sheet.findall(f"{ns}sheetData/{ns}row"):
        cells = {column(cell.attrib.get("r", "")): text_of(cell).strip() for cell in row.findall(f"{ns}c")}
        for side, subject_col, light_ok in (("left", "C", True), ("right", "I", True)):
            label = cells.get(subject_col, "")
            match = re.match(r"Subject\s+(\d+)", label)
            if match:
                name = f"Subject.{int(match.group(1))}"
                if side == "left":
                    left_subject = name
                else:
                    right_subject = name
                ensure(name)
        for subject_name, light_col in ((left_subject, "C"), (right_subject, "I")):
            light = cells.get(light_col, "")
            if light.lower().startswith("data was acquired") and subject_name:
                info = ensure(subject_name)
                info["light"] = light.rstrip(".")
                lux = re.search(r"(\d+)\s*lux", light)
                info["lux"] = int(lux.group(1)) if lux else None
        for subject_name, activity_col, distance_col, angle_col, occlusion_col in (
            (left_subject, "C", "E", "F", "G"),
            (right_subject, "I", "K", "L", "M"),
        ):
            activity = cells.get(activity_col, "")
            if not subject_name or not activity or activity in {"Activity", "Dataset details"}:
                continue
            if activity.startswith("Subject") or activity.startswith("Data was"):
                continue
            distance_text = cells.get(distance_col, "").replace(",", ".")
            distance = None
            if distance_text and distance_text not in {"–", "-", "—"}:
                try:
                    distance = float(distance_text)
                except ValueError:
                    distance = None
            occlusion = cells.get(occlusion_col, "")
            info = ensure(subject_name)
            info["activities"][activity] = {
                "distance_m": distance,
                "angle": cells.get(angle_col, ""),
                "occlusion": bool(occlusion) and occlusion not in {"–", "-", "—"},
            }
    return subjects


def pose_geometry(csv_path: Path) -> dict:
    """Raw torso series for the written explanation. The detector decision is not this."""
    lowest_cy = None
    lowest_angle = None
    flat_frames = 0
    samples = 0
    for row in read_csv(csv_path):
        shoulders = visible_points(row, (11, 12))
        hips = visible_points(row, (23, 24))
        if not shoulders or not hips:
            continue
        samples += 1
        sx = sum(point[0] for point in shoulders) / len(shoulders)
        sy = sum(point[1] for point in shoulders) / len(shoulders)
        hx = sum(point[0] for point in hips) / len(hips)
        hy = sum(point[1] for point in hips) / len(hips)
        angle = math.degrees(math.atan2(abs(sy - hy), abs(sx - hx)))
        cy = (sy + hy) / 2.0
        if angle <= 28:
            flat_frames += 1
        if lowest_cy is None or cy > lowest_cy:
            lowest_cy = cy
            lowest_angle = angle
    return {"samples": samples, "flat_frames": flat_frames, "angle_at_lowest": lowest_angle}


def visible_points(row: dict, indexes: tuple[int, ...]) -> list[tuple[float, float]]:
    points = []
    for index in indexes:
        x = num(row.get(f"l{index:02d}_x"))
        y = num(row.get(f"l{index:02d}_y"))
        visibility = num(row.get(f"l{index:02d}_v"))
        if x is None or y is None or visibility is None or visibility < 0.35:
            continue
        points.append((x, y))
    return points


def median_ms(values: list[int]) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) // 2


def explain(
    row: dict,
    detail: dict | None = None,
    light: dict | None = None,
    geom: dict | None = None,
) -> list[str]:
    notes = []
    if light and light.get("lux") == 0:
        notes.append("recorded with no visible light (0 lux), so the camera is on IR")
    if detail and detail.get("occlusion"):
        notes.append("the dataset marks this clip as occluded")
    if detail and detail.get("distance_m") is not None:
        notes.append(f"camera-to-fall distance in the dataset sheet is {detail['distance_m']:.2f} m")
    frames = num(row.get("frames"), int) or 0
    pose_frames = num(row.get("pose_frames"), int) or 0
    if frames and pose_frames / frames < 0.25:
        notes.append(
            f"a shoulder and a hip are visible on only {pose_frames}/{frames} sampled frames"
        )
    drop = num(row.get("max_drop_800"))
    if drop is not None and drop < 0.18:
        notes.append(f"raw torso drop inside 800 ms peaks at {drop:.2f}, under 0.18")
    angle = num(row.get("min_angle_deg"))
    if angle is not None and angle > 28:
        notes.append(f"torso never lies flat (minimum angle {angle:.0f}°, threshold 28°)")
    lowest = None if geom is None else geom.get("angle_at_lowest")
    flat_frames = None if geom is None else geom.get("flat_frames")
    if lowest is not None and lowest > 45 and (angle is None or angle <= 28):
        notes.append(
            f"at the lowest torso center the axis is still {lowest:.0f}° from horizontal"
        )
    if (
        flat_frames is not None
        and flat_frames <= 2
        and pose_frames >= 3
        and (angle is None or angle <= 28)
    ):
        notes.append(f"torso is within 28° of horizontal on {flat_frames} sampled frames")
    gap = num(row.get("longest_gap_ms"), int)
    if gap is not None and gap > 1200:
        notes.append(f"longest gap without a torso is {gap} ms, past the 1200 ms grace")
    tail = num(row.get("tail_after_onset_ms"), int)
    if tail is not None and tail < 2000:
        notes.append(f"only {tail} ms of video is left after the on-ground label, short of the 2000 ms hold")
    span = num(row.get("median_torso_len"))
    if span is not None and span < 0.08:
        notes.append(f"median torso length is {span:.2f} of the frame, so the person is small in view")
    edge = num(row.get("edge_fraction"))
    if edge is not None and edge > 0.25:
        notes.append(f"torso joints touch the frame edge on {edge:.0%} of posed frames")
    after = num(row.get("pose_fraction_after_lowest"))
    if after is not None and after < 0.4:
        notes.append("the pose usually disappears after the body reaches its lowest point")
    return notes


def blocker_title(code: str) -> str:
    if code in BLOCKER_LABEL:
        return BLOCKER_LABEL[code]
    parts = [BLOCKER_LABEL.get(part, part) for part in code.split("+")]
    if len(parts) == 1:
        return parts[0]
    rest = [part[:1].lower() + part[1:] if part else part for part in parts[1:]]
    return parts[0] + "; also " + "; also ".join(rest)


def config_distance(row: dict, default: dict) -> tuple:
    changes = 0
    magnitude = 0.0
    pairs = [
        ("min_drop", 0.18),
        ("max_lie_angle_deg", 28.0),
        ("still_movement", 0.035),
        ("drop_window_ms", 800.0),
    ]
    for key, scale in pairs:
        delta = abs(float(row[key]) - float(default[key]))
        if delta > 1e-3:
            changes += 1
            magnitude += delta / scale
    return (changes, round(magnitude, 4))


def choose_recommendation(sweep: list[dict]) -> tuple[dict, dict, dict | None]:
    """Keep a setting that does not add ADL alarms and does not lose falls.

    Also return the highest-recall row so the tradeoff is visible. That row
    is not the recommendation when it adds false alarms.
    """
    default = next(row for row in sweep if row["slice"] == "default")
    best_recall = max(sweep, key=lambda row: (int(row["falls_detected"]), -int(row["adl_false"])))
    eligible = []
    for row in sweep:
        if int(row["falls_detected"]) < int(default["falls_detected"]):
            continue
        if int(row["adl_false"]) > int(default["adl_false"]):
            continue
        if int(row["sitting_false"]) + int(row["kneeling_false"]) > int(default["sitting_false"]) + int(default["kneeling_false"]):
            continue
        eligible.append(row)
    eligible.sort(
        key=lambda row: (
            -int(row["falls_detected"]),
            int(row["adl_false"]),
            config_distance(row, default),
            abs(int(row["median_delay_ms"] or 10**9)),
        )
    )
    return default, eligible[0], best_recall


def fmt_config(row: dict) -> str:
    return (
        f"minDrop={float(row['min_drop']):.2f}, "
        f"maxLieAngleDeg={float(row['max_lie_angle_deg']):.0f}, "
        f"stillMovement={float(row['still_movement']):.3f}, "
        f"dropWindowMs={int(float(row['drop_window_ms']))}"
    )


def slice_rows(sweep: list[dict], name: str) -> list[dict]:
    rows = [row for row in sweep if row["slice"] == name]
    key = {
        "minDrop": "min_drop",
        "maxLieAngleDeg": "max_lie_angle_deg",
        "stillMovement": "still_movement",
        "dropWindowMs": "drop_window_ms",
    }[name]
    return sorted(rows, key=lambda row: float(row[key]))


def markdown_table(headers: list[str], rows: list[list[str]]) -> str:
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join("---" for _ in headers) + " |"]
    for row in rows:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)


def sweep_table(rows: list[dict], value_key: str, value_label: str, shipped_value: float) -> str:
    body = []
    for row in rows:
        value = float(row[value_key])
        label = f"{value:.3f}" if value_key == "still_movement" else (f"{value:.2f}" if value_key == "min_drop" else f"{value:.0f}")
        if abs(value - shipped_value) < 1e-3:
            label += " (shipped)"
        delay = row.get("median_delay_ms") or ""
        body.append(
            [
                label,
                pct(int(row["falls_detected"]), int(row["falls_total"])),
                pct(int(row["adl_false"]), int(row["adl_total"])),
                f"{row['sitting_false']}/{row['sitting_total']}",
                f"{row['kneeling_false']}/{row['kneeling_total']}",
                delay if delay else "—",
            ]
        )
    return markdown_table(
        [value_label, "Falls detected", "ADL false alarms", "Sitting FA", "Kneeling FA", "Median delay (ms)"],
        body,
    )


def save_frame(video: Path, timestamp_ms: int, dest: Path) -> bool:
    if not video.is_file():
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    seconds = max(0.0, timestamp_ms / 1000.0)
    subprocess.check_call(
        [
            "ffmpeg",
            "-y",
            "-hide_banner",
            "-loglevel",
            "error",
            "-i",
            str(video),
            "-ss",
            f"{seconds:.3f}",
            "-frames:v",
            "1",
            "-vf",
            "scale=280:-2",
            "-q:v",
            "16",
            str(dest),
        ]
    )
    if dest.stat().st_size > 40_000:
        subprocess.check_call(
            [
                "ffmpeg",
                "-y",
                "-hide_banner",
                "-loglevel",
                "error",
                "-i",
                str(dest),
                "-vf",
                "scale=200:-2",
                "-q:v",
                "20",
                str(dest.with_suffix(".tmp.jpg")),
            ]
        )
        dest.with_suffix(".tmp.jpg").replace(dest)
    return dest.is_file() and dest.stat().st_size > 0


def pick_samples(videos: list[dict], manifest: dict[str, dict], data_root: Path, sample_dir: Path) -> list[tuple[str, str]]:
    """Return (markdown image, caption) for a few tiny frames. Missing videos are skipped."""
    root = data_root / "CAUCAFall" if (data_root / "CAUCAFall").is_dir() else data_root
    falls = [row for row in videos if row["kind"] == "fall"]
    detected = [row for row in falls if row["detected"] == "1" and row.get("delay_ms")]
    misses = [row for row in falls if row["detected"] != "1"]
    sitting = [row for row in videos if row["activity_type"] == "sitting"]
    chosen = []
    if detected:
        delays = sorted(int(row["delay_ms"]) for row in detected)
        median_delay = delays[len(delays) // 2]
        detected.sort(key=lambda row: (0 if row["activity_type"] == "forward" else 1, abs(int(row["delay_ms"]) - median_delay), row["video_id"]))
        chosen.append((detected[0], "fall_detected.jpg", "Detected fall near the labeled on-ground frame"))
    if sitting:
        sitting.sort(key=lambda row: row["video_id"])
        chosen.append((sitting[0], "adl_sitting.jpg", "Sitting, an ADL the rule is meant to ignore"))
    if misses:
        counts = defaultdict(int)
        for row in misses:
            counts[row.get("blocker") or "unspecified"] += 1
        top = max(counts, key=counts.get)
        pool = [row for row in misses if (row.get("blocker") or "unspecified") == top]
        pool.sort(key=lambda row: row["video_id"])
        chosen.append((pool[0], "fall_miss.jpg", f"Missed fall ({blocker_title(top)})"))
    written = []
    for row, name, caption in chosen:
        meta = manifest.get(row["video_id"])
        if not meta:
            continue
        video = root / meta["video_relpath"]
        timestamp = num(row.get("onset_ms"), int)
        if timestamp is None:
            timestamp = num(row.get("detected_at_ms"), int) or 0
        dest = sample_dir / name
        try:
            if save_frame(video, timestamp, dest):
                written.append((f"samples/{name}", f"{caption}. {row['subject']}, {row['activity']}."))
        except (OSError, subprocess.CalledProcessError):
            continue
    return written


def build_report(
    videos: list[dict],
    sweep: list[dict],
    manifest_rows: list[dict],
    samples: list[tuple[str, str]],
    details: dict | None = None,
    geometries: dict | None = None,
) -> str:
    falls = [row for row in videos if row["kind"] == "fall"]
    adls = [row for row in videos if row["kind"] == "adl"]
    fall_hits = [row for row in falls if row["detected"] == "1"]
    adl_hits = [row for row in adls if row["detected"] == "1"]
    delays = [int(row["delay_ms"]) for row in fall_hits if row.get("delay_ms")]
    median_delay = median_ms(delays)
    excess = None if median_delay is None else median_delay - 2000
    subjects = sorted({row["subject"] for row in videos}, key=subject_number)
    manifest_by_id = {row["video_id"]: row for row in manifest_rows}
    widths = sorted({(row.get("width"), row.get("height")) for row in manifest_rows})
    analysis = sorted({(row.get("analysis_width"), row.get("analysis_height")) for row in manifest_rows})
    fps_values = [float(row["source_fps"]) for row in manifest_rows if row.get("source_fps")]
    if not fps_values:
        fps_text = "unknown"
    elif abs(min(fps_values) - max(fps_values)) < 0.01:
        fps_text = f"{fps_values[0]:.2f}"
    else:
        fps_text = f"{min(fps_values):.2f}–{max(fps_values):.2f}"
    mismatch = sum(1 for row in manifest_rows if "frame_count_mismatch" in (row.get("notes") or ""))
    missing_onset = sum(1 for row in falls if not row.get("onset_ms"))
    default, recommended, best_recall = choose_recommendation(sweep)

    lines = []
    lines.append("# CAUCAFall replay of the shipped fall rule")
    lines.append("")
    lines.append(
        "Eraso, Muñoz, Muñoz, Pinto, Universidad del Cauca / Universidad de la Amazonia. "
        "CAUCAFall, Mendeley Data, V5, DOI 10.17632/7w7fccy7ky.5. CC BY 4.0. "
        "The same recordings are described in Eraso et al., Data in Brief 45 (2022) 108610."
    )
    lines.append("")
    lines.append(
        "This is an offline check of the rule already in `FallDetector`, not a training run "
        "and not a change to the app. Pose landmarks were estimated with the same lite model "
        "and the same confidence options as `PoseReader`. The yes/no decision, the delay, and "
        "the threshold sweep were produced by calling that Kotlin class."
    )
    lines.append("")
    lines.append("## What was run")
    lines.append("")
    lines.append(
        f"Videos used: **{len(videos)}** ({len(falls)} falls, {len(adls)} ADLs) from **{len(subjects)}** subjects. "
        f"Source rate about {fps_text} fps, frame size {', '.join(f'{w}×{h}' for w, h in widths)}. "
        f"Analysis frames were sampled at 5 fps and scaled to fit inside 640×480 "
        f"({', '.join(f'{w}×{h}' for w, h in analysis)} here) without stretching. "
        "PNG frames from the archive were not downloaded; the `.avi` and the per-frame YOLO labels were."
    )
    if widths and widths != [("1080", "960")]:
        lines.append("")
        lines.append(
            "The dataset paper describes the camera as 1080×960 at about 23 fps. "
            "The files in version 5 probe as the sizes and rates above."
        )
    if details:
        dark = [name for name, info in sorted(details.items(), key=lambda item: subject_number(item[0])) if info.get("lux") == 0]
        occluded = [
            f"{name} {activity}"
            for name, info in sorted(details.items(), key=lambda item: subject_number(item[0]))
            for activity, activity_info in info.get("activities", {}).items()
            if activity_info.get("occlusion")
        ]
        distances = [
            activity_info["distance_m"]
            for info in details.values()
            for activity_info in info.get("activities", {}).values()
            if activity_info.get("distance_m") is not None
        ]
        distance_text = ""
        if distances:
            distance_text = f"Marked camera-to-fall distances run from {min(distances):.2f} m to {max(distances):.2f} m."
        lines.append("")
        lines.append(
            "Dataset details.xlsx records the light and, for falls, the distance and an occlusion mark. "
            + (f"No visible light (0 lux): {', '.join(dark)}. " if dark else "")
            + (f"Occlusion marked: {', '.join(occluded)}. " if occluded else "")
            + distance_text
        )
    lines.append("")
    lines.append(
        "The shipped rule fires only when all of the following hold: the smoothed shoulder/hip center "
        "drops by at least 0.18 of the frame inside about 800 ms, the torso was at least 58° from "
        "horizontal at the start of that window, the torso is then within 28° of horizontal, and it "
        "stays inside 0.035 of the frame for 2000 ms. Joints are smoothed (alpha 0.55). A torso joint "
        "that disappears is held for 1200 ms. One visible shoulder and one visible hip are enough."
    )
    lines.append("")
    lines.append(
        "Fall onset is the first frame whose YOLO label is `fall`. The dataset authors marked that "
        "class only when the body is on the ground because of a fall, so the delay includes the "
        "rule's 2000 ms stillness hold. It is not the time from the first loss of balance."
    )
    if mismatch:
        lines.append("")
        lines.append(
            f"{mismatch} {'video has' if mismatch == 1 else 'videos have'} a label-file count that differs from the probed video frame count by more than 3. "
            f"Onset time for {'that clip is' if mismatch == 1 else 'those clips are'} less exact."
        )
    if missing_onset:
        lines.append("")
        lines.append(f"{missing_onset} fall videos have no class-`fall` label, so they have no onset and no delay.")
    lines.append("")
    lines.append("## Headline")
    lines.append("")
    lines.append(f"- Fall detection: **{pct(len(fall_hits), len(falls))}**.")
    lines.append(f"- ADL false alarms: **{pct(len(adl_hits), len(adls))}**.")
    sit_false = sum(1 for row in adls if row["activity_type"] == "sitting" and row["detected"] == "1")
    kneel_false = sum(1 for row in adls if row["activity_type"] == "kneeling" and row["detected"] == "1")
    sit_n = sum(1 for row in adls if row["activity_type"] == "sitting")
    kneel_n = sum(1 for row in adls if row["activity_type"] == "kneeling")
    lines.append(f"- Sitting false alarms: **{pct(sit_false, sit_n)}**. Kneeling false alarms: **{pct(kneel_false, kneel_n)}**.")
    if median_delay is None:
        lines.append("- Median delay: no detected fall had an onset time.")
    else:
        lines.append(
            f"- Median delay from the on-ground label to detection: **{median_delay} ms** "
            f"({excess:+d} ms relative to the 2000 ms stillness hold) over {len(delays)} timed detections."
        )
    lines.append("")
    lines.append("## Falls by type")
    lines.append("")
    fall_rows = []
    for activity_type in FALL_TYPES:
        group = [row for row in falls if row["activity_type"] == activity_type]
        hits = [row for row in group if row["detected"] == "1"]
        group_delays = [int(row["delay_ms"]) for row in hits if row.get("delay_ms")]
        med = str(median_ms(group_delays)) if group_delays else "—"
        fall_rows.append([FALL_LABEL[activity_type], pct(len(hits), len(group)), med])
    lines.append(markdown_table(["Fall", "Detected", "Median delay (ms)"], fall_rows))
    lines.append("")
    lines.append("## Daily activities")
    lines.append("")
    adl_rows = []
    for activity_type in ADL_TYPES:
        group = [row for row in adls if row["activity_type"] == activity_type]
        hits = [row for row in group if row["detected"] == "1"]
        adl_rows.append([ADL_LABEL[activity_type], pct(len(hits), len(group))])
    lines.append(markdown_table(["Activity", "False alarms"], adl_rows))
    lines.append("")
    lines.append("## By subject")
    lines.append("")
    subject_rows = []
    for subject in subjects:
        group = [row for row in videos if row["subject"] == subject]
        fall_group = [row for row in group if row["kind"] == "fall"]
        adl_group = [row for row in group if row["kind"] == "adl"]
        subject_rows.append(
            [
                subject,
                pct(sum(1 for row in fall_group if row["detected"] == "1"), len(fall_group)),
                pct(sum(1 for row in adl_group if row["detected"] == "1"), len(adl_group)),
            ]
        )
    lines.append(markdown_table(["Subject", "Falls detected", "ADL false alarms"], subject_rows))
    lines.append("")
    lines.append("## Why falls were missed")
    lines.append("")
    misses = [row for row in falls if row["detected"] != "1"]
    if not misses:
        lines.append("Every fall video fired the shipped rule.")
    else:
        lines.append(
            f"{len(misses)} fall videos did not fire. "
            "The blocker is whichever single shipped threshold, when relaxed on its own inside the real detector, "
            "turns that video into a detection. Pose measurements under the reason are descriptive; they are not a second detector."
        )
        steep = brief_flat = 0
        for row in misses:
            geom = (geometries or {}).get(row["video_id"]) or {}
            lowest = geom.get("angle_at_lowest")
            flat_frames = geom.get("flat_frames")
            pose_frames = num(row.get("pose_frames"), int) or 0
            if lowest is not None and lowest > 60:
                steep += 1
            if flat_frames is not None and flat_frames <= 2 and pose_frames >= 3:
                brief_flat += 1
        if steep or brief_flat:
            lines.append("")
            lines.append(
                f"In {steep} misses the shoulder–hip axis is still steeper than 60° when the torso center "
                "is lowest in the frame. From an upper corner, a person lying along the camera's line of sight "
                "keeps that axis steep, so the near-horizontal check never starts. "
                f"{brief_flat} misses are within 28° of horizontal on two or fewer sampled frames. "
                "A large raw drop and a flat raw angle on different frames do not satisfy the rule: both have to "
                "hold on the smoothed pose, after an upright start, and then the 2000 ms stillness hold."
            )
        lines.append("")
        grouped = defaultdict(list)
        for row in misses:
            grouped[row.get("blocker") or "unspecified"].append(row)
        order = sorted(grouped, key=lambda key: (-len(grouped[key]), key))
        summary = [[blocker_title(key), str(len(grouped[key]))] for key in order]
        lines.append(markdown_table(["What blocked the shipped rule", "Misses"], summary))
        lines.append("")
        for key in order:
            examples = sorted(grouped[key], key=lambda row: row["video_id"])[:4]
            bits = []
            for row in examples:
                subject_info = (details or {}).get(row["subject"], {})
                activity_info = subject_info.get("activities", {}).get(row["activity"])
                reasons = explain(row, activity_info, subject_info, (geometries or {}).get(row["video_id"]))
                detail = "; ".join(reasons) if reasons else "no extra pose note"
                bits.append(f"`{row['video_id']}` ({row['activity']}, {detail})")
            lines.append(f"- **{blocker_title(key)}** ({len(grouped[key])}): " + "; ".join(bits) + ".")
    lines.append("")
    lines.append("## Threshold sweep")
    lines.append("")
    lines.append(
        "Each one-parameter table changes only that field of `FallConfig` and leaves the others at the shipped values. "
        "The grid behind the recommendation also crosses minDrop, lie angle, stillness movement, and drop window. "
        "Nothing here is written back into the app."
    )
    if sweep and max(int(row["adl_false"]) for row in sweep) == 0:
        lines.append("")
        lines.append(
            "Every row in this sweep, including the grid, has 0 ADL false alarms. "
            "Inside the ranges searched, loosening these four fields did not make walking, hopping, "
            "picking up, sitting, or kneeling fire, and it still left most falls undetected. "
            "These tables do not locate the false-alarm boundary of a closer, side-on phone view."
        )
    lines.append("")
    lines.append("### minDrop")
    lines.append("")
    lines.append(sweep_table(slice_rows(sweep, "minDrop"), "min_drop", "minDrop", 0.18))
    lines.append("")
    lines.append("### Torso lie angle (maxLieAngleDeg)")
    lines.append("")
    lines.append(sweep_table(slice_rows(sweep, "maxLieAngleDeg"), "max_lie_angle_deg", "maxLieAngleDeg", 28))
    lines.append("")
    lines.append("### stillMovement")
    lines.append("")
    lines.append(sweep_table(slice_rows(sweep, "stillMovement"), "still_movement", "stillMovement", 0.035))
    lines.append("")
    lines.append("### dropWindowMs")
    lines.append("")
    lines.append(sweep_table(slice_rows(sweep, "dropWindowMs"), "drop_window_ms", "dropWindowMs", 800))
    lines.append("")
    lines.append("## Recommended setting")
    lines.append("")
    lines.append(
        "Recommendation rule used on this sweep only: do not add ADL false alarms, do not add sitting or kneeling "
        "false alarms, and do not detect fewer falls than the shipped rule. Among settings that pass, prefer more "
        "falls, then fewer ADL alarms, then fewer changed fields."
    )
    lines.append("")
    same = config_distance(recommended, default)[0] == 0 and int(recommended["falls_detected"]) == int(default["falls_detected"])
    if same:
        lines.append(
            f"**Keep the shipped thresholds** (`{fmt_config(default)}`). "
            f"On this set they detect {default['falls_detected']}/{default['falls_total']} falls and raise "
            f"{default['adl_false']}/{default['adl_total']} ADL false alarms "
            f"(sitting {default['sitting_false']}/{default['sitting_total']}, "
            f"kneeling {default['kneeling_false']}/{default['kneeling_total']}). "
            "No evaluated setting detected more falls without adding a daily-activity alarm."
        )
    else:
        lines.append(
            f"**Suggested for this camera geometry, not applied:** `{fmt_config(recommended)}`. "
            f"It detects {recommended['falls_detected']}/{recommended['falls_total']} falls versus "
            f"{default['falls_detected']}/{default['falls_total']} shipped, with "
            f"{recommended['adl_false']}/{recommended['adl_total']} ADL false alarms "
            f"(sitting {recommended['sitting_false']}/{recommended['sitting_total']}, "
            f"kneeling {recommended['kneeling_false']}/{recommended['kneeling_total']}). "
            "Confirm on a phone before editing `FallConfig`."
        )
        missed = int(recommended["falls_total"]) - int(recommended["falls_detected"])
        if missed * 2 > int(recommended["falls_total"]):
            lines.append("")
            lines.append(
                f"That setting still misses {missed} of {recommended['falls_total']} falls. "
                "Changing these thresholds does not make the corner-camera set reliable, and `FallConfig` was left as shipped."
            )
    lines.append("")
    if best_recall and (
        int(best_recall["falls_detected"]) != int(recommended["falls_detected"])
        or int(best_recall["adl_false"]) != int(recommended["adl_false"])
    ):
        lines.append(
            f"The highest fall count in the sweep is {best_recall['falls_detected']}/{best_recall['falls_total']} "
            f"at `{fmt_config(best_recall)}`, with {best_recall['adl_false']}/{best_recall['adl_total']} ADL false alarms "
            f"(sitting {best_recall['sitting_false']}/{best_recall['sitting_total']}, "
            f"kneeling {best_recall['kneeling_false']}/{best_recall['kneeling_total']}). "
            "That tradeoff is reported here and is not the recommended edit."
        )
        lines.append("")
    if samples:
        lines.append("## Example frames")
        lines.append("")
        lines.append("Small JPEG crops from the CC BY 4.0 videos, for the rows above. They are not used by the detector.")
        lines.append("")
        for rel, caption in samples:
            lines.append(f"![{caption}]({rel})")
            lines.append("")
            lines.append(caption)
            lines.append("")
    lines.append("## Limits")
    lines.append("")
    lines.append(
        "The falls are acted by young adults (the dataset paper lists ages 23–40), instructed by a kinesiologist, "
        "wearing protective pads. An unwitnessed fall by an older person can look different."
    )
    lines.append("")
    lines.append(
        "The camera is a fixed HIKVISION sensor in an upper corner, about 2.15 m high. The paper describes 1080×960 "
        "at about 23 fps; the version-5 files used here are the sizes and rates above. Some clips are IR "
        "with little visible light, with occlusion and people at different distances. The product camera is a phone "
        "propped on a table or shelf, usually much closer, at 640×480 analysis, with a ViewPort crop. A drop of 0.18 "
        "of the frame is a different physical motion in those two pictures. These counts do not measure on-phone accuracy."
    )
    lines.append("")
    lines.append(
        "Landmarks come from the Python MediaPipe tasks runtime, not from Android `PoseReader`. The model file, "
        "IMAGE mode, one pose, and 0.35 confidence match. Camera rotation, the front-camera mirror, and the "
        "FILL_CENTER crop do not run in this harness."
    )
    lines.append("")
    lines.append(
        "A timeout-worthy detection waits 2000 ms after the lying pose. Clips that end sooner cannot confirm, "
        "even when the drop and the angle were right. Delay is measured from the on-ground label, so a median "
        "near 2000 ms means the hold, not a late drop."
    )
    lines.append("")
    lines.append(
        "Not checked here: a physical phone, heat at 640×480, manufacturer background limits, or whether a real "
        "resident who stays on the floor matches these acted landings. Thresholds in the app were not changed."
    )
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--per-video", type=Path, default=ROOT / "out" / "replay" / "per_video.csv")
    parser.add_argument("--sweep", type=Path, default=ROOT / "out" / "replay" / "threshold_sweep.csv")
    parser.add_argument("--manifest", type=Path, default=ROOT / "out" / "landmarks" / "manifest.csv")
    parser.add_argument("--data", type=Path, default=ROOT / "data")
    parser.add_argument("--report", type=Path, default=ROOT / "RESULTS.md")
    parser.add_argument("--results", type=Path, default=ROOT / "results")
    parser.add_argument("--samples", type=Path, default=ROOT / "samples")
    parser.add_argument("--details", type=Path, default=ROOT / "data" / "CAUCAFall" / "Dataset details.xlsx")
    parser.add_argument("--skip-samples", action="store_true")
    args = parser.parse_args()
    videos = read_csv(args.per_video)
    sweep = read_csv(args.sweep)
    manifest_rows = read_csv(args.manifest) if args.manifest.is_file() else []
    samples: list[tuple[str, str]] = []
    if not args.skip_samples and manifest_rows:
        samples = pick_samples(videos, {row["video_id"]: row for row in manifest_rows}, args.data, args.samples)
    details = load_dataset_details(args.details)
    geometries: dict[str, dict] = {}
    if args.manifest.is_file():
        for row in videos:
            csv_path = args.manifest.parent / f"{row['video_id']}.csv"
            if csv_path.is_file():
                geometries[row["video_id"]] = pose_geometry(csv_path)
    report = build_report(videos, sweep, manifest_rows, samples, details, geometries)
    args.report.write_text(report)
    args.results.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.per_video, args.results / "per_video.csv")
    shutil.copyfile(args.sweep, args.results / "threshold_sweep.csv")
    falls = sum(1 for row in videos if row["kind"] == "fall")
    fall_hits = sum(1 for row in videos if row["kind"] == "fall" and row["detected"] == "1")
    adls = sum(1 for row in videos if row["kind"] == "adl")
    adl_hits = sum(1 for row in videos if row["kind"] == "adl" and row["detected"] == "1")
    print(f"wrote {args.report}")
    print(f"falls {fall_hits}/{falls} adl_false {adl_hits}/{adls}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
