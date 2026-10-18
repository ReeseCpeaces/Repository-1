"""Run with: python3 -m pytest connector/tests  (or python3 connector/tests/test_grove.py)"""
import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from grove_core import Grove, GroveError  # noqa: E402


def make_vault() -> Path:
    vault = Path(tempfile.mkdtemp())
    (vault / "Grove").mkdir()
    habit = lambda i, name, role="": {"id": i, "name": name, "whenText": "", "days": [True] * 7, "steps": [{"label": name, "prop": ""}],
                                      "reminders": [], "nag": False, "nagMinutes": 15, "pinned": True, "role": role, "created": "2026-10-01"}
    setup = {"version": 1, "habits": [habit("h1", "Morning practice", "morning"), habit("h2", "Read")],
             "tasks": [{"id": "t1", "name": "Trash", "list": "Home", "days": [False, False, False, False, True, False, False], "date": "",
                        "time": "18:00", "firstOfMonth": False, "prop": "", "high": True, "reminders": ["18:00"], "nag": True, "nagMinutes": 30}],
             "holyDays": [{"id": "samhain", "name": "Samhain", "date": "2026-11-01", "detail": "Observe"}],
             "settings": {"vaultUri": "", "dailyNotePath": "x", "templatePath": "", "cutoffHour": 14, "defaultSnoozeMinutes": 10, "wolfName": ""}}
    done = lambda: {"steps": {"h2": [0]}, "tasks": {}, "habitTimes": {}, "lifestyle": False, "summaryWritten": False}
    history = {"version": 1, "currentDay": "2026-10-15",
               "days": {d: done() for d in ["2026-10-10", "2026-10-11", "2026-10-13", "2026-10-14"]}}
    (vault / "Grove/grove.json").write_text(json.dumps(setup))
    (vault / "Grove/history.json").write_text(json.dumps(history))
    return vault


def test_streak_matches_app_rules():
    g = Grove(make_vault())
    h = g.load_setup()["habits"][1]
    assert g.streak(g.load_history(), h) == 4  # one missed day (12th) only pauses it


def test_overview_and_history():
    g = Grove(make_vault())
    text = g.overview()
    assert "Read (id h2): 0/1 steps, streak 4, total 4 days" in text
    assert "Thursday, October 15" in text and "18:00 Trash" in text
    assert "Oct 12: habits 0/2; missed: Morning practice, Read" in g.history_report(5)


def test_edits_round_trip():
    g = Grove(make_vault())
    h = g.add_habit("Discursive meditation", days=["daily"], reminders=["21:00"], nag_minutes=15)
    assert h["id"] == "h3" and h["reminders"] == ["21:00"] and h["created"] == "2026-10-15"
    g.update_habit("h3", days=["weekdays"], reminders=[])
    saved = g.load_setup()["habits"][2]
    assert saved["days"] == [False, True, True, True, True, True, False] and saved["reminders"] == []
    t = g.add_task("Email GCC", time_due="15:00", date_due="2026-10-30")
    assert t["date"] == "2026-10-30" and t["reminders"] == ["15:00"] and not any(t["days"])
    g.update_task(t["id"], days=["Sun"])
    assert g.load_setup()["tasks"][1]["date"] == ""
    assert "Removed task" in g.remove("task", t["id"])
    g.set_holy_day("Winter Solstice", "2026-12-21", "Ritual")
    assert len(g.load_setup()["holyDays"]) == 2
    assert g.update_settings(default_snooze_minutes=15)["defaultSnoozeMinutes"] == 15


def test_monthly_tasks():
    g = Grove(make_vault())
    t = g.add_task("Take Taltz", time_due="14:00", month_day=13, list_name="Health", reminders=["14:00", "14:30"], high=True)
    assert t["monthDay"] == 13 and t["date"] == "" and not any(t["days"])
    from datetime import date
    assert [x["name"] for x in g.tasks_on(g.load_setup(), date(2026, 11, 13))] == ["Take Taltz"]
    assert g.tasks_on(g.load_setup(), date(2026, 11, 14)) == []
    g.update_task(t["id"], month_day=31)
    assert [x["name"] for x in g.tasks_on(g.load_setup(), date(2027, 2, 28))] == ["Take Taltz"]
    g.update_task(t["id"], days=["Mon"])
    assert g.load_setup()["tasks"][-1]["monthDay"] == 0


def test_overview_uses_grove_day_not_calendar():
    g = Grove(make_vault())  # history currentDay is Thursday 2026-10-15, whatever today's date is
    assert "Thursday, October 15" in g.overview() and "18:00 Trash" in g.overview()


def test_bad_input_is_explained():
    g = Grove(make_vault())
    for call in (lambda: g.add_habit("x", reminders=["9pm"]), lambda: g.add_task("x"), lambda: g.remove("habit", "h1"),
                 lambda: g.update_task("nope", name="y"), lambda: g.add_habit("x", days=["funday"]), lambda: g.add_task("x", month_day=40)):
        try:
            call()
            raise AssertionError("expected GroveError")
        except GroveError:
            pass


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
