#!/usr/bin/env python3
"""Scrape the J-Fall 2026 timetable (https://jfall.nl/timetable-2026/) from its Sessionize embed.

Writes, in the same shape the app already reads:
  spring-ai/src/main/resources/data/dataset-jfall26-sessions.json  (no descriptions; tools + preferences)
  spring-ai/src/main/resources/data/dataset-jfall26.json           (with descriptions; RAG ingestion)

Usage: python3 tools/scrape_jfall_sessions.py
"""
import html
import json
import re
import time
import urllib.request
from pathlib import Path

EVENT = "t6vwxz4o"
GRID_URL = f"https://sessionize.com/api/v2/{EVENT}/view/GridSmart?under=True"
MODAL_URL = f"https://sessionize.com/api/v2/{EVENT}/{{}}?id={{}}"
OUT_DIR = Path(__file__).resolve().parent.parent / "spring-ai/src/main/resources/data"


def fetch(url: str, method: str = "GET") -> str:
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def clean(fragment: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", "", fragment)).strip()


def utc(ts: str) -> str:
    return ts  # Sessionize already uses the "2026-11-12T07:00:00.0000000Z" format the app expects


def parse_grid(page: str) -> list[dict]:
    blocks = re.split(r'(?=<div data-sessionid=")', page)[1:]
    sessions = []
    for block in blocks:
        sid = re.match(r'<div data-sessionid="([^"]+)" class="([^"]*)"', block)
        times = re.search(r'TimeWithDuration\|[^|]+\|([^|]+)\|([^"]+)"', block)
        title = re.search(r'class="sz-session__title">(.*?)</h3>', block, re.S)
        room = re.search(r'class="sz-session__room">(.*?)</div>', block, re.S)
        if not (sid and times and title):
            continue
        classes = sid.group(2)
        sessions.append({
            "id": sid.group(1),
            "title": clean(title.group(1)),
            "startsAt": utc(times.group(1)),
            "endsAt": utc(times.group(2)),
            "isServiceSession": "sz-session--service" in classes,
            "isPlenumSession": "sz-session--plenum" in classes,
            "category": [],  # J-Fall publishes no tracks or levels on Sessionize
            "room": clean(room.group(1)) if room else "",
            "speakers": [clean(s) for s in re.findall(r"<li data-speakerid=[^>]*>\s*<a[^>]*>(.*?)</a>", block, re.S)],
            "recordingUrl": None,
        })
    return sessions


def description(session: dict) -> str:
    kind = "servicesession" if session["isServiceSession"] else "session"
    modal = fetch(MODAL_URL.format(kind, session["id"]), method="POST")
    m = re.search(r'class="sz-session__description">(.*?)</p>', modal, re.S)
    if not m:
        return ""
    text = clean(re.sub(r"<br\s*/?>", "\n", m.group(1)))
    lines = [line.strip() for line in text.split("\n")]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def main() -> None:
    sessions = parse_grid(fetch(GRID_URL))
    sessions.sort(key=lambda s: (s["startsAt"], s["room"]))
    with_descriptions = []
    for s in sessions:
        with_descriptions.append({**s, "description": description(s)})
        time.sleep(0.2)

    # Breaks, opening and drinks carry no text: they are not sessions anyone searches for or
    # schedules, so they go to the venue file's day programme instead (printed here).
    def is_break(s: dict) -> bool:
        return s["isServiceSession"] and not s["description"]

    for s in filter(is_break, with_descriptions):
        print(f"programme: {s['startsAt']} - {s['endsAt']} {s['title']} ({s['room']})")
    with_descriptions = [s for s in with_descriptions if not is_break(s)]
    keep = {s["id"] for s in with_descriptions}
    sessions = [s for s in sessions if s["id"] in keep]

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "dataset-jfall26-sessions.json").write_text(
        json.dumps({"sessions": sessions}, indent=2, ensure_ascii=False) + "\n")
    (OUT_DIR / "dataset-jfall26.json").write_text(
        json.dumps({"sessions": with_descriptions}, indent=2, ensure_ascii=False) + "\n")
    print(f"{len(sessions)} sessions, {sum(bool(s['description']) for s in with_descriptions)} with descriptions")


if __name__ == "__main__":
    main()
