"""Read and edit Grove's shared files (Grove/grove.json and Grove/history.json in the vault).

Mirrors the app's rules (app/src/main/java/com/grove/app/data/Logic.kt) so streaks match what
the phone shows. Plain Python, no MCP here, so it can be tested on its own.
"""
from __future__ import annotations

import json
import math
import os
import re
import time
import uuid
from datetime import date, timedelta
from pathlib import Path

WEEKDAYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]
TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


class GroveError(ValueError):
    """A request that can't be applied; the message is shown to Claude."""


class Grove:
    def __init__(self, vault: str | os.PathLike):
        self.vault = Path(vault).expanduser()
        self.dir = self.vault / "Grove"
        self.setup_path = self.dir / "grove.json"
        self.history_path = self.dir / "history.json"
        self.inbox = self.dir / "inbox"

    # ----- files -----

    def load_setup(self) -> dict:
        if not self.setup_path.exists():
            raise GroveError(f"{self.setup_path} not found. Open Grove on the phone, pick the vault folder in Settings, and let Syncthing sync.")
        return json.loads(self.setup_path.read_text(encoding="utf-8"))

    def load_history(self) -> dict:
        if not self.history_path.exists():
            return {"currentDay": date.today().isoformat(), "days": {}}
        return json.loads(self.history_path.read_text(encoding="utf-8"))

    def save_setup(self, setup: dict) -> None:
        """Write atomically so Syncthing never picks up a half-written file."""
        tmp = self.dir / f".grove.json.{os.getpid()}.{int(time.time() * 1000)}.tmp"
        tmp.write_text(json.dumps(setup, indent=4, ensure_ascii=False) + "\n", encoding="utf-8")
        os.replace(tmp, self.setup_path)

    # ----- rules (same as the app) -----

    @staticmethod
    def weekday_index(d: date) -> int:
        return (d.weekday() + 1) % 7  # Sunday-first, like the app

    def scheduled(self, h: dict, d: date) -> bool:
        return bool(h.get("days", [True] * 7)[self.weekday_index(d)])

    @staticmethod
    def steps_done(history: dict, day: str, h: dict) -> set[int]:
        rec = history.get("days", {}).get(day, {})
        return {i for i in rec.get("steps", {}).get(h["id"], []) if i < len(h["steps"])}

    def is_done(self, history: dict, day: str, h: dict) -> bool:
        return len(h["steps"]) > 0 and len(self.steps_done(history, day, h)) == len(h["steps"])

    def streak(self, history: dict, h: dict) -> int:
        today = date.fromisoformat(history["currentDay"])
        created = date.fromisoformat(h["created"])
        n = 1 if self.is_done(history, history["currentDay"], h) else 0
        misses = 0
        d = today - timedelta(days=1)
        while d >= created:
            if self.scheduled(h, d):
                if self.is_done(history, d.isoformat(), h):
                    n += 1
                    misses = 0
                else:
                    misses += 1
                    if misses >= 2:
                        break
            d -= timedelta(days=1)
        return n

    def total_days(self, history: dict, h: dict) -> int:
        return sum(1 for day in history.get("days", {}) if self.is_done(history, day, h))

    def tasks_on(self, setup: dict, d: date) -> list[dict]:
        out = []
        wd = self.weekday_index(d)
        last = (date(d.year + d.month // 12, d.month % 12 + 1, 1) - timedelta(days=1)).day
        for t in setup.get("tasks", []):
            if t.get("monthDay", 0) > 0:
                # Monthly; in shorter months a 29th-31st falls on the last day (same as the app).
                on = d.day == min(t["monthDay"], last)
            elif any(t.get("days", [])):
                on = t["days"][wd] and (not t.get("firstOfMonth") or d.day <= 7)
            else:
                on = t.get("date") == d.isoformat()
            if on:
                out.append({"id": t["id"], "name": t["name"], "time": t.get("time", ""), "list": t.get("list", "")})
        for h in setup.get("holyDays", []):
            hd = date.fromisoformat(h["date"])
            if hd == d:
                out.append({"id": "holy-" + h["id"], "name": f"{h['name']}: {h['detail']}", "time": "14:00", "list": "Holy day"})
            if hd - timedelta(days=3) == d:
                out.append({"id": "pre-" + h["id"], "name": f"{h['name']} in 3 days: prepare", "time": "14:00", "list": "Holy day"})
        return sorted(out, key=lambda t: t["time"])

    # ----- weekly goals and books (same rules as Logic.kt; weeks start Monday) -----

    def pending_inbox(self, history: dict) -> list[dict]:
        """Logs written here that the app hasn't applied yet."""
        applied = set(history.get("appliedInbox", []))
        out = []
        if self.inbox.exists():
            for f in sorted(self.inbox.glob("*.json")):
                if self.is_sync_junk(f.name):
                    continue
                try:
                    e = json.loads(f.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    continue
                if e.get("id") not in applied:
                    out.append(e)
        return out

    def effective_history(self) -> dict:
        """history.json plus pending inbox logs, so progress shows right away."""
        history = self.load_history()
        days = {k: {**v, "steps": dict(v.get("steps", {})), "tasks": dict(v.get("tasks", {})), "pages": dict(v.get("pages", {})),
                    "sessions": list(v.get("sessions", []))} for k, v in history.get("days", {}).items()}
        habits = {h["id"]: h for h in (self.load_setup().get("habits", []) if self.setup_path.exists() else [])}
        for e in self.pending_inbox(history):
            rec = days.setdefault(e.get("day") or history["currentDay"], {"steps": {}, "tasks": {}, "pages": {}, "sessions": []})
            rec.setdefault("pages", {})
            rec.setdefault("sessions", [])
            if e.get("type") == "pages":
                rec["pages"][e["book"]] = max(0, rec["pages"].get(e["book"], 0) + e.get("pages", 0))
            elif e.get("type") == "session" and e.get("goal") not in rec["sessions"]:
                rec["sessions"].append(e["goal"])
            elif e.get("type") == "habit" and e.get("item") in habits:
                rec.setdefault("steps", {})[e["item"]] = list(range(len(habits[e["item"]]["steps"]))) if e.get("done", True) else []
            elif e.get("type") == "task":
                rec.setdefault("tasks", {})
                if e.get("done", True):
                    rec["tasks"].setdefault(e["item"], "")
                else:
                    rec["tasks"].pop(e.get("item"), None)
        return {**history, "days": days}

    @staticmethod
    def week_start(d: date) -> date:
        return d - timedelta(days=d.weekday())

    @staticmethod
    def session_logged(history: dict, day: str, g: dict) -> bool:
        rec = history.get("days", {}).get(day)
        if not rec:
            return False
        return g["id"] in rec.get("sessions", []) or bool(g.get("legacyHabit") and rec.get("steps", {}).get(g["legacyHabit"]))

    def sessions_this_week(self, history: dict, g: dict, ref: date) -> int:
        ws = self.week_start(ref)
        return sum(1 for i in range(7) if (d := ws + timedelta(days=i)) <= ref and self.session_logged(history, d.isoformat(), g))

    def total_sessions(self, history: dict, g: dict) -> int:
        return sum(1 for day in history.get("days", {}) if self.session_logged(history, day, g))

    @staticmethod
    def pages_logged(history: dict, book_id: str, before: date | None = None) -> int:
        return sum(rec.get("pages", {}).get(book_id, 0) for day, rec in history.get("days", {}).items()
                   if before is None or date.fromisoformat(day) < before)

    def book_page(self, history: dict, b: dict, before: date | None = None) -> int:
        return max(0, min(b["pages"], b.get("startPage", 0) + self.pages_logged(history, b["id"], before)))

    def pages_this_week(self, history: dict, ref: date) -> int:
        ws = self.week_start(ref)
        return sum(sum(rec.get("pages", {}).values()) for day, rec in history.get("days", {}).items() if ws <= date.fromisoformat(day) <= ref)

    def weekly_page_target(self, setup: dict, history: dict, g: dict, ref: date) -> int:
        ws = self.week_start(ref)
        left = sum(b["pages"] - self.book_page(history, b, before=ws) for b in setup.get("books", []))
        weeks = max(1, math.ceil((date.fromisoformat(g["deadline"]) - ws).days / 7)) if g.get("deadline") else 1
        return max(0, math.ceil(left / weeks))

    def progress(self, setup: dict, history: dict, g: dict, ref: date) -> tuple[int, int]:
        if g["kind"] == "pages":
            return self.pages_this_week(history, ref), self.weekly_page_target(setup, history, g, ref)
        return self.sessions_this_week(history, g, ref), g.get("perWeek", 3)

    def current_book(self, setup: dict, history: dict, last: str | None = None) -> dict | None:
        if last is None:  # the book read most recently, like the app's lastBook
            last = next((bid for _, rec in sorted(history.get("days", {}).items(), reverse=True)
                         for bid, n in rec.get("pages", {}).items() if n > 0), None)
        open_books = [b for b in setup.get("books", []) if self.book_page(history, b) < b["pages"]]
        return next((b for b in open_books if b["id"] == last), open_books[0] if open_books else None)

    def find_book(self, setup: dict, book: str) -> dict:
        books = setup.get("books", [])
        for b in books:
            if b["id"] == book:
                return b
        matches = [b for b in books if book.lower() in b["title"].lower()]
        if len(matches) == 1:
            return matches[0]
        names = ", ".join(f"{b['id']} {b['title']}" for b in books)
        raise GroveError(f"Couldn't tell which book {book!r} means. Use an id: {names}")

    @staticmethod
    def inbox_id() -> str:
        """Starts with the time, so the app applies logs in the order they were made."""
        return f"{time.time_ns() // 1000:016d}-{uuid.uuid4().hex[:8]}"

    def _write_inbox(self, entry: dict) -> None:
        self.inbox.mkdir(exist_ok=True)
        tmp = self.inbox / f".{entry['id']}.tmp"
        tmp.write_text(json.dumps(entry) + "\n", encoding="utf-8")
        os.replace(tmp, self.inbox / f"{entry['id']}.json")

    def log_pages(self, book: str, pages: int, day: str | None = None) -> str:
        setup, history = self.load_setup(), self.load_history()
        b = self.find_book(setup, book)
        if pages == 0:
            raise GroveError("pages must not be 0.")
        day = self.check_date(day) or history["currentDay"]
        self._write_inbox({"id": self.inbox_id(), "type": "pages", "day": day, "book": b["id"], "pages": pages})
        eff = self.effective_history()
        goal = next((g for g in setup.get("goals", []) if g["kind"] == "pages"), None)
        msg = f"Logged {pages} pages of {b['title']} on {day}: now p. {self.book_page(eff, b)} of {b['pages']}."
        if self.link_note(setup, b):
            self.save_setup(setup)
        if self.sync_book_note(eff, b):
            msg += f" Updated {b['notePath']}."
        if goal:
            done, target = self.progress(setup, eff, goal, date.fromisoformat(eff["currentDay"]))
            msg += f" This week: {done} / {target} pages."
        return msg + " It reaches the phone when Syncthing syncs and Grove opens."

    def mark_done(self, item: str, day: str | None = None, done: bool = True) -> str:
        """Check a habit or task off (or undo it) on today or any earlier day."""
        setup, history = self.load_setup(), self.load_history()
        everything = [("habit", h) for h in setup.get("habits", [])] + [("task", t) for t in setup.get("tasks", [])]
        found = [x for x in everything if x[1]["id"] == item] or [x for x in everything if item.lower() in x[1]["name"].lower()]
        if len(found) != 1:
            names = ", ".join(f"{x[1]['id']} {x[1]['name']}" for x in everything)
            raise GroveError(f"Couldn't tell which habit or task {item!r} means. Use an id: {names}")
        kind, x = found[0]
        day = self.check_date(day) or history["currentDay"]
        if date.fromisoformat(day) > date.fromisoformat(history["currentDay"]):
            raise GroveError(f"{day} is after Grove's today ({history['currentDay']}); only today or earlier days can be marked.")
        self._write_inbox({"id": self.inbox_id(), "type": kind, "day": day, "item": x["id"], "done": done})
        eff = self.effective_history()
        msg = f"Marked {x['name']} {'done' if done else 'not done'} on {day}."
        if kind == "habit":
            msg += f" Streak now {self.streak(eff, x)}."
        return msg + " It reaches the phone when Syncthing syncs and Grove opens."

    def log_session(self, goal: str | None = None, day: str | None = None) -> str:
        setup, history = self.load_setup(), self.load_history()
        goals = [g for g in setup.get("goals", []) if g["kind"] == "sessions"]
        g = next((x for x in goals if goal in (None, x["id"]) or (goal and goal.lower() in x["name"].lower())), None)
        if g is None:
            raise GroveError("No session goal found. Use grove_overview to see goal ids.")
        day = self.check_date(day) or history["currentDay"]
        eff = self.effective_history()
        if self.session_logged(eff, day, g):
            return f"{g['name']} already has a session on {day} (one per day)."
        self._write_inbox({"id": self.inbox_id(), "type": "session", "day": day, "goal": g["id"]})
        eff = self.effective_history()
        done, target = self.progress(setup, eff, g, date.fromisoformat(eff["currentDay"]))
        return f"Logged a {g['name']} session on {day}: {done}/{target} this week."

    def set_book(self, title: str | None = None, pages: int | None = None, start_page: int | None = None, book_id: str | None = None) -> dict:
        setup = self.load_setup()
        setup.setdefault("books", [])
        if book_id:
            b = self.find_book(setup, book_id)
        else:
            if not title or not pages:
                raise GroveError("A new book needs a title and pages.")
            b = {"id": self.new_id("b", setup), "title": title, "pages": 0, "startPage": 0, "piece": self.piece_for(title)}
            setup["books"].append(b)
        if title:
            b["title"] = title
        if pages is not None:
            if pages <= 0:
                raise GroveError("pages must be more than 0.")
            b["pages"] = pages
        if start_page is not None:
            b["startPage"] = max(0, min(start_page, b["pages"]))
        self.link_note(setup, b)
        self.save_setup(setup)
        self.sync_book_note(self.effective_history(), b)
        return b

    # ----- book notes (same rules as Logic.kt / NoteText.kt) -----

    STOP = {"a", "an", "the", "of", "and", "to", "for", "in"}
    PIECES = [("mushroom", "mushrooms"), ("fung", "mushrooms"), ("wildflower", "wildflowers"), ("flower", "wildflowers"),
              ("weed", "weeds"), ("tree", "trees"), ("bird", "birds"), ("mammal", "mammals"), ("rock", "rocks"),
              ("mineral", "rocks"), ("frog", "frogs"), ("toad", "frogs"), ("salamander", "frogs"), ("snake", "snakes"), ("reptile", "snakes")]

    @staticmethod
    def title_key(title: str) -> str:
        t = title.lower().split(" (")[0].split(":")[0]
        return re.sub(r"[^a-z0-9]+", " ", t).strip()

    def title_score(self, a: str, b: str) -> float:
        x, y = self.title_key(a), self.title_key(b)
        if not x or not y:
            return 0.0
        if x == y or x.startswith(y + " ") or y.startswith(x + " "):
            return 1.0
        wx = {w for w in x.split(" ") if w not in self.STOP}
        wy = {w for w in y.split(" ") if w not in self.STOP}
        if min(len(wx), len(wy)) >= 2 and (wx <= wy or wy <= wx):
            return 0.9
        return len(wx & wy) / max(1, len(wx | wy))

    def piece_for(self, title: str) -> str:
        t = title.lower()
        return next((piece for word, piece in self.PIECES if word in t), "")

    @staticmethod
    def frontmatter(text: str) -> dict:
        lines = text.replace("\r\n", "\n").split("\n")
        if not lines or lines[0].strip() != "---":
            return {}
        out = {}
        for line in lines[1:]:
            if line.strip() == "---":
                break
            if line.startswith((" ", "-")) or ":" not in line:
                continue
            k, v = line.split(":", 1)
            v = v.strip()
            for q in ('"', "'"):
                if len(v) >= 2 and v.startswith(q) and v.endswith(q):
                    v = v[1:-1]
            out[k.strip()] = v
        return out

    @staticmethod
    def set_frontmatter(text: str, values: dict) -> str:
        """Set keys to raw YAML values (None removes the key); every other line stays as it was."""
        if not values:
            return text
        nl = "\r\n" if "\r\n" in text else "\n"
        lines = text.split(nl)
        has_front = bool(lines) and lines[0].strip() == "---" and any(x.strip() == "---" for x in lines[1:])
        if not has_front:
            added = [f"{k}: {v}" for k, v in values.items() if v is not None]
            return nl.join(["---", *added, "---", *lines]) if added else text
        remaining = dict(values)
        i = 1
        while i < len(lines) and lines[i].strip() != "---":
            line = lines[i]
            key = "" if line.startswith((" ", "-")) or ":" not in line else line.split(":", 1)[0].strip()
            if key and key in remaining:
                v = remaining.pop(key)
                while i + 1 < len(lines) and lines[i + 1].strip() != "---" and lines[i + 1].startswith((" ", "-")):
                    del lines[i + 1]
                if v is None:
                    del lines[i]
                    continue
                lines[i] = f"{key}: {v}"
            i += 1
        lines[i:i] = [f"{k}: {v}" for k, v in remaining.items() if v is not None]
        return nl.join(lines)

    @staticmethod
    def last_read_on(history: dict, b: dict) -> str | None:
        days = [d for d, rec in history.get("days", {}).items() if rec.get("pages", {}).get(b["id"], 0) > 0]
        return max(days) if days else None

    @staticmethod
    def finished_on(history: dict, b: dict) -> str | None:
        total = b.get("startPage", 0)
        for day in sorted(history.get("days", {})):
            total += history["days"][day].get("pages", {}).get(b["id"], 0)
            if total >= b["pages"]:
                return day
        return None

    def book_note_values(self, history: dict, b: dict, note: dict) -> dict:
        page = self.book_page(history, b)
        out = {"pagesRead": str(page)}
        if (last := self.last_read_on(history, b)):
            out["lastReadOn"] = last
        if self.page_count(note.get("pages")) is None:
            out["pages"] = str(b["pages"])
        if b["pages"] > 0 and page >= b["pages"]:
            out["read"] = "true"
            if not note.get("finished"):
                out["finished"] = self.finished_on(history, b) or history["currentDay"]
        elif note.get("finished"):
            out["read"] = "false"
            out["finished"] = None
        return out

    @staticmethod
    def is_sync_junk(name: str) -> bool:
        """Syncthing conflict copies, temp files and hidden folders (.stfolder, .stversions): never read or linked."""
        return ".sync-conflict-" in name or name.startswith(".") or name.startswith("~syncthing~")

    @staticmethod
    def page_count(v) -> int | None:
        """A frontmatter page count, or None when it's missing, empty, "unknown" or otherwise not a number."""
        v = str(v if v is not None else "").strip()
        return int(v) if v.isdigit() and int(v) > 0 else None

    def druid_book_notes(self, setup: dict) -> list[dict]:
        folder = setup.get("settings", {}).get("booksFolder", "Books").strip("/") or "Books"
        out = []
        for f in sorted((self.vault / folder).glob("*.md")):
            if self.is_sync_junk(f.name):
                continue
            try:
                front = self.frontmatter(f.read_text(encoding="utf-8"))
            except OSError:
                continue
            if (front.get("subType") or front.get("subtype") or "").lower() == "druid book":
                out.append({"path": f"{folder}/{f.name}", "title": front.get("title") or f.stem, "pages": self.page_count(front.get("pages")) or 0})
        return out

    def link_note(self, setup: dict, b: dict) -> bool:
        """Same rules as Logic.linkBookNotes for one book: a deleted note is unlinked (never written back),
        and an exact title match wins over a looser link. Returns True if notePath changed."""
        old = b.get("notePath", "")
        if old and not (self.vault / old).is_file():
            b["notePath"] = ""
        notes = self.druid_book_notes(setup)
        claimed = {x.get("notePath") for x in setup.get("books", []) if x is not b}
        free = [n for n in notes if n["path"] not in claimed and n["path"] != b.get("notePath")]
        best = max(free, key=lambda n: self.title_score(b["title"], n["title"]), default=None)
        if best:
            score = self.title_score(b["title"], best["title"])
            current = next((self.title_score(b["title"], n["title"]) for n in notes if n["path"] == b.get("notePath")), 1.0)
            if (not b.get("notePath") and score >= 0.6) or (b.get("notePath") and score >= 1.0 and current < 1.0):
                b["notePath"] = best["path"]
        return b.get("notePath", "") != old

    def sync_book_note(self, history: dict, b: dict) -> bool:
        """Write progress into the book's note. A note that can't be read is never written."""
        if not b.get("notePath"):
            return False
        path = self.vault / b["notePath"]
        try:
            before = path.read_text(encoding="utf-8")
        except OSError:
            return False
        after = self.set_frontmatter(before, self.book_note_values(history, b, self.frontmatter(before)))
        if after != before:
            tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
            tmp.write_text(after, encoding="utf-8")
            os.replace(tmp, path)
        return True

    # ----- floating to-dos (same as Todos.kt; the app keeps them in step with the to-do note) -----

    @staticmethod
    def shown_todos(setup: dict, day: str) -> list[dict]:
        todos = [t for t in setup.get("todos", []) if not t.get("done") or t.get("done") == day]
        return sorted(todos, key=lambda t: (bool(t.get("done")), not t.get("high"), t.get("due") or "9999", t.get("created", "")))

    def add_todo(self, name: str, due: str | None = None, list_name: str = "", high: bool = False, remind: str | None = None) -> dict:
        if not name.strip():
            raise GroveError("A to-do needs a name.")
        setup, history = self.load_setup(), self.load_history()
        t = {"id": self.new_id("d", setup), "name": name.strip(), "list": list_name.strip(), "high": high, "due": self.check_date(due) or "",
             "remind": (self.check_times([remind]) or [""])[0] if remind else "", "done": "", "created": history["currentDay"],
             "inNote": False, "noteName": "", "noteDone": False}
        setup.setdefault("todos", []).append(t)
        self.save_setup(setup)
        return t

    def update_todo(self, todo_id: str, name: str | None = None, due: str | None = None, list_name: str | None = None, high: bool | None = None,
                    remind: str | None = None, done: bool | None = None, day: str | None = None) -> dict:
        setup, history = self.load_setup(), self.load_history()
        t = self.find(setup.get("todos", []), todo_id, "to-do")
        if name is not None:
            if not name.strip():
                raise GroveError("A to-do needs a name.")
            t["name"] = name.strip()
        if due is not None:
            t["due"] = self.check_date(due) if due else ""
        if list_name is not None:
            t["list"] = list_name.strip()
        if high is not None:
            t["high"] = high
        if remind is not None:
            t["remind"] = self.check_times([remind])[0] if remind else ""
        if done is not None:
            t["done"] = (self.check_date(day) or history["currentDay"]) if done else ""
        self.save_setup(setup)
        return t

    # ----- evening check-in and quiet hours (same as Logic.kt) -----

    DEFAULT_NOTIFY = {"eveningCheckIn": True, "checkInTime": "21:00", "quietStart": "21:00", "quietEnd": "08:00"}

    def notify_settings(self, setup: dict) -> dict:
        return {**self.DEFAULT_NOTIFY, **{k: v for k, v in setup.get("settings", {}).items() if k in self.DEFAULT_NOTIFY}}

    @staticmethod
    def in_quiet_hours(settings: dict, hhmm: str) -> bool:
        """By the clock, wrapping past midnight (21:00-08:00). Start == end means no quiet hours."""
        mins = lambda t: int(t[:2]) * 60 + int(t[3:5])
        start, end, m = mins(settings["quietStart"]), mins(settings["quietEnd"]), mins(hhmm)
        if start == end:
            return False
        return start <= m < end if start < end else (m >= start or m < end)

    def check_in_items(self, setup: dict, history: dict) -> list[str]:
        """What the evening check-in lists: open habits (not Morning practice; Before-bed last) and tasks."""
        day = history["currentDay"]
        d = date.fromisoformat(day)
        rec = history.get("days", {}).get(day, {})
        open_habits = [h for h in setup["habits"] if h.get("role") != "morning" and self.scheduled(h, d) and not self.is_done(history, day, h)]
        habits = [h["name"] for h in open_habits if h.get("role") != "bedtime"]
        bedtime = [f"{h['name']} (when you go to bed)" for h in open_habits if h.get("role") == "bedtime"]
        tasks = [t["name"] for t in self.tasks_on(setup, d) if t["id"] not in rec.get("tasks", {})]
        return habits + tasks + bedtime

    # ----- reports -----

    def overview(self) -> str:
        setup, history = self.load_setup(), self.effective_history()
        day = history["currentDay"]
        d = date.fromisoformat(day)
        rec = history.get("days", {}).get(day, {})
        lines = [f"Grove day: {d.strftime('%A, %B')} {d.day}, {d.year} (days roll over at Morning practice, or {setup['settings'].get('cutoffHour', 14)}:00)", "", "Habits:"]
        for h in setup["habits"]:
            if not self.scheduled(h, d):
                continue
            done = self.is_done(history, day, h)
            n = len(self.steps_done(history, day, h))
            when = ", ".join(h.get("reminders", [])) or h.get("whenText", "")
            lines.append(f"- [{'x' if done else ' '}] {h['name']} (id {h['id']}): {n}/{len(h['steps'])} steps, "
                         f"streak {self.streak(history, h)}, total {self.total_days(history, h)} days, when: {when}")
        goals = setup.get("goals", [])
        if goals:
            lines += ["", "Weekly goals (weeks start Monday, no streaks):"]
            for g in goals:
                done, target = self.progress(setup, history, g, d)
                if g["kind"] == "pages":
                    b = self.current_book(setup, history)
                    where = f"; current book {b['title']} p. {self.book_page(history, b)} of {b['pages']}" if b else ""
                    lines.append(f"- {g['name']} (id {g['id']}): {done} / {target} pages this week, "
                                 f"{sum(bk['pages'] - self.book_page(history, bk) for bk in setup.get('books', []))} pages left, deadline {g.get('deadline')}{where}")
                else:
                    lines.append(f"- {g['name']} (id {g['id']}): {done}/{g.get('perWeek', 3)} sessions this week"
                                 f"{' (logged today)' if self.session_logged(history, day, g) else ''}, {self.total_sessions(history, g)} total")
        books = setup.get("books", [])
        if books:
            lines += ["", "Books:"]
            for b in books:
                page = self.book_page(history, b)
                lines.append(f"- {b['id']} {b['title']}: {page}/{b['pages']}" + (" (finished)" if page >= b["pages"] else "")
                             + (f", note: {b['notePath']}" if b.get("notePath") else ", no note linked"))
        ns = self.notify_settings(setup)
        quiet = "off" if ns["quietStart"] == ns["quietEnd"] else f"{ns['quietStart']}-{ns['quietEnd']}"
        check = f"check-in at {ns['checkInTime']}" if ns["eveningCheckIn"] else "check-in off"
        open_items = self.check_in_items(setup, history)
        lines += ["", f"Notifications: {check}, quiet hours {quiet}. Still open today: {', '.join(open_items) or 'nothing'}"]
        todos = self.shown_todos(setup, day)
        lines += ["", f"To-dos (no date, until checked off){' · linked to ' + setup['settings']['todoNotePath'] if setup['settings'].get('todoNotePath') else ''}:"]
        lines += [f"- [{'x' if t.get('done') else ' '}] {'! ' if t.get('high') else ''}{t['name']} (id {t['id']})"
                  + (f", by {t['due']}" if t.get("due") else "") + (f", {t['list']}" if t.get("list") else "") for t in todos] or ["- none"]
        lines += ["", "Tasks today:"]
        tasks = self.tasks_on(setup, d)
        if not tasks:
            lines.append("- none")
        for t in tasks:
            lines.append(f"- [{'x' if t['id'] in rec.get('tasks', {}) else ' '}] {t['time']} {t['name']} ({t['list']}, id {t['id']})")
        upcoming = sorted((h for h in setup.get("holyDays", []) if date.fromisoformat(h["date"]) >= d), key=lambda h: h["date"])[:3]
        if upcoming:
            lines += ["", "Upcoming holy days:"]
            lines += [f"- {h['name']} on {h['date']} ({(date.fromisoformat(h['date']) - d).days} days): {h['detail']}" for h in upcoming]
        return "\n".join(lines)

    def history_report(self, days: int = 14) -> str:
        setup, history = self.load_setup(), self.effective_history()
        end = date.fromisoformat(history["currentDay"])
        lines = []
        for i in range(days - 1, -1, -1):
            d = end - timedelta(days=i)
            key = d.isoformat()
            rec = history.get("days", {}).get(key, {})
            habits = [h["name"] for h in setup["habits"] if self.scheduled(h, d)]
            done = [h["name"] for h in setup["habits"] if self.scheduled(h, d) and self.is_done(history, key, h)]
            names = {t["id"]: t["name"] for t in setup.get("tasks", [])}
            tasks = [names.get(t, t) for t in rec.get("tasks", {})]
            missed = [n for n in habits if n not in done]
            sessions = [g["name"] for g in setup.get("goals", []) if g["kind"] == "sessions" and self.session_logged(history, key, g)]
            pages = sum(rec.get("pages", {}).values())
            lines.append(f"{d.strftime('%a %b')} {d.day}: habits {len(done)}/{len(habits)}"
                         + (f"; missed: {', '.join(missed)}" if missed else "")
                         + (f"; sessions: {', '.join(sessions)}" if sessions else "")
                         + (f"; {pages} pages read" if pages else "")
                         + (f"; tasks done: {', '.join(tasks)}" if tasks else ""))
        return "\n".join(lines)

    # ----- edits -----

    @staticmethod
    def parse_days(days: list[str] | None, default: list[bool]) -> list[bool]:
        """["daily"], ["weekdays"], ["weekends"] or weekday names like ["mon", "Thursday"]."""
        if days is None:
            return default
        names = [x.strip().lower() for x in days]
        if names in (["daily"], ["every day"], ["everyday"]):
            return [True] * 7
        if names == ["weekdays"]:
            return [False, True, True, True, True, True, False]
        if names == ["weekends"]:
            return [True, False, False, False, False, False, True]
        if names in ([], ["none"], ["once"]):
            return [False] * 7
        out = [False] * 7
        for n in names:
            if n[:3] not in WEEKDAYS:
                raise GroveError(f"Unknown weekday {n!r}. Use names like Mon, Tue, or 'daily', 'weekdays', 'weekends'.")
            out[WEEKDAYS.index(n[:3])] = True
        return out

    @staticmethod
    def check_times(times: list[str] | None) -> list[str] | None:
        if times is None:
            return None
        for t in times:
            if not TIME_RE.match(t):
                raise GroveError(f"Times must be 24-hour HH:MM, like '21:00'. Got {t!r}.")
        return sorted(set(times))

    @staticmethod
    def check_date(d: str | None) -> str | None:
        if d is None:
            return None
        try:
            return date.fromisoformat(d).isoformat()
        except ValueError:
            raise GroveError(f"Dates must be YYYY-MM-DD. Got {d!r}.") from None

    MIN_NEXT_ID = 27  # t15-t26 were handed out and removed before the counter existed

    def highest_id_number(self, setup: dict, history: dict) -> int:
        ids = [x["id"] for key in ("habits", "tasks", "goals", "books", "todos") for x in setup.get(key, [])]
        for rec in history.get("days", {}).values():
            ids += list(rec.get("steps", {})) + list(rec.get("tasks", {})) + rec.get("sessions", []) + list(rec.get("pages", {}))
        nums = [int(m.group(1)) for i in ids if (m := re.fullmatch(r"[a-z](\d{1,7})", i))]
        return max(nums, default=0)

    def new_id(self, prefix: str, setup: dict) -> str:
        """Same counter as the app (nextId in grove.json): ids are never handed out twice."""
        n = max(setup.get("nextId", 0), self.highest_id_number(setup, self.load_history()) + 1, self.MIN_NEXT_ID)
        setup["nextId"] = n + 1
        return f"{prefix}{n}"

    @staticmethod
    def find(items: list[dict], item_id: str, kind: str) -> dict:
        for x in items:
            if x["id"] == item_id:
                return x
        raise GroveError(f"No {kind} with id {item_id!r}. Use grove_setup to see ids.")

    @staticmethod
    def steps_from(steps: list[dict] | None, name: str) -> list[dict] | None:
        if steps is None:
            return None
        out = [{"label": str(s.get("label", "")).strip(), "prop": str(s.get("prop", "")).strip()} for s in steps]
        out = [s for s in out if s["label"]]
        return out or [{"label": name, "prop": ""}]

    def add_habit(self, name: str, steps: list[dict] | None = None, days: list[str] | None = None, reminders: list[str] | None = None,
                  nag: bool = True, nag_minutes: int = 15, when_text: str = "", pinned: bool = True) -> dict:
        setup = self.load_setup()
        h = {
            "id": self.new_id("h", setup), "name": name.strip(), "whenText": when_text or "Any time today",
            "days": self.parse_days(days, [True] * 7), "steps": self.steps_from(steps, name) or [{"label": name.strip(), "prop": ""}],
            "reminders": self.check_times(reminders) or [], "nag": nag, "nagMinutes": nag_minutes, "pinned": pinned,
            "role": "", "created": self.load_history()["currentDay"],
        }
        setup["habits"].append(h)
        self.save_setup(setup)
        return h

    def update_habit(self, habit_id: str, **changes) -> dict:
        setup = self.load_setup()
        h = self.find(setup["habits"], habit_id, "habit")
        mapping = {"name": "name", "when_text": "whenText", "nag": "nag", "nag_minutes": "nagMinutes", "pinned": "pinned"}
        for k, v in changes.items():
            if v is None:
                continue
            if k == "days":
                h["days"] = self.parse_days(v, h["days"])
            elif k == "reminders":
                h["reminders"] = self.check_times(v)
            elif k == "steps":
                h["steps"] = self.steps_from(v, h["name"])
            elif k in mapping:
                h[mapping[k]] = v
        self.save_setup(setup)
        return h

    def remove(self, kind: str, item_id: str) -> str:
        key = {"habit": "habits", "task": "tasks", "holy day": "holyDays", "to-do": "todos"}[kind]
        setup = self.load_setup()
        item = self.find(setup.get(key, []), item_id, kind)
        if kind == "habit" and item.get("role"):
            raise GroveError(f"{item['name']} controls the day ({item['role']}); edit it instead of removing it.")
        setup[key] = [x for x in setup[key] if x["id"] != item_id]
        self.save_setup(setup)
        return f"Removed {kind} {item['name']}."

    @staticmethod
    def check_month_day(n: int | None) -> int | None:
        if n is None:
            return None
        if not 0 <= int(n) <= 31:
            raise GroveError(f"month_day must be 1-31 (or 0 to stop repeating monthly). Got {n!r}.")
        return int(n)

    def add_task(self, name: str, time_due: str = "16:30", days: list[str] | None = None, date_due: str | None = None, list_name: str = "Inbox",
                 reminders: list[str] | None = None, nag: bool = True, nag_minutes: int = 30, first_of_month: bool = False,
                 prop: str = "", high: bool = False, month_day: int | None = None) -> dict:
        setup = self.load_setup()
        md = self.check_month_day(month_day) or 0
        flags = [False] * 7 if md else self.parse_days(days, [False] * 7)
        if not md and not any(flags) and not date_due:
            raise GroveError("A one-time task needs date_due (YYYY-MM-DD); a weekly task needs days; a monthly task needs month_day.")
        t = {
            "id": self.new_id("t", setup), "name": name.strip(), "list": list_name, "days": flags,
            "date": "" if (md or any(flags)) else self.check_date(date_due), "time": self.check_times([time_due])[0],
            "firstOfMonth": first_of_month and any(flags), "prop": prop, "high": high,
            "reminders": self.check_times(reminders) if reminders is not None else [time_due], "nag": nag, "nagMinutes": nag_minutes,
            "monthDay": md,
        }
        setup["tasks"].append(t)
        self.save_setup(setup)
        return t

    def update_task(self, task_id: str, **changes) -> dict:
        setup = self.load_setup()
        t = self.find(setup["tasks"], task_id, "task")
        mapping = {"name": "name", "list_name": "list", "nag": "nag", "nag_minutes": "nagMinutes", "first_of_month": "firstOfMonth",
                   "prop": "prop", "high": "high"}
        for k, v in changes.items():
            if v is None:
                continue
            if k == "days":
                t["days"] = self.parse_days(v, t["days"])
                if any(t["days"]):
                    t["date"] = ""
                    t["monthDay"] = 0
            elif k == "date_due":
                t["date"] = self.check_date(v)
                t["days"] = [False] * 7
                t["monthDay"] = 0
            elif k == "month_day":
                t["monthDay"] = self.check_month_day(v)
                if t["monthDay"]:
                    t["days"] = [False] * 7
                    t["date"] = ""
            elif k == "time_due":
                t["time"] = self.check_times([v])[0]
            elif k == "reminders":
                t["reminders"] = self.check_times(v)
            elif k in mapping:
                t[mapping[k]] = v
        self.save_setup(setup)
        return t

    def set_holy_day(self, name: str, date_on: str, detail: str, holy_id: str | None = None) -> dict:
        setup = self.load_setup()
        if holy_id:
            h = self.find(setup["holyDays"], holy_id, "holy day")
            h.update(name=name, date=self.check_date(date_on), detail=detail)
        else:
            h = {"id": re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-") + "-" + date_on[:4], "name": name, "date": self.check_date(date_on), "detail": detail}
            setup["holyDays"].append(h)
        self.save_setup(setup)
        return h

    def update_settings(self, **changes) -> dict:
        setup = self.load_setup()
        mapping = {"cutoff_hour": "cutoffHour", "default_snooze_minutes": "defaultSnoozeMinutes", "daily_note_path": "dailyNotePath",
                   "template_path": "templatePath", "wolf_name": "wolfName", "evening_check_in": "eveningCheckIn",
                   "check_in_time": "checkInTime", "quiet_start": "quietStart", "quiet_end": "quietEnd", "todo_note_path": "todoNotePath"}
        for k in ("check_in_time", "quiet_start", "quiet_end"):
            if changes.get(k) is not None:
                self.check_times([changes[k]])
        for k, v in changes.items():
            if v is not None and k in mapping:
                setup["settings"][mapping[k]] = v
        self.save_setup(setup)
        return setup["settings"]
