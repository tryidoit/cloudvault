# Changelog

## 0.1.0 (unreleased)
- Project foundation for Minecraft 26.3.

- Backup ZIPs are named with local date, time and time zone, e.g. backup-2026-10-06_15-10-34_PDT-ecc204ec.zip (older backups keep their old names).

## Milestone 2
- Experimental client-side encryption (AES-256-GCM, password + recovery key).
- Advanced combined retention (newest N / daily / weekly / monthly).
- S3-compatible cloud provider (R2, B2, AWS S3, MinIO...) next to Google Drive; backups remember where they are stored.
- Cloud management and storage screens, backup filters and sorting, compression presets, pause automatic backups (button and /cloudvault pause|resume).

## Round 3
- Import backups from the cloud (new computer / reinstall).
- Reset a world to its latest backup, with a separate BEFORE RESET backup made first.
- Worlds screen and world-select-screen buttons.
- Clearer settings; removed the custom Google client fields.

## Round 4
- World settings can be edited from the Worlds screen without opening the world.
- Per-world checkbox that turns CloudVault off (or on) for a world.

## Milestone 3 (polish and hardening)
- Cloud and local file names now show date, time, zone, backup type and world.
- Import from cloud is a menu: choose a world, then the backups to import (read from their names).
- "CloudVault off by default for new worlds" option and an ON/OFF indicator (HUD, pause button, join note), both switchable.
- Main screen shows what CloudVault is doing; clearer settings, tooltips, scrolling on small windows.
- Quieter, de-duplicated notifications and plain-language error messages.
- Security: https enforced for S3, stricter ZIP names, owner-only secret files, secret redaction.
- Faster backup list (in-memory index), exact shutdown wait, many new automated tests.

## Final hardening
- Safer: tampered metadata/world list cannot reach other folders; stricter ZIP names; sign-in callback ignores stray requests; no sign-in link in logs unless the browser cannot be opened.
- Lighter: faster duplicate check, bigger I/O buffers, lower-priority backup thread, no periodic disk scans in screens.
- Setup wizard is now four straight steps and never loops; "Run setup again" lives in Settings > Advanced.

## Pins, restore tests, update safety
- Pin backups and add notes.
- Restore test (automatic every 7 days by default, or on demand) proves a backup can be restored.
- Safety backup before a world opens after Minecraft or mods changed (BEFORE UPDATE).
- Warnings for low disk space and a nearly full Google Drive.
- Export ZIP and Open folder buttons.

## Unreleased
- Import from cloud now adds the world to your saves as a new world (nothing else is recorded); Import buttons always visible.
- New: Cloud Sync (per world, own cloud folder, newest 2 copies by default, automatic add/update at startup, never overwrites a world changed on two computers).
- New: backup comments for join, leave, periodic, gameplay and manual backups, all switchable on one Comments tab and on each type's tab and per world.

## Round 8
- Incremental backups (never worse than full backups; chains verified file by file on restore; compatible with world sync).
- World-in-use lock for synced worlds (warning screen: Leave world / Play anyway).
- Smart triggers: entering the Nether/End, low health, standing idle; cooldown.
- Compare two backups; share a backup as a link/code (import from code in Import).
- Upload controls: pause, hours window, only when not playing, speed limit.
- Alerts outside the game: Discord, Slack, Telegram, ntfy, Pushover, SMS (Twilio), e-mail (SMTP), webhook.
- Second destination (folder/NAS/Dropbox folder, S3, second Google account).
- CloudVault account: this computer's settings file in the cloud; new computers set themselves up from it.
- New UI: ONE Main Settings page everything branches from; breadcrumb top-left on every screen; Setup wizard button top-right on every settings page; Quick and Advanced setup; Separate Pages (Import, Worlds and Reset, View Backups, Compare, Share, Storage usage). Settings are described once in `SettingsRegistry` and rendered by one generic page screen; the Advanced wizard walks every registry page.
- Old tabbed `SettingsScreen` and `WizardScreen` moved to `reference/` (not compiled).
