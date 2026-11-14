package gitviz.platform

import gitviz.ui.isMac
import java.io.File

/** 在 Finder（或系统的文件管理器）里显示并选中这个文件或目录。 */
internal fun revealInFileManager(file: File) {
    val desktop = java.awt.Desktop.getDesktop()
    when {
        isMac -> runSystem("open", "-R", file.path)
        desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR) -> desktop.browseFileDirectory(file)
        else -> desktop.open(if (file.isDirectory) file else file.parentFile)
    }
}

internal fun runSystem(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) error(output.trim().ifEmpty { "${command.first()} failed" })
}
