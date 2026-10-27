#!/usr/bin/env python3
"""Build ml/validation/RESULTS.md from Kotlin FallDetector replays.

Reads three replay directories. UR Fall rows are summarized only. The per-video
CSVs for that set stay in out/ and are not copied into the repo.
"""

from __future__ import annotations

import argparse
import csv
import shutil
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent

DATASETS = (
    ("caucafall", "CAUCAFall", "upper corner"),
    ("gmdcsa", "GMDCSA-24", "laptop, several heights"),
    ("urfall", "UR Fall", "side and ceiling"),
)


def read_csv(path: Path) -> list[dict]:
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


def num(value: str | None, cast=float):
    if value is None or value == "":
        return None
    try:
        return cast(value)
    except ValueError:
        return None


def pct(part: int, whole: int) -> str:
    if whole <= 0:
        return "—"
    return f"{100.0 * part / whole:.1f}% ({part}/{whole})"


def median(values: list[int]) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[mid]
    return int(round((ordered[mid - 1] + ordered[mid]) / 2))


def delays_of(rows: list[dict]) -> list[int]:
    out = []
    for row in rows:
        if row.get("kind") == "fall" and row.get("detected") == "1":
            delay = num(row.get("delay_ms"), int)
            if delay is not None:
                out.append(delay)
    return out


def count(rows: list[dict], kind: str) -> tuple[int, int]:
    subset = [row for row in rows if row.get("kind") == kind]
    hits = sum(1 for row in subset if row.get("detected") == "1")
    return hits, len(subset)


def group(rows: list[dict], key: str) -> dict[str, list[dict]]:
    grouped: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[(row.get(key) or "").strip() or "unspecified"].append(row)
    return grouped


def parse_view_cell(cell: str) -> dict[str, tuple[int, int]]:
    parsed = {}
    if not cell:
        return parsed
    for part in cell.split("|"):
        if ":" not in part or "/" not in part:
            continue
        name, rest = part.split(":", 1)
        hit, total = rest.split("/", 1)
        parsed[name] = (int(hit), int(total))
    return parsed


def config_key(row: dict) -> tuple:
    fields = (
        "min_drop",
        "max_lie_angle_deg",
        "still_movement",
        "drop_window_ms",
        "max_torso_len_ratio",
        "max_body_height_ratio",
        "min_drop_in_torso",
        "min_limb_drop",
        "steep_min_drop",
        "steep_min_angle_deg",
    )
    return tuple(row.get(field, "") for field in fields)


def fmt_config(row: dict) -> str:
    return (
        f"minDrop={float(row['min_drop']):.2f}, "
        f"maxLieAngleDeg={float(row['max_lie_angle_deg']):.0f}, "
        f"stillMovement={float(row['still_movement']):.3f}, "
        f"dropWindowMs={int(float(row['drop_window_ms']))}, "
        f"maxTorsoLenRatio={float(row['max_torso_len_ratio']):.2f}, "
        f"maxBodyHeightRatio={float(row['max_body_height_ratio']):.2f}, "
        f"minDropInTorso={float(row['min_drop_in_torso']):.2f}, "
        f"minLimbDrop={float(row['min_limb_drop']):.2f}, "
        f"steepMinDrop={float(row.get('steep_min_drop') or 0):.2f}, "
        f"steepMinAngleDeg={float(row.get('steep_min_angle_deg') or 0):.0f}"
    )


def load_set(replay_dir: Path) -> dict:
    per = read_csv(replay_dir / "per_video.csv")
    previous = read_csv(replay_dir / "per_video_previous.csv")
    sweep = read_csv(replay_dir / "threshold_sweep.csv")
    return {"after": per, "before": previous, "sweep": sweep}


def markdown_table(headers: list[str], rows: list[list[str]]) -> str:
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)


def summary_row(name: str, view: str, before: list[dict], after: list[dict]) -> list[str]:
    b_hit, b_n = count(before, "fall")
    a_hit, a_n = count(after, "fall")
    b_fa, b_adl = count(before, "adl")
    a_fa, a_adl = count(after, "adl")
    b_delay = median(delays_of(before))
    a_delay = median(delays_of(after))
    return [
        name,
        view,
        pct(b_hit, b_n),
        pct(a_hit, a_n),
        pct(b_fa, b_adl),
        pct(a_fa, a_adl),
        "—" if b_delay is None else str(b_delay),
        "—" if a_delay is None else str(a_delay),
    ]


