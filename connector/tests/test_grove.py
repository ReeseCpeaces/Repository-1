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
    assert h["id"] == "h27" and h["reminders"] == ["21:00"] and h["created"] == "2026-10-15"
    g.update_habit(h["id"], days=["weekdays"], reminders=[])
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


def goal_vault(current_day: str = "2026-10-19") -> Grove:
    """A vault after the app's migration: writing and field-guide goals, the 9 books (2,587 pages)."""
    vault = make_vault()
    setup = json.loads((vault / "Grove/grove.json").read_text())
    pages = [456, 429, 218, 304, 316, 392, 252, 144, 76]
    setup["books"] = [{"id": f"b{30 + i}", "title": t, "pages": n, "startPage": 0, "piece": ""}
                      for i, (t, n) in enumerate(zip(["Trees", "Wildflowers", "Weeds", "Mushrooms", "Birds", "Mammals", "Rocks", "Frogs", "Snakes"], pages))]
    setup["goals"] = [
        {"id": "g27", "name": "Write the book", "kind": "sessions", "perWeek": 3, "legacyHabit": "h4", "created": "2026-10-01"},
        {"id": "g28", "name": "Field guides", "kind": "pages", "deadline": "2027-06-21", "prop": "read", "legacyHabit": "h5", "created": "2026-10-01"},
    ]
    setup["nextId"] = 39
    (vault / "Grove/grove.json").write_text(json.dumps(setup))
    history = json.loads((vault / "Grove/history.json").read_text())
    history["currentDay"] = current_day
    history["days"]["2026-10-14"]["steps"]["h4"] = [0]  # an old writing check-off counts as a session
    (vault / "Grove/history.json").write_text(json.dumps(history))
    return Grove(vault)


def test_weekly_page_target_matches_app():
    from datetime import date
    g = goal_vault()
    setup, history = g.load_setup(), g.load_history()
    goal = setup["goals"][1]
    assert sum(b["pages"] for b in setup["books"]) == 2587
    assert g.weekly_page_target(setup, history, goal, date(2026, 10, 19)) == 74  # 2587 / 35 weeks, rounded up
    assert g.weekly_page_target(setup, history, goal, date(2026, 10, 25)) == 74  # same all week (Sunday)


def test_logging_pages_and_sessions_through_the_inbox():
    from datetime import date
    g = goal_vault()
    assert "18 / 74 pages" in g.log_pages("Mushrooms", 18)
    assert "Mushrooms: 18/304" in g.overview()
    assert "1/3 this week" in g.log_session()
    assert "already" in g.log_session()  # once per day
    assert len(list(g.inbox.glob("*.json"))) == 2
    eff = g.effective_history()
    goal = g.load_setup()["goals"][0]
    assert g.total_sessions(eff, goal) == 2  # today's session plus the old h4 check-off
    assert g.sessions_this_week(eff, goal, date(2026, 10, 19)) == 1
    # Once the app applies an inbox file it is listed in appliedInbox and no longer counted twice.
    history = g.load_history()
    history["appliedInbox"] = [json.loads(f.read_text())["id"] for f in g.inbox.glob("*.json")]
    (g.setup_path.parent / "history.json").write_text(json.dumps(history))
    assert g.pending_inbox(history) == []


def test_books_can_be_added_and_edited():
    g = goal_vault()
    b = g.set_book("Michigan Lichens", 200, 10)
    assert b["id"] == "b39" and g.load_setup()["nextId"] == 40
    g.set_book(pages=210, book_id=b["id"])
    assert g.load_setup()["books"][-1]["pages"] == 210 and g.load_setup()["books"][-1]["startPage"] == 10


def test_ids_are_never_reused():
    g = Grove(make_vault())
    t = g.add_task("A", days=["Mon"])
    assert t["id"] == "t27"  # t15-t26 were used before the counter existed
    g.remove("task", t["id"])
    assert g.add_task("B", days=["Mon"])["id"] == "t28"
    assert g.add_habit("C")["id"] == "h29"


SNAKES_NOTE = """---
type: book
subType: Druid book
title: "Michigan Snakes: A Field Guide and Pocket Reference"
pages: 76
read: false
lastRead: ""
tags:
  - druid
---
My notes stay put.
"""


