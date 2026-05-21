package ncimgupload

import java.util.{Base64, UUID}
import scala.util.Try
import scala.xml.XML

class WebDav(config: NkConfig):
  private val auth = Base64.getEncoder.encodeToString(s"${config.username}:${config.password}".getBytes("UTF-8"))
  private val baseHeaders = Map(
    "Authorization" -> s"Basic $auth",
    "OCS-APIRequest" -> "true"
  )

  private val session = requests.Session()
  private val propfindVerb = new requests.Requester("PROPFIND", session)
  private val mkcolVerb = new requests.Requester("MKCOL", session)
  private val moveVerb = new requests.Requester("MOVE", session)

  def testConnection(): Boolean =
    try
      val url = s"${config.baseWebDavUrl}/"
      val resp = propfindVerb(
        url,
        headers = baseHeaders ++ Map("Depth" -> "0", "Content-Type" -> "application/xml"),
        data = propfindBody(Seq("d:resourcetype")),
        readTimeout = 15000,
        connectTimeout = 10000,
        check = false
      )
      resp.statusCode == 207
    catch
      case e: Exception =>
        Progress.error(s"Connection failed: ${e.getMessage}")
        false

  def propfind(path: String, depth: Int = 1, verbose: Boolean = false): Seq[CloudFileInfo] =
    val url = s"${config.baseWebDavUrl}/${path.stripPrefix("/")}"
    Progress.verbose(s"PROPFIND $url (depth=$depth)", verbose)

    val depthStr = if depth < 0 then "infinity" else depth.toString
    val resp = propfindVerb(
      url,
      headers = baseHeaders ++ Map(
        "Depth" -> depthStr,
        "Content-Type" -> "application/xml"
      ),
      data = propfindBody(Seq(
        "d:displayname", "d:getcontentlength", "d:getlastmodified",
        "d:getetag", "d:resourcetype", "oc:fileid", "oc:checksums"
      )),
      readTimeout = 120000,
      connectTimeout = 15000,
      check = false
    )

    if resp.statusCode != 207 then
      Progress.error(s"PROPFIND failed with status ${resp.statusCode}: ${resp.text().take(200)}")
      return Seq.empty

    parsePropfindResponse(resp.text(), path, verbose)

  def scanCloud(verbose: Boolean = false): Seq[FileEntry] =
    scanCloudPath("", verbose)

  def scanCloudPath(path: String, verbose: Boolean = false): Seq[FileEntry] =
    val cloudPath = path.stripPrefix("/").stripSuffix("/")
    val allInfos = propfind(cloudPath, depth = -1, verbose = verbose)

    allInfos.collect {
      case info if !info.isCollection && config.extensions.exists(ext =>
        info.filename.toLowerCase.endsWith(ext)
      ) =>
        FileEntry(
          relativePath = info.relativePath,
          filename = info.filename,
          sizeBytes = info.size,
          mtimeEpoch = info.mtime,
          checksum = info.checksum,
          source = "cloud"
        )
    }

  def putStream(localPath: os.Path, remotePath: String, checksum: Option[String] = None): Boolean =
    val url = s"${config.baseWebDavUrl}/${remotePath.stripPrefix("/")}"
    val fileData = os.read.bytes(localPath)
    val headers = baseHeaders ++ Map(
      "Content-Type" -> "application/octet-stream",
      "X-NC-WebDAV-AutoMkcol" -> "1"
    ) ++ checksum.map(c => "OC-Checksum" -> c)

    val resp = requests.put(
      url,
      headers = headers,
      data = fileData,
      readTimeout = 600000,
      connectTimeout = 15000,
      check = false
    )

    if resp.statusCode >= 200 && resp.statusCode < 300 then true
    else
      Progress.error(s"PUT failed with status ${resp.statusCode}: ${resp.text().take(200)}")
      false

  def chunkedUpload(localPath: os.Path, remotePath: String, checksum: Option[String] = None, verbose: Boolean = false): Boolean =
    val uploadId = UUID.randomUUID().toString
    val fileSize = os.size(localPath)
    val chunkSize = config.chunkSize
    val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt

    Progress.verbose(s"Starting chunked upload: $totalChunks chunks of ${Progress.formatSize(chunkSize)}", verbose)

    val destUrl = s"${config.baseWebDavUrl}/${remotePath.stripPrefix("/")}"

    val mkcolResp = mkcolVerb(
      s"${config.uploadsWebDavUrl}/$uploadId",
      headers = baseHeaders ++ Map("Destination" -> destUrl),
      readTimeout = 30000,
      check = false
    )
    if mkcolResp.statusCode != 201 then
      Progress.error(s"MKCOL failed: ${mkcolResp.statusCode}")
      return false

    val fis = new java.io.FileInputStream(localPath.toIO)
    val startTime = System.currentTimeMillis()
    try
      var chunkNum = 1
      var totalSent = 0L
      val buffer = new Array[Byte](chunkSize.toInt)

      while totalSent < fileSize do
        val bytesToRead = math.min(chunkSize, fileSize - totalSent).toInt
        var read = 0
        while read < bytesToRead do
          val n = fis.read(buffer, read, bytesToRead - read)
          if n < 0 then throw new java.io.IOException("Unexpected end of file")
          read += n

        val chunkData = if read == buffer.length then buffer else java.util.Arrays.copyOf(buffer, read)
        val chunkName = f"$chunkNum%05d"

        val chunkResp = requests.put(
          s"${config.uploadsWebDavUrl}/$uploadId/$chunkName",
          headers = baseHeaders ++ Map(
            "Content-Type" -> "application/octet-stream",
            "Destination" -> destUrl,
            "OC-Total-Length" -> fileSize.toString
          ),
          data = chunkData,
          readTimeout = 300000,
          check = false
        )

        if chunkResp.statusCode < 200 || chunkResp.statusCode >= 300 then
          Progress.error(s"Chunk $chunkNum upload failed: ${chunkResp.statusCode}")
          return false

        totalSent += read
        chunkNum += 1
        Progress.printUploadProgress(totalSent, fileSize, localPath.last, startTime)
    finally fis.close()

    val moveHeaders = baseHeaders ++ Map(
      "Destination" -> destUrl,
      "OC-Total-Length" -> fileSize.toString,
      "Overwrite" -> "T"
    ) ++ checksum.map(c => "OC-Checksum" -> c)

    val moveResp = moveVerb(
      s"${config.uploadsWebDavUrl}/$uploadId/.file",
      headers = moveHeaders,
      readTimeout = 120000,
      check = false
    )

    if moveResp.statusCode >= 200 && moveResp.statusCode < 300 then
      Progress.verbose("Chunked upload assembly complete", verbose)
      true
    else
      Progress.error(s"MOVE (assembly) failed: ${moveResp.statusCode} ${moveResp.text().take(200)}")
      false

  def exists(path: String): Boolean =
    val url = s"${config.baseWebDavUrl}/${path.stripPrefix("/")}"
    val resp = requests.head(url, headers = baseHeaders, check = false, readTimeout = 15000)
    resp.statusCode == 200

  def getChecksum(path: String): Option[String] =
    val infos = propfind(path, depth = 0)
    infos.headOption.flatMap(_.checksum)

  def mkdir(path: String): Boolean =
    val url = s"${config.baseWebDavUrl}/${path.stripPrefix("/")}"
    try
      val resp = mkcolVerb(
        url,
        headers = baseHeaders,
        readTimeout = 15000,
        connectTimeout = 10000,
        check = false
      )
      resp.statusCode == 201
    catch
      case e: Exception =>
        Progress.error(s"MKCOL failed: ${e.getMessage}")
        false

  def listFolders(path: String, verbose: Boolean = false): Seq[String] =
    val normalizedPath = path.stripPrefix("/").stripSuffix("/")
    val infos = propfind(normalizedPath, depth = 1, verbose = verbose)
    infos
      .filter(_.isCollection)
      .map(_.relativePath)
      .filterNot(rp => rp == normalizedPath || rp.isEmpty)

  private def propfindBody(props: Seq[String]): String =
    val propElements = props.map { p =>
      if p.startsWith("d:") then s"<$p/>"
      else if p.startsWith("oc:") then s"<$p/>"
      else if p.startsWith("nc:") then s"<$p/>"
      else s"<d:$p/>"
    }.mkString("\n      ")

    s"""<?xml version="1.0" encoding="utf-8" ?>
       |<d:propfind xmlns:d="DAV:"
       |            xmlns:oc="http://owncloud.org/ns"
       |            xmlns:nc="http://nextcloud.org/ns">
       |  <d:prop>
       |      $propElements
       |  </d:prop>
       |</d:propfind>""".stripMargin

  private def parsePropfindResponse(xmlStr: String, basePath: String, verbose: Boolean): Seq[CloudFileInfo] =
    val doc = XML.loadString(xmlStr)

    val responses = doc \\ "response"
    val builder = Seq.newBuilder[CloudFileInfo]

    for resp <- responses do
      val href = (resp \ "href").text.trim
      val propstat = resp \ "propstat"
      val prop = propstat \ "prop"

      val isCollection = (prop \ "resourcetype" \ "collection").nonEmpty
      val displayname = (prop \ "displayname").text.trim
      val contentLength = Try((prop \ "getcontentlength").text.trim.toLong).getOrElse(0L)
      val lastModified = (prop \ "getlastmodified").text.trim
      val etag = (prop \ "getetag").text.trim
      val fileid = Try((prop \\ "fileid").text.trim.toLong).toOption
      val checksums = (prop \\ "checksums" \ "checksum").text.trim
      val checksum = if checksums.nonEmpty then Some(checksums) else None

      val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")
      val pathSuffix = decodedHref
        .split(s"/files/${config.username}/").lastOption
        .getOrElse(decodedHref)
        .stripPrefix("/").stripSuffix("/")

      val filename = pathSuffix.split('/').lastOption.getOrElse(displayname)

      val mtime = Try {
        java.time.ZonedDateTime.parse(lastModified, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond
      }.getOrElse(0L)

      builder += CloudFileInfo(
        href = decodedHref,
        relativePath = pathSuffix,
        filename = filename,
        size = contentLength,
        mtime = mtime,
        etag = if etag.nonEmpty then Some(etag) else None,
        fileId = fileid,
        checksum = checksum,
        isCollection = isCollection
      )

    builder.result()

case class CloudFileInfo(
    href: String,
    relativePath: String,
    filename: String,
    size: Long,
    mtime: Long,
    etag: Option[String],
    fileId: Option[Long],
    checksum: Option[String],
    isCollection: Boolean
)