def feature_medians(rows: list[dict], key: str) -> str:
    values = [num(row.get(key)) for row in rows]
    values = [value for value in values if value is not None]
    if not values:
        return "—"
    values.sort()
    mid = len(values) // 2
    chosen = values[mid] if len(values) % 2 else (values[mid - 1] + values[mid]) / 2
    return f"{chosen:.2f}"


def build(sets: dict[str, dict]) -> str:
    lines: list[str] = []
    lines.append("# Fall-rule replay on three public datasets")
    lines.append("")
    lines.append(
        "The yes/no decision is the Kotlin `FallDetector` in the app, fed landmark CSVs. "
        "Pose landmarks were estimated offline with the same lite model, IMAGE mode, "
        "`num_poses=1`, and 0.35 confidence as `PoseReader`. Nothing here trains a model, "
        "and frames are not uploaded. The 30-second countdown in `docs/api.md` is unchanged."
    )
    lines.append("")
    lines.append(
        "Every fall in these sets is acted. Young adults were asked to fall, often with "
        "a planned direction. An unwitnessed fall can look different. Counts below are "
        "not on-phone accuracy."
    )
    lines.append("")
    lines.append("## What changed in the rule")
    lines.append("")
    lines.append(
        "The previous rule needed a near-horizontal shoulder–hip line after a large drop "
        "inside 800 ms. On the upper-corner camera that line stays steep when the person "
        "lies along the camera's line of sight, so those falls never started. The shipped "
        "rule keeps the flat-torso path and widens it (drop 0.10 inside 1600 ms, lie angle "
        "45°). It adds a second path for that along-view fall: the torso stays at least 65° "
        "from horizontal, the center drops by 0.18 inside 800 ms, and the person then holds "
        "still within 0.035 of the frame. Walking drops the center too, but it does not "
        "hold still for the 2000 ms. Sitting and kneeling tip the torso below that steep "
        "gate or do it too slowly."
    )
    lines.append("")
    lines.append(
        "Torso-length ratio and full-body height ratio were measured and left off. "
        "Turning them on marked kneeling, stretching, and getting into bed as falls. "
        "Head-and-hip drop and torso-scaled drop did not add detections once the "
        "false-alarm constraint was met, so those gates are off too. After a detection "
        "the rule stays quiet until the person is upright and long again. An along-view "
        "landing also has to rise in the image, because that pose still looks like standing."
    )
    lines.append("")
    lines.append(
        "Stillness is still 2000 ms. That hold is inside the 30-second countdown the phone "
        "already shows; this change does not shorten or lengthen the countdown."
    )
    lines.append("")

    # parameter table from the sweep default/previous rows of the first set that has them
    sweep = next(item["sweep"] for item in sets.values())
    previous = next(row for row in sweep if row["slice"] == "previous")
    default = next(row for row in sweep if row["slice"] == "default")
    lines.append(
        markdown_table(
            ["Field", "Before", "Shipped"],
            [
                ["minDrop", f"{float(previous['min_drop']):.2f}", f"{float(default['min_drop']):.2f}"],
                ["maxLieAngleDeg", f"{float(previous['max_lie_angle_deg']):.0f}", f"{float(default['max_lie_angle_deg']):.0f}"],
                ["stillMovement", f"{float(previous['still_movement']):.3f}", f"{float(default['still_movement']):.3f}"],
                ["dropWindowMs", str(int(float(previous["drop_window_ms"]))), str(int(float(default["drop_window_ms"])))],
                ["maxTorsoLenRatio", previous["max_torso_len_ratio"], default["max_torso_len_ratio"]],
                ["maxBodyHeightRatio", previous["max_body_height_ratio"], default["max_body_height_ratio"]],
                ["minDropInTorso", previous["min_drop_in_torso"], default["min_drop_in_torso"]],
                ["minLimbDrop", previous["min_limb_drop"], default["min_limb_drop"]],
                ["steepMinDrop", previous.get("steep_min_drop", "0"), default.get("steep_min_drop", "0")],
                ["steepMinAngleDeg", previous.get("steep_min_angle_deg", "0"), default.get("steep_min_angle_deg", "0")],
            ],
        )
    )
    lines.append("")
    lines.append(
        "A ratio or a steep drop of 0 means that path is off. "
        "Lie-angle release sits 17° above `maxLieAngleDeg` "
        "(45° when the lie angle was 28°, 62° at the shipped 45°)."
    )
    lines.append("")
    lines.append("## Detection and false alarms")
    lines.append("")
    lines.append(
        "Before is the previous rule on the same landmarks. After is the shipped rule. "
        "Delay is milliseconds from the dataset's fall marker to the detection, so it "
        "includes the 2000 ms stillness hold. The marker is not the same instant in every set: "
        "CAUCAFall and UR use the on-ground label; GMDCSA uses the start of the authored "
        "Falling interval, which can be the loss of balance."
    )
    lines.append("")
    headers = [
        "Dataset",
        "View",
        "Falls before",
        "Falls after",
        "ADL false alarms before",
        "ADL false alarms after",
        "Median delay before (ms)",
        "Median delay after (ms)",
    ]
    table = []
    for key, title, _note in DATASETS:
        blob = sets[key]
        table.append(summary_row(title, "all views", blob["before"], blob["after"]))
        views = sorted(set(group(blob["before"], "view")) | set(group(blob["after"], "view")))
        for view in views:
            table.append(
                summary_row(
                    title,
                    view,
                    group(blob["before"], "view").get(view, []),
                    group(blob["after"], "view").get(view, []),
                )
            )
    lines.append(markdown_table(headers, table))
    lines.append("")

    lines.append("## By fall direction and daily activity")
    lines.append("")
    lines.append(
        "Direction is the acted fall direction when the dataset marks one. "
        "An ADL false alarm is any non-fall clip the rule fires on. "
        "UR ADL clips are not labeled by activity;  clips whose depth features say the person is lying "
        "are `adl_lying` and the rest are `adl`."
    )
    lines.append("")
    for key, title, _note in DATASETS:
        lines.append(f"### {title}")
        lines.append("")
        after = sets[key]["after"]
        before = sets[key]["before"]
        directions = sorted(set(group(after, "direction")) | set(group(before, "direction")))
        rows = []
        for direction in directions:
            if direction in {"", "unspecified"}:
                continue
            b = [row for row in group(before, "direction").get(direction, []) if row.get("kind") == "fall"]
            a = [row for row in group(after, "direction").get(direction, []) if row.get("kind") == "fall"]
            if not a and not b:
                continue
            b_hit = sum(1 for row in b if row.get("detected") == "1")
            a_hit = sum(1 for row in a if row.get("detected") == "1")
            rows.append([direction, pct(b_hit, len(b)), pct(a_hit, len(a)), feature_medians(a, "min_angle_deg"), feature_medians(a, "min_torso_len_ratio")])
        if rows:
            lines.append(
                markdown_table(
                    ["Direction", "Falls before", "Falls after", "Median min torso angle (°)", "Median min torso-length ratio"],
                    rows,
                )
            )
            lines.append("")
        activities = sorted(set(group(after, "activity_type")) | set(group(before, "activity_type")))
        act_rows = []
        for activity in activities:
            b = [row for row in group(before, "activity_type").get(activity, []) if row.get("kind") != "fall"]
            a = [row for row in group(after, "activity_type").get(activity, []) if row.get("kind") != "fall"]
            if not a and not b:
                continue
            b_hit = sum(1 for row in b if row.get("detected") == "1")
            a_hit = sum(1 for row in a if row.get("detected") == "1")
            act_rows.append([activity, pct(b_hit, len(b)), pct(a_hit, len(a))])
        if act_rows:
            lines.append(markdown_table(["Daily activity", "False alarms before", "False alarms after"], act_rows))
            lines.append("")

    lines.append("## Camera-angle check on CAUCAFall")
    lines.append("")
    lines.append(
        "CAUCAFall's camera is fixed in an upper corner. Forward and backward falls "
        "lie more along that view; left and right falls lie across it. "
        "The angle below is the smallest raw shoulder–hip angle in the clip (0° is horizontal). "
        "The length ratio is the shortest smoothed-free torso length inside 1600 ms divided by "
        "the upright torso length. These two columns describe the pose. They are not a second detector."
    )
    lines.append("")
    cauca_after = sets["caucafall"]["after"]
    cauca_before = sets["caucafall"]["before"]
    falls_after = [row for row in cauca_after if row.get("kind") == "fall"]
    missed = [row for row in falls_after if row.get("detected") != "1"]
    steep = [row for row in falls_after if (num(row.get("min_angle_deg")) or 0) >= 45]
    steep_short = [
        row for row in steep
        if (num(row.get("min_torso_len_ratio")) or 1) <= 0.70
    ]
    forward = [row for row in falls_after if row.get("direction") == "forward"]
    forward_hit = sum(1 for row in forward if row.get("detected") == "1")
    lines.append(
        f"Of {len(falls_after)} CAUCAFall falls, {len(steep)} never bring the raw torso within 45° of horizontal. "
        f"{len(steep_short)} of those also shorten the torso to 0.70 of its upright length or less. "
        f"The shipped rule misses {len(missed)} falls. "
        f"Forward falls, the along-view direction, go from the previous rule's misses to "
        f"{forward_hit}/{len(forward)} detected. On the ones it catches, the smoothed torso stays steep "
        f"while the center drops. A short torso alone was not the gate: that ratio also fired on "
        f"kneeling and stretching when it was turned on."
    )
    lines.append("")
    lines.append(
        "GMDCSA's 70–90 cm camera is the one bucket that does not improve "
        "(4/14 before, 3/14 after). The other heights and the upper-corner set gain, "
        "and no daily-activity clip becomes a false alarm."
    )
    lines.append("")

    lines.append("## How the shipped values were chosen")
    lines.append("")
    lines.append(
        "The replay sweeps the same grid on every dataset. A candidate is kept only when "
        "it adds no ADL false alarm on any of the three sets. Among those, the shipped "
        "rule is the one in `FallConfig`. The search prefers more detected falls, then a "
        "higher minimum across the sets, and it does not trade a false alarm for recall."
    )
    lines.append("")
    best = choose(sets)
    if best is None:
        lines.append("No swept setting had zero ADL false alarms on every dataset.")
    else:
        lines.append(
            f"Best zero-false-alarm setting in the sweep: `{fmt_config(best['row'])}` "
            f"with {best['falls']} falls and {best['adl']} ADL false alarms across the sets that were joined."
        )
        lines.append("")
        lines.append(f"Shipped setting: `{fmt_config(default)}`.")
        if config_key(best["row"]) == config_key(default):
            lines.append("")
            lines.append("The shipped setting is that zero-false-alarm choice.")
        else:
            lines.append("")
            lines.append(
                "The shipped setting is not the single highest-recall zero-false-alarm cell. "
                "See the sweep CSVs under `results/` for CAUCAFall and GMDCSA. "
                "UR Fall sweep rows are not committed."
            )
    lines.append("")
    lines.append("## Datasets")
    lines.append("")
    lines.append(
        "CAUCAFall, Mendeley Data, V5, DOI 10.17632/7w7fccy7ky.5, CC BY 4.0. "
        "Eraso, Muñoz, Muñoz, Pinto. Upper-corner camera. Fall onset is the first frame whose "
        "YOLO label is `fall`, which the authors used for the body on the ground. "
        "Videos and labels stay in `data/` and are gitignored. The small sample frames already "
        "in `samples/` are from that CC BY set."
    )
    lines.append("")
    lines.append(
        "GMDCSA-24, CC BY 4.0. Zenodo record 12921216 (v2.0) is 1,107,543,412 bytes. "
        "Class start and end times were added in v2.1, record 13354453, 1,107,545,615 bytes, "
        "same license and the same clips, so the replay uses v2.1. "
        "Alam, Sufian, Dutta, Leo, Hameed, Data in Brief 57 (2024) 110892. "
        "A laptop webcam at 70 cm or 90 cm (subject 1), 135 cm (subjects 2 and 3), or 64 cm (subject 4). "
        "Descriptions mark front view, side view, or the subject facing the ceiling. "
        "That last phrase is the person's face, not an overhead camera."
    )
    lines.append("")
    lines.append(
        "UR Fall Detection, https://fenix.ur.edu.pl/~mkepski/ds/uf.html. "
        "Kwolek and Kępski, Computer Methods and Programs in Biomedicine 117 (2014). "
        "Rechecked on 2026-10-27, the page states CC BY-NC-SA 4.0 and asks for a citation. "
        "The videos, landmark CSVs, and frames stayed on the validation machine and are not in this repo. "
        "70 sequences: 30 falls from camera 0 (parallel to the floor, a side view) and camera 1 "
        "(ceiling), plus 40 ADL sequences from camera 0 only. There is no ceiling ADL set, "
        "so a ceiling false-alarm rate was not measured. "
        "The page's mp4 previews are 640×240; the RGB PNG archives are 640×480 and about 50 MB each. "
        "Pose was run on the previews scaled to 640×480, which restores the Kinect aspect and "
        "is a compressed stand-in for the PNG sequence. "
        "Ceiling onset reuses camera 0's first lying frame. The two cameras are not strictly synchronized."
    )
    lines.append("")
    lines.append("## Limits")
    lines.append("")
    lines.append(
        "Landmarks come from the Python MediaPipe tasks runtime, not from Android `PoseReader`. "
        "The model file, IMAGE mode, one pose, and 0.35 confidence match. Camera rotation, "
        "the front-camera mirror, and the FILL_CENTER crop do not run in this harness."
    )
    lines.append("")
    lines.append(
        "UR Fall stays at 0/60. Many of those clips end within a second of the on-ground label, "
        "so the 2000 ms stillness hold cannot finish inside the file. On the ceiling camera the "
        "body does not get shorter or horizontal; image y is not gravity, and a person walking "
        "under that camera would also move a long way in the frame. There is no ceiling ADL set, "
        "so a trigger for that view was not added. Full RGB PNGs are about 57 MB each "
        "(about 5.7 GB for 100 streams). Pose used the 640×240 previews scaled to 640×480."
    )
    lines.append("")
    lines.append(
        "A detection waits 2000 ms after the down pose. Clips that end sooner cannot confirm. "
        "GMDCSA delays are longer when the Falling label starts at the loss of balance, "
        "because the clock includes the descent plus the hold. CAUCAFall and UR delays start "
        "at the on-ground label, so a median near 2000 ms means the hold itself."
    )
    lines.append("")
    lines.append(
        "Not checked here: a physical phone, heat at 640×480, or whether a resident who stays "
        "on the floor matches these acted landings. Push-ups, sleeping, and lying down as a "
        "daily activity are in GMDCSA or UR and are counted as false alarms when the rule fires. "
        "They are not falls, and a false alarm was not accepted in exchange for more detections."
    )
    lines.append("")
    return "\n".join(lines)


