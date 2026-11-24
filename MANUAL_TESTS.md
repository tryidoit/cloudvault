# Manual test procedures (need a running Minecraft 26.3 + Fabric)

Automated engine tests (`devtests/`) cover logic. These steps cover what needs the game. Tick each item and record the date/result in
`IMPLEMENTATION_STATUS.md`.

## A0. Setup wizard
Fresh config: wizard opens once on the title screen (Welcome > Cloud > What to back up > Done). Back/Next work, Skip closes it, Esc closes it. Relaunch: it does NOT reappear. Settings > Advanced > Run setup again opens it. "Open all settings" appears only on the last step.

## A. Fresh install
1. Empty `mods` + new profile; add Fabric API, CloudVault (and Mod Menu). Launch.
2. Setup screen appears; skip it, then reopen via B / pause button / Mod Menu > Configure.
3. Connect Google Drive; confirm "connected". Disconnect and reconnect.

## B. Backups (one world with some builds)
1. Manual: SAVE CURRENT STATE. Expect toast, ZIP in `cloudvault/backups/<id>/` named with date, time, zone, MANUAL, world; then "uploaded and verified"; entry in View Backups; file in Drive/S3 `CloudVault/<world>`.
2. Join: leave and rejoin; JOIN backup appears (if enabled). Leave: LEAVE backup exists after quitting to title.
3. Quit the whole game right after leaving: game closes within a few seconds (no hang); next start finishes any pending upload.
4. Periodic: set 1 minute; stay in the world; backups appear only if the world changed. Close the game for a long time; start: at most ONE catch-up backup.
5. Gameplay: set short time; menus do not advance it (watch "Next gameplay backup in"); leaving/rejoining behaves sensibly.
6. Pause: pause 1 h; join/leave/periodic do nothing, manual still works; Resume.

## C. World switch and indicator
1. Worlds and Reset: untick `[x]` for a world: no backups of it at all, row says "CloudVault is off".
2. Settings > Worlds: "New worlds start with CloudVault" OFF; create a new world: indicator says OFF, nothing is backed up; tick it, back up works.
3. Indicator top-left HUD, pause menu button label and join toast follow "Show indicator".

## D. Restore
1. Restore as new world: new world opens, original untouched.
2. Replace current world (from title screen): confirm; safety backup exists; world matches the backup.
3. Reset to latest: edit the world, reset; world rolled back; BEFORE RESET backup contains the edit; `cloudvault/replaced/` holds the old folder.
4. Corrupt a local backup ZIP (edit a byte): restore refuses with a clear message.

## E. Offline / failures
1. Disconnect internet, back up: backup is Pending with "No internet" (one warning toast only). Reconnect: uploads without action.
2. Sign out in Google account permissions page: next upload says sign-in needed; reconnect fixes it.
3. Fill the disk (or use a tiny drive for the folder): clear "disk is full" message, no half backup left in `temp/`.
4. Kill the game process during a backup and during an upload; restart: no fake "valid" backup, upload resumes.

## F. Import
New computer or empty `cloudvault/metadata`: Import from cloud > choose world > Import / Import all new. Restore one. Encrypted ones ask for password/recovery key.

## G. UI
Test GUI scale 1-4 and a small window: Settings scrolls (wheel or Up/Down buttons), nothing overlaps; Tab/Enter/arrow keys reach buttons and fields; keybind change works and appears in Controls.

## H. Performance
Large world (1+ GB): game stays responsive during backup (it runs off the render thread; only the world save flush happens on the server thread); watch temp disk use; idle FPS unchanged with the mod installed.

## J. Pins, restore test, update safety, space, export
1. Pin a backup (details screen), set retention to 0 and make backups: the pinned one stays, the browser shows PINNED; Pinned filter works; add a note, leave and reopen the screen: note is there.
2. Test restore on a backup: toast says passed; main screen shows "Restore test: OK today"; the temp folder has no `restoretest-*` left. Set the interval to 3 days and play: one test appears by itself.
3. Before update: play a world, quit, then start the game with a different Minecraft version (or add/remove a mod) and open the world: the loading screen waits briefly, a BEFORE UPDATE backup appears (safety filter), opened only once per change. A new world and a switched-off world make none.
4. Space: with a nearly full drive, a warning shows after a backup and on the main screen.
5. Export ZIP: file appears in `cloudvault/exports` with the readable name and opens in a normal unzip tool; Open folder buttons open the right folders on your OS.

## I. Release
`./gradlew clean build`; open the jar: `fabric.mod.json` present, no `cloudvault-oauth.properties.example`, no `auth/`, no keys; test it in a clean profile.

## K. Import, cloud sync, comments (round 7)
1. Singleplayer > "Import from cloud": pick a world > Import. A new folder appears in saves (reopen the world list); it opens and plays. Backups list unchanged.
2. Settings > Cloud Sync: switch sync ON for a world, play, leave. Check Drive "CloudVault Sync/<world>" has a sync-... zip. Leave 3 times: only 2 stay. Change the number to 3 and 1 and check.
3. Second computer (or delete the world + its entry in cloudvault/config.json): start Minecraft: the world is added. Play on one computer, leave, start the other: it updates. Change it on both without syncing: the cloud version appears as "(cloud copy)".
4. Comments tab: switch each type ON one by one. Join: window appears once the world is playing and the game is paused. Leave: window appears at the title screen. Periodic/gameplay: window pauses the game. Manual: window appears over the CloudVault screen. Try all three buttons and Esc. Check Backup Details shows the comment and the zip contains cloudvault-comment.txt.
5. World with "Separate settings" ON: Comments tab changes only that world.

## L. Round 8 (all need checking in game)
1. Pause menu, title screen, Mod Menu "Configure" and the CloudVault key all open Main Settings. Breadcrumb top-left on every page (e.g. CloudVault > Main Settings > Cloud > Storage > Cloud Management). "Setup wizard" top-right on settings pages: chooser shows Quick / Advanced.
2. Advanced setup: Next through every page; Back keeps values; Exit setup returns.
3. Quick setup on first launch: cloud, account step (second computer: pick a settings file; wrong password refused), when to back up, incremental, sync, alerts.
4. Backups > When: smart triggers. Enter the Nether/End, drop to 2 hearts, stand still: a backup with a reason toast appears, at most once per cooldown.
5. Cloud > Upload controls: pause (backup stays PENDING, resumes), hours window, only when not playing, speed limit.
6. Alerts: add Discord/ntfy/e-mail channel, Test; break the cloud login to trigger a real alert.
7. Second destination: folder/S3/second Google; restore with the first destination emptied.
8. Two computers, synced world: open on A, open on B -> warning; "Leave world" returns to the title screen and uploads nothing; "Play anyway" continues. (Check `ClientBridge.leaveWorld()` works in 26.3.)
9. Separate Pages: Compare two backups, Share a backup, import from the code on the other computer, Storage usage.
10. Account & Devices: password on/off, second computer loads settings, deleting a file.
