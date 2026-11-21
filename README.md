<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="design/icon/gitviz-dark.svg">
    <img src="design/icon/gitviz-light.svg" width="112" alt="GitViz 图标">
  </picture>
</p>

<h1 align="center">GitViz</h1>

<p align="center">
  一个看得清分支、也敢放心操作的 Git 图形客户端。<br>
  Kotlin + Compose Desktop + JGit 编写，主要面向 macOS。
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/platform-macOS-lightgrey" alt="Platform: macOS">
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.1">
  <img src="https://img.shields.io/badge/Compose_Desktop-1.8-4285F4" alt="Compose Desktop 1.8">
  <img src="https://img.shields.io/badge/status-开发中-yellow" alt="开发中">
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="site/introduction/img/main-dark.png">
    <img src="site/introduction/img/main-light.png" width="900" alt="GitViz 主界面：分支图、提交历史和提交详情">
  </picture>
</p>

## 亮点

**看清历史**
- 带分支图的提交历史，分支、远程分支、标签、HEAD 一目了然；工具栏同时搜提交、作者、哈希、分支和标签
- 底部详情浮层：统一／并排 diff、语法高亮、文件历史和 Blame 追溯（跨重命名、合并父提交）
- 比较分支：两边各自独有的提交和文件差异，可从共同祖先比较
- 统计和洞察：提交热力图、贡献者和热点文件

**放心改动**
- 勾选式暂存，支持按 hunk、按单行部分暂存；平铺或按文件夹查看
- 提交、修改上一次提交、储藏（含未跟踪文件）
- merge、rebase、cherry-pick、reset 前先列出后果：提交数、是否快进、预计冲突的文件
- 同一仓库的写操作串行执行，误操作可通过 reflog 找回

**分支与协作**
- 右键提交和分支完成检出、合并、变基、Cherry-pick、回滚、重置、推送；把分支拖到另一个分支上即可合并或变基
- 交互式 rebase：调整顺序、改说明、合并、删除提交；rebase 保留合并拓扑，重启后仍可继续或中止
- 冲突解决：显示共同祖先和双方来源，按块选择或全文编辑
- 获取、拉取（merge／rebase／ff-only）、推送；强制推送使用 `--force-with-lease`；调用系统 git，沿用已有的 SSH 和钥匙串配置
- 工作树和子模块管理

**顺手的细节**
- 教学模式：每次操作都能看到对应的 Git 命令和说明，适合边用边学
- 文件页：带 git 状态的文件树，包括被忽略的文件
- 命令面板（Cmd+Shift+P）和完整的键盘操作
- 浅色和深色主题，石墨橙配色

<table>
  <tr>
    <td width="50%"><img src="site/introduction/img/staging-dark.png" alt="本地改动与按行暂存"></td>
    <td width="50%"><img src="site/introduction/img/settings-dark.png" alt="设置页"></td>
  </tr>
  <tr>
    <td align="center">本地改动与按行暂存</td>
    <td align="center">设置</td>
  </tr>
</table>

## 快速开始

目前还没有发布安装包，需要从源码构建。准备 JDK 17–21（Android Studio 自带的 JBR 21 就行）和 Git：

```bash
git clone https://github.com/VamPeng/gitviz.git
cd gitviz
./gradlew run
```

打包成本机安装包（macOS 上生成 `.dmg`），产物在 `build/compose/packages.noindex/`：

```bash
./gradlew packageDistributionForCurrentOS
```

## 文档

- [功能路线](docs/ROADMAP.md)：功能分为基本、重要、非必要三层，每项都标了当前进度和优先级
- [待办与验证缺口](docs/PLAN.md)
- [开发约定](AGENTS.md)
- [界面原型](docs/prototype/)

## 完整功能列表

<details>
<summary>展开查看全部功能说明</summary>

