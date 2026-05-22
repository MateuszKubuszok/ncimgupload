# ncimgupload

CLI tool to reliably sync photos/videos from Android phone to Nextcloud, compensating for the Nextcloud Android app's unreliable auto-upload. Ships as a GraalVM native binary with an interactive TUI.

## Build & Run

```bash
sbt compile                          # compile
sbt test                             # run all tests (19 tests)
sbt nativeImage                      # build standalone binary
sbt "run --help"                     # show CLI help
sbt "run setup"                      # interactive first-time config
sbt "run scan"                       # scan phone + cloud
sbt "run diff"                       # compare phone vs cloud
sbt "run upload"                     # upload missing files
sbt "run profiles"                   # list configured profiles
./target/native-image/ncimgupload    # run native binary (launches TUI)
```

## Tech Stack

Scala 3.3.6 (LTS) on JVM, compiled to native via GraalVM:
- `mainargs` — CLI parsing
- `os-lib` — file I/O and subprocess (ADB calls)
- `requests-scala` — HTTP client for WebDAV and Memories API
- `upickle` — JSON parsing for Memories API responses
- `scala-xml` — PROPFIND XML parsing
- `sqlite-jdbc` — state database
- `typesafe-config` — HOCON configuration
- `jline` — terminal UI (menus, input, progress)
- `munit` — tests
- `sbt-native-image` — GraalVM native-image builds

No cats-effect, no http4s, no fs2. This is a sequential CLI tool.

## Architecture

```
Main.scala         CLI entry point (--profile parsing, mainargs subcommands, interactive default)
Interactive.scala  TUI controller: profile selection, first-run wizard, main menu
Tui.scala          JLine3-based TUI components (menus, multi-select, input, progress, boxes)
FolderPicker.scala Nextcloud folder browser via WebDAV
MemoriesApi.scala  Nextcloud Memories app integration (fast index-based cloud scan with caching)
AdbManager.scala   ADB binary auto-download and resolution (macOS/Linux/Windows)
Profile.scala      Multi-profile support (isolated config/data/cache per Nextcloud user)
Config.scala       HOCON config loading, env var overrides, save/create, profile-aware paths
Db.scala           SQLite state database (scan cache, upload tracking)
Models.scala       Case classes: FileEntry, SyncRecord, DiffResult (with strippedMetadata)
Adb.scala          ADB subprocess wrapper: scan phone, pull, delete
WebDav.scala       Nextcloud WebDAV client: PROPFIND, PUT, MKCOL, MOVE, chunked upload, mkdir
Sync.scala         Diff logic: match by (filename, size), detect stripped metadata, handle size=0
Upload.scala       Upload orchestration (single + chunked + verify + mtime preservation)
Cleanup.scala      Safe phone file deletion with guards (currently disabled)
Checksums.scala    SHA-256/MD5 computation and verification
Progress.scala     Terminal progress bars, TUI mode support
```

## Key Design Decisions

- **Memories API as primary scan** — queries Nextcloud Memories `/api/days` endpoint for fast index-based cloud scanning. Falls back to WebDAV PROPFIND folder-by-folder if Memories is not installed. Memories responses are cached per-day locally; only days with changed photo counts are re-fetched.
- **Match by (filename, size)** — same strategy as Memories' BUID fallback. When cloud size is unknown (Memories API doesn't return it), matches by filename only.
- **Stripped metadata detection** — when phone file is larger than cloud copy (same filename), flags it as likely EXIF/GPS stripping by the Nextcloud Android app. Offers dry-run + overwrite repair at the file's current cloud location.
- **X-OC-Mtime preservation** — uploads set the `X-OC-Mtime` header to the phone file's original timestamp so files appear with correct dates in Nextcloud/Memories timeline.
- **Multi-profile support** — `--profile <name>` flag gives each Nextcloud user isolated config, database, and cache under `~/.config/ncimgupload/{name}/` and `~/.local/share/ncimgupload/{name}/`. TUI auto-prompts profile selection when multiple exist.
- **Full-Nextcloud scan** — matches phone files against ALL cloud files, not just the upload folder. Users reorganize files after upload.
- **SQLite state DB** — thousands of files, efficient queries, partial updates, resumability.
- **Chunked upload v2** for files >100MB — MKCOL session, PUT numbered chunks, MOVE `.file` to assemble.
- **Cleanup disabled** — phone file deletion is disabled until backup reliability is proven. Requires `NKUPLOAD_ENABLE_CLEANUP=1` env var to override.
- **GraalVM native image** — single ~79MB binary, no JVM required. Build-time init for `scala,geny` packages avoids Scala 3 lazy val reflection issues.
- **Interactive TUI as default** — running with no args launches the wizard/menu. CLI subcommands kept for power users. Ctrl+C exits gracefully.

