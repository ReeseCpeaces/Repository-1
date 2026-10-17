# Grove connector

Lets Claude see your Grove habits, streaks and check-ins, and change your setup (habits, tasks,
reminders, holy days, settings), the way the TickTick connector does.

## How it fits together

```
Phone (Grove app)  <-- Syncthing -->  Server: vault/Grove/grove.json + history.json  <--  this connector  <--  Claude
```

- Grove writes `Grove/grove.json` (your setup) and `Grove/history.json` (your check-ins) into the
  Obsidian vault. Syncthing copies them to the server.
- This connector runs on the server, reads those files, and edits `grove.json` when Claude asks.
- Syncthing copies the edit back to the phone; Grove applies it the next time the app opens.

Nothing is sent anywhere else. In the setup below the connector is only reachable from your own
computer or home network.

## Quick start for Reese's setup (HOMESERVER + Claude Code on the Windows PC)

Hand this section to Claude Code on the PC. It SSHes into the server (`reese@10.0.0.118`) to do steps 1–3.

1. **Find the vault.** The main vault is on the Big Drive, most likely `/mnt/bigdrive/Obsidian`. Check that
   `Grove/grove.json` exists inside it (it appears after Grove on the phone has the vault picked and Syncthing has synced).
2. **Install** (on the server, as `reese`):
   ```bash
   git clone https://github.com/ReeseCpeaces/Repository-1.git ~/grove-src   # or copy the connector folder over
   cd ~/grove-src && git checkout ccr-786ce96c-m5lntg && cd connector
   python3 -m venv .venv          # if this fails: sudo apt install python3-venv (Reese types the password)
   .venv/bin/pip install -r requirements.txt
   .venv/bin/python -c "from grove_core import Grove; print(Grove('/mnt/bigdrive/Obsidian').overview())"
   ```
3. **Connect it to Claude Code on the PC** (run on the PC, in PowerShell). Claude Code starts the connector over SSH
   whenever it needs it, so nothing new listens on the network:
   ```powershell
   claude mcp add --scope user grove -- ssh reese@10.0.0.118 /home/reese/grove-src/connector/.venv/bin/python /home/reese/grove-src/connector/grove_mcp.py --vault /mnt/bigdrive/Obsidian
   ```
   Away from home, the same works with the Tailscale address `100.94.19.79` if the PC is on Tailscale.
4. **Check:** start `claude`, run `/mcp` and look for `grove`, then ask "What does my Grove look like today?"

**From the phone:** start Claude Code on the PC with Remote Control (`claude remote-control`); that session shows up in
the Claude app on the phone and has the grove connector. This keeps everything private: nothing on the server is
exposed to the internet.

## Before you start

1. Grove is installed, and in Grove › Settings you've chosen your vault folder.
2. On the server, `Grove/grove.json` and `Grove/history.json` exist inside the vault (give Syncthing a minute).
3. The server has Python 3.10 or newer (`python3 --version`).

## Install on the server

```bash
# Get this folder onto the server, e.g. clone the repo (it's private, so use your GitHub login)
git clone https://github.com/ReeseCpeaces/Repository-1.git ~/grove-src
cd ~/grove-src/connector
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# Check it can read your vault (use your real vault path):
.venv/bin/python -c "from grove_core import Grove; print(Grove('/path/to/vault').overview())"
```

## Connect Claude on your computer

Pick the one that matches how you use Claude on your computer.

### A1. Claude desktop app

Settings › Developer › Edit config, and add (replace the paths, user and server name):

If Claude runs **on the server itself**:
```json
{
  "mcpServers": {
    "grove": {
      "command": "/home/USER/grove-src/connector/.venv/bin/python",
      "args": ["/home/USER/grove-src/connector/grove_mcp.py", "--vault", "/path/to/vault"]
    }
  }
}
```

If Claude runs on **another computer** that can SSH into the server without a password (SSH key):
```json
{
  "mcpServers": {
    "grove": {
      "command": "ssh",
      "args": ["USER@SERVER", "/home/USER/grove-src/connector/.venv/bin/python",
               "/home/USER/grove-src/connector/grove_mcp.py", "--vault", "/path/to/vault"]
    }
  }
}
```
Restart the desktop app. "grove" appears in the connectors/tools menu.

### A2. Claude Code (terminal)

On the server, or over SSH from another computer:
```bash
claude mcp add grove -- /home/USER/grove-src/connector/.venv/bin/python \
  /home/USER/grove-src/connector/grove_mcp.py --vault /path/to/vault
# from another computer: claude mcp add grove -- ssh USER@SERVER <the same command>
```

### A3. Always-on on your home network (optional)

Run it as a service on the server so any Claude Code on your home network can use it:
```bash
# /etc/systemd/system/grove-mcp.service
[Unit]
Description=Grove connector
After=network-online.target

[Service]
User=USER
ExecStart=/home/USER/grove-src/connector/.venv/bin/python /home/USER/grove-src/connector/grove_mcp.py --vault /path/to/vault --http 0.0.0.0 8765
Restart=on-failure

[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl enable --now grove-mcp
claude mcp add --transport http grove http://SERVER-IP:8765/mcp   # on the computer
```
This has no login, so **don't** forward port 8765 on your router. It's for your home network only.

## Try it

Ask Claude: "What does my Grove look like today?" or "Add a Grove habit for discursive meditation
at 9 PM." After an edit, open Grove on the phone once Syncthing has synced.

## Why not a claude.ai connector like TickTick?

Connectors added on claude.ai are called from Anthropic's servers, so the connector would need a public
internet address (Tailscale Funnel is a public address, unlike normal Tailscale) plus a login. That breaks
the rule that nothing on the server is public, so use Remote Control (above) to reach it from the phone instead.

## Tests

```bash
.venv/bin/python tests/test_grove.py
```
