package nkupload

import java.time.Instant

case class FileEntry(
    relativePath: String,
    filename: String,
    sizeBytes: Long,
    mtimeEpoch: Long,
    checksum: Option[String],
    source: String
)

case class SyncRecord(
    id: Long,
    relativePath: String,
    filename: String,
    phoneSize: Option[Long],
    phoneMtime: Option[Long],
    cloudSize: Option[Long],
    cloudEtag: Option[String],
    cloudFileId: Option[Long],
    cloudChecksum: Option[String],
    uploadStatus: String,
    verifiedAt: Option[Long],
    lastError: Option[String],
    scanPhoneAt: Option[Long],
    scanCloudAt: Option[Long]
)

object SyncRecord:
  val StatusPending   = "pending"
  val StatusUploading = "uploading"
  val StatusUploaded  = "uploaded"
  val StatusVerified  = "verified"
  val StatusFailed    = "failed"

case class DiffResult(
    missingFromCloud: Seq[FileEntry],
    missingFromPhone: Seq[FileEntry],
    matched: Seq[(FileEntry, FileEntry)],
    sizeMismatch: Seq[(FileEntry, FileEntry)]
)

case class ChunkedUploadState(
    id: Long,
    fileId: Long,
    uploadId: String,
    totalChunks: Int,
    completedChunks: Int,
    startedAt: Long,
    status: String
)
