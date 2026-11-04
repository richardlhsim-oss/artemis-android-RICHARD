# Artemis Android

Previously named Moonlight Noir

An open source client for [Apollo](https://github.com/ClassicOldSong/Apollo)/[Sunshine](https://github.com/LizardByte/Sunshine).

Artemis Android will allow you to stream your collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Artemis is currently the best fork of Moonlight with loads of optimizations for office usage.

A more seamless experience with virtual display will be Artemis paired with [Apollo](https://github.com/ClassicOldSong/Apollo).

# Optimus: desktop customizations in this fork

This build is named **Optimus**.

This fork adds remote desktop features carried over from [moonlight-android-RICHARD](https://github.com/richardlhsim-oss/moonlight-android-RICHARD):

- **Pinch to zoom at any time.** This replaces Artemis's separate zoom mode, which is removed from the game menu. Two-finger scrolls and taps still go to the host.
- **One-finger pan after a pinch:** lift one pinch finger and the remaining one keeps panning until it is lifted. A finger put down within 1 second of the pinch ending also pans, for as long as it is held.
- **Two-finger double-tap** toggles between full view and 2.5x zoom at that point.
- **Mouse clicks track the zoomed view:** with the local mouse cursor, clicks land exactly where the cursor is while zoomed, and the view pans to follow the cursor to the screen edges.
- **Modifier bar above the soft keyboard:** sticky Win, Shift, Ctrl and Alt, plus Tab, Del, Ins, a text input mode toggle (**Aa**), the game menu, and a function key pad (F1-F12, Esc, Print Screen, navigation and arrow keys, Enter, Win, Backspace, Paste, common shortcuts, rotate screen).
- **Quick menu handle (right edge):** the arrow opens a quick menu with the keyboard, touch mode, performance stats, the game menu, privacy screen (needs the PrivacyScreen helper on the host), move overlays and disconnect. The keyboard icon on the handle opens the soft keyboard.
- **Scroll bar (right edge):** drag to scroll. Optional left/right mouse buttons at the bottom, with drag lock on double-tap.
- **Move overlays:** from the quick menu, drag the handle, scroll bar or mouse buttons to a new place, then tap one without dragging to save. Opacity and which overlays show are set in the input settings.
- **Absolute touch gestures (default touch mode):** tap clicks exactly where you touch, one-finger drag pans the view at any time, double-tap selects a word like a mouse double click and dragging after it extends the selection, three-finger tap opens the soft keyboard and a four-finger tap opens the full-size on-screen keyboard, press and hold right-clicks, two-finger drag scrolls the window or pane under the fingers (no click needed), pinch zooms.
- **Text input mode (voice / swipe):** swipe typing, autocorrect and voice dictation type into the host. Toggle with **Aa** on the modifier bar or from the game menu.

Defaults for a fresh install: custom 1800x1100 resolution, 10 Mbps (also kept when the resolution changes), scroll bar on, mouse buttons off, stretch video, absolute touch mode, local mouse cursor. Clipboard sync uses Artemis's built-in Apollo support. Defaults only apply to settings that have never been saved.

# Features

If you switch back to the main stream version, you'll be missing the following awesome features which are very unlikely to be added there:

1. Custom virtual buttons with import and export support.
2. [Custom resolutions](https://github.com/moonlight-stream/moonlight-android/pull/1349).
3. Custom bitrates.
4. [Multiple mouse mode switching](https://github.com/moonlight-stream/moonlight-android/pull/1304) (normal mouse, [multi-touch](https://github.com/moonlight-stream/moonlight-android/pull/1364), touchpad, disabled, local cursor mode).
5. Optimized virtual gamepad skins and free joystick.
6. External monitor mode.
7. Joycon D-pad support.
8. Simplified performance information display.
9. [Game back menu](https://github.com/moonlight-stream/moonlight-android/pull/1171).
10. Custom shortcut commands.
11. Easy soft keyboard switching.
12. Portrait mode.
13. Display on top mode, useful for foldable phones.
14. [Virtual touchpad space and sensitivity adjustment](https://github.com/moonlight-stream/moonlight-android/issues/1348#issuecomment-2236344729) for playing right-click view games, such as Warcraft.
15. Force use device's own vibration motor (in case your gamepad's vibration is not effective).
16. Gamepad debugging page to view gamepad vibration and gyroscope information, as well as Android kernel version information.
17. Trackpad tap/scrolling support
18. Natural track pad mode with touch screen
19. Non-QWERTY keyboard layout support
20. Quick Meta key with physical BACK button
21. Frame rate lock fix for some devices
22. Video scale mode: Fit/Fill/Stretch
23. View pan/zoom support
24. Rotate screen in-game
25. Add option to quit app directly
26. Samsung DeX scrolling support
27. Proper click/scroll/right-click for trackpad on generic Android tablet when using local cursor
28. Virtual Display integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
29. Server Command integration with [Apollo](https://github.com/ClassicOldSong/Apollo)
30. Clipboard sync (requires Apollo)

# Disclaimer

This is the `go away` version of Moonlight Android.

I got kicked from Moonlight and Sunshine's Discord server literally for helping people out.

This is what I got for finding a bug, opened an issue, getting no response, troubleshoot myself, fixed the issue myself, shared it by PR to the main repo hoping my efforts can help someone else during the maintainance gap.

Yes, I'm going away. Fixes and improvements on this fork are not necessarily be merged to the main repo either. I have also started [a fork of Sunshine called Apollo](https://github.com/ClassicOldSong/Apollo) and will add useful features that will never get merged by the main repo shortly. [Apollo](https://github.com/ClassicOldSong/Apollo) and [Moonlight Noir](https://github.com/ClassicOldSong/moonlight-android) will no longer be compatible with OG Sunshine and OG Moonlight eventually, but they'll work even better with much more carefully designed features.

The main repo had stayed silent for 5 months, with nobody actually responding to issues, and people are getting totally no help besides the limited FAQ in their Discord server. I tried to answer issues and questions, solve problems within my ablilty but I got kicked out just for helping others.

**PRs for feature improvements are welcomed here unlike the main repo, your ideas are more likely to be appreciated and your efforts are actually being respected. We welcome people who can and willing to share their efforts, helping yourselves and other people in need.**

**Update**: They have contacted me and apologized for this incident, but the fact it **happened** still motivated me to start my own fork.

## Downloads
* [Download APK directly](https://github.com/ClassicOldSong/moonlight-android/releases)
* [Use Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.limelight.noir%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FClassicOldSong%2Fmoonlight-android%22%2C%22author%22%3A%22ClassicOldSong%22%2C%22name%22%3A%22Artemis%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22nonRoot%5C%22%2C%5C%22matchGroutToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D) (recommended)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
