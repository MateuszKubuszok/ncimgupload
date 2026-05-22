package ncimgupload

import mainargs.{main, arg, ParserForMethods, Flag, Leftover}
import java.time.{Instant, LocalDate, ZoneId}

object Main:
  private var verbose = false

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      new Interactive(None).run()
    else
      ParserForMethods(this).runOrExit(args.toIndexedSeq)

  @main(doc = "Launch interactive TUI mode")
  def interactive(
      @arg(doc = "Config file path") config: Option[String] = None
  ): Unit =
    new Interactive(config).run()

  @main(doc = "Interactive first-time setup")
  def setup(
      @arg(doc = "Config file path") config: Option[String] = None
  ): Unit =
    val configDir = os.Path(System.getProperty("user.home")) / ".config" / "ncimgupload"
    val configFile = configDir / "config.conf"
    if os.exists(configFile) then
      Progress.info(s"Config already exists at $configFile")
      Progress.info("Edit it directly or delete it to re-run setup.")
      return

    Progress.info("=== ncimgupload setup ===")
    Progress.info("")

    print("Nextcloud URL (e.g. https://cloud.example.com): ")
    val url = scala.io.StdIn.readLine().trim

    print("Username: ")
    val username = scala.io.StdIn.readLine().trim

    print("App password (generate at Settings > Security > App passwords): ")
    val password = scala.io.StdIn.readLine().trim

    print("Cloud destination path (default: Photos/Phone): ")
    val cloudPath = scala.io.StdIn.readLine().trim match
      case "" => "Photos/Phone"
      case p => p

    print("Phone paths to scan, comma-separated (default: DCIM/Camera): ")
    val phonePaths = scala.io.StdIn.readLine().trim match
      case "" => Seq("DCIM/Camera")
      case p => p.split(',').map(_.trim).toSeq

    print("Upload mode - 'webdav' (direct) or 'sync-folder' (via desktop sync) [webdav]: ")
    val mode = scala.io.StdIn.readLine().trim match
      case "" => "webdav"
      case m => m

    val syncFolderLine = if mode == "sync-folder" then
      print("Sync folder path (local folder synced by Nextcloud desktop): ")
      val sf = scala.io.StdIn.readLine().trim
      s"""  sync-folder = "$sf""""
    else "  # sync-folder = \"/Users/you/Nextcloud/Photos/Phone\""

    val phonePathsStr = phonePaths.map(p => s""""$p"""").mkString(", ")
    val content =
      s"""nextcloud {
         |  url = "$url"
         |  username = "$username"
         |  password = "$password"
         |}
         |
         |paths {
         |  phone = [$phonePathsStr]
         |  cloud = "$cloudPath"
         |$syncFolderLine
         |  extensions = [".jpg", ".jpeg", ".png", ".mp4", ".mov", ".heic", ".heif", ".webp", ".gif", ".3gp", ".mkv", ".avi"]
         |}
         |
         |upload {
         |  chunk-threshold = 104857600
         |  chunk-size = 10485760
         |  retries = 3
         |  verify-checksum = true
         |  mode = "$mode"
         |}
         |
         |cleanup {
         |  dry-run = true
         |  verified-only = true
         |}
         |""".stripMargin

    os.makeDir.all(configDir)
    os.write(configFile, content)
    os.perms.set(configFile, "rw-------")
    Progress.info(s"Config written to $configFile")
    Progress.info("")

    val cfg = NkConfig.load(Some(configFile.toString))
    val webdav = new WebDav(cfg)
    Progress.info("Testing connection to Nextcloud...")
    if webdav.testConnection() then
      Progress.info("Connection successful!")
    else
      Progress.error("Connection failed. Check URL, username, and app password.")

  @main(doc = "Scan Android device for photos/videos via ADB")
  def `scan-phone`(
      @arg(doc = "Config file path") config: Option[String] = None,
      @arg(short = 'v', doc = "Verbose output") verbose: Flag = Flag(false)
  ): Unit =
    val cfg = NkConfig.load(config)
    this.verbose = verbose.value
    val db = new Db(cfg.dbPath)
    db.init()
    val adb = new Adb(cfg)

    if !adb.checkAdb() || !adb.checkDevice() then
      sys.exit(1)

    val now = Instant.now().getEpochSecond
    val scanId = db.recordScanStart("phone", cfg.phonePaths.mkString(","), now)
    val entries = adb.scanPhone(verbose.value)

    for entry <- entries do
      db.upsertPhoneFile(entry, now)

    db.recordScanFinish(scanId, entries.size, Instant.now().getEpochSecond)

    val totalSize = entries.map(_.sizeBytes).sum
    val photos = entries.count(e => !isVideo(e.filename))
    val videos = entries.count(e => isVideo(e.filename))
    Progress.info(s"Found ${entries.size} files (${Progress.formatSize(totalSize)})")
    Progress.info(s"  $photos photos (${Progress.formatSize(entries.filterNot(e => isVideo(e.filename)).map(_.sizeBytes).sum)})")
    Progress.info(s"  $videos videos (${Progress.formatSize(entries.filter(e => isVideo(e.filename)).map(_.sizeBytes).sum)})")
    Progress.info("Scan saved to database.")

  @main(doc = "Scan Nextcloud for photos/videos")
  def `scan-cloud`(
      @arg(doc = "Config file path") config: Option[String] = None,
      @arg(short = 'v', doc = "Verbose output") verbose: Flag = Flag(false),
      @arg(doc = "Scan entire Nextcloud, not just cloud path") all: Flag = Flag(false),
      @arg(doc = "Use Memories API for fast scanning") memories: Flag = Flag(false)
  ): Unit =
    val cfg = NkConfig.load(config)
    this.verbose = verbose.value
    val db = new Db(cfg.dbPath)
    db.init()

    val now = Instant.now().getEpochSecond
    val scanId = db.recordScanStart("cloud", if all.value || memories.value then "/" else cfg.cloudPath, now)

    val cloudFiles = if memories.value then
      val api = new MemoriesApi(cfg)
      if api.isAvailable then
        Progress.info(s"Scanning ${cfg.nextcloudUrl} via Memories API...")
        api.scanAll(
          onProgress = (current, total) => Progress.info(s"  Scanning day $current/$total ...")
        )
      else
        Progress.error("Memories app not available. Use --all for WebDAV scan instead.")
        sys.exit(1)
    else if all.value then
      val webdav = new WebDav(cfg)
      Progress.info(s"Scanning ${cfg.nextcloudUrl} (all files) via WebDAV...")
      webdav.scanCloudAll(
        onFolder = folder => Progress.info(s"  Scanning /$folder/ ..."),
        verbose = verbose.value
      )
    else
      val webdav = new WebDav(cfg)
      Progress.info(s"Scanning ${cfg.nextcloudUrl} (/${cfg.cloudPath}/) via WebDAV...")
      webdav.scanCloudPath(cfg.cloudPath, verbose.value)

    for entry <- cloudFiles do
      db.upsertCloudFile(entry, None, None, entry.checksum, now)
      db.markCloudFileByFilenameSize(entry.filename, entry.sizeBytes, None, None, entry.checksum, now)

    db.recordScanFinish(scanId, cloudFiles.size, Instant.now().getEpochSecond)

    Progress.info(s"Found ${cloudFiles.size} files (${Progress.formatSize(cloudFiles.map(_.sizeBytes).sum)})")
    Progress.info("Scan saved to database.")

  @main(doc = "Run both phone and cloud scans")
  def scan(
      @arg(doc = "Config file path") config: Option[String] = None,
      @arg(short = 'v', doc = "Verbose output") verbose: Flag = Flag(false)
  ): Unit =
    `scan-phone`(config, verbose)
    Progress.info("")
    `scan-cloud`(config, verbose)

  @main(doc = "Compare phone vs cloud, show missing/extra files")
  def diff(
      @arg(doc = "Config file path") config: Option[String] = None,
      @arg(short = 'v', doc = "Verbose output") verbose: Flag = Flag(false),
      @arg(doc = "Re-scan before diffing") fresh: Flag = Flag(false)
  ): Unit =
    val cfg = NkConfig.load(config)
    this.verbose = verbose.value
    val db = new Db(cfg.dbPath)
    db.init()

    if fresh.value then
      scan(config, verbose)

    val lastPhone = db.getLastScan("phone")
    val lastCloud = db.getLastScan("cloud")

    if lastPhone.isEmpty || lastCloud.isEmpty then
      Progress.error("No scan data found. Run 'ncimgupload scan' first.")
      sys.exit(1)

    def formatAgo(epoch: Long): String =
      val seconds = Instant.now().getEpochSecond - epoch
      Progress.formatDuration(seconds) + " ago"

    Progress.info(s"Using cached scans (phone: ${lastPhone.map(formatAgo).getOrElse("never")}, cloud: ${lastCloud.map(formatAgo).getOrElse("never")})")
    Progress.info("Use --fresh to re-scan.")

    val adb = new Adb(cfg)
    val webdav = new WebDav(cfg)
    val phoneFiles = adb.scanPhone(verbose.value)
    val cloudFiles = webdav.scanCloud(verbose.value)
    val result = Sync.diff(phoneFiles, cloudFiles)
    Sync.printDiffSummary(result)

  @main(doc = "Upload missing files to Nextcloud")
  def upload(
      @arg(doc = "Config file path") config: Option[String] = None,
      @arg(short = 'v', doc = "Verbose output") verbose: Flag = Flag(false),
      @arg(doc = "Max files to upload") limit: Option[Int] = None,
      @arg(doc = "Upload mode override: webdav or sync-folder") mode: Option[String] = None
  ): Unit =
    val cfg0 = NkConfig.load(config)
    val cfg = mode.map(m => cfg0.copy(uploadMode = m)).getOrElse(cfg0)
    this.verbose = verbose.value
    val db = new Db(cfg.dbPath)
    db.init()
    val adb = new Adb(cfg)
    val webdav = new WebDav(cfg)

    if cfg.uploadMode == "webdav" then
      if !webdav.testConnection() then
        Progress.error("Cannot connect to Nextcloud. Check config.")
        sys.exit(1)

    if !adb.checkAdb() || !adb.checkDevice() then
      sys.exit(1)

    val uploader = new Upload(cfg, db, adb, webdav)
    uploader.uploadMissing(limit, verbose.value)

  @main(doc = "Verify uploaded files via checksum")
  def verify(
      @arg(doc = "Config file path") config: Option[String] = None,
      @arg(short = 'v', doc = "Verbose output") verbose: Flag = Flag(false)
  ): Unit =
    val cfg = NkConfig.load(config)
    this.verbose = verbose.value
    val db = new Db(cfg.dbPath)
    db.init()
    val webdav = new WebDav(cfg)

    Progress.info("Verifying uploaded files...")
    val (_, cloudCount) = db.getTotalCounts()
    val counts = db.getStatusCounts()
    val uploaded = counts.getOrElse(SyncRecord.StatusUploaded, 0)
    Progress.info(s"$uploaded files with 'uploaded' status to verify")

    // TODO: batch verification - for now just report status
    Progress.info("Batch verification not yet implemented. Use 'upload --verify-checksum' for per-file verification during upload.")

  @main(doc = "Delete confirmed-uploaded files from phone (currently disabled)")
  def cleanup(
      @arg(doc = "Config file path") config: Option[String] = None,
      @arg(short = 'v', doc = "Verbose output") verbose: Flag = Flag(false),
      @arg(doc = "Delete files created before this date (YYYY-MM-DD)") before: String,
      @arg(doc = "Actually delete (default: dry-run)") yes: Flag = Flag(false),
      @arg(doc = "Only delete checksum-verified files") `verified-only`: Flag = Flag(true)
  ): Unit =
    Progress.warn("Cleanup is currently disabled until backup reliability is proven.")
    Progress.warn("Set NKUPLOAD_ENABLE_CLEANUP=1 environment variable to override.")
    if sys.env.get("NKUPLOAD_ENABLE_CLEANUP").forall(_ != "1") then
      sys.exit(1)

    val cfg = NkConfig.load(config)
    this.verbose = verbose.value
    val db = new Db(cfg.dbPath)
    db.init()
    val adb = new Adb(cfg)
    val webdav = new WebDav(cfg)

    if !yes.value && !adb.checkAdb() then
      sys.exit(1)

    val beforeDate = LocalDate.parse(before)
    val dryRun = !yes.value
    val cleaner = new Cleanup(cfg, db, adb, webdav)
    cleaner.run(beforeDate, dryRun, `verified-only`.value, verbose.value)

  @main(doc = "Show database summary")
  def status(
      @arg(doc = "Config file path") config: Option[String] = None
  ): Unit =
    val cfg = NkConfig.load(config)
    val db = new Db(cfg.dbPath)
    db.init()

    val (phoneCount, cloudCount) = db.getTotalCounts()
    val counts = db.getStatusCounts()
    val lastPhone = db.getLastScan("phone")
    val lastCloud = db.getLastScan("cloud")

    def formatTime(epoch: Option[Long]): String =
      epoch.map(e => Instant.ofEpochSecond(e).atZone(ZoneId.systemDefault()).toString).getOrElse("never")

    Progress.info("=== ncimgupload status ===")
    Progress.info(s"Database: ${cfg.dbPath}")
    Progress.info(s"Nextcloud: ${cfg.nextcloudUrl}")
    Progress.info(s"Cloud path: ${cfg.cloudPath}")
    Progress.info(s"Upload mode: ${cfg.uploadMode}")
    Progress.info("")
    Progress.info(s"Last phone scan: ${formatTime(lastPhone)}")
    Progress.info(s"Last cloud scan: ${formatTime(lastCloud)}")
    Progress.info("")
    Progress.info(s"Files seen on phone: $phoneCount")
    Progress.info(s"Files seen on cloud: $cloudCount")
    Progress.info("")
    Progress.info("Upload status:")
    for (status, count) <- counts.toSeq.sortBy(_._1) do
      Progress.info(s"  $status: $count")

  @main(doc = "Clear the database (with confirmation)")
  def reset(
      @arg(doc = "Config file path") config: Option[String] = None,
      @arg(doc = "Skip confirmation") yes: Flag = Flag(false)
  ): Unit =
    val cfg = NkConfig.load(config)
    val db = new Db(cfg.dbPath)
    db.init()

    if !yes.value then
      print("Clear all scan data and upload tracking? This cannot be undone. [y/N] ")
      val answer = scala.io.StdIn.readLine()
      if answer == null || !answer.trim.toLowerCase.startsWith("y") then
        Progress.info("Aborted.")
        return

    db.clearAllFiles()
    Progress.info("Database cleared.")

  private def isVideo(filename: String): Boolean =
    val lower = filename.toLowerCase
    lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".3gp") ||
    lower.endsWith(".mkv") || lower.endsWith(".avi")
