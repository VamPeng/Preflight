<p align="center">
  <a href="README.md">English</a> · <b>简体中文</b>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="design/icon/preflight-dark.svg">
    <img src="design/icon/preflight-light.svg" width="112" alt="Preflight 图标">
  </picture>
</p>

<h1 align="center">Preflight</h1>

<p align="center">
  <b>看得清分支，也敢放心操作的 Git 桌面客户端</b><br>
  打开仓库就能看懂发生了什么，动手之前先知道会发生什么。
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/platform-macOS-lightgrey" alt="Platform: macOS">
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.1">
  <img src="https://img.shields.io/badge/Compose_Desktop-1.8-4285F4" alt="Compose Desktop 1.8">
  <img src="https://img.shields.io/badge/status-开发中-yellow" alt="开发中">
</p>

<p align="center">
  <a href="#功能">功能</a> ·
  <a href="#安装">安装</a> ·
  <a href="#上手">上手</a> ·
  <a href="#安全与隐私">安全与隐私</a> ·
  <a href="#当前状态">当前状态</a> ·
  <a href="docs/ROADMAP.md">路线图</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="site/introduction/img/main-dark.png">
    <img src="site/introduction/img/main-light.png" width="900" alt="Preflight 主界面：分支图、提交历史和提交详情">
  </picture>
</p>

## 为什么是 Preflight

<table>
  <tr>
    <td width="33%" valign="top">
      <h3>👀 看得清</h3>
      分支图每条分支颜色固定，悬停一个提交只点亮它所在的那条线。几万个提交也能流畅滚动。
    </td>
    <td width="33%" valign="top">
      <h3>🛡️ 先看后果</h3>
      合并、变基、Cherry-pick、重置之前，先告诉你会带入几个提交、能否快进、哪些文件可能冲突。
    </td>
    <td width="33%" valign="top">
      <h3>📖 边用边学</h3>
      打开教学模式，每次操作都会显示对应的 Git 命令和说明，慢慢就会用命令行了。
    </td>
  </tr>
</table>

## 功能

### 浏览历史

- **提交图**：本地分支、远程分支、标签、HEAD 和储藏都画在图上，侧边栏点一下就能跳到对应提交
- **搜索与过滤**：一个搜索框同时搜提交说明、作者、哈希、分支和标签；可以只看某几条分支，或只看某个文件的历史
- **README 预览**：打开仓库时先在正文区域显示 README，文字可以选中复制
- **提交详情**：在底部浮层查看，可以拖高或铺满；统一或并排 diff，带行号和语法高亮，代码可以选中复制
- **Blame 追溯**：逐行看是谁在哪次提交改的，跨重命名、跨合并一路往前追
- **比较分支**：两条分支各自独有的提交和文件差异，一目了然
- **统计和洞察**：提交热力图、贡献者排行、改动最频繁的文件

### 提交改动

<img src="site/introduction/img/staging-dark.png" width="460" align="right" alt="本地改动与按行暂存">

- 每个文件一个勾选框，批量暂存、取消暂存或丢弃
- 按代码块，甚至按单行暂存
- 平铺或按文件夹查看改动
- 提交，或修改上一次提交
- 储藏改动，未跟踪的文件也能一起储藏
- 文件有变化时自动刷新

<br clear="right">

### 分支与协作

- **右键即可操作**：检出、新建分支、打标签、合并、变基、Cherry-pick、回滚、重置、推送
- **拖拽分支**：把一个分支拖到另一个分支上，选择合并或变基
- **交互式 rebase**：调整提交顺序、改说明、合并或删除提交
- **解决冲突**：同时显示共同祖先和双方版本，按块选择保留哪一边，也可以直接编辑全文
- **同步远程**：获取、拉取（merge／rebase／仅快进）、推送；强制推送使用更安全的 `--force-with-lease`；进行中的网络操作可以取消
- **工作树和子模块**：在侧边栏查看、打开和管理

### 其他

- **文件页**：带 Git 状态的完整文件树，包括被忽略的文件，可按名称筛选和排序
- **键盘操作**：命令面板和常用快捷键，不碰鼠标也能操作
- **界面**：浅色和深色主题，中文和英文界面
- **多仓库**：在工具栏切换最近打开的仓库，或克隆新仓库

