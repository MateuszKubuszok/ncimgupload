package ncimgupload

import scala.util.Try
import java.util.Base64

class MemoriesApi(config: NkConfig):
  private val auth = Base64.getEncoder.encodeToString(s"${config.username}:${config.password}".getBytes("UTF-8"))
  private val baseHeaders = Map(
    "Authorization" -> s"Basic $auth",
    "OCS-APIRequest" -> "true",
    "Accept" -> "application/json"
  )
  private val baseUrl = config.nextcloudUrl.stripSuffix("/")
  private val cacheDir = os.Path(config.dbPath.getParent) / "memories-cache"

  def isAvailable: Boolean =
    try
      val resp = requests.get(
        s"$baseUrl/apps/memories/api/days",
        headers = baseHeaders,
        readTimeout = 15000,
        connectTimeout = 10000,
        check = false
      )
      resp.statusCode == 200
    catch
      case _: Exception => false

  def scanAll(onProgress: (Int, Int) => Unit = (_, _) => ()): Seq[FileEntry] =
    os.makeDir.all(cacheDir)
    val days = fetchDays()
    if days.isEmpty then return Seq.empty

    val cachedDays = loadCachedDayCounts()
    val result = Seq.newBuilder[FileEntry]
    var fetched = 0
    var cached = 0

    for (day, idx) <- days.zipWithIndex do
      onProgress(idx + 1, days.size)

      val cachedPhotos = cachedDays.get(day.dayId) match
        case Some(cachedCount) if cachedCount == day.count =>
          loadCachedDay(day.dayId)
        case _ => None

      val photos = cachedPhotos match
        case Some(entries) =>
          cached += 1
          entries
        case None =>
          fetched += 1
          val entries = fetchDay(day.dayId)
          saveCachedDay(day.dayId, entries)
          entries

      for photo <- photos do
        if config.extensions.exists(ext => photo.basename.toLowerCase.endsWith(ext)) then
          result += FileEntry(
            relativePath = photo.basename,
            filename = photo.basename,
            sizeBytes = photo.size,
            mtimeEpoch = photo.epoch,
            checksum = None,
            source = "cloud"
          )

    saveCachedDayCounts(days)
    Progress.verbose(s"Memories scan: $fetched days fetched, $cached days from cache", true)
    result.result()

  private def fetchDays(): Seq[DaySummary] =
    try
      val resp = requests.get(
        s"$baseUrl/apps/memories/api/days",
        headers = baseHeaders,
        readTimeout = 30000,
        connectTimeout = 10000,
        check = false
      )
      if resp.statusCode != 200 then return Seq.empty

      val json = ujson.read(resp.text())
      json.arr.flatMap { entry =>
        for
          dayId <- Try(entry("dayid").num.toLong).toOption
          count <- Try(entry("count").num.toInt).toOption
        yield DaySummary(dayId, count)
      }.toSeq
    catch
      case e: Exception =>
        Progress.verbose(s"Memories API error: ${e.getMessage}", true)
        Seq.empty

  private def fetchDay(dayId: Long): Seq[MemoriesPhoto] =
    try
      val resp = requests.get(
        s"$baseUrl/apps/memories/api/days/$dayId",
        headers = baseHeaders,
        readTimeout = 30000,
        connectTimeout = 10000,
        check = false
      )
      if resp.statusCode != 200 then return Seq.empty

      val json = ujson.read(resp.text())
      json.arr.flatMap { entry =>
        val basename = Try(entry("basename").str).toOption
        val size = extractSize(entry)
        val epoch = Try {
          if entry.obj.contains("epoch") then entry("epoch").num.toLong
          else dayId * 86400
        }.getOrElse(dayId * 86400)

        basename.map(b => MemoriesPhoto(b, size, epoch))
      }.toSeq
    catch
      case e: Exception =>
        Progress.verbose(s"Memories API error for day $dayId: ${e.getMessage}", true)
        Seq.empty

  private def extractSize(entry: ujson.Value): Long =
    val candidates = Seq("size", "filesize", "contentlength")
    candidates.flatMap { key =>
      Try {
        if entry.obj.contains(key) then
          val v = entry(key)
          if v.isInstanceOf[ujson.Num] then v.num.toLong
          else if v.isInstanceOf[ujson.Str] then v.str.toLong
          else 0L
        else 0L
      }.toOption.filter(_ > 0)
    }.headOption.getOrElse(0L)

  // --- Caching ---

  private def loadCachedDayCounts(): Map[Long, Int] =
    val file = cacheDir / "days.json"
    if !os.exists(file) then return Map.empty
    try
      val json = ujson.read(os.read(file))
      json.arr.flatMap { entry =>
        for
          dayId <- Try(entry("dayid").num.toLong).toOption
          count <- Try(entry("count").num.toInt).toOption
        yield dayId -> count
      }.toMap
    catch
      case _: Exception => Map.empty

  private def saveCachedDayCounts(days: Seq[DaySummary]): Unit =
    val json = ujson.Arr(days.map(d => ujson.Obj("dayid" -> d.dayId, "count" -> d.count))*)
    os.write.over(cacheDir / "days.json", ujson.write(json))

  private def loadCachedDay(dayId: Long): Option[Seq[MemoriesPhoto]] =
    val file = cacheDir / s"day-$dayId.json"
    if !os.exists(file) then return None
    try
      val json = ujson.read(os.read(file))
      Some(json.arr.map { entry =>
        MemoriesPhoto(
          entry("basename").str,
          Try(entry("size").num.toLong).getOrElse(0L),
          Try(entry("epoch").num.toLong).getOrElse(dayId * 86400)
        )
      }.toSeq)
    catch
      case _: Exception => None

  private def saveCachedDay(dayId: Long, photos: Seq[MemoriesPhoto]): Unit =
    val json = ujson.Arr(photos.map(p =>
      ujson.Obj("basename" -> p.basename, "size" -> p.size, "epoch" -> p.epoch)
    )*)
    os.write.over(cacheDir / s"day-$dayId.json", ujson.write(json))

private case class DaySummary(dayId: Long, count: Int)
private case class MemoriesPhoto(basename: String, size: Long, epoch: Long)
