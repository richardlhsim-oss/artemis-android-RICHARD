# StreamHost (host helper)

One executable for Apollo / Vibepollo / Sunshine hosts that, for each session:

- switches the primary monitor to the client's requested resolution (closest
  supported mode) and restores the original resolution afterwards, and
- blanks the physical monitors with a privacy screen that is excluded from
  screen capture, so the stream still shows the desktop.

## Setup

1. Build `StreamHost.exe` with `build.ps1` and copy it to the host.
2. Optional: put a shortcut to it in `shell:startup` so the privacy screen
   tray icon and hotkeys are always available.
3. In Apollo / Vibepollo, add a command preparation and keep it first in the
   list, before any lock command (Windows refuses resolution changes once the
   PC is locked):
   - Do: `"C:\path\StreamHost.exe" --start`
   - Undo: `"C:\path\StreamHost.exe" --stop`
   - Elevated: off
4. Set the host's display device configuration to Disabled so it does not
   fight over the resolution.

## Commands

| Command | Effect |
| --- | --- |
| (none) | Run the privacy screen in the tray |
| `--start [WxH[@FPS]]` | Client (or given) resolution, then privacy screen on |
| `--stop` | Privacy screen off, then original resolution |
| `--privacy-on`, `--privacy-off`, `--privacy-toggle` | Privacy screen only |
| `--set [WxH[@FPS]] [--dry-run]`, `--restore`, `--list` | Resolution only |

Hotkeys while running: `Ctrl+Alt+Shift+P` toggles the privacy screen,
`Ctrl+Alt+Shift+F9` switches it on and `Ctrl+Alt+Shift+F10` off.

Logs: `%TEMP%\StreamResolution.log` and `%TEMP%\PrivacyScreen.log`.

## Notes

- Resolution changes are not saved to the registry; a reboot restores the normal resolution.
- The privacy screen needs Windows 10 version 2004 or later and does not cover the lock screen or UAC prompts.
