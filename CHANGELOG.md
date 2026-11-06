# Changelog

Changes in Optimus (desktop-mode branch) on top of Artemis.

## 2026-11-06
- Privacy screen while connected (on by default): sends the PrivacyScreen helper's on hotkey when a session starts and its off hotkey when it ends.
- Virtual display is off by default again: the host resizes its real monitor to the resolution set in the app and restores it on disconnect.
- With a physical keyboard connected, if the soft keyboard is blocked by Android the app opens the system setting that allows it.
- Virtual display is on by default, so the host streams at the app's resolution and returns to its own desktop resolution when the session ends.
- The keyboard button shows or hides the soft keyboard explicitly instead of toggling it, so it reappears after the lock screen, dialogs or switching apps.

## 2026-11-05
- End session on disconnect (on by default): disconnecting quits the app on the host so it restores its display and runs undo commands.
- Keyboard bar detection restored after a regression with floating and split tablet keyboards; the bar only shows after the keyboard is requested.
- Voice / swipe text input on by default.
- Three-finger tap opens the soft keyboard and four-finger tap the full-size on-screen keyboard in every touch mode except multi-touch.
- Two-finger scroll is recognised sooner.

## 2026-11-04
- Two-finger scroll moves the cursor between the fingers first, so the pane under them scrolls without a click.
- Soft keyboard stays responsive after voice and swipe input.
- On-screen special keys stay hidden after a resize.
- Double-tap selects a word like a mouse double click; dragging after it extends the selection.
- Win, Backspace, Del and Ins keys on the keyboard bar and function pad.
- App renamed to Optimus.
- Absolute touch is the default: tap clicks, one-finger drag pans, press and hold right-clicks, two-finger drag scrolls.
- Quick menu handle, scroll bar, optional mouse buttons, movable overlays and overlay opacity.
- Defaults: custom 1800x1100 resolution, 10 Mbps (kept when the resolution changes), stretch video, local mouse cursor, Apollo clipboard sync on.
- Pinch to zoom anywhere replaces the separate zoom mode; one-finger pan after a pinch; two-finger double-tap smart zoom.
- Mouse clicks land under the cursor while zoomed, and the view follows the cursor to the edges.
- Modifier bar above the soft keyboard (sticky Win, Shift, Ctrl, Alt, Tab, text mode) and a function key pad.
- Text input mode for swipe typing, autocorrect and voice dictation.
