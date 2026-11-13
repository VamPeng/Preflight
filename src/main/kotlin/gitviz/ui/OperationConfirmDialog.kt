package gitviz.ui

import gitviz.git.*
import gitviz.service.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** A commit ID is shown short; a reference by its branch name. */
internal fun targetLabel(target: String): String = when {
    target.matches(Regex("[0-9a-f]{40}")) -> target.take(7)
    else -> target.removePrefix("refs/heads/").removePrefix("refs/remotes/").removePrefix("refs/tags/")
}

/** Progress and completion wording for the feedback banner. */
internal fun operationTitles(intent: OperationIntent): Pair<String, String> = when (intent.kind) {
    PreviewKind.MERGE -> t("Merging", "正在合并") to t("Merged", "已合并")
    PreviewKind.REBASE -> t("Rebasing", "正在变基") to t("Rebased", "已变基")
    PreviewKind.CHERRY_PICK -> t("Cherry-picking", "正在 Cherry-pick") to t("Cherry-picked", "已 Cherry-pick")
    PreviewKind.RESET -> t("Resetting", "正在重置") to t("Reset", "已重置")
}

/**
 * Confirmation before merge, rebase, cherry-pick and reset. The summary comes from read-only commands;
 * confirming runs the operation directly.
 */
@Composable
internal fun OperationConfirmDialog(cli: GitCli, intent: OperationIntent, currentBranch: String?, busy: Boolean,
    onDismiss: () -> Unit, onExecute: (OperationPlan) -> Unit) {
    var mode by remember(intent) { mutableStateOf(intent.mode) }
    var mainline by remember(intent) { mutableStateOf(intent.mainline) }
    var data by remember(intent, mode, mainline) { mutableStateOf<Result<OperationPlan>?>(null) }
    LaunchedEffect(cli, intent, mode, mainline) {
        data = try {
            Result.success(runInterruptible(Dispatchers.IO) { cli.planOperation(intent.copy(mode = mode, mainline = mainline)) })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    val plan = data?.getOrNull()
    val onto = intent.switchBranch ?: currentBranch ?: t("HEAD", "当前提交")
    val target = targetLabel(intent.target)
    val (title, message, confirm) = when (intent.kind) {
        PreviewKind.MERGE -> Triple(t("Merge into $onto", "合并到 $onto"), t("Merge $target into $onto.", "把 $target 合并进 $onto。"), t("Merge", "合并"))
        PreviewKind.REBASE -> Triple(t("Rebase $onto", "变基 $onto"), t("Replay the commits of $onto on top of $target.", "把 $onto 的提交接到 $target 后面。"), t("Rebase", "变基"))
        PreviewKind.CHERRY_PICK -> Triple("Cherry-pick", t("Apply the changes from $target onto $onto.", "把 $target 的改动应用到 $onto 上。"), t("Apply", "应用"))
        PreviewKind.RESET -> Triple(t("Reset $onto", "重置 $onto"), t("Move $onto to $target.", "把 $onto 移到 $target。"), t("Reset", "重置"))
    }
    val danger = plan?.facts?.any { it.tone == FactTone.DANGER } == true
    val shape = RoundedCornerShape(6.dp)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title, fontSize = TypeScale.Title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, fontSize = TypeScale.Body, color = Palette.Muted)
                if (plan != null && intent.kind == PreviewKind.CHERRY_PICK && plan.parentCount > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("Mainline parent", "主父提交"), fontSize = TypeScale.Small, color = Palette.Muted)
                    Segmented((1..plan.parentCount).map { it to "#$it" }, mainline, onSelect = { mainline = it ?: mainline })
                }
                Column(Modifier.fillMaxWidth().background(Palette.Rail, shape).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val current = data
                    when {
                        current == null -> Text(t("Checking…", "正在计算…"), fontSize = TypeScale.Small, color = Palette.Muted)
                        current.isFailure -> Text(current.exceptionOrNull()?.message.orEmpty(), fontSize = TypeScale.Small, color = Palette.Removed)
                        else -> plan?.facts?.forEach { fact ->
                            val color = when (fact.tone) { FactTone.NOTE -> Palette.Muted; FactTone.GOOD -> Palette.Added; FactTone.WARN -> Palette.Modified; FactTone.DANGER -> Palette.Removed }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.padding(top = 6.dp).size(6.dp).background(color, CircleShape))
                                Text(fact.text, fontSize = TypeScale.Small, color = color)
                            }
                        }
                    }
                }
                if (intent.kind == PreviewKind.RESET) for (m in ResetMode.entries) {
                    val on = m == mode
                    Column(
                        Modifier.fillMaxWidth().clip(shape).background(if (on) Palette.Raised else Color.Transparent)
                            .border(1.dp, if (on) Palette.Accent else Palette.Line, shape)
                            .clickable(enabled = !busy) { mode = m }.padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(m.label, fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold)
                        Text(m.hint, fontSize = TypeScale.Small, color = if (m == ResetMode.HARD) Palette.Removed else Palette.Muted)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && plan != null, onClick = { plan?.let(onExecute) }) {
                Text(confirm, color = if (danger) Palette.Removed else Palette.AccentInk)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(t("Cancel", "取消"), color = Palette.Ink) } },
        containerColor = Palette.Field,
        shape = RoundedCornerShape(10.dp),
    )
}
