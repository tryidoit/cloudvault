# CloudVault

Automatic, **verified** world backups for Minecraft (Fabric, 26.3), with uploads to **Google Drive** or **S3-compatible storage**
(Cloudflare R2, Backblaze B2, AWS S3, MinIO...) and safe restore. Client-side mod, singleplayer worlds.

> Status: all features below are implemented and the engine is covered by automated tests, but the mod has **not yet been through
> a full in-game test pass**. See `IMPLEMENTATION_STATUS.md` and `MANUAL_TESTS.md`. Keep your own copy of important worlds until you
> have restored a backup successfully at least once.

## Features
- Backups when you join a world, leave it, on a schedule (periodic), after play time (gameplay), and manually (SAVE CURRENT STATE).
- Every backup is a ZIP that is **re-opened and checked**, has a SHA-256, and is only called "uploaded" after the cloud copy is checked too.
- Cloud file names show date, time, zone, backup type and world, e.g. `backup-2026-10-06_15-10-34_PDT_JOIN_My World_ecc204ec.zip`.
- Uploads that cannot finish (no internet, signed out) stay **pending**, survive restarts and are retried.
- Retention per backup type (newest + N older, or Keep All) or advanced combined rules (newest N, 1/day, 1/week, 1/month).
- Restore as a **new world** (default, safest), **replace** a world (safety backup first), or **reset to latest backup**
  (a separate BEFORE RESET backup is made first; the old folder is kept in `cloudvault/replaced`).
- Import backups from the cloud on a new computer: pick a world, then pick backups, read from their file names.
- Per-world switch (checkbox) and per-world settings; option to have CloudVault OFF by default for worlds; on-screen ON/OFF indicator.
- Pin important backups (retention never deletes them) and add notes. Export any backup as a normal ZIP.
- Restore test: CloudVault quietly proves your latest backup can be restored (unpacks it into a temporary folder and checks it) and tells you if not.
- Safety backup before a world opens after Minecraft or your mods changed (label BEFORE UPDATE).
- Warnings when this computer or your Google Drive is nearly full.
- Pause automatic backups (1 h, 4 h, until resumed). Manual backups keep working.
- Backup browser with filters (type, pending, failed) and sorting. Storage and cloud management screens.
- Experimental client-side encryption (AES-256-GCM, password + recovery key).
- Compression presets (Fast / Balanced / Maximum). Optional Mod Menu entry.

## Install
1. Minecraft **26.3**, Fabric Loader 0.19.5 or newer, **Java 25**, the matching **Fabric API**.
2. Put `cloudvault-<version>.jar` in the `mods` folder (Mod Menu is optional).
3. Start the game. A four-step setup appears once on the title screen (Settings > Advanced > Run setup again repeats it); you can skip it and use the CloudVault button on the pause menu,
   the **B** key (rebindable in Controls), or Mod Menu > CloudVault > Configure.

## Cloud setup
- **Google Drive:** Settings > Cloud > *Connect Google Drive*. Your browser opens Google's own sign-in page. CloudVault never sees your
  password and only asks for the `drive.file` permission (it can only see files it created). If Google shows an "unverified app"
  warning, that is expected for an unreviewed app: Advanced > continue.
- **S3-compatible:** Settings > Cloud > Provider > *Configure*. Enter the https endpoint, region, bucket, access key and secret key.
  Use a private bucket and a key limited to it.
- Backups go to a `CloudVault/<world>` area only. CloudVault never touches other files in your cloud storage.

## Restore
Worlds and Reset (main screen, or the world select screen) or View Backups > pick a backup:
- **Restore as new world** is the safe default: your current world is not touched.
- **Replace current world** needs the world to be closed and asks for confirmation; a safety backup of the current world is made first.
- **Reset to latest backup** is on the Worlds screen and in the backup browser (world must be closed).

## Where data is stored
`.minecraft/cloudvault/`: `config.json`, `backups/` (local ZIPs), `metadata/` (one JSON per backup), `temp/` (work files, cleaned at
start), `auth/` (sign-in tokens, S3 secret, encryption key; owner-only on Linux/macOS), `replaced/` (worlds moved aside by a restore/reset).
Delete nothing in `metadata/` by hand: it is the list of your backups.

## Pending uploads
A backup is saved locally first. If the upload fails it shows as **Pending** with the reason and is retried (with growing delays);
on the next start CloudVault continues where it stopped. Failed (not retryable) backups are listed under the *Failed* filter; fix the
cause (for example sign in again) and use *Retry all* in Cloud Management.

## Encryption (experimental)
Settings > Advanced > Encryption. Backups are encrypted on your computer before upload; the cloud only receives ciphertext. You get a
**recovery key** once. **If you lose both the password and the recovery key, encrypted backups cannot be recovered by anyone.**
The ZIP kept on your own computer is not encrypted. Changing the password is not supported yet. Treat this as experimental.

## Security
CloudVault never asks for a password (Google sign-in happens in your browser, with PKCE), requests only the `drive.file` permission,
talks only to your chosen cloud over https, sends no telemetry, never logs tokens or keys, keeps token/key files owner-only, refuses
unsafe ZIP entries, ignores tampered metadata and never runs downloaded content. It has been reviewed and tested for these cases but
**has not been independently audited**; encryption is experimental and password boxes are visible while you type.

## Important warnings / known limitations
- Singleplayer worlds only. Large worlds take time and need free disk space for a temporary copy.
- Backups taken while the game is saving are copied first and verified; a damaged result is never reported as a good backup.
- The Google client secret that ships inside the jar is a *desktop app* secret (not confidential by Google's definition) but is kept
  out of Git; see `cloudvault-oauth.properties.example`.
- Changing the encryption password, OneDrive/Dropbox, and multiplayer/server worlds are not supported.
- Compatibility with other mods has not been tested; CloudVault uses only Fabric API events (no mixins).

## Troubleshooting
- *Nothing happens on Connect:* the sign-in link is also written to `logs/latest.log`.
- *"Google Drive API is not enabled":* enable the Drive API in the Google Cloud project that owns the client ID.
- *No CloudVault button/key:* check `logs/latest.log` for lines starting with `CloudVault`; confirm Java 25 and Fabric API.
- *Backup says pending:* hover the entry in View Backups, or open Cloud Management; usually internet or sign-in.

## Building
JDK 25, then `./gradlew build` (jar in `build/libs/`). To enable Google sign-in in your own build copy
`src/main/resources/cloudvault-oauth.properties.example` to `cloudvault-oauth.properties` and fill it in. Engine tests that need no
Minecraft are in `devtests/` (see `devtests/README.md`).

## Building
Open a terminal INSIDE the `CloudVault` folder (the one that contains `gradlew`, `build.gradle` and `src`), then:
- Windows (PowerShell / cmd): `.\gradlew.bat build`
- macOS / Linux / Git Bash: `chmod +x gradlew` once, then `./gradlew build`
Needs JDK 21. The mod jar appears in `build/libs`.

## Google Drive sign-in
Google OAuth credentials are not part of this repository. Copy `src/main/resources/cloudvault-oauth.properties.example` to `cloudvault-oauth.properties` and fill in your own Desktop-app client before building, or use S3-compatible storage / a folder instead.
