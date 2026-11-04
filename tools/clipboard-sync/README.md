# Clipboard sync (host helper)

Two-way text clipboard sync between a Windows host and the desktop mode app.
Copy on the PC and paste on the phone, or the other way round.

## Use

1. Build `ClipboardSync.exe` with `build.ps1` (uses the C# compiler that ships
   with Windows; no SDK needed) and copy it to the host.
2. Allow it through the firewall once, from an administrator PowerShell:
   `New-NetFirewallRule -DisplayName "Moonlight Clipboard Sync" -Direction Inbound -Protocol TCP -LocalPort 48250 -Action Allow -Profile Private`
3. Run it. It sits in the system tray with no window. To start it with
   Windows, put a shortcut to it in `shell:startup`.
4. On the phone, keep **Sync clipboard with PC** on in **Settings > Input
   Settings** (on by default). The app connects when a stream starts.

## Notes and limits

- Text only. Images and files are not synced.
- The phone side can only read its clipboard while the stream is in front.
  Text copied in another phone app is sent when you switch back to the stream.
- Uses TCP port 48250 on the host address used for streaming. Only
  connections from private, link-local and Tailscale addresses are accepted.
- Traffic is not encrypted. Use it on networks you trust, or over a VPN such
  as Tailscale.
- The log is at `%TEMP%\ClipboardSync.log`.
