"""Read and edit Grove's shared files (Grove/grove.json and Grove/history.json in the vault).

Mirrors the app's rules (app/src/main/java/com/grove/app/data/Logic.kt) so streaks match what
the phone shows. Plain Python, no MCP here, so it can be tested on its own.
"""
from __future__ import annotations

import json
import os
import re
import time
from datetime import date, timedelta
from pathlib import Path

WEEKDAYS = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"]
TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


class GroveError(ValueError):
    """A request that can't be applied; the message is shown to Claude."""


class Grove:
    def __init__(self, vault: str | os.PathLike):
        self.dir = Path(vault).expanduser() / "Grove"
        self.setup_path = self.dir / "grove.json"
        self.history_path = self.dir / "history.json"

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
        for t in setup.get("tasks", []):
            repeating = any(t.get("days", []))
            on = (t["days"][wd] and (not t.get("firstOfMonth") or d.day <= 7)) if repeating else t.get("date") == d.isoformat()
            if on:
                out.append({"id": t["id"], "name": t["name"], "time": t.get("time", ""), "list": t.get("list", "")})
        for h in setup.get("holyDays", []):
            hd = date.fromisoformat(h["date"])
            if hd == d:
                out.append({"id": "holy-" + h["id"], "name": f"{h['name']}: {h['detail']}", "time": "14:00", "list": "Holy day"})
            if hd - timedelta(days=3) == d:
                out.append({"id": "pre-" + h["id"], "name": f"{h['name']} in 3 days: prepare", "time": "14:00", "list": "Holy day"})
        return sorted(out, key=lambda t: t["time"])

    # ----- reports -----

    def overview(self) -> str:
        setup, history = self.load_setup(), self.load_history()
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
        setup, history = self.load_setup(), self.load_history()
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
            lines.append(f"{d.strftime('%a %b')} {d.day}: habits {len(done)}/{len(habits)}"
                         + (f"; missed: {', '.join(missed)}" if missed else "")
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

    @staticmethod
    def new_id(prefix: str, existing: list[dict]) -> str:
        used = {x["id"] for x in existing}
        n = len(existing) + 1
        while f"{prefix}{n}" in used:
            n += 1
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
            "id": self.new_id("h", setup["habits"]), "name": name.strip(), "whenText": when_text or "Any time today",
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
        key = {"habit": "habits", "task": "tasks", "holy day": "holyDays"}[kind]
        setup = self.load_setup()
        item = self.find(setup[key], item_id, kind)
        if kind == "habit" and item.get("role"):
            raise GroveError(f"{item['name']} controls the day ({item['role']}); edit it instead of removing it.")
        setup[key] = [x for x in setup[key] if x["id"] != item_id]
        self.save_setup(setup)
        return f"Removed {kind} {item['name']}."

    def add_task(self, name: str, time_due: str = "16:30", days: list[str] | None = None, date_due: str | None = None, list_name: str = "Inbox",
                 reminders: list[str] | None = None, nag: bool = True, nag_minutes: int = 30, first_of_month: bool = False,
                 prop: str = "", high: bool = False) -> dict:
        setup = self.load_setup()
        flags = self.parse_days(days, [False] * 7)
        if not any(flags) and not date_due:
            raise GroveError("A one-time task needs date_due (YYYY-MM-DD); a repeating task needs days.")
        t = {
            "id": self.new_id("t", setup["tasks"]), "name": name.strip(), "list": list_name, "days": flags,
            "date": "" if any(flags) else self.check_date(date_due), "time": self.check_times([time_due])[0],
            "firstOfMonth": first_of_month and any(flags), "prop": prop, "high": high,
            "reminders": self.check_times(reminders) if reminders is not None else [time_due], "nag": nag, "nagMinutes": nag_minutes,
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
            elif k == "date_due":
                t["date"] = self.check_date(v)
                t["days"] = [False] * 7
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
                   "template_path": "templatePath", "wolf_name": "wolfName"}
        for k, v in changes.items():
            if v is not None and k in mapping:
                setup["settings"][mapping[k]] = v
        self.save_setup(setup)
        return setup["settings"]
