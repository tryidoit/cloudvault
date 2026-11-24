# CloudVault - hand-off (state at end of this session)

Fabric client mod for Minecraft 26.3: backs up singleplayer worlds to Google Drive / S3, restores, syncs worlds between computers.
Project root = this folder. Engine = `src/main` (no Minecraft dependency, compiles and is tested here). Screens/hooks = `src/client` (needs real Minecraft; NOT compilable in the sandbox).

## 1. What the user asked for in this round
Add: (1) incremental backups that can never leave the player worse off than full backups, compatible with world sync; (2) world-in-use lock; (3) smarter automatic triggers (also in the setup wizard); (4) compare two backups; (5) upload controls; (6) failure alerts (phone number, Discord webhook, e-mail, more); (7) second destination; (8) share a backup; (9) cleaner, more intuitive UI; better setup wizard; (10) wizard reachable any time from the top right of every settings page; (11) breadcrumb top-left (e.g. `CloudVault > Main Settings > Cloud > Cloud Management`); (12) every general CloudVault button opens the MAIN SETTINGS page (like the pause menu), everything branches from it, separate pages (Import etc.) live in a sub-settings called something like "Separate Pages"; (13) an ADVANCED setup wizard that asks for every single setting; (14) CloudVault account: a settings file kept in the user's cloud storage, new device logs in to the same storage and sets itself up (incl. second destination), notification, still editable locally; local edits create/refresh this device's own cloud file; on a new device ask which file to use; file name contains device info; file updates on every settings change.

## 2. Status
| Item | Engine | Tests | Screens |
|---|---|---|---|
| Incremental backups | done | `Milestone6Test` ALL PASSED | toggles not yet in UI |
| Round 7 (import, sync, comments) | done | `Milestone5Test` ALL PASSED | done (round 7 zip) |
| Upload controls | done | Milestone7Test | done (unverified in game) |
| Alerts (Discord, Slack, Telegram, ntfy, Pushover, SMS via Twilio, e-mail SMTP, webhook) | done (`alerts/`) | NOT written yet | not started |
| Second destination (folder/NAS/Dropbox-folder, S3, 2nd Google account) | done (`mirror/MirrorManager`, `LocalFolderProvider`) | NOT written yet | not started |
| World-in-use lock | done (`lock/LockManager`, `CloudVaultCore.checkWorldLock`) | NOT written yet | warning screen not started |
| Smart triggers (Nether, End, low health, idle; cooldown) | done (`smart/SmartTriggers`, `CloudVaultCore.smartCheck`) | NOT written yet | client observer + settings not started |
| Compare two backups | done (`compare/BackupComparer`) | NOT written yet | not started |
| Share a backup (+ import from code) | done (`share/ShareManager`, `Provider.shareLink`, `SigV4.presignQuery`) | NOT written yet | not started |
| CloudVault account | done (`account/AccountManager`) | NOT written yet | not started |
| UI restructure, breadcrumbs, wizard button, Separate Pages, quick + advanced wizards | - | - | NOT STARTED |

Verified this session after the last change: main module compiles (Java 21, stubs: see section 6); `CoreSmokeTest`, `Milestone2Test`, `Milestone3Test`, `Milestone5Test`, `Milestone6Test` report ALL PASSED (Milestone4Test prints its own expected "FAILED" lines as passing cases - run it and read the last line).
NOT verified: everything in `src/client` (nothing there was touched this round except what round 7 delivered), all Minecraft API names, real Google Drive / S3 / Twilio calls.

## 3. Incident to know about
The sandbox was reset mid-session and the first copy of this round's engine work was lost. It was re-applied from the last delivered zip. `devtests/Milestone7Test.java` (tests for the "NOT written yet" rows) was lost and not recreated - write it first (design in section 7). Save to outputs often.

## 4. Engine design decisions (do not break these)
**Incremental (`incremental/`)**
- Every backup of a world can carry a `Manifest` (all files: size, SHA-256, CRC-32, `src` = id of the backup whose zip holds the content). Stored in the zip (`cloudvault-manifest.json`) and in `cloudvault/manifests/<id>.json.gz`.
- A DELTA zip holds only changed files (always incl. `level.dat`) + the manifest; name ends `.delta.zip`; metadata `kind="DELTA"`, `parentId`, `baseId`, `dependsOn[]`, `chainDepth`.
- `Incremental.decide` returns FULL whenever in doubt: disabled, SAFETY type, advanced retention on, no/unverified/unavailable parent or anything in `dependsOn`, manifest missing or pointing outside the chain, chain longer than `min(maxChain, keptBackups-1)`, base older than `fullEveryDays`, too much changed, or the chain would cost more than `N/(N+L)` of N full backups (this bound is what guarantees "never more space than full backups"). Chains never cross backup types (SESSION/PERIODIC/GAMEPLAY/MANUAL/EVENT).
- `BackupManager.buildZip`: any exception in the delta path falls back to a full backup.
- Restore/export/share/compare/test all go through `CloudManager.obtainVerifiedZip(m)`, which for a delta rebuilds the complete world with `ChainResolver.materialize` and checks EVERY file's SHA-256 (damage -> exception, world untouched). `obtainOwnZip` = one backup's own zip.
- `RetentionManager` skips backups another kept backup `dependsOn` (`BackupStore.isRequired`); manual delete of a needed backup is refused; `trimCache` keeps the closure of the newest N. Restores of cloud-only deltas work (tested).
- World sync is unaffected: sync copies (`sync/`) are always complete zips in a separate cloud folder; backup chains are per device/world and are content-hash based, so a world replaced by sync simply produces a bigger next delta (or a full).

