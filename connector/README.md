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

## Later: using it from the phone's Claude app (not set up yet)

Connectors added on claude.ai (like TickTick) are called from Anthropic's servers, so the connector
would need a public HTTPS address (for example with Tailscale Funnel) **and a login** so nobody else
can use it. That's a separate step; the options above don't need it.

## Tests

```bash
.venv/bin/python tests/test_grove.py
```
