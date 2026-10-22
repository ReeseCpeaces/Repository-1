"""Grove connector: lets Claude see your Grove habits, streaks and check-ins, and change the setup.

Runs on the server where Syncthing keeps the Obsidian vault. Edits go to Grove/grove.json, which
Syncthing carries to the phone; Grove applies them the next time it opens.

    python3 grove_mcp.py --vault ~/Obsidian/MyVault            # for Claude on this machine (stdio)
    python3 grove_mcp.py --vault ~/Obsidian/MyVault --http 0.0.0.0 8765   # home network only
"""
from __future__ import annotations

import argparse
import json
import os
import sys

from mcp.server.fastmcp import FastMCP

from grove_core import Grove, GroveError

INSTRUCTIONS = """Grove is the user's habit and task app on their Android phone.
Habits have steps, optional reminder times, and streaks with one grace day (one missed day pauses
a streak, two in a row reset it). Weekly goals have no streak: "Write the book" counts sessions
(one per day, target 3 a week) and "Field guides" counts pages read across the books, with a weekly
target of pages left / weeks left until the deadline (weeks start Monday). Log them with
grove_log_session and grove_log_pages.
Each book can be linked to its note in the vault's Books folder (notePath); Grove writes pagesRead,
lastReadOn, and read: true / finished: date into that note. In a book note `read` means finished;
in the daily note `read` means some reading was done that day. Tasks repeat on weekdays or monthly, or happen once on a date. To-dos have no date and stay until checked off.
"My Deepest Me" is a weekly prompt goal: one numbered prompt a week (read Monday, finish by Friday;
a slipped prompt stays open). "Druid exploration" counts plant-study minutes toward 20 hours by the
deadline (weekly target = minutes left / weeks left); sessions live in the plant-study note's Session log. Never put the book's prompt text anywhere; only the user's own words. Changes made
here reach the phone through Syncthing and apply when the Grove app next opens, so mention that
after editing. Times are 24-hour HH:MM. Call grove_overview first to see ids and today's state."""

mcp = FastMCP("grove", instructions=INSTRUCTIONS)
grove: Grove


def _run(fn, *args, **kwargs) -> str:
    try:
        result = fn(*args, **kwargs)
        return result if isinstance(result, str) else json.dumps(result, indent=2, ensure_ascii=False)
    except GroveError as e:
        return f"Couldn't do that: {e}"


@mcp.tool()
def grove_overview() -> str:
    """Today in Grove: each habit (done, steps, streak, total days, reminders), weekly goals and book progress, today's tasks, upcoming holy days, with ids."""
    return _run(grove.overview)


@mcp.tool()
def grove_history(days: int = 14) -> str:
    """Day-by-day record for the last `days` days: habits done vs scheduled, which were missed, and tasks checked off."""
    return _run(grove.history_report, max(1, min(days, 366)))


@mcp.tool()
def grove_setup() -> str:
    """The full setup file (habits, tasks, holy days, settings) exactly as the app stores it."""
    return _run(lambda: grove.load_setup())


@mcp.tool()
def grove_add_habit(name: str, steps: list[dict] | None = None, days: list[str] | None = None, reminders: list[str] | None = None,
                    nag: bool = True, nag_minutes: int = 15, when_text: str = "", pinned: bool = True) -> str:
    """Add a habit.

    steps: list of {"label": "...", "prop": "obsidian property or empty"}; defaults to one step named after the habit.
    days: ["daily"], ["weekdays"], ["weekends"] or weekday names like ["Mon", "Wed"]; default daily.
    reminders: 24-hour times like ["21:00"]. nag: keep re-alerting every nag_minutes until done.
    when_text: shown when there are no reminders, e.g. "After lunch". pinned: show in the pinned notification.
    """
    return _run(grove.add_habit, name, steps, days, reminders, nag, nag_minutes, when_text, pinned)


@mcp.tool()
def grove_update_habit(habit_id: str, name: str | None = None, steps: list[dict] | None = None, days: list[str] | None = None,
                       reminders: list[str] | None = None, nag: bool | None = None, nag_minutes: int | None = None,
                       when_text: str | None = None, pinned: bool | None = None) -> str:
    """Change a habit. Only the fields given are changed; `steps` and `reminders` replace the whole list (use [] to clear reminders)."""
    return _run(grove.update_habit, habit_id, name=name, steps=steps, days=days, reminders=reminders, nag=nag,
                nag_minutes=nag_minutes, when_text=when_text, pinned=pinned)


@mcp.tool()
def grove_remove_habit(habit_id: str) -> str:
    """Delete a habit. Its past check-ins stay in the phone's history. Morning and Before-bed practice can't be removed."""
    return _run(grove.remove, "habit", habit_id)


