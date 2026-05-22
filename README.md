# ncimgupload

Standalone tool to reliably back up every photo and video from an Android phone to Nextcloud, working around the [many issues](#why-not-just-use-the-nextcloud-android-app) with the Nextcloud Android app's auto-upload.

Ships as a single native binary with an interactive TUI. No JVM, no Gradle, no dev environment needed — just download and run.

## Quick Start

```bash
# Download the binary (or build from source — see below)
./ncimgupload
```

The interactive wizard will guide you through:
1. **ADB setup** — downloads automatically if not installed
2. **Phone connection** — step-by-step USB debugging instructions
3. **Nextcloud login** — URL, username, and app password
4. **Folder selection** — pick where to store backups on Nextcloud
5. **Scan & upload** — finds missing files and uploads them

## Features

- **Memories API integration** — uses the Nextcloud Memories app's indexed database for near-instant cloud scanning. Falls back to WebDAV if Memories isn't installed. Cached locally so repeat scans are fast.
- **Full-Nextcloud scan** — matches phone files against your entire Nextcloud, not just one folder. Files you've moved or organized are correctly detected as already backed up.
- **Metadata repair** — detects files where the Nextcloud Android app stripped EXIF/GPS data (phone version is larger) and offers to re-upload the complete version. Dry-run mode available.
- **Timestamp preservation** — uploads set `X-OC-Mtime` so files keep their original creation date in Nextcloud, not the upload time.
- **Chunked uploads** — files over 100 MB use Nextcloud's chunked upload protocol for reliability.
- **SHA-256 verification** — every upload is checksum-verified against the server.
- **Resumable** — SQLite state database tracks progress. Interrupted uploads can be resumed.
- **Multi-profile** — `--profile <name>` isolates config, database, and cache per Nextcloud user. Multiple users on one computer.
- **Interactive TUI** — arrow-key menus, progress bars, guided setup. No config files to write by hand.
- **CLI mode** — all subcommands available for scripting and power users.

## CLI Commands

```bash
ncimgupload                       # interactive TUI (default)
ncimgupload interactive           # explicit TUI mode
ncimgupload --profile alice       # TUI with named profile
ncimgupload setup                 # create config interactively
ncimgupload scan                  # scan phone + cloud
ncimgupload scan-cloud --all      # scan entire Nextcloud via WebDAV
ncimgupload scan-cloud --memories # scan via Memories API (fast)
ncimgupload diff                  # compare phone vs cloud
ncimgupload upload                # upload missing files
ncimgupload status                # show database summary
ncimgupload profiles              # list configured profiles
ncimgupload verify                # check uploaded file integrity
```

## Building from Source

Requires [GraalVM](https://www.graalvm.org/) with `native-image` and [sbt](https://www.scala-sbt.org/).

```bash
git clone git@github.com:MateuszKubuszok/ncimgupload.git
cd ncimgupload
sbt nativeImage              # produces target/native-image/ncimgupload
```

Or run directly via sbt (requires JVM):

```bash
sbt "run status"
```

## Multi-Profile Support

Multiple Nextcloud users can each have their own isolated configuration:

```bash
ncimgupload --profile alice       # uses ~/.config/ncimgupload/alice/config.conf
ncimgupload --profile bob         # uses ~/.config/ncimgupload/bob/config.conf
ncimgupload profiles              # list all profiles
```

In TUI mode, if multiple profiles exist, you're prompted to choose on startup. Each profile has its own config, database, and Memories cache.

## Configuration

On first run, the wizard creates `~/.config/ncimgupload/config.conf` (or `~/.config/ncimgupload/{profile}/config.conf` for named profiles). You can also create it manually — see `config.example.conf`.

Config search order: `--config` flag > `$NKUPLOAD_CONFIG` env var > `~/.config/ncimgupload/[profile/]config.conf` > `./ncimgupload.conf`

Password can be set via `NKUPLOAD_PASSWORD` env var instead of the config file.

## Why Not Just Use the Nextcloud Android App?

The Nextcloud Android app's auto-upload feature suffers from well-documented reliability problems. This tool exists because losing even one photo is unacceptable.

### Silent upload failures

- Auto-upload stops working after app updates with no notification ([#14945](https://github.com/nextcloud/android/issues/14945), [#9320](https://github.com/nextcloud/android/issues/9320))
- Failed uploads are never retried — files silently stay on the phone ([#14233](https://github.com/nextcloud/android/issues/14233))
- No way to force a rescan or manually trigger upload of missed files ([#15051](https://github.com/nextcloud/android/issues/15051))
- Upload settings silently reset to disabled after updates ([#14931](https://github.com/nextcloud/android/issues/14931))

### Corrupted and incomplete uploads

- Files arrive truncated — a 150 MB video becomes a broken 40 MB file ([#12339](https://github.com/nextcloud/android/issues/12339))
- Multiple file uploads are incomplete without warning ([#2592](https://github.com/nextcloud/android/issues/2592))
- Video auto-uploads produce broken files ([#841](https://github.com/nextcloud/android/issues/841))

### Duplicate and infinite retry loops

- Uploads get stuck "waiting to upload" and re-upload duplicates endlessly ([#11485](https://github.com/nextcloud/android/issues/11485))
- Successfully uploaded files aren't marked as done, causing infinite retries ([#16446](https://github.com/nextcloud/android/issues/16446))

### EXIF/GPS metadata stripped

- GPS coordinates are silently removed from uploaded photos ([#6248](https://github.com/nextcloud/android/issues/6248), [#12188](https://github.com/nextcloud/android/issues/12188))
- The app doesn't warn that "media read-only" permission causes GPS loss ([#12973](https://github.com/nextcloud/android/issues/12973))
- ncimgupload detects these stripped files and can re-upload the complete version

### Battery and background restrictions

- Auto-upload fails entirely when battery optimization is enabled ([#4815](https://github.com/nextcloud/android/issues/4815))
- Battery saver mode completely stops uploads ([#14215](https://github.com/nextcloud/android/issues/14215))
- App reports 16% battery usage in 6 minutes ([#16880](https://github.com/nextcloud/android/issues/16880))

### Large file failures

- Files over 20-30 MB fail to upload via the Android app ([#5609](https://github.com/nextcloud/android/issues/5609))
- Restarting failed uploads crashes the app ([#11066](https://github.com/nextcloud/android/issues/11066))
- Google Play permission changes further restrict file access ([Nextcloud blog](https://nextcloud.com/blog/nextcloud-android-file-upload-issue-google/))

## Requirements

- Android phone with USB debugging enabled
- Nextcloud server with WebDAV access
- USB cable

ADB is downloaded automatically on first run. No other tools required.

## License

Apache License 2.0