def test_book_notes_get_progress():
    g = goal_vault()
    books = g.vault / "Books"
    books.mkdir()
    (books / "Michigan Snakes.md").write_text(SNAKES_NOTE)
    (books / "Some Novel.md").write_text("---\ntype: book\nsubType: Novel\ntitle: Michigan Snakes\npages: 10\n---\n")
    b = g.set_book("Michigan Snakes (Harding & Holman)", book_id="b38", start_page=60)  # 76 pages; linking happens on any edit
    assert b["notePath"] == "Books/Michigan Snakes.md"
    note = (books / "Michigan Snakes.md").read_text()
    assert "pagesRead: 60" in note and "read: false" in note and 'lastRead: ""' in note and note.endswith("My notes stay put.\n")
    assert "  - druid" in note and "finished" not in note
    assert "Updated Books/Michigan Snakes.md" in g.log_pages("b38", 16)
    note = (books / "Michigan Snakes.md").read_text()
    assert "pagesRead: 76" in note and "read: true" in note and "finished: 2026-10-19" in note and "lastReadOn: 2026-10-19" in note
    assert "note: Books/Michigan Snakes.md" in g.overview()
    g.log_pages("b38", -5)  # a correction takes it back below the total
    note = (books / "Michigan Snakes.md").read_text()
    assert "pagesRead: 71" in note and "read: false" in note and "finished" not in note


def test_frontmatter_edits_only_touch_given_keys():
    text = "---\na: 1\nlist:\n  - x\nb: 2\n---\nbody\n"
    assert Grove.set_frontmatter(text, {"list": "3", "c": "4", "a": None}) == "---\nlist: 3\nb: 2\nc: 4\n---\nbody\n"
    assert Grove.frontmatter(text) == {"a": "1", "list": "", "b": "2"}
    g = Grove(make_vault())
    assert g.title_score("Michigan Trees (Barnes & Wagner)", "Michigan Trees, Revised and Updated: A Guide") == 1.0
    assert g.title_score("Michigan Trees", "Trees of Michigan") == 0.9
    assert g.title_score("Michigan Trees (Barnes & Wagner)", "Michigan Trees: A Guide to the Trees") == 1.0
    assert g.title_score("Michigan Snakes (Harding & Holman)", "Michigan Frogs, Toads, and Salamanders") < 0.6


def druid(title: str, pages: str = "304") -> str:
    return f"---\ntype: book\nsubType: Druid book\ntitle: {title}\npages: {pages}\nread: false\n---\nbody\n"


def test_conflict_copies_deleted_notes_and_unknown_pages():
    g = goal_vault()
    books = g.vault / "Books"
    books.mkdir()
    (books / "Mushrooms of the Upper Midwest.md").write_text(druid("Mushrooms of the Upper Midwest", "unknown"))
    (books / "Mushrooms of the Upper Midwest.sync-conflict-20261018-202415-KUKTOLE.md").write_text(druid("Mushrooms of the Upper Midwest"))
    (books / "Magic Mushrooms of the Upper Midwest (2022).md").write_text(druid("Magic Mushrooms of the Upper Midwest"))
    (g.inbox).mkdir()
    (g.inbox / "x.sync-conflict-20261018-202415-KUKTOLE.json").write_text('{"id": "c1", "type": "pages", "book": "b33", "pages": 9}')
    assert [n["path"] for n in g.druid_book_notes(g.load_setup())] == [
        "Books/Magic Mushrooms of the Upper Midwest (2022).md", "Books/Mushrooms of the Upper Midwest.md"]
    assert g.pending_inbox(g.load_history()) == []
    # Mushrooms (b33) linked to the wrong note earlier: the exact title wins, and "unknown" pages get filled in.
    setup = g.load_setup()
    setup["books"][3]["title"] = "Mushrooms of the Upper Midwest (Marrone & Yerich)"
    setup["books"][3]["notePath"] = "Books/Magic Mushrooms of the Upper Midwest (2022).md"
    g.save_setup(setup)
    g.log_pages("b33", 4)
    assert g.load_setup()["books"][3]["notePath"] == "Books/Mushrooms of the Upper Midwest.md"
    note = (books / "Mushrooms of the Upper Midwest.md").read_text()
    assert "pages: 304" in note and "pagesRead: 4" in note
    assert "pagesRead" not in (books / "Magic Mushrooms of the Upper Midwest (2022).md").read_text()
    # A deleted note is unlinked, never written back.
    (books / "Mushrooms of the Upper Midwest.md").unlink()
    (books / "Magic Mushrooms of the Upper Midwest (2022).md").unlink()
    g.log_pages("b33", 2)
    assert g.load_setup()["books"][3]["notePath"] == ""
    assert not (books / "Mushrooms of the Upper Midwest.md").exists()


def test_mark_done_on_an_earlier_day():
    g = Grove(make_vault())  # Read (h2) done 10, 11, 13, 14; today 15
    assert "Streak now 5" in g.mark_done("h2", "2026-10-12")
    assert "Marked Trash done on 2026-10-15" in g.mark_done("Trash")
    assert "[x] 18:00 Trash" in g.overview()
    assert "Streak now 4" in g.mark_done("Read", "2026-10-12", done=False)
    try:
        g.mark_done("h2", "2026-10-16")
        raise AssertionError("expected GroveError")
    except GroveError:
        pass


