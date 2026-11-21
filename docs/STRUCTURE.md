# 开发与代码结构

面向想要构建、调试或修改 Preflight 的开发者。使用介绍见 [README](../README.zh-CN.md)，功能进度见 [功能路线](ROADMAP.md)。

## 开发环境

做新功能前先看[功能路线](ROADMAP.md)，做完后更新状态；开发约定见 [AGENTS.md](../AGENTS.md)。

需要 JDK 17–21（Android Studio 自带的 JBR 21 就行）和 Git。

```bash
./gradlew classes                          # 只编译，不启动界面
./gradlew test                             # 无窗口测试，包括离屏截图
```

macOS 日常界面调试使用下面的 Dev 安装流程，统一从 `/Applications/Preflight Dev.app` 启动。不要直接打开构建目录中的副本或额外启动 Java 界面进程。

开发运行和开发安装包使用蓝色 `DEV` 图标，窗口名为 `Preflight Dev`；正式安装版使用原来的米白琥珀色图标和 `Preflight` 名称。图标文件位于 `design/icon/`、`packaging/icons/` 和 `src/main/resources/`。

调试写操作用练习仓库，不要用本仓库。脚本会生成带分支、合并冲突、储藏和未提交改动的仓库，`origin` 是本地裸仓库，推送不会碰到 GitHub，弄坏了重新跑一次就恢复：

```bash
./scripts/playground.sh
open "/Applications/Preflight Dev.app" --args "/tmp/preflight-playground"
```

启动参数仅在 Dev 尚未运行时生效；已有窗口可通过「打开仓库」选择练习仓库。

打包安装包：`./gradlew packageDistributionForCurrentOS`

macOS 安装独立开发版（可以和正式版同时运行；复制前先正常退出旧 Dev 并确认进程结束）：

```bash
./gradlew createDistributable -Pdev=true
ditto "build/compose/packages.noindex/dev/main/app/Preflight Dev.app" "/Applications/Preflight Dev.app"
open "/Applications/Preflight Dev.app"
```

打包产物统一放在 `build/compose/packages.noindex/`，避免 Spotlight 把构建副本也列成应用入口；日常只使用“应用程序”里的正式版和开发版。

## 代码结构

源码在 `src/main/kotlin/gitviz/`，按职责分包；测试在 `src/test/kotlin/gitviz/` 下用同样的分包。`git`、`logic`、`service` 三个包里没有界面代码。

| 包 | 文件 | 作用 |
|---|---|---|
| 根目录 | `Main.kt` | 入口、主窗口和各页面的切换 |
| `git/` | `GitRepo.kt` | git 读取层（JGit）：提交、引用、改动文件、diff |
| | `GitCli.kt` | 调用系统 git 执行所有写操作 |
| | `GitProcess.kt`、`Operations.kt` | 字节输入输出、执行结果核对、仓库写入协调与操作事务 |
| | `OperationPreview.kt`、`Rebase.kt` | 操作前的只读后果摘要与直接执行、rebase 暂停流程 |
| | `ReferenceComparison.kt`、`BlameTrace.kt` | 固定 OID 的分支比较与来源行连续追溯 |
| | `PatchDocument.kt` | 完整补丁与部分暂存 |
| | `Conflicts.kt`、`ConflictSources.kt` | 解析冲突标记、冲突各方的来源 |
| | `Teaching.kt` | 关键操作命令记录、解释与敏感参数脱敏 |
| | `Insights.kt`、`InteractiveRebase.kt`、`Pull.kt` | 历史统计、线性提交编辑与受控 todo、拉取策略 |
| | `Worktrees.kt`、`Submodules.kt`、`Lfs.kt` | 工作树、子模块及其操作边界；LFS 指针识别 |
| | `TemporaryFiles.kt` | 清理临时文件，不跟随符号链接 |
| `logic/` | `GraphLayout.kt` | 分支图布局：给每个提交分配列，算出线怎么连 |
| | `DiffLines.kt`、`DiffHighlight.kt` | 给 diff 逐行分类；完整源码分词和高亮缓存 |
| | `RefTree.kt`、`FileTree.kt` | 把引用名按 `/` 拆成树；读工作区目录和每个文件的 git 状态 |
| | `ChangeTree.kt` | 改动文件按文件夹分层、合并中间层、收起，以及勾选状态 |
| | `Readme.kt`、`Markdown.kt` | 读取预览用的文本文件、找工作区的 README；轻量 Markdown 解析 |
| `service/` | `ServiceHttp.kt` | 有超时、大小限制和取消的 HTTP 请求 |
| | `Ai.kt` | 受限改动快照、模型协议 |
| `platform/` | `Vibrancy.kt`、`WindowSize.kt` | macOS 毛玻璃窗口、窗口尺寸保存与恢复 |
| | `WorkTreeWatcher.kt`、`DirectoryChooser.kt`、`FileManager.kt` | 工作区文件监听、系统目录选择框、在 Finder 中显示 |
| `ui/` | `Theme.kt`、`Components.kt`、`Logo.kt` | 浅色和深色配色、字号、字体；图标、按钮、头像这些通用组件 |
| | `AppBars.kt`、`Welcome.kt` | 工具栏、搜索框、侧边栏条目、状态栏；欢迎页 |
| | `Commands.kt` | 共享命令可用状态和快捷键 |
| | `GitMenus.kt`、`OperationConfirmDialog.kt` | 提交和分支的右键菜单、确认对话框 |
| | `NetBanner.kt` | 通用操作进度、结果、警告和失败反馈，以及完整输出和复制 |
| | `Settings.kt` | 用户选项、界面语言、最近的仓库、设置页 |
| `ui/history/` | `CommitList.kt`、`GraphCell.kt` | 提交列表、每一行的分支图 |
| | `CommitHistoryLayout.kt`、`CommitHistoryScrollbar.kt` | 提交列表底层、可展开和拖动的底部详情浮层、滚动条 |
| | `CommitDetail.kt` | 提交详情、diff（可选中复制）、文件历史、Blame |
| | `Sidebar.kt`、`SidebarGroups.kt`、`BranchFilterBar.kt`、`BranchDrag.kt` | 左侧引用树面板、工作树和子模块分组、分支过滤、分支拖拽 |
| `ui/changes/` | `ChangesView.kt`、`PartialStageView.kt` | 本地改动（勾选、批量操作、平铺／按文件夹）、提交说明、按行暂存 |
| | `StashView.kt` | 储藏列表和内容 |
| | `ConflictView.kt`、`ConflictEditor.kt` | 解决冲突的界面 |
| `ui/preview/` | `PreviewArea.kt`、`TextDocumentView.kt` | 占满正文区域的预览区（标题栏和关闭按钮）；可选中复制的 Markdown／纯文本渲染。以后的文件预览也放这里 |
| `ui/tools/` | `FilesView.kt`、`ReflogView.kt` | 文件页、引用日志 |
| | `InsightsView.kt`、`InteractiveRebaseView.kt` | 洞察、交互式变基 |
| | `ComparisonView.kt`、`BlameTraceView.kt`、`PullDialog.kt` | 分支比较、来源追溯、拉取选项 |
| | `AiView.kt` | AI 提交草稿与发送确认 |

