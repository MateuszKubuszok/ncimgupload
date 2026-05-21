package ncimgupload

import java.nio.file.{Files, Path}

class DbTest extends munit.FunSuite:
  private var dbPath: Path = _
  private var db: Db = _

  override def beforeEach(context: BeforeEach): Unit =
    dbPath = Files.createTempFile("nkupload-test-", ".db")
    Files.delete(dbPath)
    db = new Db(dbPath)
    db.init()

  override def afterEach(context: AfterEach): Unit =
    Files.deleteIfExists(dbPath)

  test("init creates tables") {
    val counts = db.getStatusCounts()
    assert(counts.isEmpty)
  }

  test("upsert and query phone files") {
    val entry = FileEntry("DCIM/Camera/IMG_001.jpg", "IMG_001.jpg", 1024, 1000, None, "phone")
    db.upsertPhoneFile(entry, 5000)
    val pending = db.getPendingFiles()
    assertEquals(pending.size, 1)
    assertEquals(pending.head.filename, "IMG_001.jpg")
    assertEquals(pending.head.phoneSize, Some(1024L))
    assertEquals(pending.head.uploadStatus, "pending")
  }

  test("upsert phone file twice updates") {
    val entry1 = FileEntry("DCIM/Camera/IMG_001.jpg", "IMG_001.jpg", 1024, 1000, None, "phone")
    val entry2 = FileEntry("DCIM/Camera/IMG_001.jpg", "IMG_001.jpg", 2048, 2000, None, "phone")
    db.upsertPhoneFile(entry1, 5000)
    db.upsertPhoneFile(entry2, 6000)
    val pending = db.getPendingFiles()
    assertEquals(pending.size, 1)
    assertEquals(pending.head.phoneSize, Some(2048L))
  }

  test("mark cloud file by filename and size") {
    val entry = FileEntry("DCIM/Camera/IMG_001.jpg", "IMG_001.jpg", 1024, 1000, None, "phone")
    db.upsertPhoneFile(entry, 5000)
    val updated = db.markCloudFileByFilenameSize("IMG_001.jpg", 1024, Some("\"etag\""), Some(42L), Some("SHA256:abc"), 6000)
    assertEquals(updated, 1)
    val pending = db.getPendingFiles()
    assertEquals(pending.size, 0)
  }

  test("status counts") {
    db.upsertPhoneFile(FileEntry("a.jpg", "a.jpg", 100, 1000, None, "phone"), 5000)
    db.upsertPhoneFile(FileEntry("b.jpg", "b.jpg", 200, 1000, None, "phone"), 5000)
    db.updateStatus("a.jpg", "uploaded")
    val counts = db.getStatusCounts()
    assertEquals(counts.getOrElse("pending", 0), 1)
    assertEquals(counts.getOrElse("uploaded", 0), 1)
  }

  test("scan history") {
    val id = db.recordScanStart("phone", "DCIM/Camera", 1000)
    db.recordScanFinish(id, 42, 2000)
    val last = db.getLastScan("phone")
    assertEquals(last, Some(2000L))
  }

  test("total counts") {
    db.upsertPhoneFile(FileEntry("a.jpg", "a.jpg", 100, 1000, None, "phone"), 5000)
    db.upsertPhoneFile(FileEntry("b.jpg", "b.jpg", 200, 1000, None, "phone"), 5000)
    val entry = FileEntry("Photos/c.jpg", "c.jpg", 300, 1000, None, "cloud")
    db.upsertCloudFile(entry, Some("etag"), Some(1L), None, 5000)
    val (phone, cloud) = db.getTotalCounts()
    assertEquals(phone, 2)
    assertEquals(cloud, 1)
  }

  test("clear all") {
    db.upsertPhoneFile(FileEntry("a.jpg", "a.jpg", 100, 1000, None, "phone"), 5000)
    db.clearAllFiles()
    val (phone, cloud) = db.getTotalCounts()
    assertEquals(phone, 0)
    assertEquals(cloud, 0)
  }
