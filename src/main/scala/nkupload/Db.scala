package nkupload

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.util.Using

class Db(path: Path):
  Files.createDirectories(path.getParent)
  private val url = s"jdbc:sqlite:${path.toAbsolutePath}"

  private def withConnection[A](f: Connection => A): A =
    val conn = DriverManager.getConnection(url)
    try
      conn.setAutoCommit(true)
      f(conn)
    finally conn.close()

  def init(): Unit = withConnection { conn =>
    val stmt = conn.createStatement()
    stmt.executeUpdate("""
      CREATE TABLE IF NOT EXISTS files (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        relative_path TEXT NOT NULL UNIQUE,
        filename TEXT NOT NULL,
        phone_size INTEGER,
        phone_mtime INTEGER,
        cloud_size INTEGER,
        cloud_etag TEXT,
        cloud_file_id INTEGER,
        cloud_checksum TEXT,
        upload_status TEXT DEFAULT 'pending',
        upload_started_at INTEGER,
        upload_finished_at INTEGER,
        verified_at INTEGER,
        last_error TEXT,
        scan_phone_at INTEGER,
        scan_cloud_at INTEGER
      )
    """)
    stmt.executeUpdate(
      "CREATE INDEX IF NOT EXISTS idx_files_status ON files(upload_status)"
    )
    stmt.executeUpdate(
      "CREATE INDEX IF NOT EXISTS idx_files_filename ON files(filename)"
    )
    stmt.executeUpdate("""
      CREATE TABLE IF NOT EXISTS scan_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        source TEXT NOT NULL,
        started_at INTEGER NOT NULL,
        finished_at INTEGER,
        file_count INTEGER,
        path_scanned TEXT
      )
    """)
    stmt.executeUpdate("""
      CREATE TABLE IF NOT EXISTS chunked_uploads (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        file_id INTEGER REFERENCES files(id),
        upload_id TEXT NOT NULL,
        total_chunks INTEGER,
        completed_chunks INTEGER DEFAULT 0,
        started_at INTEGER,
        status TEXT DEFAULT 'in_progress'
      )
    """)
    stmt.close()
  }

  def upsertPhoneFile(entry: FileEntry, now: Long): Unit = withConnection { conn =>
    val ps = conn.prepareStatement("""
      INSERT INTO files (relative_path, filename, phone_size, phone_mtime, scan_phone_at, upload_status)
      VALUES (?, ?, ?, ?, ?, 'pending')
      ON CONFLICT(relative_path) DO UPDATE SET
        phone_size = excluded.phone_size,
        phone_mtime = excluded.phone_mtime,
        scan_phone_at = excluded.scan_phone_at
    """)
    ps.setString(1, entry.relativePath)
    ps.setString(2, entry.filename)
    ps.setLong(3, entry.sizeBytes)
    ps.setLong(4, entry.mtimeEpoch)
    ps.setLong(5, now)
    ps.executeUpdate()
    ps.close()
  }

  def upsertCloudFile(entry: FileEntry, etag: Option[String], fileId: Option[Long], checksum: Option[String], now: Long): Unit =
    withConnection { conn =>
      val ps = conn.prepareStatement("""
        INSERT INTO files (relative_path, filename, cloud_size, cloud_etag, cloud_file_id, cloud_checksum, scan_cloud_at, upload_status)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'uploaded')
        ON CONFLICT(relative_path) DO UPDATE SET
          cloud_size = excluded.cloud_size,
          cloud_etag = excluded.cloud_etag,
          cloud_file_id = excluded.cloud_file_id,
          cloud_checksum = excluded.cloud_checksum,
          scan_cloud_at = excluded.scan_cloud_at,
          upload_status = CASE
            WHEN files.upload_status = 'verified' THEN 'verified'
            ELSE 'uploaded'
          END
      """)
      ps.setString(1, entry.relativePath)
      ps.setString(2, entry.filename)
      ps.setLong(3, entry.sizeBytes)
      etag.foreach(ps.setString(4, _)); if etag.isEmpty then ps.setNull(4, java.sql.Types.VARCHAR)
      fileId.foreach(ps.setLong(5, _)); if fileId.isEmpty then ps.setNull(5, java.sql.Types.BIGINT)
      checksum.foreach(ps.setString(6, _)); if checksum.isEmpty then ps.setNull(6, java.sql.Types.VARCHAR)
      ps.setLong(7, now)
      ps.executeUpdate()
      ps.close()
    }

  def markCloudFileByFilenameSize(filename: String, sizeBytes: Long, etag: Option[String], fileId: Option[Long], checksum: Option[String], now: Long): Int =
    withConnection { conn =>
      val ps = conn.prepareStatement("""
        UPDATE files SET
          cloud_size = ?,
          cloud_etag = ?,
          cloud_file_id = ?,
          cloud_checksum = ?,
          scan_cloud_at = ?,
          upload_status = CASE
            WHEN upload_status = 'verified' THEN 'verified'
            ELSE 'uploaded'
          END
        WHERE filename = ? AND phone_size = ? AND cloud_size IS NULL
      """)
      ps.setLong(1, sizeBytes)
      etag.foreach(ps.setString(2, _)); if etag.isEmpty then ps.setNull(2, java.sql.Types.VARCHAR)
      fileId.foreach(ps.setLong(3, _)); if fileId.isEmpty then ps.setNull(3, java.sql.Types.BIGINT)
      checksum.foreach(ps.setString(4, _)); if checksum.isEmpty then ps.setNull(4, java.sql.Types.VARCHAR)
      ps.setLong(5, now)
      ps.setString(6, filename)
      ps.setLong(7, sizeBytes)
      val updated = ps.executeUpdate()
      ps.close()
      updated
    }

  def updateStatus(relativePath: String, status: String, error: Option[String] = None): Unit =
    withConnection { conn =>
      val ps = conn.prepareStatement(
        "UPDATE files SET upload_status = ?, last_error = ? WHERE relative_path = ?"
      )
      ps.setString(1, status)
      error.foreach(ps.setString(2, _)); if error.isEmpty then ps.setNull(2, java.sql.Types.VARCHAR)
      ps.setString(3, relativePath)
      ps.executeUpdate()
      ps.close()
    }

  def markVerified(relativePath: String, checksum: String, now: Long): Unit =
    withConnection { conn =>
      val ps = conn.prepareStatement(
        "UPDATE files SET upload_status = 'verified', cloud_checksum = ?, verified_at = ? WHERE relative_path = ?"
      )
      ps.setString(1, checksum)
      ps.setLong(2, now)
      ps.setString(3, relativePath)
      ps.executeUpdate()
      ps.close()
    }

  def getPendingFiles(): Seq[SyncRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM files WHERE upload_status IN ('pending', 'failed') AND phone_size IS NOT NULL AND cloud_size IS NULL ORDER BY phone_mtime ASC"
    )
    readRecords(ps)
  }

  def getVerifiedFilesBeforeDate(beforeEpoch: Long): Seq[SyncRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM files WHERE upload_status IN ('verified', 'uploaded') AND phone_mtime IS NOT NULL AND phone_mtime < ? ORDER BY phone_mtime ASC"
    )
    ps.setLong(1, beforeEpoch)
    readRecords(ps)
  }

  def getStatusCounts(): Map[String, Int] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT upload_status, COUNT(*) FROM files GROUP BY upload_status"
    )
    val rs = ps.executeQuery()
    val builder = Map.newBuilder[String, Int]
    while rs.next() do
      builder += rs.getString(1) -> rs.getInt(2)
    rs.close()
    ps.close()
    builder.result()
  }

  def getTotalCounts(): (Int, Int) = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT COUNT(CASE WHEN phone_size IS NOT NULL THEN 1 END), COUNT(CASE WHEN cloud_size IS NOT NULL THEN 1 END) FROM files"
    )
    val rs = ps.executeQuery()
    val result = if rs.next() then (rs.getInt(1), rs.getInt(2)) else (0, 0)
    rs.close()
    ps.close()
    result
  }

  def getLastScan(source: String): Option[Long] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT finished_at FROM scan_history WHERE source = ? ORDER BY finished_at DESC LIMIT 1"
    )
    ps.setString(1, source)
    val rs = ps.executeQuery()
    val result = if rs.next() then Some(rs.getLong(1)) else None
    rs.close()
    ps.close()
    result
  }

  def recordScanStart(source: String, path: String, now: Long): Long = withConnection { conn =>
    val ps = conn.prepareStatement(
      "INSERT INTO scan_history (source, started_at, path_scanned) VALUES (?, ?, ?)",
      java.sql.Statement.RETURN_GENERATED_KEYS
    )
    ps.setString(1, source)
    ps.setLong(2, now)
    ps.setString(3, path)
    ps.executeUpdate()
    val keys = ps.getGeneratedKeys
    val id = if keys.next() then keys.getLong(1) else -1L
    keys.close()
    ps.close()
    id
  }

  def recordScanFinish(scanId: Long, fileCount: Int, now: Long): Unit = withConnection { conn =>
    val ps = conn.prepareStatement(
      "UPDATE scan_history SET finished_at = ?, file_count = ? WHERE id = ?"
    )
    ps.setLong(1, now)
    ps.setInt(2, fileCount)
    ps.setLong(3, scanId)
    ps.executeUpdate()
    ps.close()
  }

  def clearAllFiles(): Unit = withConnection { conn =>
    conn.createStatement().executeUpdate("DELETE FROM files")
    conn.createStatement().executeUpdate("DELETE FROM scan_history")
    conn.createStatement().executeUpdate("DELETE FROM chunked_uploads")
  }

  private def readRecords(ps: PreparedStatement): Seq[SyncRecord] =
    val rs = ps.executeQuery()
    val builder = Seq.newBuilder[SyncRecord]
    while rs.next() do
      builder += readRecord(rs)
    rs.close()
    ps.close()
    builder.result()

  private def readRecord(rs: ResultSet): SyncRecord =
    SyncRecord(
      id = rs.getLong("id"),
      relativePath = rs.getString("relative_path"),
      filename = rs.getString("filename"),
      phoneSize = Option(rs.getLong("phone_size")).filterNot(_ => rs.wasNull()),
      phoneMtime = Option(rs.getLong("phone_mtime")).filterNot(_ => rs.wasNull()),
      cloudSize = Option(rs.getLong("cloud_size")).filterNot(_ => rs.wasNull()),
      cloudEtag = Option(rs.getString("cloud_etag")),
      cloudFileId = Option(rs.getLong("cloud_file_id")).filterNot(_ => rs.wasNull()),
      cloudChecksum = Option(rs.getString("cloud_checksum")),
      uploadStatus = rs.getString("upload_status"),
      verifiedAt = Option(rs.getLong("verified_at")).filterNot(_ => rs.wasNull()),
      lastError = Option(rs.getString("last_error")),
      scanPhoneAt = Option(rs.getLong("scan_phone_at")).filterNot(_ => rs.wasNull()),
      scanCloudAt = Option(rs.getLong("scan_cloud_at")).filterNot(_ => rs.wasNull())
    )