## Cloud Scanning Strategy

Two scan backends, auto-selected:

1. **Memories API** (preferred): `GET /apps/memories/api/days` returns day summaries, then `GET /apps/memories/api/days/{dayId}` returns photos per day with `basename`, `epoch`, `auid`. No file size returned — matching is filename-only. Responses cached to `~/.local/share/ncimgupload/memories-cache/`; only days with changed counts are re-fetched on subsequent runs.

2. **WebDAV PROPFIND** (fallback): lists top-level folders with depth=1, then scans each folder with depth=infinity. Returns full metadata including file sizes, enabling stripped metadata detection. Slower but works without Memories app.

## WebDAV Protocol Notes

Nextcloud WebDAV base: `/remote.php/dav/files/{username}/`
Chunked uploads: `/remote.php/dav/uploads/{username}/{upload-id}/`

Custom HTTP methods: PROPFIND, MKCOL, MOVE via `requests.Requester`.

Important headers:
- `OC-Checksum: SHA256:{hex}` — upload integrity verification
- `X-OC-Mtime: {epoch}` — preserve original file timestamp
- `X-NC-WebDAV-AutoMkcol: 1` — auto-create parent directories on PUT
- `OC-Total-Length` — required for chunked upload chunks
- `Destination` — required for MOVE and chunked upload MKCOL

## Profiles

Each profile gets isolated paths:
- Default: `~/.config/ncimgupload/config.conf` + `~/.local/share/ncimgupload/state.db`
- Named: `~/.config/ncimgupload/{name}/config.conf` + `~/.local/share/ncimgupload/{name}/state.db`

Select via `--profile <name>` (CLI) or the profile picker (TUI). `profiles` subcommand lists all.

## Configuration

Config search order: `--config` > `$NKUPLOAD_CONFIG` > `~/.config/ncimgupload/[profile/]config.conf` > `./ncimgupload.conf`

Password can be set via `NKUPLOAD_PASSWORD` env var instead of config file.

See `config.example.conf` for all options.

## Testing

```bash
sbt test                    # all 19 tests
sbt "testOnly *SyncTest"    # sync matching + metadata detection + size=0 tests
sbt "testOnly *DbTest"     # database tests
sbt "testOnly *WebDav*"    # WebDAV parsing tests
```

Tests use munit. WebDAV and DB tests use real parsing/database (not mocked HTTP).

## Native Image Build Notes

GraalVM native-image config in `native-image/` directory:
- `reflect-config.json` — reflection for requests-scala lazy vals, SQLite JDBC, JLine3, Typesafe Config
- `resource-config.json` — SQLite native libs, JLine resources
- `jni-config.json` — SQLite JNI

Key settings: `--initialize-at-build-time=scala,geny`, `--enable-url-protocols=https`, absolute paths for config files via `baseDirectory.value`.

## ADB Notes

- Auto-downloaded on first run to `~/.local/share/ncimgupload/platform-tools/`
- Downloads from `https://dl.google.com/android/repository/platform-tools-latest-{darwin,linux,windows}.zip`
- macOS Gatekeeper quarantine auto-removed via `xattr -d`
- Phone storage base: `/storage/emulated/0/`
- File listing: `adb shell find ... -exec stat -c '%s %Y %n' {} +`