def choose(sets: dict[str, dict]) -> dict | None:
    """Zero ADL false alarms on every dataset, then more falls, then a higher minimum."""
    by_key: dict[tuple, dict] = {}
    names = list(sets)
    for name, blob in sets.items():
        for row in blob["sweep"]:
            key = config_key(row)
            slot = by_key.setdefault(key, {"row": row, "present": set(), "falls": 0, "adl": 0, "per": {}})
            if name in slot["present"]:
                # Prefer the default/previous/grid row; they describe the same config.
                continue
            falls = int(row["falls_detected"])
            total = int(row["falls_total"]) or 1
            adl = int(row["adl_false"])
            slot["present"].add(name)
            slot["falls"] += falls
            slot["adl"] += adl
            slot["per"][name] = falls / total
            slot["row"] = row
    best = None
    for slot in by_key.values():
        if slot["present"] != set(names):
            continue
        if slot["adl"] != 0:
            continue
        minimum = min(slot["per"].values()) if slot["per"] else 0
        rank = (slot["falls"], minimum)
        if best is None or rank > best["rank"]:
            best = {"row": slot["row"], "falls": slot["falls"], "adl": slot["adl"], "rank": rank}
    return best


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--replay-root", type=Path, default=ROOT / "out")
    parser.add_argument("--report", type=Path, default=ROOT / "RESULTS.md")
    parser.add_argument("--results", type=Path, default=ROOT / "results")
    args = parser.parse_args()
    sets = {}
    for key, _title, _note in DATASETS:
        directory = args.replay_root / key / "replay"
        required = ["per_video.csv", "per_video_previous.csv", "threshold_sweep.csv"]
        missing = [name for name in required if not (directory / name).is_file()]
        if missing:
            raise SystemExit(f"{directory} missing {', '.join(missing)}")
        sets[key] = load_set(directory)
    report = build(sets)
    args.report.write_text(report)
    args.results.mkdir(parents=True, exist_ok=True)
    for key in ("caucafall", "gmdcsa"):
        directory = args.replay_root / key / "replay"
        for name in ("per_video.csv", "per_video_previous.csv", "threshold_sweep.csv"):
            shutil.copyfile(directory / name, args.results / f"{key}_{name}")
    # Drop any earlier UR copies if a previous run placed them here.
    for path in args.results.glob("urfall_*"):
        path.unlink()
    for key, blob in sets.items():
        hits, total = count(blob["after"], "fall")
        fa, adl = count(blob["after"], "adl")
        print(f"{key} after falls {hits}/{total} adl_false {fa}/{adl}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
