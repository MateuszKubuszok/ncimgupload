# Design Document

## Problem Statement

The Nextcloud Android app has a long-standing bug where it fails to upload some photos and videos during auto-upload. The issue has been open for years with no fix in sight. This leaves users with no reliable way to ensure all their phone media is backed up to their Nextcloud instance.

The user's requirements:
1. Keep using Nextcloud (no migration to another provider)
2. Discover which files on the phone are missing from the cloud
3. Upload those missing files
4. After confirming backups exist in multiple places, safely delete old files from the phone to free storage
5. Make it reusable for other Nextcloud users
6. Work as a standalone binary — no dev environment required
7. Support multiple Nextcloud users on one computer

## Why a Standalone CLI Tool

Several alternatives were considered:

**Fix the Android app** — The bug is in the Nextcloud Android app's upload queue management. The issue is complex (race conditions, retry logic, permission model changes by Google) and has resisted fixes for years. We can't wait.

**Use Nextcloud Memories app** — Memories can detect which phone files are already in the cloud via its indexed database. We now use the Memories API for fast cloud scanning, but Memories alone lacks: (1) the ability to upload from phone via USB, and (2) safe deletion tracking. ncimgupload uses Memories as a data source, not a replacement.

**Use rclone or similar** — rclone supports WebDAV but doesn't understand the phone-to-cloud sync workflow. It can't scan a phone via ADB, match files by camera naming conventions, or provide the safety guarantees needed for deletion.

