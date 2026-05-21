package ncimgupload

class FolderPicker(webdav: WebDav, tui: Tui):

  def pickFolder(startPath: String = ""): String =
    var currentPath = startPath.stripPrefix("/").stripSuffix("/")
    var done = false
    var result = currentPath

    while !done do
      tui.println()
      tui.println(tui.header("Select destination folder on Nextcloud"))
      tui.println()
      val displayPath = if currentPath.isEmpty then "/" else s"/$currentPath/"
      tui.infoMessage(s"Current: ${tui.Bold}$displayPath${tui.Reset}")
      tui.println()

      tui.waitMessage("Loading folders...")
      val subfolders = try
        webdav.listFolders(if currentPath.isEmpty then "" else currentPath)
      catch
        case e: Exception =>
          tui.errorMessage(s"Failed to list folders: ${e.getMessage}")
          Seq.empty

      val options = scala.collection.mutable.ArrayBuffer[String]()
      val actions = scala.collection.mutable.ArrayBuffer[String]()

      options += s"${tui.Green}✓ Select this folder${tui.Reset}"
      actions += "select"

      if currentPath.nonEmpty then
        options += s"${tui.Dim}⬆  Go up (..)${tui.Reset}"
        actions += "up"

      options += s"${tui.Cyan}+ Create new folder here${tui.Reset}"
      actions += "create"

      for folder <- subfolders.sorted do
        val name = folder.split('/').last
        options += s"📁 $name"
        actions += s"cd:$folder"

      // Erase the "Loading folders..." line
      tui.print(s"\r${tui.ClearLine}")
      tui.print(tui.moveUpPublic(1))
      tui.print(s"\r${tui.ClearLine}")

      val choice = tui.selectMenu("Choose an option:", options.toSeq)

      if choice < 0 then
        result = currentPath
        done = true
      else
        actions(choice) match
          case "select" =>
            result = currentPath
            done = true
          case "up" =>
            currentPath = currentPath.split('/').dropRight(1).mkString("/")
          case "create" =>
            val name = tui.readLine("New folder name")
            if name.nonEmpty then
              val newPath = if currentPath.isEmpty then name else s"$currentPath/$name"
              tui.waitMessage(s"Creating folder /$newPath/ ...")
              if webdav.mkdir(newPath) then
                tui.successMessage(s"Created /$newPath/")
                currentPath = newPath
                result = currentPath
                done = true
              else
                tui.errorMessage(s"Failed to create folder. It may already exist.")
          case s"cd:$folder" =>
            currentPath = folder

    result
