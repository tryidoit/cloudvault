# Implementation Status
[x] Complete  [~] In progress  [ ] Not started  [!] Blocked  [?] Needs testing

## Foundation
[x] Project files (MC 26.3, Loom 1.18, Loader 0.19.5, Fabric API 0.161.0+26.3, Java 25)
[x] `./gradlew build` — SUCCESS (2026-10-06)
[x] Game runs; B key (in world only), pause button, Mod Menu config, /cloudvault and /cloudvault backup commands

## Milestone 1 — engine (pure Java, in src/main; compile-checked and tested via devtests/CoreSmokeTest.java: ALL PASSED)
[x] Config + per-world overrides (config.json)
[x] Backup engine: temp copy, ZIP, ZIP verify, SHA-256, metadata, disk-space check, session.lock skipped
[x] Backup queue (priority Manual>Session>Gameplay>Periodic, de-dup keys, single worker)
[x] Retention per category (0..100, Keep All, never deletes unverified/newer-missing, pending kept)
[x] Duplicate detection (fingerprint)
[x] Periodic scheduler (calendar months/years, no backlog), gameplay timer
[x] Cloud layer: CloudProvider, upload+verify (size+MD5), retry/backoff, pending persisted, resume at startup
[x] Timestamped ZIP names (backup-YYYY-MM-DD_HH-mm-ss_<zone>-<id8>.zip; name stored in metadata.zipFileName; old backups keep backup-<uuid>.zip)
[x] Local cache trim; download/export; manual delete
[x] Restore: checksum verify, safety backup, as-new-world, replace (old folder kept in cloudvault/replaced), safe ZIP extraction
[x] Google Drive provider + OAuth (built-in client, sign-in and upload verified by user 2026-10-06; Drive API had to be enabled in the Cloud project)

## Milestone 1 — Minecraft side (src/client; compiled only against hand-made stubs, API names checked in Fabric 26.3 sources/docs)
[?] World join/leave hooks (SERVER_STARTED / SERVER_STOPPED), flush via saveAllChunks
[?] Keybind "Open CloudVault" (default B), pause-menu button (top-left), tick scheduler
[?] Screens: main, settings (all categories, cloud, advanced, per-world), backup browser, details, restore, confirm, first-launch wizard
[?] Toasts (SystemToast, one id for all)
[?] Amount fields: +/- buttons AND typed numeric box (retention 1-100, time amounts). 0 / Keep All only via buttons (they show warnings)
[?] Flush now calls PlayerList.saveAll() then saveAllChunks (ClientBridge.flush) — verify names compile
[ ] Real-world tests (large worlds, offline, shutdown during upload)

