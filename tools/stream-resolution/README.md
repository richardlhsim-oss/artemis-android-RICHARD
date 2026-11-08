# Stream resolution (host helper)

Switches the host's primary monitor to the streaming client's resolution when a
session starts and restores the original resolution when it ends. Use it when
the host's own display handling does not change the resolution.

## Use

1. Build `StreamResolution.exe` with `build.ps1` and copy it to the host.
2. In Apollo / Vibepollo, add a command preparation (global, or for the Desktop
   app) and keep it first in the list, before any lock command:
   - Do: `"C:\path\StreamResolution.exe" --set`
   - Undo: `"C:\path\StreamResolution.exe" --restore`
   - Elevated: off
3. Set the host's display device configuration to Disabled so it does not
   fight over the resolution.

`--set` uses the resolution and frame rate the client requested
(`SUNSHINE_CLIENT_WIDTH`, `SUNSHINE_CLIENT_HEIGHT`, `SUNSHINE_CLIENT_FPS`). Pass
a mode such as `--set 1920x1080@60` to use a fixed one instead. If the monitor
does not support the exact mode, the closest supported one is used.

Other commands: `--list` prints the supported modes, `--dry-run` after `--set`
shows the chosen mode without applying it.

## Notes

- Changes are not saved to the registry, so a reboot always returns to the
  normal resolution.
- Windows does not allow resolution changes while the PC is locked, so the
  command must run before any lock command.
- Log: `%TEMP%\StreamResolution.log`.