@mcp.tool()
def grove_add_task(name: str, time_due: str = "16:30", days: list[str] | None = None, date_due: str | None = None,
                   list_name: str = "Inbox", reminders: list[str] | None = None, nag: bool = True, nag_minutes: int = 30,
                   first_of_month: bool = False, prop: str = "", high: bool = False, month_day: int | None = None) -> str:
    """Add a task. Weekly: give `days` (e.g. ["Thu"]). Monthly: give `month_day` (1-31, e.g. 13 for the 13th of every
    month; 29-31 fall on the last day of shorter months). One-time: give `date_due` (YYYY-MM-DD).

    time_due is the 24-hour due time. reminders default to the due time. first_of_month: only the first matching
    weekday of each month. prop: Obsidian daily-note property to tick when done. high: high priority.
    """
    return _run(grove.add_task, name, time_due, days, date_due, list_name, reminders, nag, nag_minutes, first_of_month, prop, high, month_day)


@mcp.tool()
def grove_update_task(task_id: str, name: str | None = None, time_due: str | None = None, days: list[str] | None = None,
                      date_due: str | None = None, list_name: str | None = None, reminders: list[str] | None = None,
                      nag: bool | None = None, nag_minutes: int | None = None, first_of_month: bool | None = None,
                      prop: str | None = None, high: bool | None = None, month_day: int | None = None) -> str:
    """Change a task. Only the fields given are changed. date_due makes it one-time, days makes it weekly,
    month_day (1-31) makes it monthly."""
    return _run(grove.update_task, task_id, name=name, time_due=time_due, days=days, date_due=date_due, list_name=list_name,
                reminders=reminders, nag=nag, nag_minutes=nag_minutes, first_of_month=first_of_month, prop=prop, high=high,
                month_day=month_day)


@mcp.tool()
def grove_remove_task(task_id: str) -> str:
    """Delete a task."""
    return _run(grove.remove, "task", task_id)


@mcp.tool()
def grove_mark_done(item: str, day: str | None = None, done: bool = True) -> str:
    """Check off a habit or task on today or any earlier day (e.g. a forgotten Before-bed practice), or undo it with done=false.
    item: a habit/task id (e.g. "h2") or a unique part of its name. day: YYYY-MM-DD, default Grove's today."""
    return _run(grove.mark_done, item, day, done)


@mcp.tool()
def grove_log_pages(book: str, pages: int, day: str | None = None) -> str:
    """Log field-guide pages read. book: a book id (e.g. "b30") or a unique part of its title (e.g. "Mushrooms").
    pages: pages read (negative to correct a mistake). day: YYYY-MM-DD, default today's Grove day."""
    return _run(grove.log_pages, book, pages, day)


@mcp.tool()
def grove_log_session(goal: str | None = None, day: str | None = None) -> str:
    """Log one session for a weekly session goal (default: the writing goal). One per day. day: YYYY-MM-DD, default today."""
    return _run(grove.log_session, goal, day)


@mcp.tool()
def grove_add_book(title: str, pages: int, start_page: int = 0) -> str:
    """Add a book to the reading goal. start_page: pages already read before tracking began.
    Links its note in the vault's Books folder (subType: Druid book) when the title matches."""
    return _run(grove.set_book, title, pages, start_page)


@mcp.tool()
def grove_update_book(book_id: str, title: str | None = None, pages: int | None = None, start_page: int | None = None) -> str:
    """Change a book's title, page count or starting page. Only the fields given are changed.
    Also updates the book's note in the vault (pagesRead, lastReadOn, read/finished)."""
    return _run(grove.set_book, title, pages, start_page, book_id)


@mcp.tool()
def grove_add_todo(name: str, due: str | None = None, list_name: str = "", high: bool = False, remind: str | None = None) -> str:
    """Add a floating to-do: no date, it stays on Grove's Today until checked off (and in the linked Obsidian to-do note).
    due: optional "do by" YYYY-MM-DD. remind: optional daily reminder time HH:MM until done."""
    return _run(grove.add_todo, name, due, list_name, high, remind)


@mcp.tool()
def grove_update_todo(todo_id: str, name: str | None = None, due: str | None = None, list_name: str | None = None, high: bool | None = None,
                      remind: str | None = None, done: bool | None = None, day: str | None = None) -> str:
    """Change a to-do, or check it off with done=true (day defaults to Grove's today) / reopen it with done=false.
    Use "" for due or remind to clear them."""
    return _run(grove.update_todo, todo_id, name=name, due=due, list_name=list_name, high=high, remind=remind, done=done, day=day)


