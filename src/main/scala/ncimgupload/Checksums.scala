package ncimgupload

import java.io.{BufferedInputStream, FileInputStream}
import java.security.MessageDigest

object Checksums:
  private val bufferSize = 8 * 1024 * 1024

  def computeFile(path: os.Path, algo: String = "SHA-256"): String =
    val digest = MessageDigest.getInstance(algo)
    val fis = new BufferedInputStream(new FileInputStream(path.toIO), bufferSize)
    try
      val buffer = new Array[Byte](bufferSize)
      var bytesRead = fis.read(buffer)
      while bytesRead >= 0 do
        digest.update(buffer, 0, bytesRead)
        bytesRead = fis.read(buffer)
      val hexDigest = digest.digest().map(b => f"$b%02x").mkString
      s"${algoLabel(algo)}:$hexDigest"
    finally fis.close()

  def computeBytes(data: Array[Byte], algo: String = "SHA-256"): String =
    val digest = MessageDigest.getInstance(algo)
    digest.update(data)
    val hexDigest = digest.digest().map(b => f"$b%02x").mkString
    s"${algoLabel(algo)}:$hexDigest"

  def parse(checksumStr: String): Option[(String, String)] =
    val idx = checksumStr.indexOf(':')
    if idx > 0 then
      Some((checksumStr.substring(0, idx).toUpperCase, checksumStr.substring(idx + 1)))
    else None

  def verify(localPath: os.Path, cloudChecksum: String): Boolean =
    parse(cloudChecksum) match
      case Some((algo, _)) =>
        val javaAlgo = algo match
          case "SHA256" | "SHA-256" => "SHA-256"
          case "MD5" => "MD5"
          case "SHA1" | "SHA-1" => "SHA-1"
          case other => other
        val localChecksum = computeFile(localPath, javaAlgo)
        localChecksum.equalsIgnoreCase(cloudChecksum)
      case None => false

  private def algoLabel(javaAlgo: String): String = javaAlgo match
    case "SHA-256" => "SHA256"
    case "SHA-1" => "SHA1"
    case other => other
