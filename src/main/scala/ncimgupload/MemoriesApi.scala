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
  private val cacheFile = os.Path(config.dbPath.getParent) / "memories-cache.json"

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
    val days = fetchDays()
    if days.isEmpty then return Seq.empty

    val cache = loadCache()
    val changedDays = days.filter { day =>
      cache.get(day.dayId) match
        case Some(cached) => cached.count != day.count
        case None => true
    }

    val updatedCache = if changedDays.isEmpty then
      onProgress(days.size, days.size)
      cache
    else
      val newEntries = scala.collection.mutable.Map.from(cache)
      for (day, idx) <- changedDays.zipWithIndex do
        onProgress(idx + 1, changedDays.size)
        val photos = fetchDay(day.dayId)
        newEntries(day.dayId) = CachedDay(day.count, photos)
      // remove days no longer on server
      val serverDayIds = days.map(_.dayId).toSet
      newEntries.filterInPlace((k, _) => serverDayIds.contains(k))
      val result = newEntries.toMap
      saveCache(result)
      result

    val allPhotos = Seq.newBuilder[FileEntry]
    for day <- days do
      updatedCache.get(day.dayId).foreach { cached =>
        for photo <- cached.photos do
          if config.extensions.exists(ext => photo.basename.toLowerCase.endsWith(ext)) then
            allPhotos += FileEntry(
              relativePath = photo.basename,
              filename = photo.basename,
              sizeBytes = photo.size,
              mtimeEpoch = photo.epoch,
              checksum = None,
              source = "cloud"
            )
      }

    allPhotos.result()

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

  // --- Single-file cache ---

  private case class CachedDay(count: Int, photos: Seq[MemoriesPhoto])

  private def loadCache(): Map[Long, CachedDay] =
    if !os.exists(cacheFile) then return Map.empty
    try
      val json = ujson.read(os.read(cacheFile))
      json.obj.flatMap { case (dayIdStr, dayObj) =>
        for dayId <- Try(dayIdStr.toLong).toOption yield
          val count = Try(dayObj("count").num.toInt).getOrElse(0)
          val photos = Try {
            dayObj("photos").arr.map { p =>
              MemoriesPhoto(
                p("basename").str,
                Try(p("size").num.toLong).getOrElse(0L),
                Try(p("epoch").num.toLong).getOrElse(0L)
              )
            }.toSeq
          }.getOrElse(Seq.empty)
          dayId -> CachedDay(count, photos)
      }.toMap
    catch
      case _: Exception => Map.empty

  private def saveCache(cache: Map[Long, CachedDay]): Unit =
    val json = ujson.Obj()
    for (dayId, cached) <- cache do
      json(dayId.toString) = ujson.Obj(
        "count" -> cached.count,
        "photos" -> ujson.Arr(cached.photos.map(p =>
          ujson.Obj("basename" -> p.basename, "size" -> p.size, "epoch" -> p.epoch)
        )*)
      )
    os.makeDir.all(cacheFile / os.up)
    os.write.over(cacheFile, ujson.write(json))

private case class DaySummary(dayId: Long, count: Int)
private case class MemoriesPhoto(basename: String, size: Long, epoch: Long)
