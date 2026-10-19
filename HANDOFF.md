# Grove: project handoff for Claude Code

This catches a new Claude session up on **Grove**, Reese's Android habit and task app, built in a
cloud Claude Code session in October 2026. Repo: `ReeseCpeaces/Repository-1`, branch
`ccr-786ce96c-m5lntg` (all work is on this branch; there is no `main` yet).

## About Reese (how to work with them)

- Not a professional developer or sysadmin. Plain language, short step-by-step answers.
- Uses voice-to-text and has learning disabilities affecting spelling: read for meaning, don't
  correct their writing unless asked.
- Phone: Samsung Galaxy Ultra (Android). PC: Windows, with Claude Code that SSHes into the server.
- Server: HOMESERVER (Ubuntu + CasaOS), `reese@10.0.0.118`, Tailscale `100.94.19.79`. **Rule:
  nothing on the server is exposed to the public internet.** No port forwarding, no Tailscale
  Funnel, no Cloudflare tunnels.
- Don't ask for passwords or keys in chat; Reese types sudo passwords themselves. Ask before deleting.

## Why Grove exists

Reese moved off TickTick (Premium was ~$50/yr and habit widgets were paywalled). Grove replaces
it: free, home-screen widgets, reminders that keep going until done, an Obsidian daily-note link,
and a "forest" that grows with streaks, watched by a druid wolf. Context: Reese is on medical leave
with an irregular sleep schedule, working through an AODA druid candidate year and the GCC
Novitiate, writing a novel, and keeping up the house. Morning and bedtime practices follow when they
wake and sleep, not the clock; everything else sits in the 2–6 PM window.

## What's built

- **Today screen:** habits (round checks) and tasks (square checks) kept separate, a week strip,
  Wheel of the Year (holy days with countdowns), all tasks list, add/edit for habits and tasks.
- **Habits:** steps (each can tick an Obsidian property), days of week, reminder times, "keep
  reminding until done" every N minutes, pinned-notification toggle.
- **Streaks:** one missed day *pauses* a streak, two in a row *reset* it. Total days are tracked too.
  Only three daily habits keep streaks: Morning practice (h1), Before-bed practice (h2), 15-min house reset (h3).
- **Weekly goals (no streaks, weeks start Monday):** `Goal` in `Models.kt`, created by `Migrate.kt`
  from the retired habits h4/h5 (their history still counts).
  - *Write the book* (`kind = sessions`): 3 a week, at most one per day, shows `1/3 this week` or `4/3 ★`.
    Nudges Thu 3 PM if 0 sessions, Sat 3 PM if under 3.
  - *Field guides* (`kind = pages`): pages logged per book (`DayRecord.pages`), Books screen with
    progress bars. Weekly target = pages left at the start of the week ÷ weeks left until
    2027-06-21, rounded up (74 from Oct 19, 2026). Ticks `read` in the note on days with pages.
    Nudges Thu 5:30 PM if under a third of the target, Sat 5:30 PM if under the target.
- **Book notes:** each book links to its Media DB note in `Books/` (`Book.notePath`, matched by title
  with `Logic.titleScore`; notes with `subType: Druid book` that match no book are added as new books).
  `obsidian/BookNotes.kt` writes `pagesRead`, `lastReadOn`, `pages` (only if missing), and when finished
  `read: true` + `finished: yyyy-MM-dd` (back to `read: false` and no `finished` if pages drop below the total).
  Media DB's `lastRead` and everything else in the note are left alone; an unreadable note is never written.
  In a book note `read` = finished; in the daily note `read` = read something that day.
  Safety rules (after the Oct 18 duplicate-books bug): Syncthing files are ignored everywhere
  (`Logic.isSyncJunk`: names with `.sync-conflict-`, dotfiles like `.stfolder`/`.stversions`, `~syncthing~`);
  `pages: unknown` or any non-number counts as missing; a linked note that's gone is unlinked and matched
  again, never recreated; an exact title match replaces a looser link; a note close to an existing
  book's title is never added as a new book. Editing a book note on the server while Grove writes the
  same note from the phone can still make a Syncthing conflict copy; Grove ignores it, delete it by hand. Books screen has
  "Open note" (`obsidian://open?vault=<vault folder name>&file=<path>`).
