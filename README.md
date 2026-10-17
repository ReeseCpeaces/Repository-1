# Grove

A personal habit and task app for Android: home-screen widgets, reminders that repeat until done
(with snooze), a pinned checklist notification, Obsidian daily-note sync, and a forest that grows
with your streaks, watched over by a wolf.

## Getting the app

Every push builds an APK with GitHub Actions and publishes it to the **latest** release
(Releases › Grove (latest build) › `grove.apk`). Open that on the phone to install or update
(allow "Install unknown apps" for your browser when asked).

## Layout

- `app/src/main/java/com/grove/app/data` – data model, streak/day rules (`Logic.kt`), first-run data (`Seed.kt`)
- `notify` – reminders, snooze, pinned notification
- `widget` – Glance home-screen widgets
- `obsidian` – daily-note properties and summary
- `ui` – Compose screens, including the Forest
- `art/` – source SVGs (from SVG Repo); `tools/svg2vector.py` converts them to `res/drawable`
