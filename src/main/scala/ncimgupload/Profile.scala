package ncimgupload

object Profile:
  private val home = os.Path(System.getProperty("user.home"))
  private val configBase = home / ".config" / "ncimgupload"
  private val dataBase = home / ".local" / "share" / "ncimgupload"

  var current: Option[String] = None

  def configDir: os.Path = current match
    case Some(name) => configBase / name
    case None => configBase

  def dataDir: os.Path = current match
    case Some(name) => dataBase / name
    case None => dataBase

  def configPath: os.Path = configDir / "config.conf"
  def dbPath: java.nio.file.Path = (dataDir / "state.db").toNIO

  def listProfiles: Seq[String] =
    val profiles = Seq.newBuilder[String]
    if os.exists(configBase / "config.conf") then
      profiles += "default"
    if os.exists(configBase) then
      for entry <- os.list(configBase) if os.isDir(entry) do
        if os.exists(entry / "config.conf") then
          profiles += entry.last
    profiles.result()

  def displayName: String = current.getOrElse("default")