- **"Add a line?"** (`ui/LinePrompt.kt`, `Logic.lineItems`): after checking off Morning practice (`SOP:`),
  Before-bed practice (`Meditation:`), nature tasks t3/t8 or prop `fifteen in nature` (`Nature:`), the
  elderberry t9 or prop `tree` (`Plant:`), or logging pages (`Reading (<book>, p. X–Y):`), a box offers one
  line. Saved in `DayRecord.lines`; `ObsidianSync` appends them as `- Prefix: text` at the end of the
  `## Daily Log` section, outside the grove block (`linesWritten` keeps them from being written twice).
  Skip/tap outside = no line and not asked again that day (`DayRecord.prompted`). Things checked off
  from a widget or notification are offered when the app is next opened. Per-item off switches in
  Settings (`Settings.linePromptOff`). Reese's Dataview tables collect these lines by their exact
  prefixes (`Nature:`, `Plant:`, `Meditation:`, `SOP:`, `Lifestyle:`), so keep them.
- **Ids never reused:** `nextId` counter in grove.json (`Logic.allocateId`, connector `new_id`).
  Starts at 27 because t15–t26 were used and deleted before the counter existed.
- **The day doesn't end at midnight:** it rolls over when Morning practice is checked off, or at a
  backup cutoff hour (default 2 PM). So Before-bed practice at 3 AM counts for the previous day.
- **Marking earlier days:** any day up to Grove's today can be checked off or undone later
  (`Logic.toggleHabit/toggleStep/toggleTask(..., day)`; future days are refused by `Logic.canMark`).
  Today shows a "Yesterday · not checked off" section; the habit page's 7-day strip is tappable; the Day
  view (arrows go back indefinitely) has checkable habits and tasks for past days. No time is recorded
  for past days. Marking a day before a habit's `created` moves `created` back. The note for that day is
  updated too (only if it exists). The connector's `grove_mark_done(item, day, done)` does the same
  through the inbox (`type` "habit"/"task", `item`, `done`).
- **Reminders:** exact alarms, Done / Snooze N min / "Snooze…" picker (5 min–2 h); repeats until done.
- **Pinned notification:** today's habits with a check-off button for the next one; reposts if swiped.
- **Widgets (Glance):** calendar + today (dots on days with tasks, month arrows, tap a day to see its
  tasks, tap the heading to open the Day view); Wheel of the Year.
- **Day view:** hour-by-hour day like a calendar; tap an hour to add a task there.
- **Forest tab:** flat-art scene from Reese's SVG Repo icons (in `art/`). One plant type per habit,
  growing with total days (starts bare). Time of day follows the routine: dark before Morning
  practice, day after it, night after Before-bed practice. The wolf sleeps / sits / howls (head and
  tail are separate pieces). Dormant plants turn gray when a streak is paused. Writing grows teal trees
  with total sessions (never gray). Reading grows a seedling with every page, and each finished
  book adds a permanent piece (Fluent Emoji Flat icons, MIT, in `art/fluent/`).
- **Obsidian daily note** (`Journal/Daily notes/{yyyy}/{MMMM}/{yyyy-MM-dd}.md` in the main vault):
  Grove ticks 8 frontmatter checkboxes it owns (`Daily Note`, `sphere of protection`,
  `discursive meditation`, `contemplative meditation`, `read`, `fifteen in nature`, `tree`,
  `lifestyle changes`) and keeps a summary between `<!-- grove:start -->`/`<!-- grove:end -->`
  under `## Daily Log`. Everything else in the note is untouched. New notes come from the template
  path in Settings (Templater date expressions are filled in).
- **Vault sync:** Grove writes its setup to `Grove/grove.json` and check-ins to `Grove/history.json`
  in the vault (Syncthing carries them to the server). Edits to `grove.json` are applied when the
  app opens. On a fresh install, picking the vault restores from these files. The connector logs
  pages/sessions by dropping files in `Grove/inbox/`; the app applies them, records their ids in
  `appliedInbox` (history.json) and deletes them.
