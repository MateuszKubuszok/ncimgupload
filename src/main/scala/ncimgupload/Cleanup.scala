package ncimgupload

import java.io.{FileWriter, PrintWriter}
import java.nio.file.Path
import java.time.{Instant, LocalDate, ZoneId}

class Cleanup(config: NkConfig, db: Db, adb: Adb, webdav: WebDav):
  private val logPath = config.dbPath.getParent.resolve("cleanup.log")

  def run(beforeDate: LocalDate, dryRun: Boolean, verifiedOnly: Boolean, verbose: Boolean): Unit =
    val beforeEpoch = beforeDate.atStartOfDay(ZoneId.systemDefault()).toEpochSecond
    val candidates = if verifiedOnly then
      db.getVerifiedFilesBeforeDate(beforeEpoch).filter(_.uploadStatus == SyncRecord.StatusVerified)
    else
      db.getVerifiedFilesBeforeDate(beforeEpoch)

    if candidates.isEmpty then
      Progress.info("No files eligible for cleanup.")
      return

    val totalSize = candidates.flatMap(_.phoneSize).sum
    val oldest = candidates.minByOption(_.phoneMtime.getOrElse(Long.MaxValue))
    val newest = candidates.maxByOption(_.phoneMtime.getOrElse(0L))

    def formatDate(epoch: Option[Long]): String =
      epoch.map(e => Instant.ofEpochSecond(e).atZone(ZoneId.systemDefault()).toLocalDate.toString).getOrElse("unknown")

    Progress.info("")
    if dryRun then
      Progress.info("DRY RUN (use --yes to actually delete)")
      Progress.info("")

    Progress.info(s"Files eligible for cleanup (${if verifiedOnly then "verified in cloud" else "uploaded to cloud"}, created before $beforeDate):")
    Progress.info(s"  ${candidates.size} files (${Progress.formatSize(totalSize)})")
    Progress.info(s"  Oldest: ${oldest.map(_.filename).getOrElse("?")} (${formatDate(oldest.flatMap(_.phoneMtime))})")
    Progress.info(s"  Newest: ${newest.map(_.filename).getOrElse("?")} (${formatDate(newest.flatMap(_.phoneMtime))})")

    if dryRun then
      Progress.info("")
      Progress.info("To delete these files from your phone, run:")
      Progress.info(s"  ncimgupload cleanup --before $beforeDate --yes")
      return

    Progress.info("")
    print(s"Delete ${candidates.size} files (${Progress.formatSize(totalSize)}) from phone? [y/N] ")
    val answer = scala.io.StdIn.readLine()
    if answer == null || !answer.trim.toLowerCase.startsWith("y") then
      Progress.info("Aborted.")
      return

    var deleted = 0
    var skipped = 0
    val logWriter = new PrintWriter(new FileWriter(logPath.toFile, true))

    try
      for (record, idx) <- candidates.zipWithIndex do
        Progress.printProgress(idx + 1, candidates.size, record.filename)

        val stillInCloud = webdav.exists(s"${config.cloudPath}/${record.filename}")
        if !stillInCloud then
          Progress.warn(s"  ${record.filename} no longer in cloud, skipping deletion")
          logWriter.println(s"${Instant.now()} SKIPPED ${record.relativePath} (not in cloud)")
          skipped += 1
        else if adb.deleteFile(record.relativePath) then
          logWriter.println(s"${Instant.now()} DELETED ${record.relativePath} size=${record.phoneSize.getOrElse(0)} verified=${record.uploadStatus}")
          deleted += 1
        else
          Progress.warn(s"  Failed to delete ${record.filename} from phone")
          logWriter.println(s"${Instant.now()} FAILED ${record.relativePath}")
          skipped += 1
    finally logWriter.close()

    Progress.info("")
    Progress.info(s"Cleanup complete: $deleted deleted, $skipped skipped")
    Progress.info(s"Deletion log: $logPath")
