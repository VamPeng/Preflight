package gitviz.ui.tools

import gitviz.git.*
import gitviz.service.*
import gitviz.ui.*
import gitviz.ui.history.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@Composable internal fun PullDialog(cli: GitCli, busy: Boolean, onDismiss: () -> Unit, onFetch: (String) -> Unit, onPull: (PullSettings) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t("Pull settings", "拉取设置")) }, text = {
        Loaded(cli, Modifier.width(520.dp).heightIn(min = 180.dp), { cli.pullOptions() }) { options ->
            var strategy by remember { mutableStateOf(options.strategy) }
            var remote by remember { mutableStateOf(options.upstreamRemote ?: options.remotes.firstOrNull().orEmpty()) }
            var branch by remember { mutableStateOf(options.upstreamBranch ?: options.current) }
            var remoteMenu by remember { mutableStateOf(false) }
            var branchMenu by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(PullStrategy.entries.map { it to when (it) { PullStrategy.MERGE -> "Merge"; PullStrategy.REBASE -> "Rebase"; PullStrategy.FF_ONLY -> "Fast-forward only" } }, strategy, onSelect = { strategy = it })
                Row {
                    DialogButton(t("Remote: ", "远程：") + remote.ifEmpty { t("None", "无") }) { remoteMenu = true }
                    DialogButton(t("Fetch only", "仅获取"), Palette.AccentInk, remote.isNotBlank() && !busy) { onDismiss(); onFetch(remote) }
                }
                Text(t("Upstream branch", "上游分支"), fontSize = TypeScale.Small)
                Row {
                    BasicTextField(branch, { branch = it }, singleLine = true, textStyle = TextStyle(color = Palette.Ink, fontSize = TypeScale.Body), modifier = Modifier.weight(1f).textInputFocus().padding(8.dp))
                    DialogButton(t("Choose…", "选择…")) { branchMenu = true }
                }
                if (options.upstreamRemote == null || options.upstreamBranch == null || remote != options.upstreamRemote || branch != options.upstreamBranch)
                    Text(t("This selection becomes the current branch’s upstream.", "此选择将设为当前分支的上游。"), fontSize = TypeScale.Small, color = Palette.Muted)
                DialogButton(t("Pull", "拉取"), Palette.AccentInk, !busy && remote.isNotBlank() && branch.isNotBlank()) {
                    onDismiss(); onPull(PullSettings(strategy, remote, branch.trim(), remote != options.upstreamRemote || branch.trim() != options.upstreamBranch, options.head, options.current))
                }
            }
            if (remoteMenu) GitMenuPopup(options.remotes.map { name -> MenuItem(name) { remote = name } }, { remoteMenu = false })
            if (branchMenu) GitMenuPopup(options.branches[remote].orEmpty().map { name -> MenuItem(name) { branch = name } }, { branchMenu = false })
        }
    }, confirmButton = {}, dismissButton = { DialogButton(t("Cancel", "取消"), onClick = onDismiss) })
}
