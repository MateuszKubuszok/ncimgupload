package nkupload

class SyncTest extends munit.FunSuite:
  private def phoneFile(name: String, size: Long, mtime: Long = 1000L) =
    FileEntry(s"DCIM/Camera/$name", name, size, mtime, None, "phone")

  private def cloudFile(name: String, size: Long, mtime: Long = 1000L) =
    FileEntry(s"Photos/Phone/$name", name, size, mtime, None, "cloud")

  test("matched files by filename and size") {
    val phone = Seq(phoneFile("IMG_001.jpg", 1024), phoneFile("IMG_002.jpg", 2048))
    val cloud = Seq(cloudFile("IMG_001.jpg", 1024), cloudFile("IMG_002.jpg", 2048))
    val result = Sync.diff(phone, cloud)
    assertEquals(result.matched.size, 2)
    assertEquals(result.missingFromCloud.size, 0)
    assertEquals(result.missingFromPhone.size, 0)
    assertEquals(result.sizeMismatch.size, 0)
  }

  test("missing from cloud") {
    val phone = Seq(phoneFile("IMG_001.jpg", 1024), phoneFile("IMG_002.jpg", 2048))
    val cloud = Seq(cloudFile("IMG_001.jpg", 1024))
    val result = Sync.diff(phone, cloud)
    assertEquals(result.matched.size, 1)
    assertEquals(result.missingFromCloud.size, 1)
    assertEquals(result.missingFromCloud.head.filename, "IMG_002.jpg")
  }

  test("missing from phone (already deleted locally)") {
    val phone = Seq(phoneFile("IMG_001.jpg", 1024))
    val cloud = Seq(cloudFile("IMG_001.jpg", 1024), cloudFile("IMG_003.jpg", 3072))
    val result = Sync.diff(phone, cloud)
    assertEquals(result.matched.size, 1)
    assertEquals(result.missingFromPhone.size, 1)
    assertEquals(result.missingFromPhone.head.filename, "IMG_003.jpg")
  }

  test("size mismatch detected") {
    val phone = Seq(phoneFile("IMG_001.jpg", 1024))
    val cloud = Seq(cloudFile("IMG_001.jpg", 1000))
    val result = Sync.diff(phone, cloud)
    assertEquals(result.matched.size, 0)
    assertEquals(result.sizeMismatch.size, 1)
    assertEquals(result.sizeMismatch.head._1.sizeBytes, 1024L)
    assertEquals(result.sizeMismatch.head._2.sizeBytes, 1000L)
  }

  test("case-insensitive filename matching") {
    val phone = Seq(phoneFile("img_001.JPG", 1024))
    val cloud = Seq(cloudFile("IMG_001.jpg", 1024))
    val result = Sync.diff(phone, cloud)
    assertEquals(result.matched.size, 1)
    assertEquals(result.missingFromCloud.size, 0)
  }

  test("different paths same filename matches") {
    val phone = Seq(FileEntry("DCIM/Camera/IMG_001.jpg", "IMG_001.jpg", 1024, 1000, None, "phone"))
    val cloud = Seq(FileEntry("Photos/Phone/2024/01/IMG_001.jpg", "IMG_001.jpg", 1024, 1000, None, "cloud"))
    val result = Sync.diff(phone, cloud)
    assertEquals(result.matched.size, 1)
    assertEquals(result.missingFromCloud.size, 0)
  }

  test("empty lists") {
    val result = Sync.diff(Seq.empty, Seq.empty)
    assertEquals(result.matched.size, 0)
    assertEquals(result.missingFromCloud.size, 0)
    assertEquals(result.missingFromPhone.size, 0)
    assertEquals(result.sizeMismatch.size, 0)
  }

  test("large mixed scenario") {
    val phone = (1 to 100).map(i => phoneFile(s"IMG_$i.jpg", i * 1000L))
    val cloud = (1 to 80).map(i => cloudFile(s"IMG_$i.jpg", i * 1000L)) ++
      Seq(cloudFile("IMG_999.jpg", 999000))
    val result = Sync.diff(phone, cloud)
    assertEquals(result.matched.size, 80)
    assertEquals(result.missingFromCloud.size, 20)
    assertEquals(result.missingFromPhone.size, 1)
    assertEquals(result.missingFromPhone.head.filename, "IMG_999.jpg")
  }
