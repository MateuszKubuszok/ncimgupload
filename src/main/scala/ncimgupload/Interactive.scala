package ncimgupload

import java.time.Instant

class Interactive(configPath: Option[String]):
  private var tui: Tui = null
  private var config: NkConfig = null

  def run(): Unit =
    tui = new Tui()
    Progress.tuiMode = true
    try
      if Profile.current.isEmpty then
        selectProfileIfMultiple()

      NkConfig.tryLoad(configPath) match
        case Some(cfg) =>
          config = cfg
          mainMenu()
        case None =>
          firstRunWizard()
    catch
      case _: org.jline.reader.UserInterruptException =>
        tui.println()
        tui.println("Interrupted.")
      case _: org.jline.reader.EndOfFileException =>
        tui.println()
    finally
      Progress.tuiMode = false
      tui.close()

  private def selectProfileIfMultiple(): Unit =
    val existing = Profile.listProfiles
    if existing.size > 1 then
      tui.println()
      val options = existing :+ "+ Create new profile"
      val choice = tui.selectMenu("Select profile:", options)
      if choice >= 0 && choice < existing.size then
        val selected = existing(choice)
        Profile.current = if selected == "default" then None else Some(selected)
      else if choice == existing.size then
        val name = tui.readLine("Profile name")
        if name.nonEmpty && name != "default" then
          Profile.current = Some(name)

  // ──────────────────────────────────────────────
  //  First-run wizard
  // ──────────────────────────────────────────────

  private def firstRunWizard(): Unit =
    tui.clearScreen()
    tui.println(tui.box("ncimgupload — Phone to Nextcloud Backup", Seq(
      "",
      "  Back up every photo and video from your",
      "  Android phone to your Nextcloud server.",
      "",
      s"  This wizard will guide you through:",
      s"  ${tui.Cyan}1.${tui.Reset} Installing ADB (Android Debug Bridge)",
      s"  ${tui.Cyan}2.${tui.Reset} Connecting your phone",
      s"  ${tui.Cyan}3.${tui.Reset} Connecting to Nextcloud",
      s"  ${tui.Cyan}4.${tui.Reset} Choosing where to store backups",
      s"  ${tui.Cyan}5.${tui.Reset} Scanning and uploading your files",
      "",
    )))
    tui.println()
    if !tui.confirm("Ready to begin?", default = true) then return

    // Step 1: ADB
    tui.println()
    tui.println(tui.header("Step 1: ADB Setup"))
    tui.println()
    val adbLocation = setupAdb()
    if adbLocation.isEmpty then return

    // Step 2: Phone connection
    tui.println()
    tui.println(tui.header("Step 2: Connect Your Phone"))
    tui.println()
    val adbPath = adbLocation.get.path.toString
    if !connectPhone(adbPath) then return

    // Step 3: Nextcloud
    tui.println()
    tui.println(tui.header("Step 3: Connect to Nextcloud"))
    tui.println()
    val ncConfig = setupNextcloud()
    if ncConfig.isEmpty then return
    val (ncUrl, ncUser, ncPass) = ncConfig.get

    // Step 4: Folder selection
    tui.println()
    tui.println(tui.header("Step 4: Choose Backup Destination"))
    tui.println()
    val tempConfig = NkConfig.fromValues(ncUrl, ncUser, ncPass, adbPath = Some(adbPath))
    val webdav = new WebDav(tempConfig)
    val picker = new FolderPicker(webdav, tui)
    val cloudPath = picker.pickFolder("Photos")
    tui.successMessage(s"Destination: /$cloudPath/")

    // Step 5: Phone paths
    tui.println()
    tui.println(tui.header("Step 5: Select Phone Folders"))
    tui.println()
    val phonePaths = selectPhonePaths(adbPath)

    // Save config
    config = NkConfig.fromValues(
      nextcloudUrl = ncUrl,
      username = ncUser,
      password = ncPass,
      phonePaths = phonePaths,
      cloudPath = cloudPath,
      adbPath = Some(adbPath)
    )
    val savePath = configPath.map(os.Path(_)).getOrElse(NkConfig.defaultConfigPath)
    config.save(savePath)
    tui.println()
    tui.successMessage(s"Configuration saved to $savePath")

    // Step 6: Initial scan + upload
    tui.println()
    if tui.confirm("Scan your phone and cloud now to find files that need uploading?", default = true) then
      scanAndUpload()
    else
      tui.infoMessage("You can scan and upload later from the main menu.")
      tui.println()
      tui.println("Run the app again to access the main menu.")

  private def setupAdb(): Option[AdbManager.AdbLocation] =
    tui.waitMessage("Looking for ADB...")
    AdbManager.findAdb() match
      case Some(loc) =>
        val version = AdbManager.adbVersion(loc.path).getOrElse("unknown version")
        val source = if loc.managed then "managed" else "system"
        tui.successMessage(s"ADB found ($source): $version")
        Some(loc)
      case None =>
        tui.warnMessage("ADB not found on this system.")
        tui.println()
        tui.infoMessage("ADB (Android Debug Bridge) is needed to access your phone's files.")
        tui.infoMessage("It can be downloaded automatically (~15 MB).")
        tui.println()
        if tui.confirm("Download ADB now?", default = true) then
          try
            val startTime = System.currentTimeMillis()
            val loc = AdbManager.downloadAdb { (current, total) =>
              tui.showProgress("Downloading ADB", current, total, startTime)
            }
            tui.successMessage("ADB installed successfully!")
            Some(loc)
          catch
            case e: Exception =>
              tui.errorMessage(s"Failed to download ADB: ${e.getMessage}")
              tui.infoMessage("You can install it manually:")
              tui.infoMessage("  macOS:   brew install android-platform-tools")
              tui.infoMessage("  Linux:   sudo apt install adb")
              tui.infoMessage("  Windows: Download from developer.android.com")
              None
        else
          tui.infoMessage("You can install ADB manually and re-run this wizard.")
          None

  private def connectPhone(adbPath: String): Boolean =
    val tempConfig = NkConfig.fromValues("https://example.com", "user", "pass", adbPath = Some(adbPath))
    val adb = new Adb(tempConfig)

    var attempts = 0
    while attempts < 10 do
      if adb.checkDevice() then
        tui.successMessage("Phone connected!")
        return true

      if attempts == 0 then
        tui.println()
        tui.println(tui.box("Phone Connection Guide", Seq(
          "",
          s"  ${tui.Bold}1.${tui.Reset} Connect your phone to this computer via USB",
          "",
          s"  ${tui.Bold}2.${tui.Reset} Enable USB debugging on your phone:",
          s"     Settings → Developer Options → USB Debugging",
          "",
          s"  ${tui.Dim}If you don't see Developer Options:${tui.Reset}",
          s"     Settings → About Phone → tap Build Number 7 times",
          "",
          s"  ${tui.Bold}3.${tui.Reset} When prompted on phone, tap ${tui.Bold}Allow USB Debugging${tui.Reset}",
          s"     (check 'Always allow from this computer')",
          "",
        )))

      tui.println()
      if !tui.confirm("Press Enter to check again, or 'n' to skip", default = true) then
        return false
      attempts += 1

    tui.errorMessage("Could not detect phone after multiple attempts.")
    false

  private def setupNextcloud(): Option[(String, String, String)] =
    var configured = false
    var url = ""
    var username = ""
    var password = ""

    while !configured do
      url = tui.readLine("Nextcloud server URL", "https://")
      if url.isEmpty || url == "https://" then
        tui.errorMessage("URL is required.")
        if !tui.confirm("Try again?", default = true) then return None
      else
        username = tui.readLine("Username")
        if username.isEmpty then
          tui.errorMessage("Username is required.")
          if !tui.confirm("Try again?", default = true) then return None
        else
          tui.println()
          tui.println(tui.box("App Password", Seq(
            "",
            s"  An app password is needed for secure access.",
            s"  To create one:",
            "",
            s"  ${tui.Bold}1.${tui.Reset} Open your Nextcloud in a browser",
            s"  ${tui.Bold}2.${tui.Reset} Go to Settings → Security",
            s"  ${tui.Bold}3.${tui.Reset} Under 'Devices & sessions', enter a name",
            s"     (e.g., '${tui.Cyan}ncimgupload${tui.Reset}')",
            s"  ${tui.Bold}4.${tui.Reset} Click 'Create new app password'",
            s"  ${tui.Bold}5.${tui.Reset} Copy the generated password",
            "",
          )))
          tui.println()
          password = tui.readPassword("App password")
          if password.isEmpty then
            tui.errorMessage("Password is required.")
            if !tui.confirm("Try again?", default = true) then return None
          else
            tui.println()
            tui.waitMessage("Testing connection...")
            val testConfig = NkConfig.fromValues(url, username, password)
            val webdav = new WebDav(testConfig)
            if webdav.testConnection() then
              tui.successMessage(s"Connected to ${url.stripSuffix("/")} as $username")
              configured = true
            else
              tui.errorMessage("Connection failed. Check URL, username, and password.")
              if !tui.confirm("Try again?", default = true) then return None

    Some((url, username, password))

  private def selectPhonePaths(adbPath: String): Seq[String] =
    tui.waitMessage("Scanning phone for camera folders...")
    val tempConfig = NkConfig.fromValues("https://example.com", "user", "pass", adbPath = Some(adbPath))
    val adb = new Adb(tempConfig)
    val paths = adb.listPaths()

    if paths.isEmpty then
      tui.warnMessage("Could not list phone directories. Using default: DCIM/Camera")
      return Seq("DCIM/Camera")

    val defaultSelected = paths.zipWithIndex.collect {
      case (p, i) if p.toLowerCase.contains("camera") => i
    }.toSet

    val selected = tui.selectMultiple(
      "Select folders to back up:",
      paths,
      defaultSelected
    )

    if selected.isEmpty then
      tui.warnMessage("No folders selected. Using default: DCIM/Camera")
      Seq("DCIM/Camera")
    else
      selected.toSeq.sorted.map(paths)

  // ──────────────────────────────────────────────
  //  Main menu
  // ──────────────────────────────────────────────

  private def mainMenu(): Unit =
    var running = true
    while running do
      try
        tui.clearScreen()
        val db = new Db(config.dbPath)
        db.init()
        val lastScan = db.getLastScan("phone")
        val lastScanStr = lastScan.map { epoch =>
          val seconds = Instant.now().getEpochSecond - epoch
          s"Last sync: ${Progress.formatDuration(seconds)} ago"
        }.getOrElse("No scans yet")

        val profileLabel = Profile.current.map(p => s" ${tui.Dim}[$p]${tui.Reset}").getOrElse("")
        tui.println(tui.box(s"ncimgupload$profileLabel", Seq(
          s"  ${config.nextcloudUrl} · ${tui.Dim}$lastScanStr${tui.Reset}",
        )))
        tui.println()

        val choice = tui.selectMenu(
          "What would you like to do?",
          Seq(
            "Scan & Upload",
            "View Status",
            "Settings",
            "Exit"
          ),
          Seq(
            "Scan phone and cloud, then upload missing files",
            "Show backup status and file counts",
            "View or change configuration",
            "Quit ncimgupload"
          )
        )

        choice match
          case 0 => scanAndUpload()
          case 1 => viewStatus()
          case 2 => settingsMenu()
          case _ => running = false
      catch
        case _: org.jline.reader.UserInterruptException => running = false
        case _: org.jline.reader.EndOfFileException => running = false

  private def scanAndUpload(): Unit =
    val db = new Db(config.dbPath)
    db.init()
    val adb = new Adb(config)
    val webdav = new WebDav(config)

    // Check prerequisites
    if !adb.checkAdb() then
      tui.errorMessage("ADB is not available. Check your configuration.")
      pressEnter()
      return
    if !adb.checkDevice() then
      tui.errorMessage("No phone connected. Connect via USB and enable USB debugging.")
      pressEnter()
      return
    if !webdav.testConnection() then
      tui.errorMessage("Cannot connect to Nextcloud. Check your configuration.")
      pressEnter()
      return

    // Scan phone
    tui.println()
    tui.waitMessage("Scanning phone...")
    val now = Instant.now().getEpochSecond
    val phoneScanId = db.recordScanStart("phone", config.phonePaths.mkString(","), now)
    Progress.tuiMode = false
    val phoneFiles = adb.scanPhone(false)
    Progress.tuiMode = true
    for entry <- phoneFiles do
      db.upsertPhoneFile(entry, now)
    db.recordScanFinish(phoneScanId, phoneFiles.size, Instant.now().getEpochSecond)
    tui.successMessage(s"Found ${phoneFiles.size} files on phone (${Progress.formatSize(phoneFiles.map(_.sizeBytes).sum)})")

    // Scan Nextcloud: try Memories API first (fast), fall back to PROPFIND (slow)
    val cloudNow = Instant.now().getEpochSecond
    val cloudScanId = db.recordScanStart("cloud", "/", cloudNow)

    val memories = new MemoriesApi(config)
    tui.waitMessage("Checking for Memories app on Nextcloud...")
    var usedMemories = false
    val cloudEntries = if memories.isAvailable then
      usedMemories = true
      tui.successMessage("Memories app detected — using fast index scan")
      tui.waitMessage("Scanning via Memories API...")
      val entries = memories.scanAll(
        onProgress = (current, total) =>
          tui.print(s"\r${tui.ClearLine}  ${tui.Dim}Scanning day $current/$total ...${tui.Reset}")
      )
      tui.print(s"\r${tui.ClearLine}")
      entries
    else
      tui.warnMessage("Memories app not found — falling back to WebDAV folder scan (slower)")
      tui.waitMessage("Scanning all folders on Nextcloud...")
      val entries = webdav.scanCloudAll(
        onFolder = folder => tui.print(s"\r${tui.ClearLine}  ${tui.Dim}Scanning /$folder/ ...${tui.Reset}"),
        verbose = false
      )
      tui.print(s"\r${tui.ClearLine}")
      entries

    for entry <- cloudEntries do
      db.upsertCloudFile(entry, None, None, entry.checksum, cloudNow)
      db.markCloudFileByFilenameSize(entry.filename, entry.sizeBytes, None, None, entry.checksum, cloudNow)
    db.recordScanFinish(cloudScanId, cloudEntries.size, Instant.now().getEpochSecond)
    tui.successMessage(s"Found ${cloudEntries.size} matching files on Nextcloud (${Progress.formatSize(cloudEntries.map(_.sizeBytes).sum)})")

    // Diff
    val result = Sync.diff(phoneFiles, cloudEntries)
    tui.println()
    val missingSize = result.missingFromCloud.map(_.sizeBytes).sum
    val statusLines = Seq.newBuilder[(String, String)]
    statusLines += "Missing from cloud" -> s"${result.missingFromCloud.size} files (${Progress.formatSize(missingSize)})"
    statusLines += "Already backed up" -> s"${result.matched.size} files"
    if result.strippedMetadata.nonEmpty then
      statusLines += "Stripped metadata" -> s"${tui.Yellow}${result.strippedMetadata.size} files (phone has more data than cloud)${tui.Reset}"
    if result.sizeMismatch.nonEmpty then
      statusLines += "Size mismatch" -> s"${result.sizeMismatch.size} files"
    tui.showStatus(statusLines.result())

    if usedMemories && result.strippedMetadata.isEmpty then
      tui.println()
      tui.infoMessage("Note: Metadata repair detection requires a full WebDAV scan (file sizes needed).")
      tui.infoMessage("Run with --all flag in CLI mode to detect stripped metadata.")

    if result.missingFromCloud.isEmpty && result.strippedMetadata.isEmpty then
      tui.println()
      tui.successMessage("All files are backed up! Nothing to upload.")
      pressEnter()
      return

    if result.missingFromCloud.nonEmpty then
      tui.println()
      if tui.confirm(s"Upload ${result.missingFromCloud.size} missing files (${Progress.formatSize(missingSize)})?", default = true) then
        tui.println()
        Progress.tuiMode = false
        val uploader = new Upload(config, db, adb, webdav)
        uploader.uploadFiles(result.missingFromCloud, false)
        Progress.tuiMode = true
        tui.println()
        tui.successMessage("Upload complete!")

    if result.strippedMetadata.nonEmpty then
      tui.println()
      tui.warnMessage(s"${result.strippedMetadata.size} files appear to have been uploaded without full metadata (e.g., GPS location).")
      tui.infoMessage("The phone version is larger — likely the cloud copy was stripped by Android permissions.")
      tui.infoMessage("Re-uploading will OVERWRITE the cloud copy at its current location.")
      tui.println()
      if tui.confirm("View these files?", default = true) then
        repairStrippedFiles(result.strippedMetadata, adb, webdav)

    pressEnter()

  private def repairStrippedFiles(
      files: Seq[(FileEntry, FileEntry)],
      adb: Adb,
      webdav: WebDav
  ): Unit =
    tui.println()
    tui.println(tui.header("Files with stripped metadata"))
    tui.println()
    val totalDiff = files.map { case (p, c) => p.sizeBytes - c.sizeBytes }.sum
    for (phone, cloud) <- files.take(20) do
      val diff = phone.sizeBytes - cloud.sizeBytes
      tui.println(s"  ${phone.filename}")
      tui.println(s"    ${tui.Dim}phone: ${Progress.formatSize(phone.sizeBytes)}  cloud: ${Progress.formatSize(cloud.sizeBytes)}  (+${Progress.formatSize(diff)} missing)${tui.Reset}")
      tui.println(s"    ${tui.Dim}cloud location: /${cloud.relativePath}${tui.Reset}")
    if files.size > 20 then
      tui.println(s"  ${tui.Dim}... and ${files.size - 20} more${tui.Reset}")
    tui.println()
    tui.infoMessage(s"Total missing data: ${Progress.formatSize(totalDiff)} across ${files.size} files")

    tui.println()
    val choice = tui.selectMenu(
      "What would you like to do?",
      Seq(
        "Dry run (show what would be re-uploaded, no changes)",
        "Re-upload all (overwrite cloud copies with full phone versions)",
        "Back"
      )
    )

    choice match
      case 0 => dryRunRepair(files)
      case 1 => executeRepair(files, adb, webdav)
      case _ => ()

  private def dryRunRepair(files: Seq[(FileEntry, FileEntry)]): Unit =
    tui.println()
    tui.println(tui.header("Dry Run — No changes will be made"))
    tui.println()
    for ((phone, cloud), idx) <- files.zipWithIndex do
      val diff = phone.sizeBytes - cloud.sizeBytes
      tui.println(s"  [${idx + 1}/${files.size}] ${phone.filename}")
      tui.println(s"    ${tui.Dim}Would pull from phone: ${phone.relativePath} (${Progress.formatSize(phone.sizeBytes)})${tui.Reset}")
      tui.println(s"    ${tui.Dim}Would overwrite cloud: /${cloud.relativePath} (${Progress.formatSize(cloud.sizeBytes)} → ${Progress.formatSize(phone.sizeBytes)})${tui.Reset}")
    tui.println()
    tui.successMessage(s"Dry run complete. ${files.size} files would be re-uploaded.")
    tui.infoMessage("Run again and choose 'Re-upload all' to apply changes.")

  private def executeRepair(
      files: Seq[(FileEntry, FileEntry)],
      adb: Adb,
      webdav: WebDav
  ): Unit =
    tui.println()
    tui.warnMessage("This will OVERWRITE cloud copies with the full phone versions.")
    if !tui.confirm(s"Confirm re-upload of ${files.size} files?", default = false) then
      return

    tui.println()
    val tmpDir = os.Path(config.dbPath.getParent.resolve("tmp"))
    os.makeDir.all(tmpDir)
    var repaired = 0
    var failed = 0

    for ((phone, cloud), idx) <- files.zipWithIndex do
      tui.print(s"\r${tui.ClearLine}  [${idx + 1}/${files.size}] ${phone.filename}")
      val localTmp = tmpDir / phone.filename
      try
        if adb.pullFile(phone.relativePath, localTmp) then
          val checksum = Checksums.computeFile(localTmp)
          val mtime = Some(phone.mtimeEpoch)
          val success = if os.size(localTmp) >= config.chunkThreshold then
            webdav.chunkedUpload(localTmp, cloud.relativePath, Some(checksum), mtime = mtime)
          else
            webdav.putStream(localTmp, cloud.relativePath, Some(checksum), mtime = mtime)
          if success then repaired += 1
          else failed += 1
        else
          failed += 1
      catch
        case e: Exception =>
          failed += 1
      finally
        if os.exists(localTmp) then os.remove(localTmp)

    tui.println()
    tui.println()
    if repaired > 0 then
      tui.successMessage(s"Repaired $repaired files with full metadata.")
    if failed > 0 then
      tui.errorMessage(s"$failed files failed to re-upload.")

  private def viewStatus(): Unit =
    tui.clearScreen()
    val db = new Db(config.dbPath)
    db.init()

    val (phoneCount, cloudCount) = db.getTotalCounts()
    val counts = db.getStatusCounts()
    val lastPhone = db.getLastScan("phone")
    val lastCloud = db.getLastScan("cloud")

    def formatTime(epoch: Option[Long]): String =
      epoch.map { e =>
        val seconds = Instant.now().getEpochSecond - e
        s"${Progress.formatDuration(seconds)} ago"
      }.getOrElse("never")

    tui.println(tui.header("Backup Status"))
    tui.println()
    tui.showStatus(Seq(
      "Nextcloud"       -> config.nextcloudUrl,
      "Cloud path"      -> s"/${config.cloudPath}/",
      "Upload mode"     -> config.uploadMode,
      "Database"        -> config.dbPath.toString,
    ))
    tui.println()
    tui.showStatus(Seq(
      "Last phone scan"  -> formatTime(lastPhone),
      "Last cloud scan"  -> formatTime(lastCloud),
    ))
    tui.println()
    tui.showStatus(Seq(
      "Files on phone" -> phoneCount.toString,
      "Files on cloud" -> cloudCount.toString,
    ))
    tui.println()
    tui.println(s"  ${tui.Bold}Upload status:${tui.Reset}")
    for (status, count) <- counts.toSeq.sortBy(_._1) do
      val icon = status match
        case "verified" => tui.Green + "●" + tui.Reset
        case "uploaded" => tui.Cyan + "●" + tui.Reset
        case "pending"  => tui.Yellow + "●" + tui.Reset
        case "failed"   => tui.Red + "●" + tui.Reset
        case _          => tui.Dim + "●" + tui.Reset
      tui.println(s"    $icon $status: $count")

    tui.println()
    pressEnter()

  private def settingsMenu(): Unit =
    var back = false
    while !back do
      tui.clearScreen()
      tui.println(tui.header("Settings"))
      tui.println()

      val choice = tui.selectMenu(
        "Configuration:",
        Seq(
          "View current config",
          "Switch profile",
          "Re-run setup wizard",
          "Back to main menu"
        )
      )

      choice match
        case 0 =>
          tui.println()
          tui.showStatus(Seq(
            "Profile"        -> Profile.displayName,
            "Nextcloud URL"  -> config.nextcloudUrl,
            "Username"       -> config.username,
            "Cloud path"     -> config.cloudPath,
            "Phone paths"    -> config.phonePaths.mkString(", "),
            "Upload mode"    -> config.uploadMode,
            "ADB path"       -> config.adbPath.getOrElse("auto-detect"),
            "Database"       -> config.dbPath.toString,
            "Extensions"     -> config.extensions.mkString(", "),
            "Chunk threshold" -> Progress.formatSize(config.chunkThreshold),
            "Verify checksum" -> config.verifyChecksum.toString,
          ))
          tui.println()
          pressEnter()
        case 1 =>
          switchProfile()
          back = true
        case 2 =>
          firstRunWizard()
          back = true
        case _ =>
          back = true

  private def switchProfile(): Unit =
    val existing = Profile.listProfiles
    val options = existing :+ "+ Create new profile"
    tui.println()
    val choice = tui.selectMenu("Select profile:", options)
    if choice >= 0 && choice < existing.size then
      val selected = existing(choice)
      Profile.current = if selected == "default" then None else Some(selected)
      NkConfig.tryLoad(configPath) match
        case Some(cfg) =>
          config = cfg
          tui.successMessage(s"Switched to profile: ${Profile.displayName}")
        case None =>
          tui.warnMessage(s"Profile '${Profile.displayName}' has no config. Running setup wizard.")
          firstRunWizard()
    else if choice == existing.size then
      val name = tui.readLine("Profile name")
      if name.nonEmpty && name != "default" then
        Profile.current = Some(name)
        tui.infoMessage(s"Created profile: $name. Running setup wizard.")
        firstRunWizard()

  private def pressEnter(): Unit =
    tui.print(s"  ${tui.Dim}Press Enter to continue...${tui.Reset}")
    val lineReader = org.jline.reader.LineReaderBuilder.builder()
      .terminal(tui.terminal)
      .build()
    lineReader.readLine("")