**Provider "area" API** (`CloudProvider.putFile/listFiles/shareLink`): areas `sync`, `account`, `shares` = separate top-level cloud folders ("CloudVault Sync", "CloudVault Account", "CloudVault Shares"). `uploadSync/listSync` delegate to it. Implemented for Drive, S3, `LocalFolderProvider`.

**Alerts**: `config.json` holds only on/off + channel list (type/name/enabled). All addresses, tokens, phone numbers live in `auth/secrets.json` (`SecretStore`, keys `alert.<channelId>.<field>`, `mirror.s3.secret`). Plain `http://` is refused except localhost. SMS needs a Twilio account (a phone number alone cannot send SMS) - say so in the UI. Wi-Fi-only uploads are deliberately NOT offered (Java cannot detect it reliably).

**Mirror**: independent of the first destination (own queue key, own retry/backoff, never changes `uploadStatus`). Deleting a backup deletes both copies (`RetentionManager.onDeleted`, `CloudManager.deleteBackup`; failed deletes remembered in `mirror-orphans.json`). Restore falls back to the second copy when the first cannot be downloaded. A second Google account: build `GoogleAuth(authDir, "google_tokens_mirror.json", ...)` + `GoogleDriveProvider` in `ClientBridge` and call `core.mirror.setProvider(...)`; folder/S3 are built by `MirrorManager.build`.

**Lock**: only for worlds with sync ON. File `lock-<id8>-<ms>.json` in the world's sync folder; heartbeat 120 s; stale after `config.lock.staleMinutes`. Acquired at join (`checkWorldLock`), released when the leave-upload succeeds (left to go stale if it failed). Conflict -> `core.lockPrompt` (client must show a screen with "Leave world" / "Play anyway"; "Leave" must call `core.skipSyncOnLeave()` BEFORE disconnecting so the untouched local copy is not uploaded over the other computer's newer one; "Play anyway" runs the `Runnable`). Cloud unreachable = never blocks.

**Account**: `settings_<device>_<OS>_<id8>.json` in `CloudVault Account/Settings`. Content: device info, whole config minus local-only fields (worlds, account, cloud login, firstLaunchDone), per-world settings of synced worlds keyed by syncId, and secrets encrypted with the optional account password (PBKDF2 + AES-GCM, `SecretBox`). No password = no secrets in the cloud. Backup-encryption key and cloud login are never included. `settingsChanged()` (call it from every UI change) = save + apply + `account.markDirty()` (4 s debounce). New-device flow: connect storage -> `account.listProfiles()` -> user picks -> `account.applyProfile(profile, passwordOrNull)` (`Result.needsPassword`) -> toast already sent.

## 5. What is left (after this session)
Done this session: `devtests/Milestone7Test` (alerts incl. SMTP, upload gate, mirror, smart, lock, compare, share, SigV4, account; ALL PASSED), `settings/SettingsRegistry` + `Milestone8Test` (coverage by reflection; ALL PASSED), all client screens and wiring (PageScreen/MenuScreen/PageTree, Main Settings hub, breadcrumbs, wizard chooser, Quick + Advanced wizard, AlertChannels/ChannelEdit, Account, Mirror, CloudConnect, Compare, Share, ShareImport, LockWarning, WorldSettings), `ClientBridge.settingsChanged/applyMirror/leaveWorld/clipboard`, smart observer + watchdog + startup tasks in `CloudVaultClient`, docs.
STILL TO DO: compile and run in real Minecraft 26.3 (nothing in `src/client` was ever compiled against it; this session only type-checked with hand-made stubs); fix API names; real-service tests (Drive share links, second Google account, Twilio); MANUAL_TESTS section L.
Unverified API guesses: `mc.disconnect(Screen, boolean)`, `mc.level.dimension().identifier()`, `keyboardHandler.get/setClipboard`, EditBox/Button APIs as used before, `Screen.shouldCloseOnEsc`.

## 7. Build / test here
- JDK 21 (`apt-get install openjdk-21-jdk-headless`), dependency jar: Gson+SLF4J from `pip download tabula-py` -> `tabula/tabula-1.0.5-jar-with-dependencies.jar`, stub `net.fabricmc.api.ModInitializer`.
- `javac -nowarn -cp $J -d out $(find src/main -name '*.java') ModInitializer.java` then `javac -cp out:$J -d out devtests/*.java` and `java -cp out:$J Milestone6Test` etc. Real build: `./gradlew build` on a machine with Minecraft 26.3 / Loom.

## 8. Known limits to tell the user
Drive share links and second-Google-account sign-in are untested against real Google; the comment/lock/wizard screens and all Minecraft API names (`mc.gui.screen()`, dimension key, player health) are unverified in game; incremental backups hash the whole world copy on each backup (extra read pass) in exchange for much smaller zips/uploads; incremental is disabled when advanced retention rules are on or when too few backups are kept for a chain to pay off.
