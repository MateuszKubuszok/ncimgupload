# Design Document

## Problem Statement

The Nextcloud Android app has a long-standing bug where it fails to upload some photos and videos during auto-upload. The issue has been open for years with no fix in sight. This leaves users with no reliable way to ensure all their phone media is backed up to their Nextcloud instance.

The user's requirements:
1. Keep using Nextcloud (no migration to another provider)
2. Discover which files on the phone are missing from the cloud
3. Upload those missing files
4. After confirming backups exist in multiple places, safely delete old files from the phone to free storage
5. Make it reusable for other Nextcloud users

## Why a Standalone CLI Tool

Several alternatives were considered:

**Fix the Android app** — The bug is in the Nextcloud Android app's upload queue management. The issue is complex (race conditions, retry logic, permission model changes by Google) and has resisted fixes for years. We can't wait.

**Use Nextcloud Memories app** — Memories can detect which phone files are already in the cloud, but it lacks two critical features: (1) telling the Android app which files it missed, and (2) being able to delete confirmed-uploaded files from the phone. Building on Memories would require modifying a PHP server app and an Android app — more complex than a standalone tool.

**Use rclone or similar** — rclone supports WebDAV but doesn't understand the phone-to-cloud sync workflow. It can't scan a phone via ADB, match files by camera naming conventions, or provide the safety guarantees needed for deletion.

**A standalone CLI tool** is the simplest solution: it composes ADB (phone access) with WebDAV (cloud access) and a local SQLite database (state tracking) into a workflow purpose-built for this problem.

## Why Scala

The user is a Scala developer (maintainer of the Kindlings library). While Python would be the typical choice for CLI glue code, Scala was explicitly preferred. The trade-offs:

**Scala advantages:**
- Familiar language — faster development, easier maintenance
- Strong type system catches bugs at compile time
- Li Haoyi's ecosystem (os-lib, requests-scala, mainargs) provides Python-like ergonomics
- JVM gives access to robust HTTP, XML, and SQLite libraries

**Scala disadvantages:**
- JVM startup time (~1s for sbt run) — acceptable for a batch tool
- Requires Java + sbt installed — but both are already present
- Heavier than a shell script — but the tool's complexity (XML parsing, SQLite, chunked uploads) justifies it

## File Matching Strategy

The core challenge: files on the phone (e.g., `DCIM/Camera/IMG_20240520_091523.jpg`) may be stored under a different path on the cloud (e.g., `Photos/Phone/IMG_20240520_091523.jpg` or `Photos/Phone/2024/05/IMG_20240520_091523.jpg`).

### Decision: Match by (filename, size)

Android camera apps generate filenames with embedded timestamps:
- `IMG_20240520_091523.jpg` — photo taken 2024-05-20 at 09:15:23
- `VID_20240520_183012.mp4` — video taken 2024-05-20 at 18:30:12
- `PXL_20240520_091523.jpg` — Pixel camera variant

These are unique enough that filename collision is extremely rare. Adding file size as a second discriminator makes accidental matches nearly impossible.

### Why Not Content Hashes

