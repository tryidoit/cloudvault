# Development Notes

## Toolchain (verified 2026-10-05)
- MC 26.3 unobfuscated: no mappings, `implementation`, Mojang names. Plugin `net.fabricmc.fabric-loom` 1.18-SNAPSHOT, Gradle 9.7.1, Java 25.
- Source sets: `src/main` = Minecraft-free engine (pure Java + Gson); `src/client` = Minecraft glue + GUI.
- 26.x API facts taken from fabric-docs reference code + Fabric API 26.3 branch: `Screen(Component)`, `extractRenderState(GuiGraphicsExtractor,...)`, `graphics.text(font,String,x,y,color,shadow)`, `minecraft.gui.setScreen(..)`, `minecraft.gui.toastManager().addToast(new SystemToast(..))`, `KeyMappingHelper`, `KeyMapping.Category.register(Identifier)`, `Screens.getWidgets`, `ServerLifecycleEvents`, `server.saveAllChunks(bool,bool,bool)`.
- UNVERIFIED names (fix first if the build fails): `EditBox` ctor/setResponder, `WorldData.getLevelName()`, `LevelResource.ROOT`, `Util.getPlatform().openUri(String)`, `clearWidgets()`, `Screen.tick()`, `InputConstants.KEY_B`, `PlayerList.saveAll()`, `EditBox.setResponder`/`setMaxLength` (used by the new amount boxes).
- ALL Minecraft touch points for world/toast/browser/screens are centralized in `client/ClientBridge.java`.

## First build results (2026-10-06)
- Needs Java 25 to run Gradle. `src/main` engine compiled clean.
- Fixed from real 26.3 API (checked against FabricMC/fabric-api 26.3 branch): `net.minecraft.util.Util`, `InputConstants.Type.KEYBOARD` + `SDLScancode.SDL_SCANCODE_B` (key mappings take scancodes), screen via `mc.gui.screen()` (no `mc.screen`). `Util.OS.openUri(String)` does not exist: browser opening is now `ClientBridge.openBrowser` (OS command only: `open` / `rundll32` / `xdg-open`; java.awt.Desktop hangs on macOS inside Minecraft).

## Built-in Google client
- Put the Desktop-app OAuth Client ID/secret in `src/main/resources/cloudvault-oauth.properties` and rebuild: players then only click Connect. Players cannot override it any more (the custom client ID/secret fields were removed on purpose: no one else needs a Google Cloud project). Google Cloud: consent screen must be set to "In production" (Testing mode expires tokens after 7 days, 100-user cap); drive.file scope is non-sensitive.

- Built-in client is configured (project cloudvault-512852, consent screen currently in TESTING: add test users or switch to In production).

## Mod Menu
- Optional. `CloudVaultModMenu` (modmenu entrypoint) opens CloudVaultScreen from the mod list Configure button. Compile-only dependency `com.terraformersmc:modmenu:21.0.0` (clientCompileOnly, non-transitive); 21.0.0 = MC 26.3. Unverified until built.

## Commands / key debug
- `/cloudvault` opens the screen (next tick, so chat does not close it); `/cloudvault backup` = manual backup. Key press logs "CloudVault key pressed" to latest.log. Reported: B key did nothing (cause unknown) - check log.

