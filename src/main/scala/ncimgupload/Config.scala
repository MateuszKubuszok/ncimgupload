package ncimgupload

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

  def save(path: os.Path): Unit =
    val phonePathsStr = phonePaths.map(p => s""""$p"""").mkString(", ")
    val extensionsStr = extensions.map(e => s""""$e"""").mkString(", ")
    val syncFolderLine = syncFolder.map(sf => s"""  sync-folder = "$sf"""").getOrElse("  # sync-folder not configured")
    val adbLine = adbPath.map(p => s"""  path = "$p"""").getOrElse("  # path = auto-detect")
    val content =
      s"""nextcloud {
         |  url = "$nextcloudUrl"
         |  username = "$username"
         |  password = "$password"
         |}
         |
         |paths {
         |  phone = [$phonePathsStr]
         |  cloud = "$cloudPath"
         |$syncFolderLine
         |  extensions = [$extensionsStr]
         |}
         |
         |upload {
         |  chunk-threshold = $chunkThreshold
         |  chunk-size = $chunkSize
         |  retries = $retries
         |  verify-checksum = $verifyChecksum
         |  mode = "$uploadMode"
         |}
         |
         |cleanup {
         |  dry-run = $cleanupDryRun
         |  verified-only = $cleanupVerifiedOnly
         |}
         |
         |adb {
         |$adbLine
         |}
         |""".stripMargin

    os.makeDir.all(path / os.up)
    os.write.over(path, content)
    os.perms.set(path, "rw-------")

  override def toString: String =
    s"NkConfig(url=$nextcloudUrl, user=$username, password=***, phonePaths=$phonePaths, cloudPath=$cloudPath, mode=$uploadMode)"

object NkConfig:
  val defaultExtensions: Set[String] = Set(
    ".jpg", ".jpeg", ".png", ".mp4", ".mov", ".heic", ".heif",
    ".webp", ".gif", ".3gp", ".mkv", ".avi"
  )

  private def defaultDbPath: Path = Profile.dbPath

  def defaultConfigPath: os.Path = Profile.configPath

  def configExists: Boolean =
    os.exists(defaultConfigPath) ||
      sys.env.get("NKUPLOAD_CONFIG").exists(p => new File(p).exists()) ||
      new File("ncimgupload.conf").exists()

  def tryLoad(configPath: Option[String] = None): Option[NkConfig] =
    Try(load(configPath)).toOption

  def load(configPath: Option[String]): NkConfig =
    val file = configPath
      .map(p => new File(p))
      .orElse(sys.env.get("NKUPLOAD_CONFIG").map(p => new File(p)))
      .orElse(Option(defaultConfigPath.toIO).filter(_.exists()))
      .orElse(Option(new File("ncimgupload.conf")).filter(_.exists()))
      .getOrElse(throw new RuntimeException(
        s"No config file found. Create one at $defaultConfigPath or run 'ncimgupload setup'.\n" +
        "See config.example.conf for the format."
      ))

    checkPermissions(file.toPath)
    val raw = ConfigFactory.parseFile(file).resolve()
    parse(raw)

  def fromValues(
      nextcloudUrl: String,
      username: String,
      password: String,
      phonePaths: Seq[String] = Seq("DCIM/Camera"),
      cloudPath: String = "Photos/Phone",
      adbPath: Option[String] = None
  ): NkConfig =
    NkConfig(
      nextcloudUrl = nextcloudUrl.stripSuffix("/"),
      username = username,
      password = password,
      phonePaths = phonePaths,
      cloudPath = cloudPath,
      syncFolder = None,
      extensions = defaultExtensions,
      chunkThreshold = 104857600L,
      chunkSize = 10485760L,
      retries = 3,
      verifyChecksum = true,
      uploadMode = "webdav",
      cleanupDryRun = true,
      cleanupVerifiedOnly = true,
      adbPath = adbPath,
      adbDevice = None,
      dbPath = defaultDbPath
    )

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