- 教学模式：设置中开启，在操作反馈里查看和复制对应 Git 命令与说明
- 统计和洞察：提交热力图、贡献者和热点文件；完整本地历史去重扫描，可选时间范围、刷新或取消
- 交互式 rebase：从提交右键或命令面板进入，编辑线性区间的顺序、说明、合并和删除；编辑后直接执行，可继续或中止
- 工作树和子模块：有多个工作树或配置了子模块时，侧边栏引用树下方出现「工作树」「子模块」分组。工作树可打开、在 Finder 中显示、复制路径、移除；分支右键「在新工作树中检出…」（远程分支会检出为跟踪它的本地分支，本地已有同名分支就直接用）。子模块可初始化并更新、更新到记录的提交、打开
- 打开本地仓库（启动目录是仓库会自动打开，也可以点「打开仓库」选择）
- 左侧引用树：本地分支、远程、标签，点击跳到对应提交
- 工具栏搜索：同时搜提交、作者、哈希、分支和标签
- 带分支图的提交历史：分支、远程分支、标签、HEAD 标记
- 底部浮层详情：默认选中 HEAD（未加载时选首个真实提交）；首次上滑显示，切换提交直接刷新；顶部箭头展开覆盖整个主区域/收起、拖动顶边调高，列表尾部留白避免遮挡。点文件看 diff（统一或并排，带行号）、文件历史和 Blame
- 浅色和深色主题，配色是石墨橙，在设置页切换
- 本地改动：每个文件右边一个勾选框，列表底栏的 ⋯ 菜单批量暂存、取消暂存、丢弃（丢弃前确认）；可在平铺和按文件夹之间切换，文件夹勾选带上它下面的所有文件；列表可左右滑动、左栏宽度可拖动；提交、修改上一次提交，文件有变化时自动刷新工作区
- 拉取和推送：调用系统的 git，沿用现有的 SSH 和钥匙串配置；推送被拒绝时可以强制推送（`--force-with-lease`），进行中的获取、拉取、推送可以取消
- 操作反馈：浮在窗口右下角的卡片，不占列表空间；蓝色进度、绿色成功、黄色需处理、红色失败；成功 5 秒后收起（悬停或展开详情时保留），错误手动关闭，详情可展开和复制，失败可按提示重试或处理冲突
- 身份验证：要密码时由 git 和 ssh 弹出 macOS 系统对话框，密码不经过应用，也不保存
- 仓库：工具栏切换最近的仓库、打开或克隆新仓库
- 写操作保护：同一仓库的写操作串行执行，多个 GitViz 进程互斥；写操作前不做备份，误操作通过 reflog 找回提交
- Blame 追溯：来源路径／行号、上一版本区间、合并父选择、重命名和删除前历史，保留前后导航位置
- 比较分支：分支右键「与 main 比较」（当前分支名），列出两边各自独有的提交（独立分页，点击跳回历史）和文件差异；文件可从共同祖先或直接比较两端，可交换方向；分支移动后自动重新解析
- 获取与拉取：独立 fetch，pull 支持 merge／rebase／ff-only；有上游时点一下完成，无上游时先选择，策略和上游在「拉取设置…」里改并按仓库保存
- 键盘：列表 j/k、上下键、Enter；Cmd/Ctrl+F 搜索、+R 刷新、+Enter 提交、+Shift+P 命令面板；工具栏的拉取、推送右键可进入拉取设置和强制推送
- Diff 高亮：Kotlin、Java、JavaScript、TypeScript、JSON、Python；后台按完整旧／新源码分词，单侧超过 1 MiB 或 20,000 行回退纯文本，缓存上限 32 MiB
- 部分暂存：支持 hunk／单行暂存与取消暂存，统一和并排视图共享选择，工作区内容保持不变；超过 1 MiB、二进制等文件只读显示 diff
- 分支拖拽：从侧边栏或图标签拖到目标分支，选择合并／变基后确认执行
- 操作确认：merge、rebase、cherry-pick、reset 前的确认框列出后果摘要（提交数、是否快进、预计冲突文件、硬重置丢弃的文件数），确认后直接执行
- Rebase：保留合并拓扑，暂停后继续、跳过、保留空提交或中止；重启后仍可继续或中止
- 设置：主题、提交列表行高、默认 diff 视图
- 文件：一棵带列（状态、大小、修改时间）的文件树，文件夹原地展开，包括被 .gitignore 忽略的文件，标出每个文件的 git 状态，文件夹里有改动时名字后加小黄点；顶部按名称筛选（后台扫描所有未被忽略的目录，匹配的路径自动展开并高亮），可显示／隐藏被忽略的、全部折叠；上下键移动、左右键收起／展开、回车打开，双击文件用系统程序打开
- 储藏：新建（包括未跟踪的文件）、查看、应用、弹出、删除
- 右键提交：检出、新建分支、打标签、合并、Cherry-pick、回滚、重置、复制
- 右键分支：切换、合并、重命名、删除、推送和强制推送。推送只更新所选分支的远程目标，无需先切换分支。没有远程上游时，普通推送会推到 origin 并设为上游。强制推送需确认源分支、远程目标和预期远端提交，远端提交变化时 Git 拒绝覆盖。双击分支可切换，远程分支可双击或右键检出为本地分支
- 解决冲突：显示共同祖先和双方来源，按块选择或全文编辑，切换文件保留草稿；保存前检查外部修改，二进制和删除冲突支持整文件处理
- 界面信息（2026-11-16 视觉改版）：工具栏标题旁显示当前分支名，拉取、推送按钮带待处理提交数角标（领先／落后数不在工具栏重复写）；提交列表顶部有「未提交的改动 · N 个文件」行，点击进本地改动；提交行末尾标「合并」「有正文」；侧边栏本地分支显示领先／落后数，30 天没有新提交的分支标灰并写明天数；打开仓库默认选中 HEAD 提交
- 提交历史每页加载 2000 条（`GitRepo.PAGE_SIZE`），滚动到底部时继续加载

</details>

## 使用边界

