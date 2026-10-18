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
grove_log_session and grove_log_pages. Tasks repeat on weekdays or monthly, or happen once on a date. Changes made
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
    """Add a book to the reading goal. start_page: pages already read before tracking began."""
    return _run(grove.set_book, title, pages, start_page)


@mcp.tool()
def grove_update_book(book_id: str, title: str | None = None, pages: int | None = None, start_page: int | None = None) -> str:
    """Change a book's title, page count or starting page. Only the fields given are changed."""
    return _run(grove.set_book, title, pages, start_page, book_id)


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
                          template_path: str | None = None, wolf_name: str | None = None) -> str:
    """Change settings: cutoff_hour (backup hour a new day starts), default_snooze_minutes, the Obsidian daily note path or template path."""
    return _run(grove.update_settings, cutoff_hour=cutoff_hour, default_snooze_minutes=default_snooze_minutes,
                daily_note_path=daily_note_path, template_path=template_path, wolf_name=wolf_name)


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