## Milestone 2 notes
- New engine packages: `crypto/` (BackupCrypto format, EncryptionService key file `auth/encryption.properties`), `cloud/s3/` (SigV4, S3Provider), `cloud/StorageManager`, `backup/RetentionPlanner`, `backup/BackupFilter`, `config/RetentionPolicy`, `config/CompressionPreset`.
- Backups remember their provider (`BackupMetadata.cloudProvider`, null = google_drive) so delete/download/refresh go to the right one. Encrypted uploads: `<name>.zip.cvenc`; `cloudSize/cloudMd5` describe the encrypted copy, `sha256` stays the plain zip's.
- S3 secret key lives in `auth/s3.secret`, never in config.json.
- Screens: CloudManageScreen, StorageScreen, S3SettingsScreen, EncryptionScreen; Settings has a new Retention tab; pause buttons on the main screen.
- Local checking without Minecraft: Gson + slf4j classes can be taken from any fat jar (e.g. PyPI tabula-py's jar). Compile `src/main` plus a stub `net.fabricmc.api.ModInitializer`, then run `devtests/CoreSmokeTest` and `devtests/Milestone2Test`. Client screens were type-checked against hand-written stand-ins only: real Minecraft API drift is still possible.

## Round 3 (import / reset / settings / world select)
- `cloud/CloudImporter`: rebuilds records from the cloud listing. Providers give `cv_folder` ("Name [id8]") and props (Drive appProperties; S3 via `details()` HEAD). Props now include worldId and mcVersion on upload. Everything from the cloud is validated (UUID ids only, never used as file names). Imported backups without a checksum get one on first restore (`m.sha256 == null`).
- `RestoreManager.resetToLatest`: verify latest backup -> BackupType.PRE_RESET backup (label BEFORE RESET, category SAFETY so never auto-deleted and never "latest") -> swap folder (old one kept in cloudvault/replaced). World must not be loaded.
- Client: `WorldsScreen` (per-world Back up / Backups / Reset), `ResetFlow`, world-select buttons added in `CloudVaultClient` by matching the screen class NAME `...SelectWorldScreen` (no compile-time dependency; top-left corner, may need moving if it collides in 26.3), `ClientBridge.importFromCloud`.
- SettingsScreen rewritten: tab descriptions, clearer labels, 36px rows for label+control rows.

## Per-world off switch
- `WorldSettings.disabled`; enforced in `BackupManager.run` (non-safety types return null), `CloudManager.upload` (leaves status untouched), `CloudVaultCore` join/leave/poll, `ClientBridge.saveAndBackup` (toast). `Core.setWorldEnabled(id, name, enabled)` is the single entry point (re-enabling resumes pending uploads). `SettingsScreen` can be opened for a specific world id (`scopeWorldId`).

## Milestone 3 findings
- Real bugs found by the new tests: backslash entry names were not refused by the ZIP extractor (Windows traversal); manual-backup failure ignored the notification setting; queue idle check had a race; every `find()` re-read all metadata (now an in-memory index returning copies).
- Concurrency review: BackupQueue is one worker thread, so backups/uploads/retention never overlap; GUI never calls the engine's blocking methods on the render thread (screens use background threads).
- Naming: `CloudFileName` builds/parses `backup-<date>_<time>_<zone>_<TYPE>_<world>_<id8>.zip[.cvenc]`; old names still parse. Drive/S3 list filters rely on the `backup-` prefix.
- Settings scroll: rows outside the window are simply not added (`SettingsScreen.btn` override); wheel via Fabric `ScreenMouseEvents.afterMouseScroll` (verified in fabric-api source).
- Not verifiable here: anything that needs the Minecraft jars (screens are only type-checked against stand-ins), Gradle build, real Google/S3 accounts.

## Final hardening notes
- Every path-building field read from disk or the cloud goes through `BackupStore.valid` / `WorldUtil` filters / `CloudImporter` validation.
- Fingerprint is "v2:" (order-independent hash); old stored fingerprints never match, so one extra backup is expected after updating.
- Wizard sets `firstLaunchDone` in its constructor. It must stay that way, otherwise a closed-by-crash wizard reappears.
- Security statement for release notes: reviewed and tested against the cases above; NOT independently audited; encryption stays labelled experimental; password fields are visible while typing.

## Extra features notes
- `BackupManager.copyNow` + `submitFinish` split a backup in two so the world can be copied at SERVER_STARTING (verified in fabric-api source: fires right before `initServer()`), then compressed/verified/uploaded later. `finish()` never throws.
- `RestoreTester` lives in the restore package to reuse the package-private `extractSafely`. Automatic tests only use a local copy; they never download.
- `UpdateCheck.reason` triggers once per change: the core records last seen Minecraft version and mod hash per world (`WorldSettings`).
- Pinned backups are filtered out BEFORE retention counts, so pinning never reduces how many normal backups are kept.
- `BackupStore.update(id, fn)` is the atomic way to change one record from the GUI.

## Architecture
- `CloudVaultCore` = brain (config, store, queue, schedulers, cloud, restore). `ClientBridge` wires it to Minecraft.
- Storage: `<gameDir>/cloudvault/{config.json, worlds.json, backups/<worldId>/, metadata/, temp/, auth/, exports/, replaced/}`.
- World id = UUID mapped from folder name in worlds.json (world folder is never modified). Renaming folders outside CloudVault creates a new id.
- Leave backup runs after SERVER_STOPPED (world saved); a shutdown hook waits up to 30 s for the queue; unfinished uploads stay PENDING and resume next launch.
- Cloud layout: CloudVault/<World name [id8]>/backup-<uuid>.zip. Verification = size + MD5 (Drive has no SHA-256); SHA-256 stored in appProperties and local metadata.
- Google OAuth: user must create their own OAuth client (type Desktop) in Google Cloud Console and paste Client ID (and secret if shown) in Cloud settings. Scope drive.file only. Refresh token in cloudvault/auth/google_tokens.json (chmod 600 where supported). Tokens never logged.

## Known limitations
- Restored-as-new world keeps the original name inside level.dat (only the folder is " - Restored").
- Replace requires the world to be closed (button disabled in-world).
- Single-PUT upload (no chunk resume): interrupted uploads restart from zero.

## Testing the engine without Minecraft
javac with Gson on classpath, then run devtests/CoreSmokeTest (needs stub `org.slf4j` + `net.fabricmc.api.ModInitializer`, or just run through Gradle later).

## Last task: pins/notes, restore test, before-update backup, space warnings, export. Next: user builds and runs MANUAL_TESTS.md (section J).

## Round 7 notes
- `sync/SyncManager` (push on leave via queue key `sync:<worldId>`, `pullAll` at startup), `sync/SyncFileName` (`sync-<date>_<time>_<zone>_<world>_<id8>.zip`). Providers: `uploadSync`/`listSync` (Drive folder "CloudVault Sync", S3 prefix "CloudVault Sync/"); backup listing is untouched (still `backup-*` under CloudVault).
- Identity across computers = `WorldSettings.syncId` (UUID); matching uses its first 8 characters (file/folder names). Decision rules in pullAll: same remote id as lastSyncRemoteId = nothing; local fingerprint == syncLocalFp (and not syncDirty) = replace; else = add copy and remember conflictRemoteId. syncLocalFp is recorded right after each upload/download (fingerprint is mtime based, so it is only ever compared on the same computer).
- `CloudManager.downloadPlainZip` records nothing (used by import and sync). `RestoreManager.extractAsNewWorld` / `replaceFromZip`. `CloudVaultCore.importWorldFromCloud`.
- Comments: `BackupManager.Request.withComment`, file `cloudvault-comment.txt` written into the temp copy before zipping; `BackupSettings.commentFor(type)`; `CloudVaultCore.CommentPrompt` is implemented by `client/CommentPrompts` (queue + tick), `CommentScreen`. Every prompt is answered exactly once (flushAll in the shutdown hook).
- Engine test: `devtests/Milestone5Test` (uses a folder as a fake cloud).

## Round 8 notes
- Settings: add a field to `CloudVaultConfig`/`BackupSettings` -> register it in `SettingsRegistry` (or `IGNORED`); `Milestone8Test` fails otherwise. Pages appear in Main Settings and in the Advanced wizard automatically.
- Breadcrumb = chain of parent screens (`BaseScreen.crumbs()`). Call `header()` first in `init()`; override `wizardButton()` for settings pages and `showCrumbs()` for dialogs.
- After any change in a screen call `ClientBridge.settingsChanged()` (save + apply + account file refresh + second-destination re-point), never `saveConfig()` alone.
- The second Google account uses a provider whose id is `google_drive_mirror`.
- Build/test here: JDK 21, Gson+SLF4J jar from `pip download tabula-py`, stub `net.fabricmc.api.ModInitializer`; run every `devtests/*Test` (Milestone4Test prints expected FAILED lines; read the last line).