@mcp.tool()
def grove_remove_todo(todo_id: str) -> str:
    """Delete a to-do (it's also removed from the linked Obsidian to-do note)."""
    return _run(grove.remove, "to-do", todo_id)


@mcp.tool()
def grove_prompt_thought(text: str, day: str | None = None) -> str:
    """Add a thought to this week's My Deepest Me prompt; it goes into the prompt's note under ## Thoughts as "- yyyy-MM-dd: text".
    Only the user's own words: never write the book's prompt text."""
    return _run(grove.prompt_thought, text, day)


@mcp.tool()
def grove_prompt_done(answer: str | None = None) -> str:
    """Mark this week's My Deepest Me prompt done, with an optional final answer (saved under ## Answer in its note).
    Ticks the prompt in the tracker note; the next prompt opens next Monday."""
    return _run(grove.prompt_done, answer)


@mcp.tool()
def grove_prompt_title(title: str) -> str:
    """Set the current prompt's short title, in the user's own words (never the book's text)."""
    return _run(grove.prompt_title, title)


@mcp.tool()
def grove_log_exploration(plant: str, minutes: int, note: str | None = None, day: str | None = None) -> str:
    """Log a Druid exploration (plant study) session: written as a line in the plant-study note's "## Session log"
    ("- yyyy-MM-dd · Plant · (min:: N) · note"); the app picks it up. plant: a plant name or a unique part of it
    (a new name adds the plant). day: YYYY-MM-DD, default Grove's today."""
    return _run(grove.log_exploration, plant, minutes, note, day)


@mcp.tool()
def grove_exploration_log(plant: str | None = None) -> str:
    """List plant study sessions, newest first, optionally for one plant."""
    return _run(grove.exploration_log, plant)


@mcp.tool()
def grove_set_holy_day(name: str, date_on: str, detail: str, holy_id: str | None = None) -> str:
    """Add a holy day (or change one when holy_id is given). Grove adds a heads-up task 3 days before it."""
    return _run(grove.set_holy_day, name, date_on, detail, holy_id)


@mcp.tool()
def grove_remove_holy_day(holy_id: str) -> str:
    """Delete a holy day."""
    return _run(grove.remove, "holy day", holy_id)


@mcp.tool()
def grove_update_settings(cutoff_hour: int | None = None, default_snooze_minutes: int | None = None, daily_note_path: str | None = None,
                          template_path: str | None = None, wolf_name: str | None = None, evening_check_in: bool | None = None,
                          check_in_time: str | None = None, quiet_start: str | None = None, quiet_end: str | None = None,
                          todo_note_path: str | None = None, plant_study_note_path: str | None = None) -> str:
    """Change settings: cutoff_hour (backup hour a new day starts), default_snooze_minutes, the Obsidian daily note path or template path,
    evening_check_in (one notification at check_in_time listing what's still open), and quiet hours quiet_start-quiet_end
    (HH:MM, by the clock; no reminders, repeats or nudges then; equal times turn quiet hours off), and todo_note_path (the Obsidian
    note, relative to the vault, whose "- [ ]" checklist the app keeps in step with Grove's to-dos; "" unlinks)."""
    return _run(grove.update_settings, cutoff_hour=cutoff_hour, default_snooze_minutes=default_snooze_minutes,
                daily_note_path=daily_note_path, template_path=template_path, wolf_name=wolf_name, evening_check_in=evening_check_in,
                check_in_time=check_in_time, quiet_start=quiet_start, quiet_end=quiet_end, todo_note_path=todo_note_path, plant_study_note_path=plant_study_note_path)


def main() -> None:
    global grove
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--vault", default=os.environ.get("GROVE_VAULT"), help="Path to the Obsidian vault folder (or set GROVE_VAULT)")
    p.add_argument("--http", nargs=2, metavar=("HOST", "PORT"), help="Serve over HTTP at http://HOST:PORT/mcp instead of stdio")
    a = p.parse_args()
    if not a.vault:
        p.error("--vault is required")
    grove = Grove(a.vault)
    if not grove.setup_path.exists():
        print(f"Warning: {grove.setup_path} doesn't exist yet. Pick the vault in Grove's settings and let Syncthing sync.", file=sys.stderr)
    if a.http:
        mcp.settings.host, mcp.settings.port = a.http[0], int(a.http[1])
        if a.http[0] not in ("127.0.0.1", "localhost"):
            # Reached by IP address on the home network, so allow any Host header.
            from mcp.server.transport_security import TransportSecuritySettings
            mcp.settings.transport_security = TransportSecuritySettings(enable_dns_rebinding_protection=False)
        mcp.run(transport="streamable-http")
    else:
        mcp.run()


if __name__ == "__main__":
    main()
