# ncimgupload

CLI tool to reliably sync photos/videos from Android phone to Nextcloud, compensating for the Nextcloud Android app's unreliable auto-upload.

## Build & Run

```bash
sbt compile                          # compile
sbt test                             # run all tests
sbt nativeImage                      # build standalone binary
sbt "run --help"                     # show CLI help
sbt "run setup"                      # interactive first-time config
sbt "run scan"                       # scan phone + cloud
sbt "run diff"                       # compare phone vs cloud
sbt "run upload"                     # upload missing files
./target/native-image/ncimgupload    # run native binary (launches TUI)
```

## Tech Stack

Scala 3.3.6 (LTS) on JVM, compiled to native via GraalVM:
- `mainargs` — CLI parsing
- `os-lib` — file I/O and subprocess (ADB calls)
- `requests-scala` — HTTP client for WebDAV
- `scala-xml` — PROPFIND XML parsing
- `sqlite-jdbc` — state database
- `typesafe-config` — HOCON configuration
- `jline` — terminal UI (menus, input, progress)
- `munit` — tests
- `sbt-native-image` — GraalVM native-image builds

No cats-effect, no http4s, no fs2. This is a sequential CLI tool.

## Architecture

```
Main.scala        CLI entry point (mainargs subcommands + interactive default)
Interactive.scala TUI controller: first-run wizard + main menu
Tui.scala         JLine3-based TUI components (menus, input, progress, boxes)
FolderPicker.scala Nextcloud folder browser via WebDAV
AdbManager.scala  ADB binary auto-download and resolution
Config.scala      HOCON config loading, env var overrides, save/create
Db.scala          SQLite state database (scan cache, upload tracking)
Models.scala      Case classes: FileEntry, SyncRecord, DiffResult
Adb.scala         ADB subprocess wrapper: scan phone, pull, delete
WebDav.scala      Nextcloud WebDAV client: PROPFIND, PUT, chunked upload, MKCOL, MOVE
Sync.scala        Diff logic: match phone files to cloud files, detect stripped metadata
Upload.scala      Upload orchestration (single + chunked + verify)
Cleanup.scala     Safe phone file deletion with guards (currently disabled)
Checksums.scala   SHA-256/MD5 computation and verification
Progress.scala    Terminal progress bars (no deps), TUI mode support
```

## Key Design Decisions

- **Match by (filename, size)** not content hash — computing hashes on phone via ADB is slow/unreliable
- **Full-Nextcloud scan** — matches phone files against ALL cloud files, not just the upload folder. Users reorganize files after upload.
- **Stripped metadata detection** — when phone file is larger than cloud copy (same filename), flags it as likely EXIF/GPS stripping by the Nextcloud Android app
- **SQLite state DB** not JSON — thousands of files, need efficient queries, partial updates, and resumability
- **Chunked upload v2** for files >100MB — MKCOL session, PUT numbered chunks, MOVE `.file` to assemble
- **Cleanup disabled** — phone file deletion is disabled until backup reliability is proven
- **GraalVM native image** — ships as a single binary, no JVM required. JLine3 + SQLite JDBC both GraalVM-compatible.
- **Interactive TUI as default** — running with no args launches the wizard/menu. CLI subcommands kept for power users.

## WebDAV Protocol Notes

Nextcloud WebDAV base: `/remote.php/dav/files/{username}/`
Chunked uploads: `/remote.php/dav/uploads/{username}/{upload-id}/`

Custom HTTP methods: PROPFIND, MKCOL, MOVE via `requests.Requester`.

Important headers: `OC-Checksum`, `X-NC-WebDAV-AutoMkcol`, `OC-Total-Length`, `Destination`.

## Configuration

Config search order: `--config` > `$NKUPLOAD_CONFIG` > `~/.config/ncimgupload/config.conf` > `./ncimgupload.conf`

Password can be set via `NKUPLOAD_PASSWORD` env var instead of config file.

## Testing

```bash
sbt test                    # all tests
sbt "testOnly *SyncTest"    # sync matching + metadata detection tests
sbt "testOnly *DbTest"     # database tests
sbt "testOnly *WebDav*"    # WebDAV parsing tests
```

## Native Image Build Notes

GraalVM native-image config in `native-image/` directory:
- `reflect-config.json` — reflection for requests-scala lazy vals, SQLite JDBC, JLine3, Typesafe Config
- `resource-config.json` — SQLite native libs, JLine resources
- `jni-config.json` — SQLite JNI

Key build-time init: `--initialize-at-build-time=scala,geny` (avoids Scala 3 lazy val reflection issues at runtime).

## ADB Notes

- Auto-downloaded on first run to `~/.local/share/ncimgupload/platform-tools/`
- Phone storage base: `/storage/emulated/0/`
- File listing: `adb shell find ... -exec stat -c '%s %Y %n' {} +`
