package nkupload

import com.typesafe.config.{Config as TConfig, ConfigFactory}
import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

case class NkConfig(
    nextcloudUrl: String,
    username: String,
    password: String,
    phonePaths: Seq[String],
    cloudPath: String,
    syncFolder: Option[String],
    extensions: Set[String],
    chunkThreshold: Long,
    chunkSize: Long,
    retries: Int,
    verifyChecksum: Boolean,
    uploadMode: String,
    cleanupDryRun: Boolean,
    cleanupVerifiedOnly: Boolean,
    adbPath: Option[String],
    adbDevice: Option[String],
    dbPath: Path
):
  def baseWebDavUrl: String =
    val base = nextcloudUrl.stripSuffix("/")
    s"$base/remote.php/dav/files/$username"

  def uploadsWebDavUrl: String =
    val base = nextcloudUrl.stripSuffix("/")
    s"$base/remote.php/dav/uploads/$username"

  override def toString: String =
    s"NkConfig(url=$nextcloudUrl, user=$username, password=***, phonePaths=$phonePaths, cloudPath=$cloudPath, mode=$uploadMode)"

object NkConfig:
  private val defaultExtensions = Set(
    ".jpg", ".jpeg", ".png", ".mp4", ".mov", ".heic", ".heif",
    ".webp", ".gif", ".3gp", ".mkv", ".avi"
  )

  private val defaultDbPath: Path =
    Path.of(System.getProperty("user.home"), ".local", "share", "nkupload", "state.db")

  def load(configPath: Option[String]): NkConfig =
    val file = configPath
      .map(p => new File(p))
      .orElse(sys.env.get("NKUPLOAD_CONFIG").map(p => new File(p)))
      .orElse(Option(new File(System.getProperty("user.home"), ".config/nkupload/config.conf")).filter(_.exists()))
      .orElse(Option(new File("nkupload.conf")).filter(_.exists()))
      .getOrElse(throw new RuntimeException(
        "No config file found. Create one at ~/.config/nkupload/config.conf or run 'nkupload setup'.\n" +
        "See config.example.conf for the format."
      ))

    checkPermissions(file.toPath)
    val raw = ConfigFactory.parseFile(file).resolve()
    parse(raw)

  private def checkPermissions(path: Path): Unit =
    Try {
      val perms = Files.getPosixFilePermissions(path)
      val permStr = perms.toString
      if permStr.contains("GROUP") || permStr.contains("OTHER") then
        System.err.println(s"WARNING: Config file $path has overly open permissions. Run: chmod 600 $path")
    }

  private def parse(raw: TConfig): NkConfig =
    val password = sys.env.getOrElse("NKUPLOAD_PASSWORD",
      if raw.hasPath("nextcloud.password") then raw.getString("nextcloud.password")
      else throw new RuntimeException("No password configured. Set nextcloud.password in config or NKUPLOAD_PASSWORD env var.")
    )

    val extensions =
      if raw.hasPath("paths.extensions") then
        raw.getStringList("paths.extensions").asScala.map(_.toLowerCase).toSet
      else defaultExtensions

    val dbPath =
      if raw.hasPath("database.path") then Path.of(raw.getString("database.path"))
      else defaultDbPath

    NkConfig(
      nextcloudUrl = raw.getString("nextcloud.url").stripSuffix("/"),
      username = raw.getString("nextcloud.username"),
      password = password,
      phonePaths =
        if raw.hasPath("paths.phone") then raw.getStringList("paths.phone").asScala.toSeq
        else Seq("DCIM/Camera"),
      cloudPath =
        if raw.hasPath("paths.cloud") then raw.getString("paths.cloud")
        else "Photos/Phone",
      syncFolder =
        if raw.hasPath("paths.sync-folder") then Some(raw.getString("paths.sync-folder"))
        else None,
      extensions = extensions,
      chunkThreshold =
        if raw.hasPath("upload.chunk-threshold") then raw.getLong("upload.chunk-threshold")
        else 104857600L,
      chunkSize =
        if raw.hasPath("upload.chunk-size") then raw.getLong("upload.chunk-size")
        else 10485760L,
      retries =
        if raw.hasPath("upload.retries") then raw.getInt("upload.retries")
        else 3,
      verifyChecksum =
        if raw.hasPath("upload.verify-checksum") then raw.getBoolean("upload.verify-checksum")
        else true,
      uploadMode =
        if raw.hasPath("upload.mode") then raw.getString("upload.mode")
        else "webdav",
      cleanupDryRun =
        if raw.hasPath("cleanup.dry-run") then raw.getBoolean("cleanup.dry-run")
        else true,
      cleanupVerifiedOnly =
        if raw.hasPath("cleanup.verified-only") then raw.getBoolean("cleanup.verified-only")
        else true,
      adbPath =
        if raw.hasPath("adb.path") then Some(raw.getString("adb.path"))
        else None,
      adbDevice =
        if raw.hasPath("adb.device") then Some(raw.getString("adb.device"))
        else None,
      dbPath = dbPath
    )
