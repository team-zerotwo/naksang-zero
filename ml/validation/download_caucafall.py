#!/usr/bin/env python3
"""Download CAUCAFall videos and per-frame labels from Mendeley Data.

The published zip is about 7.8 GB because every frame is also stored as a PNG.
This script uses the public dataset API and keeps only what the offline
validation needs: each activity's .avi, the YOLO .txt labels (including
classes.txt), and Dataset details.xlsx. PNG frames are skipped.

Dataset: Eraso, Muñoz, Muñoz, Pinto, Universidad del Cauca / Universidad de
la Amazonia. CAUCAFall, Mendeley Data, V5. DOI 10.17632/7w7fccy7ky.5.
License: CC BY 4.0.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from http.client import IncompleteRead
from pathlib import Path

DATASET_ID = "7w7fccy7ky"
VERSION = "5"
API = "https://data.mendeley.com/public-api"
HEADERS = {
    "Accept": "application/vnd.mendeley-public-dataset.1+json",
    "User-Agent": "naksang-caucafall-validation/1.0",
}
KEEP_SUFFIXES = {".avi", ".txt", ".xlsx"}


def fetch_json(url: str, retries: int = 5):
    last_error = None
    for attempt in range(retries):
        try:
            request = urllib.request.Request(url, headers=HEADERS)
            with urllib.request.urlopen(request, timeout=90) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, IncompleteRead, OSError) as exc:
            last_error = exc
            time.sleep(min(30, 2 ** attempt))
    raise RuntimeError(f"GET {url} failed: {last_error}")


def list_folders() -> list[dict]:
    return fetch_json(f"{API}/datasets/{DATASET_ID}/folders/{VERSION}")


def list_files(folder_id: str) -> list[dict]:
    files: list[dict] = []
    start = 0
    while True:
        url = (
            f"{API}/datasets/{DATASET_ID}/files"
            f"?folder_id={folder_id}&version={VERSION}&$start={start}&$limit=1000"
        )
        batch = fetch_json(url)
        files.extend(batch)
        if len(batch) < 1000:
            return files
        start += 1000


def build_index(dest: Path) -> list[dict]:
    folders = list_folders()
    by_id = {item["id"]: item for item in folders}
    root = next(item for item in folders if item["name"] == "CAUCAFall" and not item.get("parent_id"))
    subjects = [item for item in folders if item.get("parent_id") == root["id"]]
    entries: list[dict] = []

    root_files = list_files("root")
    for item in root_files:
        name = item["filename"]
        if Path(name).suffix.lower() not in KEEP_SUFFIXES:
            continue
        entries.append(file_entry(dest / "CAUCAFall" / name, item))

    for subject in sorted(subjects, key=lambda item: item["name"]):
        activities = [item for item in folders if item.get("parent_id") == subject["id"]]
        for activity in sorted(activities, key=lambda item: item["name"]):
            folder = dest / "CAUCAFall" / subject["name"] / activity["name"]
            for item in list_files(activity["id"]):
                name = item["filename"]
                if Path(name).suffix.lower() not in KEEP_SUFFIXES:
                    continue
                entries.append(file_entry(folder / name, item))
            print(f"indexed {subject['name']} / {activity['name']}", flush=True)
    # by_id is unused beyond the walk; keep the name lookup honest if a parent is missing.
    missing = [item["name"] for item in folders if item.get("parent_id") not in by_id and item["id"] != root["id"]]
    if missing:
        print("warning: folders outside the subject tree:", ", ".join(missing), flush=True)
    return entries


def file_entry(path: Path, item: dict) -> dict:
    details = item["content_details"]
    return {
        "path": str(path),
        "url": details["download_url"],
        "size": int(item.get("size") or details.get("size") or 0),
        "sha256": details.get("sha256_hash") or "",
        "filename": item["filename"],
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_one(entry: dict) -> str:
    path = Path(entry["path"])
    expected = int(entry["size"])
    if path.is_file() and expected and path.stat().st_size == expected:
        if path.suffix.lower() == ".avi" and entry.get("sha256"):
            if sha256_file(path) != entry["sha256"]:
                path.unlink()
            else:
                return "skip"
        else:
            return "skip"
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    last_error = None
    for attempt in range(5):
        try:
            request = urllib.request.Request(entry["url"], headers={"User-Agent": HEADERS["User-Agent"]})
            with urllib.request.urlopen(request, timeout=180) as response, tmp.open("wb") as handle:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    handle.write(chunk)
            if expected and tmp.stat().st_size != expected:
                raise IOError(f"size {tmp.stat().st_size} != {expected}")
            if path.suffix.lower() == ".avi" and entry.get("sha256"):
                got = sha256_file(tmp)
                if got != entry["sha256"]:
                    raise IOError(f"sha256 {got} != {entry['sha256']}")
            tmp.replace(path)
            return "ok"
        except (urllib.error.URLError, TimeoutError, IncompleteRead, OSError) as exc:
            last_error = exc
            if tmp.exists():
                tmp.unlink()
            time.sleep(min(30, 2 ** attempt))
    raise RuntimeError(f"{path.name}: {last_error}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dest",
        type=Path,
        default=Path(__file__).resolve().parent / "data",
        help="Directory that will contain CAUCAFall/ (gitignored)",
    )
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--refresh-index", action="store_true")
    args = parser.parse_args()
    dest = args.dest
    dest.mkdir(parents=True, exist_ok=True)
    index_path = dest / "file_index.json"
    if index_path.is_file() and not args.refresh_index:
        entries = json.loads(index_path.read_text())
        print(f"using cached index {index_path} ({len(entries)} files)", flush=True)
    else:
        print("listing CAUCAFall folders and labels/videos...", flush=True)
        entries = build_index(dest)
        index_path.write_text(json.dumps(entries))
        print(f"wrote {index_path} ({len(entries)} files)", flush=True)

    # Videos first so pose extraction can start while the per-frame labels finish.
    entries.sort(key=lambda entry: (0 if str(entry["filename"]).lower().endswith(".avi") else 1, entry["path"]))
    avis = [entry for entry in entries if entry["filename"].lower().endswith(".avi")]
    txts = [entry for entry in entries if entry["filename"].lower().endswith(".txt")]
    print(
        f"queued {len(avis)} videos, {len(txts)} label files, "
        f"{len(entries) - len(avis) - len(txts)} other",
        flush=True,
    )
    ok = skip = failed = 0
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {pool.submit(download_one, entry): entry for entry in entries}
        done = 0
        for future in as_completed(futures):
            done += 1
            entry = futures[future]
            try:
                status = future.result()
            except Exception as exc:  # noqa: BLE001 - keep going and report every miss
                failed += 1
                failures.append(f"{entry['path']}: {exc}")
                print(f"FAIL {entry['filename']}: {exc}", flush=True)
                continue
            if status == "skip":
                skip += 1
            else:
                ok += 1
            if done % 200 == 0 or done == len(entries):
                print(f"progress {done}/{len(entries)} downloaded={ok} skipped={skip} failed={failed}", flush=True)
    summary = {
        "dataset_id": DATASET_ID,
        "version": VERSION,
        "doi": "10.17632/7w7fccy7ky.5",
        "files": len(entries),
        "videos": len(avis),
        "labels": len(txts),
        "downloaded": ok,
        "skipped": skip,
        "failed": failed,
        "failures": failures,
    }
    (dest / "download_summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({key: summary[key] for key in ("videos", "labels", "downloaded", "skipped", "failed")}))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
