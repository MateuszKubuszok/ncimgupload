package ncimgupload

import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.keymap.{KeyMap, BindingReader}
import org.jline.reader.{LineReader, LineReaderBuilder}
import org.jline.utils.InfoCmp.Capability
import java.io.PrintWriter

class Tui extends AutoCloseable:
  val terminal: Terminal = TerminalBuilder.builder()
    .system(true)
    .jansi(false)
    .jna(false)
    .build()

  private val writer: PrintWriter = terminal.writer()

  def width: Int = math.max(terminal.getWidth, 40)
  def height: Int = math.max(terminal.getHeight, 10)

  def close(): Unit = terminal.close()

  // --- ANSI helpers ---

  private val ESC = ""
  val Bold      = s"$ESC[1m"
  val Dim       = s"$ESC[2m"
  val Reset     = s"$ESC[0m"
  val Green     = s"$ESC[32m"
  val Yellow    = s"$ESC[33m"
  val Red       = s"$ESC[31m"
  val Cyan      = s"$ESC[36m"
  val Blue      = s"$ESC[34m"
  val ClearLine = s"$ESC[2K"
  val HideCursor = s"$ESC[?25l"
  val ShowCursor = s"$ESC[?25h"

  private def moveUp(n: Int): String = if n > 0 then s"$ESC[${n}A" else ""
  def moveUpPublic(n: Int): String = moveUp(n)

  // --- Output helpers ---

  def print(s: String): Unit =
    writer.print(s)
    writer.flush()

  def println(s: String = ""): Unit =
    writer.println(s)
    writer.flush()

  def println(): Unit =
    writer.println()
    writer.flush()

  def clearScreen(): Unit =
    writer.print(s"$ESC[2J$ESC[H")
    writer.flush()

  // --- Box drawing ---

  def box(title: String, content: Seq[String]): String =
    val innerWidth = math.max(
      title.length + 2,
      content.map(stripAnsi(_).length).maxOption.getOrElse(0)
    ).min(width - 4)
    val top = s"┌─ $Bold$title$Reset ${s"─" * math.max(0, innerWidth - title.length - 1)}┐"
    val bottom = s"└${"─" * (innerWidth + 2)}┘"
    val lines = content.map { line =>
      val visLen = stripAnsi(line).length
      val padding = math.max(0, innerWidth - visLen)
      s"│ $line${" " * padding} │"
    }
    (top +: lines :+ bottom).mkString("\n")

  def header(title: String): String =
    val line = "─" * math.min(title.length + 4, width)
    s"$Bold$title$Reset\n$Dim$line$Reset"

  // --- Menu selection ---

  def selectMenu(title: String, options: Seq[String], descriptions: Seq[String] = Seq.empty): Int =
    var selected = 0
    var done = false
    val attrs = terminal.enterRawMode()
    val reader = terminal.reader()
    print(HideCursor)
    try
      drawMenu(title, options, descriptions, selected)
      while !done do
        val c = reader.read()
        c match
          case 27 => // ESC sequence
            val next = reader.read()
            if next == '[' then
              reader.read() match
                case 'A' => selected = (selected - 1 + options.size) % options.size // up
                case 'B' => selected = (selected + 1) % options.size               // down
                case _   => ()
            else if next == -1 || next == 27 then
              return -1 // Esc pressed alone
          case 13 | 10 => done = true // Enter
          case 'q' | 'Q' => return -1
          case 'k' | 'K' => selected = (selected - 1 + options.size) % options.size
          case 'j' | 'J' => selected = (selected + 1) % options.size
          case _ => ()
        if !done then
          val totalLines = 1 + options.size + (if descriptions.nonEmpty then options.size else 0)
          print(moveUp(totalLines))
          drawMenu(title, options, descriptions, selected)
    finally
      print(ShowCursor)
      terminal.setAttributes(attrs)
    selected

  private def drawMenu(title: String, options: Seq[String], descriptions: Seq[String], selected: Int): Unit =
    println(s"$Bold$title$Reset")
    for (opt, idx) <- options.zipWithIndex do
      val marker = if idx == selected then s"$Green  ▸ " else "    "
      val style = if idx == selected then Bold else ""
      val reset = Reset
      println(s"$ClearLine$marker$style$opt$reset")
      if descriptions.nonEmpty && idx < descriptions.size && descriptions(idx).nonEmpty then
        val desc = descriptions(idx)
        val indent = if idx == selected then s"$Green      " else s"$Dim      "
        println(s"$ClearLine$indent$desc$Reset")

  // --- Multi-select ---

  def selectMultiple(title: String, options: Seq[String], preselected: Set[Int] = Set.empty): Set[Int] =
    var selected = 0
    var checked = preselected.to(scala.collection.mutable.Set)
    var done = false
    val attrs = terminal.enterRawMode()
    val reader = terminal.reader()
    print(HideCursor)
    try
      drawMultiSelect(title, options, selected, checked.toSet)
      println(s"${Dim}  ↑↓ navigate · space toggle · enter confirm$Reset")
      while !done do
        val c = reader.read()
        c match
          case 27 =>
            val next = reader.read()
            if next == '[' then
              reader.read() match
                case 'A' => selected = (selected - 1 + options.size) % options.size
                case 'B' => selected = (selected + 1) % options.size
                case _   => ()
            else if next == -1 || next == 27 then
              return Set.empty
          case 13 | 10 => done = true
          case ' ' =>
            if checked.contains(selected) then checked -= selected
            else checked += selected
          case 'k' | 'K' => selected = (selected - 1 + options.size) % options.size
          case 'j' | 'J' => selected = (selected + 1) % options.size
          case _ => ()
        if !done then
          val totalLines = 1 + options.size + 1
          print(moveUp(totalLines))
          drawMultiSelect(title, options, selected, checked.toSet)
          println(s"$ClearLine${Dim}  ↑↓ navigate · space toggle · enter confirm$Reset")
    finally
      print(ShowCursor)
      terminal.setAttributes(attrs)
    checked.toSet

  private def drawMultiSelect(title: String, options: Seq[String], cursor: Int, checked: Set[Int]): Unit =
    println(s"$Bold$title$Reset")
    for (opt, idx) <- options.zipWithIndex do
      val marker = if idx == cursor then s"$Green ▸ " else "   "
      val check = if checked.contains(idx) then s"$Green◉$Reset" else s"$Dim○$Reset"
      val style = if idx == cursor then Bold else ""
      println(s"$ClearLine$marker$check $style$opt$Reset")

  // --- Text input ---

  def readLine(prompt: String, default: String = ""): String =
    val lineReader = LineReaderBuilder.builder()
      .terminal(terminal)
      .build()
    val displayPrompt = if default.nonEmpty then s"$Bold$prompt$Reset [$Dim$default$Reset]: " else s"$Bold$prompt$Reset: "
    val result = lineReader.readLine(displayPrompt)
    if result == null || result.trim.isEmpty then default else result.trim

  def readPassword(prompt: String): String =
    val lineReader = LineReaderBuilder.builder()
      .terminal(terminal)
      .build()
    val result = lineReader.readLine(s"$Bold$prompt$Reset: ", '*')
    if result == null then "" else result

  // --- Confirmation ---

  def confirm(prompt: String, default: Boolean = false): Boolean =
    val hint = if default then "Y/n" else "y/N"
    val lineReader = LineReaderBuilder.builder()
      .terminal(terminal)
      .build()
    val result = lineReader.readLine(s"$prompt [$hint]: ")
    if result == null || result.trim.isEmpty then default
    else result.trim.toLowerCase.startsWith("y")

  // --- Progress ---

  def showProgress(label: String, current: Long, total: Long, startTime: Long): Unit =
    val pct = if total > 0 then (current * 100) / total else 0
    val barWidth = math.min(30, width - 40)
    val filled = if total > 0 then (barWidth.toLong * current / total).toInt else 0
    val bar = "█" * filled + "░" * (barWidth - filled)
    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    val speed = if elapsed > 0 then current / elapsed else 0.0
    val eta = if speed > 0 then ((total - current) / speed).toLong else 0L
    val etaStr = if current < total then s" ETA ${Progress.formatDuration(eta)}" else ""
    print(s"\r$ClearLine  $bar ${pct}% ${Progress.formatSize(current)}/${Progress.formatSize(total)}$etaStr  $label")
    if current >= total then println()

  // --- Status display ---

  def showStatus(lines: Seq[(String, String)]): Unit =
    val maxKeyLen = lines.map(_._1.length).maxOption.getOrElse(0)
    for (key, value) <- lines do
      val padding = " " * (maxKeyLen - key.length)
      println(s"  $Dim$key$padding$Reset  $value")

  // --- Spinner / waiting ---

  def waitMessage(message: String): Unit =
    println(s"  $Dim⏳$Reset $message")

  def successMessage(message: String): Unit =
    println(s"  $Green✓$Reset $message")

  def errorMessage(message: String): Unit =
    println(s"  $Red✗$Reset $message")

  def warnMessage(message: String): Unit =
    println(s"  $Yellow⚠$Reset $message")

  def infoMessage(message: String): Unit =
    println(s"  $Cyan▸$Reset $message")

  // --- Utility ---

  private def stripAnsi(s: String): String =
    s.replaceAll("\\[[0-9;]*[a-zA-Z]", "")
