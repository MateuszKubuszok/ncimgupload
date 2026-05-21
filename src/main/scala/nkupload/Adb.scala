package nkupload

import scala.util.{Try, Success, Failure}

class Adb(config: NkConfig):
  private val adbCmd = config.adbPath.getOrElse("adb")
  private val deviceArgs: Seq[String] = config.adbDevice.map(d => Seq("-s", d)).getOrElse(Seq.empty)
  private val phoneBase = "/storage/emulated/0"

  private def adb(args: String*): os.CommandResult =
    os.proc(adbCmd +: deviceArgs ++: args).call(
      check = false,
      timeout = 120000
    )

  def checkAdb(): Boolean =
    val result = Try(os.proc(adbCmd, "version").call(check = false, timeout = 5000))
    result match
      case Success(r) if r.exitCode == 0 => true
      case _ =>
        Progress.error(s"ADB not found at '$adbCmd'. Install with: brew install android-platform-tools")
        false

  def checkDevice(): Boolean =
    val result = adb("devices")
    if result.exitCode != 0 then
      Progress.error("Failed to list ADB devices")
      return false
    val lines = result.out.text().linesIterator.drop(1).filter(_.trim.nonEmpty).toSeq
    val devices = lines.filter(_.contains("device")).filterNot(_.contains("unauthorized"))
    if devices.isEmpty then
      Progress.error("No Android device found. Connect via USB and enable USB debugging.")
      false
    else
      if devices.size > 1 && config.adbDevice.isEmpty then
        Progress.warn(s"Multiple devices found (${devices.size}). Set adb.device in config to choose one.")
      true

  def scanPhone(verbose: Boolean = false): Seq[FileEntry] =
    val entries = Seq.newBuilder[FileEntry]
    val extFilter = config.extensions.map(e => s"-iname '*$e'").mkString(" -o ")

    for phonePath <- config.phonePaths do
      val fullPath = s"$phoneBase/$phonePath"
      Progress.info(s"Scanning $fullPath via ADB...")

      val findCmd = s"find '$fullPath' -type f \\( $extFilter \\) -exec stat -c '%s %Y %n' {} +"
      Progress.verbose(s"Running: adb shell $findCmd", verbose)
      val result = adb("shell", findCmd)

      if result.exitCode != 0 then
        val stderr = result.err.text().trim
        if stderr.contains("No such file or directory") then
          Progress.warn(s"Path not found on phone: $fullPath")
        else if stderr.nonEmpty then
          Progress.warn(s"ADB error scanning $fullPath: $stderr")
      else
        for line <- result.out.text().linesIterator if line.trim.nonEmpty do
          parseFindStatLine(line.trim, phonePath) match
            case Some(entry) =>
              entries += entry
              Progress.verbose(s"Found: ${entry.filename} (${Progress.formatSize(entry.sizeBytes)})", verbose)
            case None =>
              Progress.verbose(s"Failed to parse: $line", verbose)

    entries.result()

  private def parseFindStatLine(line: String, phonePath: String): Option[FileEntry] =
    val firstSpace = line.indexOf(' ')
    if firstSpace < 0 then return None
    val secondSpace = line.indexOf(' ', firstSpace + 1)
    if secondSpace < 0 then return None

    Try {
      val size = line.substring(0, firstSpace).toLong
      val mtime = line.substring(firstSpace + 1, secondSpace).toLong
      val fullPath = line.substring(secondSpace + 1)
      val relativePath = if fullPath.startsWith(s"$phoneBase/") then
        fullPath.stripPrefix(s"$phoneBase/")
      else fullPath

      val filename = relativePath.split('/').last
      FileEntry(
        relativePath = relativePath,
        filename = filename,
        sizeBytes = size,
        mtimeEpoch = mtime,
        checksum = None,
        source = "phone"
      )
    }.toOption

  def pullFile(remotePath: String, localPath: os.Path): Boolean =
    val fullRemote = if remotePath.startsWith("/") then remotePath else s"$phoneBase/$remotePath"
    os.makeDir.all(localPath / os.up)
    val result = adb("pull", fullRemote, localPath.toString)
    if result.exitCode != 0 then
      Progress.error(s"Failed to pull $fullRemote: ${result.err.text().trim}")
    result.exitCode == 0

  def deleteFile(remotePath: String): Boolean =
    val fullRemote = if remotePath.startsWith("/") then remotePath else s"$phoneBase/$remotePath"
    val result = adb("shell", "rm", s"'$fullRemote'")
    if result.exitCode != 0 then
      Progress.error(s"Failed to delete $fullRemote: ${result.err.text().trim}")
    result.exitCode == 0

  def listPaths(verbose: Boolean = false): Seq[String] =
    Progress.info(s"Listing available paths on phone...")
    val result = adb("shell", "ls", s"$phoneBase/DCIM/")
    if result.exitCode != 0 then
      Progress.warn("Could not list DCIM directory")
      Seq.empty
    else
      val paths = result.out.text().linesIterator.filter(_.trim.nonEmpty).map(d => s"DCIM/$d").toSeq
      Progress.info(s"Found directories: ${paths.mkString(", ")}")
      paths
