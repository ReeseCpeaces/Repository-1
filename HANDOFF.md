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

## What's built (Grove 0.1.10 at time of writing)

- **Today screen:** habits (round checks) and tasks (square checks) kept separate, a week strip,
  Wheel of the Year (holy days with countdowns), all tasks list, add/edit for habits and tasks.
- **Habits:** steps (each can tick an Obsidian property), days of week, reminder times, "keep
  reminding until done" every N minutes, pinned-notification toggle.
- **Streaks:** one missed day *pauses* a streak, two in a row *reset* it. Total days are tracked too.
- **The day doesn't end at midnight:** it rolls over when Morning practice is checked off, or at a
  backup cutoff hour (default 2 PM). So Before-bed practice at 3 AM counts for the previous day.
- **Reminders:** exact alarms, Done / Snooze N min / "Snooze…" picker (5 min–2 h); repeats until done.
- **Pinned notification:** today's habits with a check-off button for the next one; reposts if swiped.
- **Widgets (Glance):** calendar + today (dots on days with tasks, month arrows, tap a day to see its
  tasks, tap the heading to open the Day view); Wheel of the Year.
- **Day view:** hour-by-hour day like a calendar; tap an hour to add a task there.
- **Forest tab:** flat-art scene from Reese's SVG Repo icons (in `art/`). One plant type per habit,
  growing with total days (starts bare). Time of day follows the routine: dark before Morning
  practice, day after it, night after Before-bed practice. The wolf sleeps / sits / howls (head and
  tail are separate pieces). Dormant plants turn gray when a streak is paused.
- **Obsidian daily note** (`Journal/Daily notes/{yyyy}/{MMMM}/{yyyy-MM-dd}.md` in the main vault):
  Grove ticks 8 frontmatter checkboxes it owns (`Daily Note`, `sphere of protection`,
  `discursive meditation`, `contemplative meditation`, `read`, `fifteen in nature`, `tree`,
  `lifestyle changes`) and keeps a summary between `<!-- grove:start -->`/`<!-- grove:end -->`
  under `## Daily Log`. Everything else in the note is untouched. New notes come from the template
  path in Settings (Templater date expressions are filled in).
- **Vault sync:** Grove writes its setup to `Grove/grove.json` and check-ins to `Grove/history.json`
  in the vault (Syncthing carries them to the server). Edits to `grove.json` are applied when the
  app opens. On a fresh install, picking the vault restores from these files.
- **Connector (`connector/`):** an MCP server that runs on the server and reads/edits those files.
  Tools: `grove_overview`, `grove_history`, `grove_setup`, add/update/remove habit and task, holy
  days, settings. Connect over SSH (see `connector/README.md`, "Quick start for Reese's setup"):
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
| `obsidian/` | `NoteText` (pure note edits, tested), `ObsidianSync` (daily note via SAF), `VaultSync` (grove.json / history.json) |
| `ui/` | Compose screens: Today, Day, Habit, Edit, Settings, Forest |
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
- If streak or day rules change, change `Logic.kt` and `connector/grove_core.py` together.

## Known gaps / ideas not done yet

- Pinned notification checks off only the next habit (no per-habit buttons).
- No Forest home-screen widget yet.
- No in-app preview of what Grove writes to the note (Settings has "Write today's summary now").
- One more leafy tree SVG (`tree-svgrepo-com (2).svg` in Reese's Drive "Grove art" folder) wasn't
  used, and the AI-generated PNG there was never opened.
- SVG Repo licenses vary per icon; check before sharing the app beyond Reese.
- `My Deepest Me` prompts are a single step; the 30 prompts themselves aren't in the app yet.