AI 提交草稿和 Reflog 暂时隐藏日常入口，保留实现，待打磨后再开放。时间轴回放、托管平台（GitHub／GitLab）、Subtree、LFS 页和 AI 分支总结已于 2026-11-13 删除，需要时从 git 历史找回。

待办与验证缺口见 [docs/PLAN.md](docs/PLAN.md)。交互式 rebase 只编辑当前本地分支的线性区间，最多 500 条，基准提交本身不参与编辑。

工作树和子模块只在手动操作时写入。工作树移除不会删除分支，也不允许移除含忽略文件或其他本地改动的目录；存在 `assume-unchanged`／`skip-worktree` 索引标志时，即使表面干净也拒绝移除，需先检查文件并处理标志。目录已被手动删除的工作树标为「目录缺失」，不在应用里移除，可在终端运行 `git worktree prune` 清理记录。子模块更新到父仓库暂存区记录的提交，默认不递归；已初始化子模块更新前保留 `refs/gitviz/submodule-before/…`，恢复需在子模块内处理。Diff 遇到 Git LFS 指针文件时只显示指针信息，不读取大对象。

AI 需要手动开启，填写模型、完整 API 端点和令牌，选择 Responses API 或 Chat Completions 兼容协议。连接仅保存在应用会话中，更换端点会清除令牌。准备快照不会发送请求；检查／编辑输入并勾选发送确认后，点击生成才会发送到所选服务，可能产生服务费用。提交草稿仅使用暂存内容。单次最多 200 个差异路径、每个文本 blob 64 KiB、输入 120,000 字符，省略部分会明确标注。已有草稿需确认替换，结果不会自动执行 Git 操作。

AI 协议、超时与取消已完成本地测试；真实模型服务尚未联调。

## 开发

做新功能前先看[功能路线](docs/ROADMAP.md)，做完后更新状态。

需要 JDK 17–21（Android Studio 自带的 JBR 21 就行）和 Git。

```bash
./gradlew classes                          # 只编译，不启动界面
./gradlew test                             # 无窗口测试，包括离屏截图
```

macOS 日常界面调试使用下面的 Dev 安装流程，统一从 `/Applications/GitViz Dev.app` 启动。不要直接打开构建目录中的副本或额外启动 Java 界面进程。

开发运行和开发安装包使用蓝色 `DEV` 图标，窗口名为 `GitViz Dev`；正式安装版使用原来的米白琥珀色图标和 `GitViz` 名称。图标文件位于 `design/icon/`、`packaging/icons/` 和 `src/main/resources/`。

调试写操作用练习仓库，不要用 gitviz 本身。脚本会生成带分支、合并冲突、储藏和未提交改动的仓库，`origin` 是本地裸仓库，推送不会碰到 GitHub，弄坏了重新跑一次就恢复：

```bash
./scripts/playground.sh
open "/Applications/GitViz Dev.app" --args "/tmp/gitviz-playground"
```

启动参数仅在 Dev 尚未运行时生效；已有窗口可通过「打开仓库」选择练习仓库。

打包安装包：`./gradlew packageDistributionForCurrentOS`

macOS 安装独立开发版（可以和正式版同时运行；复制前先正常退出旧 Dev 并确认进程结束）：

```bash
./gradlew createDistributable -Pdev=true
ditto "build/compose/packages.noindex/dev/main/app/GitViz Dev.app" "/Applications/GitViz Dev.app"
open "/Applications/GitViz Dev.app"
```

打包产物统一放在 `build/compose/packages.noindex/`，避免 Spotlight 把构建副本也列成应用入口；日常只使用“应用程序”里的正式版和开发版。

## 代码结构

<details>
<summary>展开查看各个包和文件的作用</summary>

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
| | `CommitDetail.kt` | 提交详情、diff、文件历史、Blame |
| | `Sidebar.kt`、`SidebarGroups.kt`、`BranchFilterBar.kt`、`BranchDrag.kt` | 左侧引用树面板、工作树和子模块分组、分支过滤、分支拖拽 |
| `ui/changes/` | `ChangesView.kt`、`PartialStageView.kt` | 本地改动（勾选、批量操作、平铺／按文件夹）、提交说明、按行暂存 |
| | `StashView.kt` | 储藏列表和内容 |
| | `ConflictView.kt`、`ConflictEditor.kt` | 解决冲突的界面 |
| `ui/tools/` | `FilesView.kt`、`ReflogView.kt` | 文件页、引用日志 |
| | `InsightsView.kt`、`InteractiveRebaseView.kt` | 洞察、交互式变基 |
| | `ComparisonView.kt`、`BlameTraceView.kt`、`PullDialog.kt` | 分支比较、来源追溯、拉取选项 |
| | `AiView.kt` | AI 提交草稿与发送确认 |

</details>

界面原型在 `docs/prototype/`，改界面前先看那里。`ScreenshotTest` 会把主界面渲染成图片存到 `build/screens/`。

git 层和界面层是分开的，以后换界面框架也能保留 `git/` 和 `logic/` 两个包。

## 许可证

[MIT](LICENSE) © VamPeng