## Milestone 2 - advanced features (engine tested by devtests/Milestone2Test.java + CryptoTest-style checks; screens type-checked only)
[x] 1. Client-side encryption, EXPERIMENTAL label, off by default: AES-256-GCM chunked (CVENC1), random data key wrapped by PBKDF2 password AND recovery key in every file header, tamper/truncation/wrong-key detected, cloud only receives ciphertext; Encryption screen (setup, recovery key shown once + RECOVERY-KEY.txt, on/off, unlock, remove key)
[x] 2. Advanced retention: combined rules (newest N, 1/day for D days, 1/week for W weeks, 1/month for M months), per category, per world; unverified backups never deleted
[x] 3. Providers: S3-compatible (AWS S3, Cloudflare R2, Backblaze B2, MinIO...) with SigV4 (checked against AWS's published examples), multipart upload, Content-MD5 per request; provider switch in Settings -> Cloud. OneDrive/Dropbox NOT implemented (need their own app registrations and cannot be tested here)
[x] 4. Cloud management screen: status/account, usage, refresh cloud (re-uploads backups that vanished), open cloud folder, reconnect, switch account, retry all
[x] 5. Browser filters (All/Manual/Periodic/Gameplay/Join/Leave/Pending/Failed) and sort (Newest/Oldest/Size/Type)
[x] 6. Compression presets Fast(1)/Balanced(6)/Maximum(9) with descriptions
[x] 7. Pause automatic backups (1h / 4h / until resumed; /cloudvault pause [minutes], /cloudvault resume); manual and safety backups never paused
[x] 8. Storage screen: local/cloud sizes, verified/pending/failed counts, free local space, clean temp, retry all
[x] Timestamped ZIP names
[ ] 9. "Other optional features from CLOUDVAULT_SPEC.md" - spec file not available; user to list any wanted extras

## Round 3
[x] Import from cloud (Cloud Management, Worlds screen, world-select button): rebuilds backup list on a new computer; encrypted ones then need the password or recovery key
[x] Reset world to latest backup (Worlds screen and backup browser): makes a separate BEFORE RESET backup first; old folder kept in cloudvault/replaced
[x] Removed everything needing extra developer app registrations (custom Google client ID/secret). OneDrive/Dropbox never implemented.
[x] Settings page: tab descriptions, clearer labels, "Advanced retention is ON" hint on pages whose number is then unused
[x] World select screen: "CloudVault Worlds" and "Import from cloud" buttons (top-left)

## Per-world control (round 4)
[x] World settings editable for any world without opening it: Worlds screen -> Settings (opens the World tab for that world; other tabs edit its own settings once "Separate settings" is ON)
[x] "[x] CloudVault enabled for this world" checkbox (Worlds screen rows, World tab, main screen when in a world). Off = no automatic or manual backups, no uploads, no retention for that world. Existing backups stay browsable; reset/restore still make their safety backup (protects data); switching on again lets waiting uploads continue.

## Final hardening round
[x] Bugs fixed: concurrent saves of one file could mix data (unique temp names now); OAuth callback could be ended by any stray local request (state is now checked, wrong requests ignored); callback server closed before the browser got its page; worlds.json was rewritten on every lookup; extractor accepted Windows device names / trailing dots; stale .tmp files from a crash are swept at start
[x] Tampered files: metadata and worlds.json entries with path tricks (../, separators, odd characters) are ignored and never used to build a path; invalid records are refused on save
[x] Optimization (same behaviour): duplicate-check fingerprint is streaming, order-independent, one stat per file (20000 files in ~100 ms, no big strings); 256 KB buffers for zip/hash/extract; worker thread runs at lower priority; metadata index; status screen reads counts without copying; Worlds screen no longer rescans the disk on a timer; HUD indicator allocates nothing per frame
[x] Fingerprint format changed (v2): after updating, the first periodic/manual duplicate check treats the world as changed once (one extra backup)
[x] Setup wizard: 4 straight steps (Welcome, Cloud, What to back up, Done) with Back/Next; never opens settings on the way; full settings offered only on the last step; counts as done when opened so it cannot loop; "Run setup again" in Settings > Advanced
[?] Not verifiable here: password boxes are not masked (Minecraft text box masking API not confirmed for 26.3), real-game behaviour of all screens

## Extra features round (pins, restore test, before-update backup, space warnings, export)
[x] Pin a backup (never deleted by retention, does not count against limits) and add a short note; Pinned filter; notes are local only
[x] Restore test: unpacks the latest backup into temp with the real safe extractor, checks level.dat (gzip NBT) and region files, removes temp; automatic every N days per world (default 7, off = 0, never downloads), manual "Test restore" in backup details (may download); result shown on main screen and in the browser; failure notifies
[x] Before-update backup: SERVER_STARTING (before the world loads) compares level.dat version with the running Minecraft and a hash of the mod list; copies the world immediately, finishes in the background; type BEFORE UPDATE (safety: never auto-deleted, never "latest"); once per change; setting in Advanced
[x] Space warnings: low local disk (under 2 GB or 3x the backup, notification + status line); Google Drive over 90% (checked at most hourly after an upload)
[x] Export: Export ZIP / Open folder in backup details, Open backups/exports folder in Storage; export file has the readable backup name; only CloudVault's own folders can be opened
[?] In-game: SERVER_STARTING timing on a real big world (loading screen waits for the copy), Open folder on each OS, the new screens
[?] The mod list used for the "mods changed" check comes from FabricLoader.getAllMods() (includes libraries and built-ins; any change triggers one safety backup)

## Known limits / ideas
- Imported backups from before this version have no stored world id; they are grouped by the 8-character id in the cloud folder name.
- Changing the encryption password is not supported (old backups keep their original wrapper); create a new key instead.
- A timed pause that expires causes at most one catch-up periodic backup.

## Milestone 3 - polish, testing, security, docs
Legend: [x] done and verified by automated tests here | [?] implemented, needs in-game testing | [!] cannot be done without Gradle/Minecraft | [ ] not done

GUI / UX
[?] Main screen: world, provider + connection, activity (Idle/Saving/Preparing/Copying/Compressing/Verifying/Uploading/Verifying cloud copy/Restoring/Waiting for upload), last backup, counts, last problem, timers; compact two-column buttons
[?] Settings: tab descriptions, plain labels, ON/OFF everywhere, tooltips on confusing controls (retention 0 / Keep All, skip unchanged, gameplay timer, cloud verification, pending uploads, local cache, encryption, per-world switch)
[?] Settings scroll (mouse wheel + Up/Down buttons) for small windows; other screens fit down to ~270 px high
[?] Keyboard navigation: standard Minecraft widgets only (Tab/Enter), not verified in game
[?] On-screen ON/OFF indicator (HUD), pause-menu label, join note; all switchable
[x] Notifications: respect the setting, de-duplicated (same warning/error at most once per 15 min), automatic backups only speak up on problems
[x] Friendly, secret-free error text (disk full, no internet, permission, file in use, damaged archive, sign-in needed)

Reliability / tests (devtests: CoreSmokeTest, Milestone2Test, Milestone3Test, all passing)
[x] Manual/join/leave/periodic/gameplay logic, retention (0, 1, many, Keep All, advanced rules), pending-upload survival, restart recovery, offline/retry
[x] Corrupt/empty metadata skipped, leftovers from a crash cleaned, partial files never become backups, interrupted upload resumes
[x] Failed replacement never deletes the previous valid backup; 7 simultaneous triggers give only valid archives
[x] Config validation: malformed JSON, nulls, negative/huge values, old formats
[x] Queue is a single worker (no two backups touch a world at once); idle wait is exact; metadata index (3000 backups: lookups in ms)
[?] Real interruption (kill -9 mid compress/upload/download), shutdown timing, large worlds: see MANUAL_TESTS.md

Restore / security
[x] Restore as new / replace (safety backup first) / reset to latest (BEFORE RESET backup first); corrupt backup refused
[x] ZIP extraction: ../, absolute, drive letters, backslashes, NUL, nested traversal all refused (tested with 8 hostile names)
[x] Symlinks in worlds are skipped when backing up; cleanup never follows links
[x] Tokens/keys never logged; messages pass through redaction; token, encryption key and S3 secret files are owner-only (POSIX)
[x] S3 requires https (plain http only for localhost); links opened in the browser must be https or loopback
[x] No telemetry; network only to the chosen cloud provider and Google sign-in
[x] Cloud code only touches the CloudVault/ area (listing is filtered to files named backup-*)
[x] Encryption: AES-256-GCM chunked, tamper/truncation/wrong key detected, cloud receives ciphertext (tested). Still labelled EXPERIMENTAL: no external review, password change not supported
[?] The Google desktop client secret ships inside the jar (not confidential per Google, kept out of Git via .gitignore)

Docs / release
[x] README, CHANGELOG, DEVELOPMENT_NOTES, MANUAL_TESTS, .gitignore, OAuth example file
[!] `./gradlew clean build` of this round, jar inspection, clean-profile test, mod compatibility (Sodium, Lithium, Mod Menu): need the user's machine
[ ] Release readiness: NOT release-ready until the in-game checklist in MANUAL_TESTS.md has been run

## Known limits / ideas
- Changing the encryption password is not supported (create a new key).
- Metadata edited by hand while the game runs is not noticed until restart (index is cached in memory).
- A timed pause that expires causes at most one catch-up periodic backup.
- Imported backups made before world ids were stored are grouped by the 8-character id in the cloud folder name.

## Round 7 - real import, cloud sync, comments
[x] Import from cloud now really imports: pick a world (Import = newest backup) or Open it and import any backup. The backup is downloaded, decrypted if needed, checked and unpacked into saves as a NEW world (folder "Name (date time)"; never overwrites). Nothing else is recorded: no backup-list entries, no settings, no uploads. The Import buttons are always shown (greyed while busy). Import buttons: world select screen, Worlds and Reset screen. (devtests/Milestone5Test: engine tested)
[x] Cloud sync (own cloud folder "CloudVault Sync", Drive and S3): per-world switch (default OFF; global "new worlds start with sync" default OFF), uploads when you leave the world, keeps the newest N copies (default 2, changeable globally and per world, 1-20). Settings: new "Cloud Sync" tab; per-world switch also in the Worlds tab. At startup (and when the world list opens, at most every 10 min) worlds missing on this computer are added, older unchanged ones are updated (old folder kept in cloudvault/replaced), and a world changed on BOTH computers is never overwritten: the cloud version is added as "<name> (cloud copy)". A world deleted on purpose is not brought back. Failed uploads are retried at next start. "Check the cloud now" and "Upload this world now" buttons. (engine tested with two simulated computers)
[x] Comments: per backup type (join, leave, periodic, gameplay, manual; safety types never ask). Window pauses singleplayer; Save / Back up without comment / Skip this backup; Esc = back up without comment. Stored in the backup record and inside the zip as cloudvault-comment.txt (not extracted on restore); shown in Backup Details. All switches on the new "Comments" tab and on each type's own tab; a world with "Separate settings" has its own. Leave: the window appears on the title screen (45 s limit, then the backup happens without comment; closing the game also backs up without comment).
[?] In-game, not verifiable here: the comment window timing (join while loading, leave back at title), world select buttons (matched by class name SelectWorldScreen / ...WorldSelection...), sync at startup, Esc handling, new tabs layout (6 per row).
[ ] Comments are not copied into Google Drive/S3 file properties (only inside the zip); a cloud-only backup shows its comment after it was downloaded once.

## Round 8 status
| Item | Engine | Tests | Screens |
|---|---|---|---|
| Incremental | done | Milestone6Test | toggles in Backups > Keeping |
| Upload controls, alerts (incl. SMTP), mirror, lock, smart triggers, compare, share (+SigV4 reference signature), account | done | Milestone7Test ALL PASSED | written, NOT run in game |
| SettingsRegistry (+ coverage test) | done | Milestone8Test ALL PASSED | PageScreen / MenuScreen / Advanced wizard |
| Main Settings hub, breadcrumbs, wizard button, Separate Pages, Quick wizard | - | - | written; type-checked only against stubs |
Client code (`src/client`) has never been compiled against real Minecraft 26.3.