- **Connector (`connector/`):** an MCP server that runs on the server and reads/edits those files.
  Tools: `grove_overview`, `grove_history`, `grove_setup`, add/update/remove habit and task, holy
  days, settings, `grove_log_pages`, `grove_log_session`, `grove_add_book`, `grove_update_book`.
  The connector writes book-note progress itself (same rules as the app) when pages are logged or a book is edited. Connect over SSH (see `connector/README.md`, "Quick start for Reese's setup"):
  ```powershell
  claude mcp add --scope user grove -- ssh reese@10.0.0.118 /home/reese/grove-src/connector/.venv/bin/python /home/reese/grove-src/connector/grove_mcp.py --vault /mnt/bigdrive/Obsidian
  ```
  For the phone, use Claude Code with Remote Control rather than a public claude.ai connector.

## Code map

| Path | What |
|---|---|
| `app/src/main/java/com/grove/app/data/` | `Models.kt` (data), `Logic.kt` (streaks, day rollover, schedules; pure, unit-tested), `Seed.kt` (first-run habits/tasks/holy days from the TickTick setup), `Store.kt` (JSON file + StateFlow), `Effects.kt` (what runs after every change) |
| `notify/` | `AlarmScheduler`, `ReminderReceiver` (alarms, buttons, boot), `Notifier` (channels, pinned + reminder notifications), `SnoozeActivity` |
| `widget/Widgets.kt` | Glance widgets and their actions |
| `obsidian/` | `NoteText` (pure note edits, tested), `ObsidianSync` (daily note via SAF), `VaultSync` (grove.json / history.json / inbox), `BookNotes` (book notes in `Books/`) |
| `data/Migrate.kt` | One-way data upgrades run on every load/change (habits h4/h5 → goals, books, `nextId`) |
| `ui/` | Compose screens: Today, Day, Habit, Edit, Settings, Forest, Goals/Books (`GoalsScreen.kt`) |
| `art/`, `tools/svg2vector.py` | Source SVGs and the converter to `res/drawable` (wolf split by hand into `wolf_tail/body/head.xml`) |
| `connector/` | `grove_core.py` (rules mirrored from `Logic.kt`), `grove_mcp.py`, tests, README |
| `.github/workflows/android.yml` | Unit tests, build, publish |

## Building and shipping

- Every push runs GitHub Actions: unit tests, `assembleRelease`, then replaces the **`latest`**
  release with `grove.apk`. Reese installs from
  https://github.com/ReeseCpeaces/Repository-1/releases/tag/latest. Version is `0.1.<run number>`,
  shown at the bottom of Grove › Settings.
- Builds are signed with `app/grove-release.jks` (password in `app/build.gradle.kts`). **Never
  change or regenerate this key:** Android refuses updates signed with a different key ("App not
  installed"), and Reese would have to uninstall and lose on-phone data. (This happened once already.)
- The original cloud environment couldn't download the Android SDK (`dl.google.com` blocked), so CI
  was the compile check. Locally with Android Studio or the SDK installed: `./gradlew testDebugUnitTest assembleRelease`.
- Connector tests: `python3 connector/tests/test_grove.py`.
- The app has never been run in an emulator by Claude; Reese tests on the phone. Ask what they see.

## Rules that matter

- Habit/task `id`s are the keys for streak history: never change or reuse them.
- Weekday arrays are Sunday-first. Times are `HH:mm` 24-hour; dates `yyyy-MM-dd`. Tasks repeat weekly (`days`), monthly (`monthDay` 1–31) or once (`date`).
- "Today" in the app is Grove's day (`Logic.groveToday(data)`, which lags the calendar after midnight), never `LocalDate.now()`.
- Habits with `role` `"morning"` / `"bedtime"` drive the day rollover and forest time of day.
- Grove owns those 8 note properties; ticking them by hand in Obsidian gets reverted.
- If streak, day, week or page-target rules change, change `Logic.kt` and `connector/grove_core.py` together.

## Known gaps / ideas not done yet

- Pinned notification checks off only the next habit (no per-habit buttons).
- No Forest home-screen widget yet.
- No in-app preview of what Grove writes to the note (Settings has "Write today's summary now").
- One more leafy tree SVG (`tree-svgrepo-com (2).svg` in Reese's Drive "Grove art" folder) wasn't
  used, and the AI-generated PNG there was never opened.
- SVG Repo licenses vary per icon; check before sharing the app beyond Reese.
- `My Deepest Me` prompts are a single step; the 30 prompts themselves aren't in the app yet.
