# ADB Workflow

How ncimgupload interacts with Android phones via ADB (Android Debug Bridge).

## Prerequisites

```bash
brew install android-platform-tools
```

On the phone:
1. Settings > About Phone > tap "Build Number" 7 times to enable Developer Options
2. Settings > Developer Options > enable "USB Debugging"
3. Connect phone via USB cable
4. Accept the "Allow USB debugging?" prompt on the phone

## Phone Storage Layout

Android stores camera media under `/storage/emulated/0/`:

```
/storage/emulated/0/
├── DCIM/
│   ├── Camera/              # Main camera app photos/videos
│   ├── Screenshots/         # Screenshots
│   └── Facebook/            # App-specific camera folders
├── Pictures/
│   ├── WhatsApp/            # WhatsApp images
│   ├── Telegram/            # Telegram images
│   └── Instagram/           # Instagram saved media
├── Movies/                  # Video recordings
└── Download/                # Downloaded files
```

The exact structure varies by device and installed apps. The `DCIM/Camera/` directory is the most common location for photos taken by the built-in camera app.

ncimgupload scans the paths configured in `paths.phone` (default: `["DCIM/Camera"]`).

## ADB Commands Used

### Device Detection

```bash
adb devices
```

Output:
```
List of devices attached
ABC123DEF456	device        # ready
XYZ789		unauthorized  # needs USB debugging approval
```

### File Listing

```bash
adb shell find '/storage/emulated/0/DCIM/Camera' -type f \
  \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.mp4' \) \
  -exec stat -c '%s %Y %n' {} +
```

Output format: `{size_bytes} {mtime_unix_seconds} {full_path}`
```
4210432 1716198923 /storage/emulated/0/DCIM/Camera/IMG_20240520_091523.jpg
1073741824 1716145812 /storage/emulated/0/DCIM/Camera/VID_20240519_183012.mp4
```

This combines `find` (file discovery with extension filter) and `stat` (metadata extraction) in a single ADB shell invocation for efficiency.

### File Transfer (Pull)

```bash
adb pull /storage/emulated/0/DCIM/Camera/IMG_20240520_091523.jpg /tmp/ncimgupload/
```

Transfers the file from phone to local disk. Used before upload to:
1. Compute SHA-256 checksum locally
2. Upload to Nextcloud via WebDAV
3. Clean up local temp file after upload

### File Deletion

```bash
adb shell rm '/storage/emulated/0/DCIM/Camera/IMG_20240520_091523.jpg'
```

Only used by the `cleanup` command, with multiple safety guards.

## Performance Considerations

- **ADB over USB 2.0**: ~30 MB/s transfer speed
- **ADB over USB 3.0**: ~100+ MB/s transfer speed
- **File listing**: fast even for thousands of files (single shell invocation)
- **Individual pulls**: overhead per file (~100ms handshake); for many small files, this adds up

For a typical phone with 3000 photos (12 GB) and 500 videos (30 GB):
- Listing all files: ~2 seconds
- Pulling a single 4MB photo: ~0.5 seconds
- Pulling a single 1GB video: ~10 seconds (USB 3.0)

## Filename Conventions

Android camera apps use predictable naming:

| Pattern | Source |
|---|---|
| `IMG_YYYYMMDD_HHMMSS.jpg` | Standard Android camera |
| `VID_YYYYMMDD_HHMMSS.mp4` | Standard Android video |
| `PXL_YYYYMMDD_HHMMSS.jpg` | Google Pixel camera |
| `MVIMG_YYYYMMDD_HHMMSS.jpg` | Motion photo (Pixel) |
| `Screenshot_YYYYMMDD_HHMMSS.png` | Screenshots |
| `IMG_YYYYMMDD_HHMMSS_HDR.jpg` | HDR variants |
| `IMG_YYYYMMDD_HHMMSS(1).jpg` | Burst mode duplicates |

The timestamp in the filename is the key matching property — it makes each file practically unique, enabling reliable matching by `(filename, size)` even when directory structures differ between phone and cloud.

## Troubleshooting

**"No Android device found"**
- Check USB cable is data-capable (not charge-only)
- Try `adb kill-server && adb start-server`
- Check for USB debugging prompt on phone screen

**"unauthorized"**
- Unlock phone and accept the USB debugging authorization dialog
- If dialog doesn't appear: revoke USB debugging authorizations in Developer Options, reconnect

**"Permission denied" on file access**
- Some Android versions restrict ADB access to certain directories
- `/storage/emulated/0/DCIM/` should always be accessible
- App-specific directories under `/Android/data/` may require root

**Slow transfers**
- Use a USB 3.0 cable and port
- Avoid transferring while phone is doing other I/O
- For many small files, consider `adb pull` of entire directory rather than individual files
