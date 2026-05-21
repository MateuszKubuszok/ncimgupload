# nkupload

CLI tool to reliably sync photos/videos from Android phone to Nextcloud, compensating for the Nextcloud Android app's unreliable auto-upload.

## Build & Run

```bash
sbt compile                          # compile
sbt test                             # run all tests (17 tests)
sbt "run --help"                     # show CLI help
sbt "run setup"                      # interactive first-time config
sbt "run scan"                       # scan phone + cloud
sbt "run diff"                       # compare phone vs cloud
sbt "run upload"                     # upload missing files
sbt "run cleanup --before 2024-01-01" # dry-run cleanup
```

## Tech Stack

Scala 3.3.6 (LTS) on JVM with Li Haoyi's ecosystem:
- `mainargs` — CLI parsing
- `os-lib` — file I/O and subprocess (ADB calls)
- `requests-scala` — HTTP client for WebDAV
- `scala-xml` — PROPFIND XML parsing
- `sqlite-jdbc` — state database
- `typesafe-config` — HOCON configuration
- `munit` — tests

No cats-effect, no http4s, no fs2. This is a sequential CLI tool.

## Architecture

```
Main.scala        CLI entry point (mainargs subcommands)
Config.scala      HOCON config loading, env var overrides, validation
Db.scala          SQLite state database (scan cache, upload tracking)
Models.scala      Case classes: FileEntry, SyncRecord, DiffResult
Adb.scala         ADB subprocess wrapper: scan phone, pull, delete
WebDav.scala      Nextcloud WebDAV client: PROPFIND, PUT, chunked upload, MKCOL, MOVE
Sync.scala        Diff logic: match phone files to cloud files
Upload.scala      Upload orchestration (single + chunked + verify)
Cleanup.scala     Safe phone file deletion with guards
Checksums.scala   SHA-256/MD5 computation and verification
Progress.scala    Terminal progress bars (no deps)
```

## Key Design Decisions

See `docs/design.md` for full rationale. The critical ones:

- **Match by (filename, size)** not content hash — computing hashes on phone via ADB is slow/unreliable, and Android camera filenames with timestamps are practically unique
- **SQLite state DB** not JSON — thousands of files, need efficient queries, partial updates, and resumability
- **Custom WebDAV Requesters** — `requests-scala` doesn't have built-in PROPFIND/MKCOL/MOVE; we create `new requests.Requester("PROPFIND", session)` etc.
- **Chunked upload v2** for files >100MB — MKCOL session, PUT numbered chunks, MOVE `.file` to assemble
- **Cleanup is dry-run by default** — requires `--yes` flag AND interactive confirmation

## WebDAV Protocol Notes

Nextcloud WebDAV base: `/remote.php/dav/files/{username}/`
Chunked uploads: `/remote.php/dav/uploads/{username}/{upload-id}/`

Custom HTTP methods used:
- `PROPFIND` with `Depth: infinity` for recursive file listing
- `MKCOL` for creating upload sessions
- `MOVE` for assembling chunked uploads

Important headers:
- `OC-Checksum: SHA256:{hex}` — upload integrity verification
- `X-NC-WebDAV-AutoMkcol: 1` — auto-create parent directories on PUT
- `OC-Total-Length` — required for chunked upload chunks
- `Destination` — required for MOVE and chunked upload MKCOL

## Configuration

Config search order: `--config` > `$NKUPLOAD_CONFIG` > `~/.config/nkupload/config.conf` > `./nkupload.conf`

Password can be set via `NKUPLOAD_PASSWORD` env var instead of config file.

See `config.example.conf` for all options.

## Testing

```bash
sbt test                    # all tests
sbt "testOnly *SyncTest"    # just sync matching tests
sbt "testOnly *DbTest"     # just database tests
sbt "testOnly *WebDav*"    # just WebDAV parsing tests
```

Tests use munit. WebDAV and DB tests use real parsing/database (not mocked HTTP).

## ADB Notes

- Requires `brew install android-platform-tools`
- Phone must have USB debugging enabled
- File listing uses `adb shell find ... -exec stat -c '%s %Y %n' {} +`
- File transfer uses `adb pull`
- Deletion uses `adb shell rm`
- Phone storage base: `/storage/emulated/0/`
