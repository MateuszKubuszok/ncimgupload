package ncimgupload

import java.io.{BufferedInputStream, FileOutputStream}
import java.util.zip.ZipInputStream
import scala.util.{Try, Success, Failure}

object AdbManager:
  case class AdbLocation(path: os.Path, managed: Boolean)

  private val dataDir = os.Path(System.getProperty("user.home")) / ".local" / "share" / "ncimgupload"
  private val platformToolsDir = dataDir / "platform-tools"

  private def adbBinaryName: String =
    if isWindows then "adb.exe" else "adb"

  private def managedAdbPath: os.Path =
    platformToolsDir / "platform-tools" / adbBinaryName

  def isDownloaded: Boolean =
    os.exists(managedAdbPath)

  def findAdb(configAdbPath: Option[String] = None): Option[AdbLocation] =
    configAdbPath
      .map(os.Path(_))
      .filter(os.exists(_))
      .map(p => AdbLocation(p, managed = false))
      .orElse(findOnPath())
      .orElse(findManaged())

  def downloadAdb(onProgress: (Long, Long) => Unit = (_, _) => ()): AdbLocation =
    val url = downloadUrl
    Progress.info(s"Downloading ADB from $url ...")

    os.makeDir.all(platformToolsDir)

    val zipPath = platformToolsDir / "platform-tools.zip"
    try
      val resp = requests.get(url, check = false, readTimeout = 120000, connectTimeout = 15000)
      if resp.statusCode != 200 then
        throw new RuntimeException(s"Download failed with HTTP ${resp.statusCode}")

      val data = resp.bytes
      onProgress(data.length.toLong, data.length.toLong)
      os.write.over(zipPath, data)

      extractZip(zipPath, platformToolsDir)

      if !isWindows then
        os.perms.set(managedAdbPath, "rwxr-xr-x")
        handleMacQuarantine(managedAdbPath)

      if !os.exists(managedAdbPath) then
        throw new RuntimeException(s"Extraction succeeded but ADB binary not found at $managedAdbPath")

      verifyAdb(managedAdbPath)
      AdbLocation(managedAdbPath, managed = true)
    finally
      if os.exists(zipPath) then os.remove(zipPath)

  def adbVersion(adbPath: os.Path): Option[String] =
    Try {
      val result = os.proc(adbPath.toString, "version").call(check = false, timeout = 5000)
      if result.exitCode == 0 then
        result.out.text().linesIterator.nextOption().map(_.trim)
      else None
    }.toOption.flatten

  private def findOnPath(): Option[AdbLocation] =
    val cmd = if isWindows then "where" else "which"
    Try {
      val result = os.proc(cmd, "adb").call(check = false, timeout = 5000)
      if result.exitCode == 0 then
        val path = os.Path(result.out.text().linesIterator.next().trim)
        if os.exists(path) then Some(AdbLocation(path, managed = false)) else None
      else None
    }.toOption.flatten

  private def findManaged(): Option[AdbLocation] =
    if os.exists(managedAdbPath) then Some(AdbLocation(managedAdbPath, managed = true))
    else None

  private def downloadUrl: String =
    val osName = System.getProperty("os.name").toLowerCase
    val platform =
      if osName.contains("mac") || osName.contains("darwin") then "darwin"
      else if osName.contains("linux") then "linux"
      else if osName.contains("windows") then "windows"
      else throw new RuntimeException(s"Unsupported OS: $osName")
    s"https://dl.google.com/android/repository/platform-tools-latest-$platform.zip"

  private def isWindows: Boolean =
    System.getProperty("os.name").toLowerCase.contains("windows")

  private def extractZip(zipPath: os.Path, destDir: os.Path): Unit =
    val fis = new BufferedInputStream(java.nio.file.Files.newInputStream(zipPath.toNIO))
    val zis = new ZipInputStream(fis)
    try
      var entry = zis.getNextEntry
      while entry != null do
        val outPath = destDir / os.SubPath(entry.getName)
        if entry.isDirectory then
          os.makeDir.all(outPath)
        else
          os.makeDir.all(outPath / os.up)
          val fos = new FileOutputStream(outPath.toIO)
          try
            val buffer = new Array[Byte](8192)
            var n = zis.read(buffer)
            while n >= 0 do
              fos.write(buffer, 0, n)
              n = zis.read(buffer)
          finally fos.close()
        zis.closeEntry()
        entry = zis.getNextEntry
    finally zis.close()

  private def handleMacQuarantine(path: os.Path): Unit =
    val osName = System.getProperty("os.name").toLowerCase
    if osName.contains("mac") || osName.contains("darwin") then
      Try {
        os.proc("xattr", "-d", "com.apple.quarantine", path.toString).call(
          check = false, timeout = 5000
        )
      }

  private def verifyAdb(path: os.Path): Unit =
    val result = Try(os.proc(path.toString, "version").call(check = false, timeout = 5000))
    result match
      case Success(r) if r.exitCode == 0 =>
        Progress.info(s"ADB installed: ${r.out.text().linesIterator.nextOption().getOrElse("")}")
      case Success(r) =>
        throw new RuntimeException(s"ADB binary found but failed to run (exit code ${r.exitCode}). ${r.err.text().take(200)}")
      case Failure(e) =>
        throw new RuntimeException(s"ADB binary found but failed to execute: ${e.getMessage}")
