<p align="center">
  <b>English</b> · <a href="README.zh-CN.md">简体中文</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="design/icon/preflight-dark.svg">
    <img src="design/icon/preflight-light.svg" width="112" alt="Preflight icon">
  </picture>
</p>

<h1 align="center">Preflight</h1>

<p align="center">
  <b>A Git desktop client that shows your branches clearly and lets you act with confidence</b><br>
  Understand your repository at a glance, and see what an operation will do before you run it.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/platform-macOS-lightgrey" alt="Platform: macOS">
  <img src="https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 2.1">
  <img src="https://img.shields.io/badge/Compose_Desktop-1.8-4285F4" alt="Compose Desktop 1.8">
  <img src="https://img.shields.io/badge/status-in_development-yellow" alt="Status: in development">
</p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#installation">Installation</a> ·
  <a href="#getting-started">Getting started</a> ·
  <a href="#security-and-privacy">Security &amp; privacy</a> ·
  <a href="#project-status">Status</a> ·
  <a href="docs/ROADMAP.md">Roadmap</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="site/introduction/img/main-dark.png">
    <img src="site/introduction/img/main-light.png" width="900" alt="Preflight main window: branch graph, commit history and commit details">
  </picture>
</p>

## Why Preflight

<table>
  <tr>
    <td width="33%" valign="top">
      <h3>👀 See clearly</h3>
      Every branch keeps its own color in the graph, and hovering a commit highlights only the line it belongs to. Scrolling stays smooth even with tens of thousands of commits.
    </td>
    <td width="33%" valign="top">
      <h3>🛡️ Know the outcome first</h3>
      Before a merge, rebase, cherry-pick or reset, Preflight tells you how many commits are involved, whether it can fast-forward, and which files may conflict.
    </td>
    <td width="33%" valign="top">
      <h3>📖 Learn as you go</h3>
      Turn on <b>Show Git commands</b> to see the equivalent Git command and an explanation for every operation, and pick up the command line along the way.
    </td>
  </tr>
</table>

## Features

### Browse history

- **Commit graph**: local branches, remote branches, tags, HEAD and stashes are all drawn on the graph; click a ref in the sidebar to jump to its commit
- **Search and filter**: one search box covers commit messages, authors, hashes, branches and tags; show only selected branches, or only the history of one file
- **README preview**: opening a repository shows its README across the main area first, with selectable, copyable text
- **Commit details**: in a bottom panel you can drag taller or expand to fill the window; unified or side-by-side diffs with line numbers and syntax highlighting, and code you can select and copy
- **Blame tracing**: see who changed each line in which commit, and keep following it back across renames and merges
- **Branch comparison**: commits and file differences unique to each side of two branches
- **Insights**: commit heatmap, top contributors and most frequently changed files

### Commit changes

<img src="site/introduction/img/staging-dark.png" width="460" align="right" alt="Local changes with line-level staging">

- A checkbox on every file to stage, unstage or discard in bulk
- Stage by hunk, or even by single line
- View changes as a flat list or grouped by folder
- Commit, or amend the last commit
- Stash changes, including untracked files
- Refreshes automatically when files change

<br clear="right">

### Branches and collaboration

- **Right-click actions**: checkout, new branch, tag, merge, rebase, cherry-pick, revert, reset, push
- **Drag and drop branches**: drop one branch onto another, then choose merge or rebase
- **Interactive rebase**: reorder commits, reword messages, squash or drop commits
- **Conflict resolution**: shows the common ancestor and both sides; pick a side per block, or edit the full text
- **Remote sync**: fetch, pull (merge / rebase / fast-forward only) and push; force pushes use the safer `--force-with-lease`; network operations in progress can be cancelled
- **Worktrees and submodules**: view, open and manage them from the sidebar

### More

- **Files view**: the full file tree with Git status, including ignored files, with name filtering and sorting
- **Keyboard friendly**: a command palette and common shortcuts let you work without the mouse
- **Appearance**: light and dark themes, English and Chinese interface
- **Multiple repositories**: switch between recent repositories from the toolbar, or clone a new one

## Installation

