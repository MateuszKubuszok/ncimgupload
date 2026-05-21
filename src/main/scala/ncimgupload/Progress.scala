package ncimgupload

import java.io.PrintStream

object Progress:
  private val isTerminal = System.console() != null
  var tuiMode: Boolean = false

  def formatSize(bytes: Long): String =
    if bytes < 1024 then s"${bytes} B"
    else if bytes < 1024 * 1024 then f"${bytes / 1024.0}%.1f KB"
    else if bytes < 1024L * 1024 * 1024 then f"${bytes / (1024.0 * 1024)}%.1f MB"
    else f"${bytes / (1024.0 * 1024 * 1024)}%.1f GB"

  def formatDuration(seconds: Long): String =
    if seconds < 60 then s"${seconds}s"
    else if seconds < 3600 then s"${seconds / 60}m ${seconds % 60}s"
    else s"${seconds / 3600}h ${(seconds % 3600) / 60}m"

  def printProgress(current: Int, total: Int, label: String, out: PrintStream = System.err): Unit =
    if isTerminal then
      val pct = if total > 0 then (current * 100) / total else 0
      val barWidth = 30
      val filled = (barWidth * current) / math.max(total, 1)
      val bar = "#" * filled + "-" * (barWidth - filled)
      out.print(s"\r[$bar] $pct% ($current/$total) $label")
      if current >= total then out.println()
    else if current == total then
      out.println(s"  $label: $current/$total done")

  def printUploadProgress(
      bytesSent: Long,
      totalBytes: Long,
      filename: String,
      startTime: Long,
      out: PrintStream = System.err
  ): Unit =
    if isTerminal then
      val pct = if totalBytes > 0 then (bytesSent * 100) / totalBytes else 0
      val barWidth = 30
      val filled = (barWidth.toLong * bytesSent / math.max(totalBytes, 1L)).toInt
      val bar = "#" * filled + "-" * (barWidth - filled)
      val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
      val speed = if elapsed > 0 then bytesSent / elapsed else 0.0
      val speedStr = formatSize(speed.toLong) + "/s"
      val eta = if speed > 0 then ((totalBytes - bytesSent) / speed).toLong else 0L
      out.print(s"\r[$bar] $pct% ${formatSize(bytesSent)}/${formatSize(totalBytes)}  $speedStr  ETA ${formatDuration(eta)}  $filename")
      if bytesSent >= totalBytes then out.println()

  def info(msg: String): Unit = if !tuiMode then println(msg)
  def warn(msg: String): Unit = if !tuiMode then System.err.println(s"WARNING: $msg")
  def error(msg: String): Unit = if !tuiMode then System.err.println(s"ERROR: $msg")
  def verbose(msg: String, isVerbose: Boolean): Unit = if isVerbose && !tuiMode then System.err.println(s"  [debug] $msg")