Computing SHA-256 on the phone via ADB would require either:
- `adb shell sha256sum` — not available on all Android devices
- `adb pull` every file to hash locally — defeats the purpose of scanning (we'd download everything)

Hashes are used as a **post-upload verification** step, not for matching. After uploading a file, we compute its local hash and compare against the cloud's `OC-Checksum` to confirm integrity.

### Why Not EXIF Metadata

EXIF dates could provide another matching dimension, but:
- Extracting EXIF via ADB requires either pulling the file or running a tool on the phone
- Not all files have EXIF (screenshots, WhatsApp images, videos)
- Filename + size is sufficient for the camera use case

## State Database (SQLite)

### Why a Database

A naive approach would scan both sources and compare in memory every time. This is wasteful:
- ADB scanning over USB is slow (seconds for thousands of files)
- WebDAV PROPFIND with `Depth: infinity` is slow for large directories
- We need to track upload progress for resumability
- We need to know which files have been checksum-verified for safe cleanup

SQLite provides:
- Cached scan results so `diff` works without re-scanning
- Upload status tracking (`pending → uploading → uploaded → verified`)
- Chunked upload resumability (which chunks are done)
- Efficient queries (e.g., "verified files with mtime before X")

### Why SQLite Over JSON

With thousands of files, JSON becomes:
- Slow to parse/write on every operation
- No support for partial updates (must rewrite entire file)
- No concurrent access protection
- No indexing for queries

SQLite is in the JVM via JDBC (sqlite-jdbc), needs no server, and handles all of this.

## WebDAV Protocol Choices

### Custom HTTP Methods

The Nextcloud WebDAV API uses HTTP methods not in the standard set:
- `PROPFIND` — list files with metadata
- `MKCOL` — create directories / upload sessions
- `MOVE` — assemble chunked uploads / rename files

The `requests-scala` library only has built-in methods for GET/POST/PUT/DELETE/HEAD/OPTIONS/PATCH. For custom methods, we create `Requester` instances:

```scala
private val propfindVerb = new requests.Requester("PROPFIND", session)
```

### Chunked Upload v2

Files over 100MB (configurable) use Nextcloud's chunked upload protocol:

1. **MKCOL** creates a temporary upload directory at `/dav/uploads/{user}/{uuid}/`
2. **PUT** uploads numbered chunks (00001, 00002, ...) into that directory
3. **MOVE** of the virtual `.file` resource triggers server-side assembly

This provides:
- **Resumability**: if upload is interrupted, existing chunks are preserved for 24 hours
- **Integrity**: `OC-Checksum` header on the MOVE request lets the server verify the assembled file
- **Progress tracking**: we can report per-chunk progress for large videos

### PROPFIND Depth: infinity

For scanning the cloud, we use `Depth: infinity` to get the entire directory tree in one request. This is efficient for our use case (hundreds to low thousands of files) but could be problematic for very large collections. The alternative (recursive `Depth: 1` requests) would require many round trips.

## Safety Design

The cleanup command is the most dangerous part of the tool. Design principles:

1. **Dry-run by default**: without `--yes`, cleanup only shows what would be deleted
2. **Interactive confirmation**: even with `--yes`, prompts "Delete N files (X GB)? [y/N]"
3. **Verified-only default**: only deletes files whose checksums have been verified in the cloud
4. **Pre-deletion cloud check**: HEAD request confirms each file still exists on the cloud before deleting from phone
5. **Deletion log**: every deletion is logged with timestamp, path, size, and verification status to `~/.local/share/nkupload/cleanup.log`
6. **Never deletes from cloud**: the tool only removes files from the phone
7. **Date filtering required**: `--before DATE` scopes deletions to files older than a specific date

## Configuration Design

HOCON format via Typesafe Config was chosen over:
- **INI**: too simple, no lists or nested structure
- **YAML**: requires a third-party parser
- **JSON**: verbose, no comments
- **TOML**: not in the JVM ecosystem without extra dependencies

HOCON supports comments, lists, nested objects, and environment variable substitution natively. Typesafe Config is battle-tested and tiny.

Password handling:
- Config file should be `chmod 600`; tool warns if permissions are too open
- Password can be set via `NKUPLOAD_PASSWORD` env var to avoid storing in file
- Password is never logged or printed (masked in verbose output)

## Future Considerations

Things deliberately not built yet:

- **Parallel uploads**: could use `concurrent.futures.ThreadPoolExecutor` but USB/network bandwidth is usually the bottleneck, not concurrency
- **Date-organized cloud directories**: uploading to `Photos/Phone/2024/05/` instead of flat — configurable but not yet implemented
- **Batch verification**: `verify` command currently only reports status; could re-download and verify existing cloud files
- **Nextcloud Memories integration**: could use Memories API to cross-reference, but WebDAV PROPFIND is sufficient
- **GraalVM native image**: would eliminate JVM startup time but adds build complexity
