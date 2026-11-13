package gitviz.platform

import gitviz.ui.isMac
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

internal fun chooseDirectory(title: String, startDir: File? = null): File? {
    if (isMac) {
        // macOS 用系统的 NSOpenPanel：有侧边栏和搜索，跟随系统深色模式和语言
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        try {
            startDir?.takeIf { it.isDirectory }?.let { dialog.directory = it.path }
            dialog.isVisible = true
            val name = dialog.file ?: return null
            return File(dialog.directory, name)
        } finally {
            dialog.dispose()
            System.clearProperty("apple.awt.fileDialogForDirectories")
        }
    }
    val chooser = JFileChooser(startDir?.takeIf { it.isDirectory }).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
