package gitviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
internal fun Welcome(loading: Boolean, recent: List<String>, onRecent: (String) -> Unit, onOpen: () -> Unit, onClone: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator()
        } else {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GitVizMark(size = 40.dp)
                        Text("Preflight", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(t("Choose a local git repository to see its branches and commits.", "选择一个本地 git 仓库，查看它的分支和提交。"), fontSize = TypeScale.Body, color = Palette.Muted)
                }
                val shape = RoundedCornerShape(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        t("Open repository…", "打开仓库…"), fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, color = Palette.OnAccent,
                        modifier = Modifier.clip(shape).background(Palette.Accent, shape).clickable(onClick = onOpen)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        t("Clone repository", "克隆仓库"), fontSize = TypeScale.Body,
                        modifier = Modifier.clip(shape).border(1.dp, Palette.Line, shape).clickable(onClick = onClone)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (recent.isNotEmpty()) {
                    Column {
                        HorizontalDivider()
                        Text(t("Recent", "最近打开"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.padding(vertical = 8.dp))
                        for (path in recent) {
                            Row(
                                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(5.dp)).hoverRow()
                                    .clickable { onRecent(path) }.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(File(path).name, fontSize = TypeScale.Body, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(path, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Faint, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
