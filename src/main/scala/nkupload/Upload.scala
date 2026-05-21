package nkupload

import java.time.Instant

class Upload(config: NkConfig, db: Db, adb: Adb, webdav: WebDav):
  private val tmpDir = os.Path(config.dbPath.getParent.resolve("tmp"))

  def uploadMissing(limit: Option[Int], verbose: Boolean): Unit =
    val pending = db.getPendingFiles()
    val toUpload = limit.map(pending.take).getOrElse(pending)

    if toUpload.isEmpty then
      Progress.info("No files to upload. Run 'nkupload scan' first to discover files.")
      return

    val totalSize = toUpload.flatMap(_.phoneSize).sum
    Progress.info(s"${toUpload.size} files to upload (${Progress.formatSize(totalSize)})")
    Progress.info("")

    os.makeDir.all(tmpDir)
    var uploaded = 0
    var failed = 0

    for (record, idx) <- toUpload.zipWithIndex do
      Progress.info(s"[${idx + 1}/${toUpload.size}] ${record.filename} (${Progress.formatSize(record.phoneSize.getOrElse(0L))})")
      db.updateStatus(record.relativePath, SyncRecord.StatusUploading)

      val localTmp = tmpDir / record.filename
      try
        if uploadSingle(record, localTmp, verbose) then
          uploaded += 1
        else
          failed += 1
      catch
        case e: Exception =>
          Progress.error(s"  Unexpected error: ${e.getMessage}")
          db.updateStatus(record.relativePath, SyncRecord.StatusFailed, Some(e.getMessage))
          failed += 1
      finally
        if os.exists(localTmp) then os.remove(localTmp)

    Progress.info("")
    Progress.info(s"Upload complete: $uploaded uploaded, $failed failed out of ${toUpload.size}")

  private def uploadSingle(record: SyncRecord, localTmp: os.Path, verbose: Boolean): Boolean =
    Progress.info("  Pulling from phone...")
    if !adb.pullFile(record.relativePath, localTmp) then
      db.updateStatus(record.relativePath, SyncRecord.StatusFailed, Some("ADB pull failed"))
      return false

    val fileSize = os.size(localTmp)
    Progress.info(s"  Computing checksum...")
    val checksum = Checksums.computeFile(localTmp)
    Progress.verbose(s"  Checksum: $checksum", verbose)

    val cloudDest = s"${config.cloudPath}/${record.filename}"

    config.uploadMode match
      case "sync-folder" => uploadViaSyncFolder(record, localTmp, cloudDest, checksum, verbose)
      case _ => uploadViaWebDav(record, localTmp, cloudDest, checksum, fileSize, verbose)

  private def uploadViaWebDav(record: SyncRecord, localTmp: os.Path, cloudDest: String, checksum: String, fileSize: Long, verbose: Boolean): Boolean =
    val success = if fileSize >= config.chunkThreshold then
      Progress.info(s"  Uploading (chunked, ${((fileSize + config.chunkSize - 1) / config.chunkSize).toInt} chunks)...")
      webdav.chunkedUpload(localTmp, cloudDest, Some(checksum), verbose)
    else
      Progress.info(s"  Uploading to $cloudDest...")
      webdav.putStream(localTmp, cloudDest, Some(checksum))

    if !success then
      db.updateStatus(record.relativePath, SyncRecord.StatusFailed, Some("Upload failed"))
      return false

    if config.verifyChecksum then
      Progress.info("  Verifying...")
      val cloudChecksum = webdav.getChecksum(cloudDest)
      cloudChecksum match
        case Some(cc) if Checksums.verify(localTmp, cc) =>
          Progress.info("  Checksum match OK")
          db.markVerified(record.relativePath, checksum, Instant.now().getEpochSecond)
        case Some(cc) =>
          Progress.warn(s"  Checksum MISMATCH! local=$checksum cloud=$cc")
          db.updateStatus(record.relativePath, SyncRecord.StatusFailed, Some(s"Checksum mismatch: cloud=$cc"))
          return false
        case None =>
          Progress.warn("  Could not retrieve cloud checksum, marking as uploaded (unverified)")
          db.updateStatus(record.relativePath, SyncRecord.StatusUploaded)
    else
      db.updateStatus(record.relativePath, SyncRecord.StatusUploaded)

    true

  private def uploadViaSyncFolder(record: SyncRecord, localTmp: os.Path, cloudDest: String, checksum: String, verbose: Boolean): Boolean =
    config.syncFolder match
      case Some(folder) =>
        val destPath = os.Path(folder) / record.filename
        os.makeDir.all(destPath / os.up)
        Progress.info(s"  Copying to sync folder: $destPath")
        os.copy(localTmp, destPath, replaceExisting = false)
        db.updateStatus(record.relativePath, SyncRecord.StatusUploaded)
        Progress.info("  Placed in sync folder (desktop client will upload)")
        true
      case None =>
        Progress.error("  sync-folder mode selected but paths.sync-folder not configured")
        db.updateStatus(record.relativePath, SyncRecord.StatusFailed, Some("sync-folder not configured"))
        false
