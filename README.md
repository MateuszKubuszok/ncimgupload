# nkupload

CLI tool to reliably sync photos and videos from an Android phone to Nextcloud.

Solves the problem of the Nextcloud Android app's unreliable auto-upload by providing:
- **Discovery**: scan phone and cloud, find which files are missing
- **Upload**: transfer missing files via WebDAV API or desktop sync folder
- **Cleanup**: safely delete phone files confirmed to be in the cloud

## Quick start

```bash
# Prerequisites
brew install android-platform-tools   # ADB for phone access

# Clone and build
git clone https://github.com/yourusername/nkupload.git
cd nkupload
sbt compile

# First-time setup (interactive)
sbt "run setup"

# Or create config manually at ~/.config/nkupload/config.conf
# (see config.example.conf)
```

## Usage

```bash
# Scan phone and cloud
sbt "run scan"

# See what's missing
sbt "run diff"

# Upload missing files
sbt "run upload"
sbt "run upload --limit 10"          # upload at most 10 files

# Check status
sbt "run status"

# Clean up old files from phone (dry-run first)
sbt "run cleanup --before 2024-01-01"
sbt "run cleanup --before 2024-01-01 --yes"   # actually delete
```

## Commands

| Command | Description |
|---|---|
| `setup` | Interactive first-time configuration |
| `scan-phone` | Scan Android device for photos/videos via ADB |
| `scan-cloud` | Scan Nextcloud for photos/videos via WebDAV |
| `scan` | Run both scans |
| `diff` | Compare phone vs cloud, show missing files |
| `upload` | Upload missing files to Nextcloud |
| `verify` | Verify uploaded files via checksum |
| `cleanup` | Delete confirmed-uploaded files from phone |
| `status` | Show database summary |
| `reset` | Clear the tracking database |

## Configuration

Config file location (searched in order):
1. `--config PATH` flag
2. `$NKUPLOAD_CONFIG` environment variable
3. `~/.config/nkupload/config.conf`
4. `./nkupload.conf`

See [config.example.conf](config.example.conf) for all options.

### Nextcloud app password

Generate one at: Nextcloud web UI > Settings > Security > App passwords

Set via config file or `NKUPLOAD_PASSWORD` environment variable.

### Upload modes

- **`webdav`** (default): uploads directly to Nextcloud via WebDAV API. Files > 100MB use chunked upload with resumability.
- **`sync-folder`**: copies files to a local folder synced by the Nextcloud desktop client. Simpler but depends on desktop sync.

## How it works

1. **Scan phone**: ADB lists files in configured directories (e.g., `DCIM/Camera/`) with sizes and timestamps
2. **Scan cloud**: WebDAV PROPFIND lists files in the configured cloud path with metadata
3. **Match**: files are matched by `(filename, size)` — Android camera filenames include timestamps and are practically unique
4. **Upload**: pulls file from phone via ADB, computes SHA-256, uploads via WebDAV with `OC-Checksum` header, verifies
5. **Cleanup**: only deletes files confirmed in cloud (with checksum verification), requires explicit `--yes` flag and interactive confirmation

## Safety

- Cleanup is **dry-run by default** — shows what would be deleted without acting
- Requires `--yes` flag AND interactive confirmation to delete
- Only deletes files with verified checksums by default
- Re-checks cloud existence before each phone deletion
- Every deletion is logged to `~/.local/share/nkupload/cleanup.log`
- Never deletes files from the cloud

## Requirements

- Java 11+ (tested with 24)
- sbt
- ADB (`brew install android-platform-tools`)
- USB debugging enabled on Android phone
- Nextcloud server with WebDAV access
