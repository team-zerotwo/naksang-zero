#!/usr/bin/env python3
"""Summarize Kotlin rule + model + lying-still replays.

Reads per_video_signals.csv written by CaucaFallReplay. UR rows are aggregated
only. The per-video file for that set is not copied into the repo.
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path


def read_csv(path: Path) -> list[dict]:
    with path.open(newline="") as handle:
        return list(csv.DictReader(handle))


def pct(part: int, whole: int) -> str:
    if whole <= 0:
        return "—"
    return f"{100.0 * part / whole:.1f}% ({part}/{whole})"


def median(values: list[int]) -> str:
    if not values:
        return "—"
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return str(ordered[mid])
    return str(int(round((ordered[mid - 1] + ordered[mid]) / 2)))


def delays(rows: list[dict], key: str) -> list[int]:
    out = []
    for row in rows:
        if row.get("kind") != "fall":
            continue
        if not row.get(key):
            continue
        if not row.get("onset_ms"):
            continue
        out.append(int(row[key]) - int(row["onset_ms"]))
    return out


def count_hits(rows: list[dict], kind: str, key: str) -> tuple[int, int]:
    subset = [row for row in rows if row.get("kind") == kind]
    hits = sum(1 for row in subset if row.get(key))
    return hits, len(subset)


def table(headers: list[str], rows: list[list[str]]) -> str:
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1] / "validation" / "out")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parents[1] / "validation" / "results" / "v6_signals.md")
    args = parser.parse_args()
    sets = (
        ("caucafall", "CAUCAFall"),
        ("gmdcsa", "GMDCSA-24"),
        ("urfall", "UR Fall"),
    )
    body = ["# Rules, pose model, and lying-still", ""]
    summary = []
    for folder, title in sets:
        path = args.root / folder / "replay" / "per_video_signals.csv"
        if not path.is_file():
            body.append(f"{title}: missing {path}")
            continue
        rows = read_csv(path)
        summary.append(
            [
                title,
                pct(*count_hits(rows, "fall", "rule_at_ms")),
                pct(*count_hits(rows, "fall", "model_at_ms")),
                pct(*count_hits(rows, "fall", "lying_at_ms")),
                pct(*count_hits(rows, "fall", "combined_at_ms")),
                pct(*count_hits(rows, "adl", "rule_at_ms")),
                pct(*count_hits(rows, "adl", "model_at_ms")),
                pct(*count_hits(rows, "adl", "lying_at_ms")),
                pct(*count_hits(rows, "adl", "combined_at_ms")),
                median(delays(rows, "rule_at_ms")),
                median(delays(rows, "combined_at_ms")),
            ]
        )
        falls = [row for row in rows if row.get("kind") == "fall"]
        saw = sum(1 for row in falls if row.get("saw_lying") == "1")
        fast = sum(1 for row in falls if row.get("saw_fast_descent") == "1")
        lying_hit, _ = count_hits(rows, "fall", "lying_at_ms")
        body.append(
            f"{title}: {saw}/{len(falls)} fall clips entered the floor-lying pose, "
            f"{fast} of those descents were fast, {lying_hit} finished the stillness timer inside the file."
        )
        views = sorted({(row.get("view") or "").strip() or "unspecified" for row in rows})
        if len(views) > 1:
            for view in views:
                subset = [row for row in rows if ((row.get("view") or "").strip() or "unspecified") == view]
                summary.append(
                    [
                        title,
                        pct(*count_hits(subset, "fall", "rule_at_ms")),
                        pct(*count_hits(subset, "fall", "model_at_ms")),
                        pct(*count_hits(subset, "fall", "lying_at_ms")),
                        pct(*count_hits(subset, "fall", "combined_at_ms")),
                        pct(*count_hits(subset, "adl", "rule_at_ms")),
                        pct(*count_hits(subset, "adl", "model_at_ms")),
                        pct(*count_hits(subset, "adl", "lying_at_ms")),
                        pct(*count_hits(subset, "adl", "combined_at_ms")),
                        median(delays(subset, "rule_at_ms")),
                        median(delays(subset, "combined_at_ms")),
                    ]
                )
                summary[-1][0] = f"{title} / {view}"
    body.append("")
    body.append(
        table(
            [
                "Dataset",
                "Rule falls",
                "Model falls",
                "Lying-still falls",
                "Combined falls",
                "Rule ADL",
                "Model ADL",
                "Lying ADL",
                "Combined ADL",
                "Rule median delay",
                "Combined median delay",
            ],
            summary,
        )
    )
    body.append("")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(body) + "\n")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
