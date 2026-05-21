package nkupload

import java.nio.file.Path

class WebDavParsingTest extends munit.FunSuite:
  private def makeConfig = NkConfig(
    nextcloudUrl = "https://cloud.example.com",
    username = "testuser",
    password = "testpass",
    phonePaths = Seq("DCIM/Camera"),
    cloudPath = "Photos/Phone",
    syncFolder = None,
    extensions = Set(".jpg", ".mp4"),
    chunkThreshold = 100000000L,
    chunkSize = 10000000L,
    retries = 3,
    verifyChecksum = true,
    uploadMode = "webdav",
    cleanupDryRun = true,
    cleanupVerifiedOnly = true,
    adbPath = None,
    adbDevice = None,
    dbPath = Path.of("/tmp/nkupload-test.db")
  )

  test("parse PROPFIND multistatus response") {
    val xml = """<?xml version="1.0"?>
      <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
        <d:response>
          <d:href>/remote.php/dav/files/testuser/Photos/Phone/</d:href>
          <d:propstat>
            <d:prop>
              <d:displayname>Phone</d:displayname>
              <d:resourcetype><d:collection/></d:resourcetype>
              <d:getlastmodified>Thu, 16 May 2024 10:30:00 GMT</d:getlastmodified>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
        </d:response>
        <d:response>
          <d:href>/remote.php/dav/files/testuser/Photos/Phone/IMG_001.jpg</d:href>
          <d:propstat>
            <d:prop>
              <d:displayname>IMG_001.jpg</d:displayname>
              <d:getcontentlength>4096</d:getcontentlength>
              <d:resourcetype/>
              <d:getlastmodified>Wed, 15 May 2024 09:00:00 GMT</d:getlastmodified>
              <d:getetag>"abc123"</d:getetag>
              <oc:fileid>42</oc:fileid>
              <oc:checksums>
                <oc:checksum>SHA256:deadbeef</oc:checksum>
              </oc:checksums>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
        </d:response>
        <d:response>
          <d:href>/remote.php/dav/files/testuser/Photos/Phone/VID_002.mp4</d:href>
          <d:propstat>
            <d:prop>
              <d:displayname>VID_002.mp4</d:displayname>
              <d:getcontentlength>1048576</d:getcontentlength>
              <d:resourcetype/>
              <d:getlastmodified>Mon, 13 May 2024 14:30:00 GMT</d:getlastmodified>
              <d:getetag>"def456"</d:getetag>
              <oc:fileid>43</oc:fileid>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
        </d:response>
      </d:multistatus>"""

    val webdav = new WebDav(makeConfig)
    val method = webdav.getClass.getDeclaredMethod("parsePropfindResponse", classOf[String], classOf[String], classOf[Boolean])
    method.setAccessible(true)
    val infos = method.invoke(webdav, xml, "Photos/Phone", false.asInstanceOf[AnyRef]).asInstanceOf[Seq[CloudFileInfo]]

    assertEquals(infos.size, 3)

    val dir = infos.find(_.filename == "Phone").get
    assert(dir.isCollection)

    val img = infos.find(_.filename == "IMG_001.jpg").get
    assert(!img.isCollection)
    assertEquals(img.size, 4096L)
    assertEquals(img.etag, Some("\"abc123\""))
    assertEquals(img.fileId, Some(42L))
    assertEquals(img.checksum, Some("SHA256:deadbeef"))

    val vid = infos.find(_.filename == "VID_002.mp4").get
    assertEquals(vid.size, 1048576L)
    assertEquals(vid.checksum, None)
  }