Download the macOS Apple silicon / Intel or Windows x64 installer from [the Preflight website](https://git.vambda.com/). The Mac installer uses ad-hoc signing and is not notarized by Apple; the Windows installer is unsigned. To build from source, you will need:

- JDK 17–21 (the JBR 21 bundled with Android Studio works)
- Git (2.38 or later recommended; used to predict conflicts before an operation)

```bash
git clone https://github.com/VamPeng/gitviz.git
cd gitviz
./gradlew run
```

You can also build an installer (a `.dmg` on macOS). The output goes to `build/compose/packages.noindex/`:

```bash
./gradlew packageDistributionForCurrentOS
```

The product website (`https://git.vambda.com`) lives in `site/introduction/`. `scripts/package-site.sh` builds the release DMG and packs it with the page into `build/site/` (local only). `site/deploy/upload.sh` publishes that bundle to the server and is a remote change. Supply a Windows x64 installer with `PREFLIGHT_WINDOWS_INSTALLER=/path/to/Preflight-<version>.exe`, or keep it at `build/installers/Preflight-<version>-windows-x64.exe` for subsequent builds. If the Windows build has a different version, also set `PREFLIGHT_WINDOWS_VERSION`. Build the Intel DMG with an x64 JDK under Rosetta: `JAVA_HOME=/path/to/x64-jdk ./gradlew -Dorg.gradle.java.home=/path/to/x64-jdk --project-cache-dir build/intel-gradle --no-daemon packageDmg`. Intel outputs are isolated in `build/intel/`; the site packager includes that version’s DMG automatically, or accepts `PREFLIGHT_MAC_X64_INSTALLER`. The website lists both chips and asks users to choose when the browser cannot identify the architecture. Published download filenames cannot be reused for different content. `scripts/release_manifest.py` generates `public/release.json` from verified installer files: schema 1, product name, and a `releases` list containing platform, architecture, version, download URL and SHA-256. The site serves it with revalidation on every request.

## Getting started

Packaged releases check `https://git.vambda.com/release.json` once after startup. When a newer version is available for your platform, a dismissible notice opens the website's download page. Checks run in the background with an eight-second timeout; failures stay silent. Development builds do not check, and the app does not download or install updates automatically. Previously published installers need to be replaced with a build containing this feature first.

1. Click **Open repository** to choose a local Git repository, or clone a new one
2. Click a commit in the list to see its details; click a branch in the sidebar to jump to it
3. Most actions live in the right-click menus. Not sure what something does? Turn on **Show Git commands** under **Learning** in Settings first

Common shortcuts (use Ctrl instead of ⌘ on Windows / Linux):

| Shortcut | Action |
|---|---|
| `⌘ F` | Search |
| `⌘ R` | Refresh |
| `⌘ ⇧ P` | Command palette |
| `⌘ Enter` | Commit |
| `j` / `k` or `↑` / `↓` | Move through lists |
| `Enter` | Open the selected item |
| `Esc` | Leave search or selection |
| `⌘ W` | Close the window and keep the app running (macOS) |

## Security and privacy

- **Passwords never pass through Preflight**: when authentication is needed, git and ssh show the macOS system dialog and reuse your existing SSH keys and Keychain; the app neither reads nor stores passwords
- **Uses your system git**: every write operation runs the git installed on your machine, so behavior matches the command line
- **Guarded operations**: write operations on the same repository run one at a time, even across multiple Preflight windows; discarding changes, hard resets and force pushes ask for confirmation first
- **Mistakes are recoverable**: Preflight does not make extra backups; commits can be recovered from Git's reflog

## Project status

Preflight is under active development. Everyday viewing, committing, branching and syncing already work. See the [roadmap](docs/ROADMAP.md) for the status of each feature and the [plan](docs/PLAN.md) for open work (both in Chinese).

Current limitations:

- Windows installation and runtime behavior have not been verified on this Mac; Linux has not been verified
- AI-generated commit messages and the Reflog view are implemented but hidden for now, until they are polished
- Interactive rebase only edits a linear range of commits on the current branch
- Git LFS files show pointer information only

## Contributing

To build, debug or change the code, see [Development and code structure](docs/STRUCTURE.md) (in Chinese). Issues with bug reports and suggestions are welcome.

## License

[MIT](LICENSE) © VamPeng