界面原型在 `docs/prototype/`，改界面前先看那里。`ScreenshotTest` 会把主界面渲染成图片存到 `build/screens/`。

git 层和界面层是分开的，以后换界面框架也能保留 `git/` 和 `logic/` 两个包。

## 实现细节与边界

AI 提交草稿和 Reflog 暂时隐藏日常入口，保留实现，待打磨后再开放。时间轴回放、托管平台（GitHub／GitLab）、Subtree、LFS 页和 AI 分支总结已于 2026-11-13 删除，需要时从 git 历史找回。

待办与验证缺口见 [PLAN.md](PLAN.md)。交互式 rebase 只编辑当前本地分支的线性区间，最多 500 条，基准提交本身不参与编辑。

工作树和子模块只在手动操作时写入。工作树移除不会删除分支，也不允许移除含忽略文件或其他本地改动的目录；存在 `assume-unchanged`／`skip-worktree` 索引标志时，即使表面干净也拒绝移除，需先检查文件并处理标志。目录已被手动删除的工作树标为「目录缺失」，不在应用里移除，可在终端运行 `git worktree prune` 清理记录。子模块更新到父仓库暂存区记录的提交，默认不递归；已初始化子模块更新前保留 `refs/preflight/submodule-before/…`，恢复需在子模块内处理。Diff 遇到 Git LFS 指针文件时只显示指针信息，不读取大对象。

AI 需要手动开启，填写模型、完整 API 端点和令牌，选择 Responses API 或 Chat Completions 兼容协议。连接仅保存在应用会话中，更换端点会清除令牌。准备快照不会发送请求；检查／编辑输入并勾选发送确认后，点击生成才会发送到所选服务，可能产生服务费用。提交草稿仅使用暂存内容。单次最多 200 个差异路径、每个文本 blob 64 KiB、输入 120,000 字符，省略部分会明确标注。已有草稿需确认替换，结果不会自动执行 Git 操作。

AI 协议、超时与取消已完成本地测试；真实模型服务尚未联调。

一些实现上的限制：

- 提交历史每页加载 2000 条（`GitRepo.PAGE_SIZE`），滚动到底部时继续加载
- Diff 高亮支持 Kotlin、Java、JavaScript、TypeScript、JSON、Python；后台按完整旧／新源码分词，单侧超过 1 MiB 或 20,000 行回退纯文本，缓存上限 32 MiB
- 部分暂存时，超过 1 MiB、二进制等文件只读显示 diff，只能整文件暂存
- 操作确认里的预计冲突文件依赖 `git merge-tree`（Git 2.38+）
- 写操作前不做备份，误操作通过 reflog 找回提交
