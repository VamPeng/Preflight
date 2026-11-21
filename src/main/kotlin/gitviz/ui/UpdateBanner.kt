package gitviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gitviz.service.AvailableUpdate

/** 沿用操作反馈卡片样式，单独保存更新状态，不覆盖 Git 操作的反馈。 */
@Composable
internal fun UpdateBanner(update: AvailableUpdate, onDownload: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.widthIn(max = 360.dp).shadow(8.dp, shape).clip(shape)
        .background(Palette.Field).border(1.dp, Palette.Link.copy(alpha = 0.4f), shape)
        .padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("Preflight ${update.version} is available", "发现新版本 Preflight ${update.version}"),
            color = Palette.Ink, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold)
        Text(t("Download the new version from the website.", "前往官网下载新版安装包。"),
            color = Palette.Muted, fontSize = TypeScale.Small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(t("Download", "去下载"), Palette.AccentInk, onClick = onDownload)
            DialogButton(t("Later", "稍后"), onClick = onDismiss)
        }
    }
}