## 安装

可从 [Preflight 官网](https://git.vambda.com/) 下载 macOS Apple 芯片／Intel 或 Windows x64 安装包。Mac 包采用 ad-hoc 签名，尚未经过 Apple 公证；Windows 包未签名。如需从源码构建，请先装好：

- JDK 17–21（Android Studio 自带的 JBR 21 就可以）
- Git（建议 2.38 及以上，操作前的冲突预判会用到）

```bash
git clone https://github.com/VamPeng/gitviz.git
cd gitviz
./gradlew run
```

也可以打包成安装包（macOS 上生成 `.dmg`），产物在 `build/compose/packages.noindex/`：

```bash
./gradlew packageDistributionForCurrentOS
```

官网发布使用 `scripts/package-site.sh` 打包，再运行 `site/deploy/upload.sh` 上传。Intel 包使用 x64 JDK 和 Rosetta 构建：`JAVA_HOME=/path/to/x64-jdk ./gradlew -Dorg.gradle.java.home=/path/to/x64-jdk --project-cache-dir build/intel-gradle --no-daemon packageDmg`，产物隔离在 `build/intel/`，站点脚本自动包含同版本的 Intel DMG，也可用 `PREFLIGHT_MAC_X64_INSTALLER` 指定。Windows 版本不同时设置 `PREFLIGHT_WINDOWS_VERSION`。官网列出两个 Mac 芯片入口，浏览器无法识别时由用户选择。

## 上手

正式版启动后在后台检查一次 `https://git.vambda.com/release.json`；当前系统和架构有新版时显示可关闭的提示，点击后打开官网下载页。检查 8 秒超时，失败静默跳过；开发版不检查，也不会自动下载安装。此前发布的安装包需要先手动安装包含此功能的新版。

1. 点「打开仓库」选择本地的 Git 仓库，或者克隆一个新仓库
2. 在提交列表里单击提交查看详情，在左侧侧边栏点分支跳到对应位置
3. 想做的操作大多在右键菜单里；拿不准的话，先在设置里打开**教学模式**

常用快捷键（Windows／Linux 上把 ⌘ 换成 Ctrl）：

| 快捷键 | 作用 |
|---|---|
| `⌘ F` | 搜索 |
| `⌘ R` | 刷新 |
| `⌘ ⇧ P` | 命令面板 |
| `⌘ Enter` | 提交 |
| `j` / `k` 或 `↑` / `↓` | 在列表中移动 |
| `Enter` | 打开所选项 |
| `Esc` | 退出搜索或选择 |
| `⌘ W` | 关闭窗口，应用继续在后台运行（macOS） |

## 安全与隐私

- **密码不经过 Preflight**：需要认证时由 git 和 ssh 弹出 macOS 系统对话框，沿用你已有的 SSH 密钥和钥匙串，应用不读取也不保存密码
- **使用系统自带的 git**：所有写操作都调用你电脑上的 git，行为和命令行一致
- **操作有保护**：同一仓库的写操作按顺序执行，多开 Preflight 也不会同时写入；丢弃改动、硬重置、强制推送前都会确认
- **误操作可以找回**：Preflight 不额外备份，提交可以通过 Git 的 reflog 找回

## 当前状态

Preflight 还在积极开发中，日常的查看、提交、分支和同步操作都已可用。每项功能的进度见[功能路线](docs/ROADMAP.md)，待办事项见 [PLAN](docs/PLAN.md)。

暂时的限制：

- 当前 Mac 环境未验证 Windows 安装与运行；Linux 尚未验证
- AI 生成提交说明、Reflog 视图已经实现，但暂时隐藏入口，打磨后再开放
- 交互式 rebase 只能编辑当前分支上的一段线性提交
- Git LFS 文件只显示指针信息

## 参与开发

想要构建、调试或修改代码，请看[开发与代码结构](docs/STRUCTURE.md)。欢迎提 Issue 报告问题或提建议。

## 许可证

[MIT](LICENSE) © VamPeng
