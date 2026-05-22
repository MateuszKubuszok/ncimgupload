package ncimgupload

object Sync:
  def diff(phoneFiles: Seq[FileEntry], cloudFiles: Seq[FileEntry]): DiffResult =
    val cloudByNameSize: Map[(String, Long), FileEntry] =
      cloudFiles.groupBy(f => (f.filename.toLowerCase, f.sizeBytes))
        .view.mapValues(_.head).toMap

    val cloudByName: Map[String, Seq[FileEntry]] =
      cloudFiles.groupBy(_.filename.toLowerCase)

    val missingFromCloud = Seq.newBuilder[FileEntry]
    val matched = Seq.newBuilder[(FileEntry, FileEntry)]
    val sizeMismatch = Seq.newBuilder[(FileEntry, FileEntry)]
    val strippedMetadata = Seq.newBuilder[(FileEntry, FileEntry)]
    val matchedCloudPaths = scala.collection.mutable.Set.empty[String]

    for phoneFile <- phoneFiles do
      val key = (phoneFile.filename.toLowerCase, phoneFile.sizeBytes)
      cloudByNameSize.get(key) match
        case Some(cloudFile) =>
          matched += ((phoneFile, cloudFile))
          matchedCloudPaths += cloudFile.relativePath
        case None =>
          cloudByName.get(phoneFile.filename.toLowerCase) match
            case Some(cloudMatches) =>
              val cloudFile = cloudMatches.head
              if cloudFile.sizeBytes == 0L then
                // Size unknown (e.g., Memories API) — match by filename, count as backed up
                matched += ((phoneFile, cloudFile))
              else if phoneFile.sizeBytes > cloudFile.sizeBytes then
                strippedMetadata += ((phoneFile, cloudFile))
              else
                sizeMismatch += ((phoneFile, cloudFile))
              matchedCloudPaths += cloudFile.relativePath
            case None =>
              missingFromCloud += phoneFile

    val missingFromPhone = cloudFiles.filterNot(f => matchedCloudPaths.contains(f.relativePath))

    DiffResult(
      missingFromCloud = missingFromCloud.result(),
      missingFromPhone = missingFromPhone,
      matched = matched.result(),
      sizeMismatch = sizeMismatch.result(),
      strippedMetadata = strippedMetadata.result()
    )

  def printDiffSummary(result: DiffResult): Unit =
    val totalPhoneSize = result.missingFromCloud.map(_.sizeBytes).sum
    val totalMatchedSize = result.matched.map(_._1.sizeBytes).sum

    Progress.info("")
    Progress.info(s"=== Missing from cloud (need upload) ===")
    Progress.info(s"  ${result.missingFromCloud.size} files (${Progress.formatSize(totalPhoneSize)})")
    if result.missingFromCloud.nonEmpty then
      Progress.info("  Recent:")
      result.missingFromCloud.sortBy(-_.mtimeEpoch).take(5).foreach { f =>
        val date = java.time.Instant.ofEpochSecond(f.mtimeEpoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate
        Progress.info(s"    ${f.filename}   ${Progress.formatSize(f.sizeBytes)}  $date")
      }
      if result.missingFromCloud.size > 5 then
        Progress.info(s"    ... and ${result.missingFromCloud.size - 5} more")

    if result.strippedMetadata.nonEmpty then
      Progress.info("")
      Progress.info(s"=== Stripped metadata (phone has more data than cloud) ===")
      Progress.info(s"  ${result.strippedMetadata.size} files — likely uploaded without EXIF/GPS data")
      result.strippedMetadata.take(10).foreach { case (phone, cloud) =>
        val diff = phone.sizeBytes - cloud.sizeBytes
        Progress.info(s"    ${phone.filename}  phone: ${Progress.formatSize(phone.sizeBytes)}  cloud: ${Progress.formatSize(cloud.sizeBytes)}  (+${Progress.formatSize(diff)} missing)")
      }
      if result.strippedMetadata.size > 10 then
        Progress.info(s"    ... and ${result.strippedMetadata.size - 10} more")

    if result.sizeMismatch.nonEmpty then
      Progress.info("")
      Progress.info(s"=== Size mismatch (cloud larger than phone) ===")
      Progress.info(s"  ${result.sizeMismatch.size} files")
      result.sizeMismatch.take(10).foreach { case (phone, cloud) =>
        val diff = math.abs(phone.sizeBytes - cloud.sizeBytes)
        Progress.info(s"    ${phone.filename}  phone: ${phone.sizeBytes}  cloud: ${cloud.sizeBytes}  (diff: $diff bytes)")
      }

    Progress.info("")
    Progress.info(s"=== Matched (on both phone and cloud) ===")
    Progress.info(s"  ${result.matched.size} files (${Progress.formatSize(totalMatchedSize)})")

    if result.missingFromPhone.nonEmpty then
      Progress.info("")
      Progress.info(s"=== On cloud only (already deleted from phone) ===")
      Progress.info(s"  ${result.missingFromPhone.size} files")