def test_evening_check_in_and_quiet_hours():
    g = Grove(make_vault())  # Thursday Oct 15: Trash due, Read not done, Morning practice not done
    ns = g.notify_settings(g.load_setup())
    assert ns == {"eveningCheckIn": True, "checkInTime": "21:00", "quietStart": "21:00", "quietEnd": "08:00"}
    assert [g.in_quiet_hours(ns, t) for t in ("20:59", "21:00", "23:30", "00:30", "07:59", "08:00")] == [False, True, True, True, True, False]
    assert g.check_in_items(g.load_setup(), g.load_history()) == ["Read", "Trash"]  # never Morning practice
    assert "Still open today: Read, Trash" in g.overview()
    g.update_settings(quiet_start="22:00", quiet_end="22:00", evening_check_in=False)
    assert not g.in_quiet_hours(g.notify_settings(g.load_setup()), "23:00")
    assert "check-in off, quiet hours off" in g.overview()
    try:
        g.update_settings(quiet_end="8am")
        raise AssertionError("expected GroveError")
    except GroveError:
        pass


def test_todos():
    g = Grove(make_vault())
    t = g.add_todo("Renew registration", due="2026-11-30")
    assert t["id"] == "d27"
    g.add_todo("Call the vet", high=True)
    text = g.overview()
    assert "- [ ] ! Call the vet (id d28)" in text and "- [ ] Renew registration (id d27), by 2026-11-30" in text
    g.update_todo("d28", done=True)
    assert g.load_setup()["todos"][1]["done"] == "2026-10-15"
    g.update_settings(todo_note_path="To-do.md")
    assert "linked to To-do.md" in g.overview()
    g.remove("to-do", "d27")
    assert [x["id"] for x in g.load_setup()["todos"]] == ["d28"]


PROMPT_GOAL = {"id": "g45", "name": "My Deepest Me", "kind": "prompt", "deadline": "2027-06-21", "created": "2026-10-19",
               "nudges": [{"weekday": 1, "time": "16:00", "below": 1.0}, {"weekday": 6, "time": "15:00", "below": 1.0}],
               "total": 30, "current": 1, "title": "", "opened": "2026-10-19", "doneOn": "",
               "noteFolder": "Druidry/GCC/My Deepest Me", "tracker": "Druidry/GCC/My Deepest Me - Tracker.md", "notesMade": []}


def test_my_deepest_me_prompts():
    g = goal_vault()  # Monday 2026-10-19
    setup = g.load_setup()
    setup["goals"].append(dict(PROMPT_GOAL))
    g.save_setup(setup)
    tracker = g.vault / "Druidry/GCC/My Deepest Me - Tracker.md"
    tracker.parent.mkdir(parents=True)
    tracker.write_text("# Tracker\n- [ ] **Prompt 1**: week of Oct 19, 2026\n- [ ] **Prompt 2**: week of Oct 26, 2026\n")
    assert "Prompt 1 · aim for Friday, no title yet, 5 spare weeks before 2027-06-21" in g.overview()
    g.prompt_title("who I was as a kid")
    g.prompt_thought("the creek behind the house")
    note = g.vault / "Druidry/GCC/My Deepest Me/Prompt 1.md"
    text = note.read_text()
    assert "title: who I was as a kid\n" in text and "# Prompt 1: who I was as a kid\n## Thoughts\n- 2026-10-19: the creek behind the house\n## Answer" in text
    assert "Prompt 1 done" in g.prompt_done("It started there.")
    text = note.read_text()
    assert "finished: 2026-10-19" in text and text.rstrip().endswith("## Answer\nIt started there.")
    assert tracker.read_text() == ("# Tracker\n- [x] **Prompt 1**: week of Oct 19, 2026 · [[Druidry/GCC/My Deepest Me/Prompt 1|note]]\n"
                                   "- [ ] **Prompt 2**: week of Oct 26, 2026\n")
    assert "Prompt 1 done for this week ✓" in g.overview()
    # Next Monday the next prompt opens; a deleted note is never recreated.
    history = json.loads(g.history_path.read_text())
    history["currentDay"] = "2026-10-26"
    g.history_path.write_text(json.dumps(history))
    assert "Prompt 2 · aim for Friday" in g.overview()
    g.prompt_thought("first idea")
    (g.vault / "Druidry/GCC/My Deepest Me/Prompt 2.md").unlink()
    try:
        g.prompt_thought("again")
        raise AssertionError("expected GroveError")
    except GroveError:
        pass


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
