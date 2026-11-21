package gitviz.ui.preview

import gitviz.logic.*
import gitviz.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 正文区域里要预览的内容。打开仓库时的 README 是第一种；
 * 以后预览别的文件（图片、二进制说明等）在这里加一种，再在 [PreviewArea] 里加一个分支。
 */
sealed interface PreviewContent {
    /** 标题栏左侧显示的名字，一般是相对工作区的路径。 */
    val title: String

    class Document(val document: TextDocument) : PreviewContent {
        override val title get() = document.path
    }
}

/**
 * 预览区：占满工具栏以下的整个正文区域。顶部一行左边文件名、右边关闭按钮，下面是内容。
 * 内容里的文字都能选中复制。
 */
@Composable
internal fun PreviewArea(content: PreviewContent, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(Palette.Ground)) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.File, Palette.Muted)
            Text(content.title, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Hint(t("Close preview", "关闭预览")) { IconButton(Icons.Close, onClick = onClose) }
        }
        HorizontalDivider()
        val body = Modifier.fillMaxWidth().weight(1f)
        when (content) {
            is PreviewContent.Document -> TextDocumentView(content.document, body)
        }
    }
}