**A standalone CLI tool** is the simplest solution: it composes ADB (phone access) with WebDAV (cloud access), the Memories API (fast indexing), and a local SQLite database (state tracking) into a workflow purpose-built for this problem.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  Main.scala (CLI entry + --profile parsing)                  │
│    ├── Interactive.scala (TUI: wizard + menu)                │
│    │     ├── Tui.scala (JLine3 rendering)                    │
│    │     ├── FolderPicker.scala (Nextcloud folder browser)   │
│    │     └── Profile.scala (multi-user isolation)            │
│    └── CLI subcommands (scan, diff, upload, status, ...)     │
├─────────────────────────────────────────────────────────────┤
│  Cloud Scanning (auto-selected)                              │
│    ├── MemoriesApi.scala (fast, index-based, cached)         │
│    └── WebDav.scala (PROPFIND fallback, has file sizes)      │
├─────────────────────────────────────────────────────────────┤
│  Phone Access                                                │
│    ├── Adb.scala (scan, pull, delete via ADB subprocess)     │
│    └── AdbManager.scala (auto-download ADB binary)           │
├─────────────────────────────────────────────────────────────┤
│  Core Logic                                                  │
│    ├── Sync.scala (diff: matched / missing / stripped)       │
│    ├── Upload.scala (single + chunked + verify + mtime)      │
│    ├── Checksums.scala (SHA-256 / MD5)                       │
│    └── Cleanup.scala (safe deletion, currently disabled)     │
├─────────────────────────────────────────────────────────────┤
│  State                                                       │
│    ├── Db.scala (SQLite: files, scan_history, chunked_uploads)│
│    ├── Config.scala (HOCON loading + saving)                 │
│    └── Models.scala (FileEntry, SyncRecord, DiffResult)      │
└─────────────────────────────────────────────────────────────┘
```

## Cloud Scanning Strategy

### Two-Backend Approach

The tool auto-detects and uses the fastest available method:

**1. Memories API (preferred):** Queries `/apps/memories/api/days` for a list of day summaries `{dayid, count}`, then fetches each day's photos via `/apps/memories/api/days/{dayId}`. Returns `basename`, `epoch`, `auid`, `mimetype` but NOT file size.

- Very fast: reads from Memories' server-side database index
- Cached locally: day responses stored as JSON in `~/.local/share/ncimgupload/memories-cache/`
- Incremental: only re-fetches days where the photo count changed
- Limitation: no file sizes → matching is filename-only, stripped metadata detection unavailable

**2. WebDAV PROPFIND (fallback):** Lists top-level directories (depth=1) then scans each folder recursively (depth=infinity). Returns full metadata including file sizes, checksums, and ETags.

- Slower: makes one HTTP request per top-level folder
- Complete: has file sizes, enabling stripped metadata detection
- No Memories app required

### Why Not PROPFIND on Root

A single PROPFIND with `Depth: infinity` on the Nextcloud root times out for large collections (tested: 120s timeout exceeded with ~47k files). Scanning folder-by-folder keeps each request manageable.

## File Matching Strategy

### Match by (filename, size)

Android camera apps generate filenames with embedded timestamps (`IMG_20240520_091523.jpg`, `VID_20240520_183012.mp4`). These are practically unique. Adding file size as a discriminator makes false matches nearly impossible.

This is the same strategy as Nextcloud Memories' BUID (Basename Unique ID) fallback: `md5(basename + size)`.

When cloud file size is unknown (Memories API), matching falls back to filename only. This is safe because camera filenames with timestamps are unique within a user's collection.

### Stripped Metadata Detection

When the same filename exists on both phone and cloud but the phone version is larger, this indicates the Nextcloud Android app uploaded the file without EXIF/GPS data (due to Android's media permission restrictions). The `strippedMetadata` category in `DiffResult` tracks these separately from normal size mismatches (where cloud > phone).

Repair flow: pull phone version → upload to the file's current cloud location (overwrite, not to unsorted folder) with `X-OC-Mtime` preserved. Requires explicit user confirmation. Dry-run mode available.

### Why Not Content Hashes for Matching

Computing SHA-256 on the phone via ADB would require either `adb shell sha256sum` (not always available) or pulling every file locally (defeats scanning purpose). Hashes are used for **post-upload verification** only.

## Upload Design

### Timestamp Preservation

Every upload sets the `X-OC-Mtime` header to the phone file's original modification time. Without this, Nextcloud sets the upload timestamp as mtime, breaking timeline ordering in Memories and other photo apps.

### Chunked Upload v2

Files over 100MB use Nextcloud's chunked upload protocol:
1. **MKCOL** creates a temporary upload directory at `/dav/uploads/{user}/{uuid}/`
2. **PUT** uploads numbered chunks (00001, 00002, ...) into that directory
3. **MOVE** of the virtual `.file` resource triggers server-side assembly

The MOVE request carries both `OC-Checksum` (integrity) and `X-OC-Mtime` (timestamp preservation).

### Upload from Diff Result

The TUI's "Scan & Upload" flow passes the exact list of missing files from `Sync.diff` directly to `Upload.uploadFiles`. It does NOT re-query the database for pending files, avoiding stale state issues where the DB hasn't been properly updated by the cloud scan.

## Multi-Profile Support

Each profile name maps to isolated paths:
- Config: `~/.config/ncimgupload/{name}/config.conf`
- Database: `~/.local/share/ncimgupload/{name}/state.db`
- Cache: `~/.local/share/ncimgupload/{name}/memories-cache/`

The default (unnamed) profile uses the base directories without a subdirectory, preserving backward compatibility.

Profile is selected via `--profile <name>` (parsed before mainargs) or the TUI profile picker (shown when multiple profiles exist). The `Profile` singleton holds the current selection and provides path resolution used by `Config`, `Db`, and `MemoriesApi`.

## Safety Design

The cleanup command (phone file deletion) is **disabled by default**. It requires `NKUPLOAD_ENABLE_CLEANUP=1` env var to override. This remains disabled until backup reliability is proven through real-world usage.

When eventually enabled, safety guards include:
1. Dry-run by default (requires `--yes`)
2. Interactive confirmation
3. Verified-only: only deletes checksum-verified files
4. Pre-deletion cloud check: HEAD request confirms file still exists
5. Deletion log: every action logged to `cleanup.log`
6. Never deletes from cloud
7. Date filtering required (`--before DATE`)

## GraalVM Native Image

The tool compiles to a single ~79MB native binary via GraalVM native-image. Key configuration:

- `--initialize-at-build-time=scala,geny` — avoids Scala 3 `LazyVals` reflection failures at runtime
- `--enable-url-protocols=https` — required for WebDAV and ADB downloads
- Reflection config for: `requests-scala` lazy val fields, SQLite JDBC, JLine3 terminal providers, Typesafe Config
- JLine3 and SQLite JDBC both ship with their own native-image metadata (`META-INF/native-image/`)
- Config file paths use absolute paths via sbt's `baseDirectory.value`

## Configuration Design

HOCON format via Typesafe Config. Search order: `--config` > `$NKUPLOAD_CONFIG` > profile-aware default path > `./ncimgupload.conf`.

Password handling: config file should be `chmod 600`; tool warns if permissions are too open. Password can be set via `NKUPLOAD_PASSWORD` env var.

## Future Considerations

- **Parallel uploads**: USB/network bandwidth is usually the bottleneck, not concurrency
- **Date-organized cloud directories**: uploading to `Photos/Phone/2024/05/` instead of flat
- **Batch verification**: `verify` command could re-download and verify existing cloud files
- **MTP support**: access phone via File Transfer mode instead of ADB (avoids USB debugging requirement), feasible on Linux via `jmtpfs`
- **ETag-based incremental PROPFIND**: cache ETags per folder, skip unchanged folders on WebDAV scan
