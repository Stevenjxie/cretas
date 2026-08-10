#!/usr/bin/env bash
#
# 安全移除 git worktree —— 先摘掉里面的 junction, 再删目录。
#
# 为什么需要这个包装 (2026-08-09/10 两次真实事故):
#   有 session 为省 npm install 时间, 在 worktree 里用 `mklink /J` 把 node_modules
#   链到主仓库或别的 worktree。`git worktree remove --force` 在 Windows 上会【穿透
#   junction】把 target 递归删空 —— 主仓库的 web-admin/node_modules 被掏空两次,
#   下一次 deploy 跑 vite build 直接失败。
#
#   直接 `git worktree remove --force` 看不出任何异常, 破坏发生在【别的目录】里,
#   这是它难被归因的原因: 两次事故我都先怀疑 npm 中断、并发 session, 最后靠
#   `fsutil reparsepoint query` 全盘扫描才实证。
#
# 用法:
#   ./scripts/safe-worktree-remove.sh ../cretas-my-task
#   ./scripts/safe-worktree-remove.sh --all-merged        # 清理所有 已合并+干净+未锁定 的
#
set -euo pipefail

usage() {
    cat <<'EOF'
用法:
  scripts/safe-worktree-remove.sh <worktree 路径>
  scripts/safe-worktree-remove.sh --all-merged

--all-merged 只删同时满足三条的 worktree (任一不满足即跳过并说明原因):
  1) HEAD 已是 origin/main 的祖先 (工作已合并)
  2) 工作区干净 (git status --porcelain 为空)
  3) 未 locked
这三条都满足的 worktree 按定义可重建, 删掉不丢任何东西。
EOF
}

# 摘掉 <dir> 下的所有 junction —— 只摘链接本身, 不动 target。
# ⛔ 这一步必须在 git worktree remove 之前, 否则就是事故本身。
detach_junctions() {
    local dir="$1"
    command -v powershell >/dev/null 2>&1 || return 0
    powershell -NoProfile -Command "
        \$d = '$dir'
        if (-not (Test-Path \$d)) { exit 0 }
        Get-ChildItem -LiteralPath \$d -Recurse -Depth 4 -Directory -Force -ErrorAction SilentlyContinue |
          Where-Object { \$_.Attributes -band [IO.FileAttributes]::ReparsePoint } |
          ForEach-Object {
            try {
              # 非递归 Delete: junction 只摘 reparse point, target 一个文件不动
              [System.IO.Directory]::Delete(\$_.FullName, \$false)
              Write-Output ('  已摘链 ' + \$_.FullName)
            } catch {
              Write-Output ('  摘链失败 ' + \$_.FullName + ' :: ' + \$_.Exception.Message)
            }
          }
    " 2>/dev/null || true
}

remove_one() {
    local wt="$1"
    echo "→ $wt"
    detach_junctions "$wt"
    if git worktree remove --force "$wt" 2>/dev/null; then
        echo "  已删除"
    else
        # 常见于 .git 文件已丢失的半损坏 worktree —— prune 清掉失效登记, 目录留给人工确认
        echo "  ⚠️  git 拒绝删除 (通常是 .git 已丢失)。已跑 prune 清理登记, 目录保留待人工确认:"
        git worktree prune -v 2>&1 | sed 's/^/     /' || true
    fi
}

[ $# -ge 1 ] || { usage; exit 2; }

if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then usage; exit 0; fi

if [ "$1" != "--all-merged" ]; then
    remove_one "$1"
    exit 0
fi

git fetch --quiet origin main
main_wt=$(git rev-parse --show-toplevel)

git worktree list --porcelain \
  | awk '/^worktree /{w=$2} /^HEAD /{h=$2} /^locked/{l=1} /^$/{if(w!="")print w"|"h"|"(l?"L":"-"); w="";h="";l=0}' \
  | while IFS='|' read -r wt head lock; do
        [ "$wt" = "$main_wt" ] && continue
        if [ "$lock" = "L" ]; then echo "跳过(已锁定)   $(basename "$wt")"; continue; fi
        if ! git merge-base --is-ancestor "$head" origin/main 2>/dev/null; then
            echo "跳过(未合并)   $(basename "$wt")"; continue
        fi
        if [ -n "$(git -C "$wt" status --porcelain 2>/dev/null | head -1)" ]; then
            echo "跳过(有改动)   $(basename "$wt")"; continue
        fi
        remove_one "$wt"
    done
