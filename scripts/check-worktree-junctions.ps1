<#
.SYNOPSIS
  扫描 worktree 里指向【别的 worktree / 主仓库】的 NTFS junction, 这些是 node_modules 被掏空的雷。

.DESCRIPTION
  背景 (2026-08-09/10 两次真实事故): 有 session 为了省 npm install 时间, 在自己的 worktree 里
  用 `mklink /J` 把 node_modules 链到主仓库或别的 worktree。之后 `git worktree remove --force`
  会【穿透 junction】把 target 目录递归删空 —— 主仓库的 web-admin/node_modules 因此被掏空两次,
  下一次 deploy 跑 vite build 直接 "vite 不是内部或外部命令" 失败。

  实测证据 (2026-08-10 全盘扫描, 11 个 junction):
    cretas-material-basic-code-evidence-20260809\root-local-e2e\node_modules-shared-junction
      -> C:\Users\Steve\my-prototype-logistics\web-admin\node_modules      <- 直指主仓库
    cretas-plan-customer-select-20260809\web-admin\node_modules
      -> C:\Users\Steve\cretas-rest-ai\web-admin\node_modules              <- 指向未合并的活跃 worktree

  `.claude/rules/concurrent-edit-safety.md` Rule 7 早就禁止这种做法, 但五天后照犯 ——
  决心不构成约束力, 只有机制构成。本脚本就是那个机制。

.PARAMETER Root
  worktree 的父目录 (默认取本仓库的上级目录)。

.PARAMETER Fix
  加上此开关则【摘掉链接本身】(非递归), 不动 target。不加只报告。

.EXAMPLE
  pwsh -File scripts/check-worktree-junctions.ps1
  pwsh -File scripts/check-worktree-junctions.ps1 -Fix
#>
[CmdletBinding()]
param(
    [string]$Root,
    [switch]$Fix
)

$ErrorActionPreference = 'Stop'

if (-not $Root) {
    $repo = (& git rev-parse --show-toplevel 2>$null)
    if (-not $repo) { throw "不在 git 仓库里, 且未指定 -Root" }
    $Root = Split-Path (Resolve-Path $repo) -Parent
}

# 只扫到深度 4: node_modules 通常在 <worktree>/web-admin/ 或 <worktree>/frontend/CretasFoodTrace/ 下。
# 再深就会走进 node_modules 内部的几万个包, 扫描时间从秒级变分钟级。
$DEPTH = 4

Write-Host "扫描 junction: $Root (深度 $DEPTH)" -ForegroundColor Cyan

$dirs = Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'cretas-*' -or (Test-Path (Join-Path $_.FullName '.git')) }

$hits = @()
foreach ($d in $dirs) {
    Get-ChildItem -LiteralPath $d.FullName -Recurse -Depth $DEPTH -Directory -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint } |
        # ⚠️ 排除【node_modules 内部】的 reparse point。危险的是"链接本身就是 node_modules"
        # (删它会穿透到别人的依赖目录), 而不是 node_modules 里面那些包级 symlink ——
        # pnpm/npm 会在包目录里造成千上万个, 2026-08-10 首跑就被 MallCenter/mall_admin_ui
        # 刷出上千条假告警。告警一多就没人看, 真雷反而被淹掉。
        # 判据: 路径里 node_modules 后面【还有内容】的就是内部实现, 跳过;
        #       node_modules 是最后一段 (或压根没有这一段) 的才是我们要防的共享链接。
        Where-Object { $_.FullName -notmatch '\\node_modules\\' } |
        ForEach-Object {
            $raw = & fsutil reparsepoint query $_.FullName 2>$null | Select-String 'Substitute Name'
            $target = (($raw -join '') -replace '.*\\\?\?\\', '').Trim()
            # 只有指向【仓库工作区】的才危险。指向 npm/codex 全局缓存的无所谓 —— 那不是 worktree,
            # 删掉它对任何人都没影响, 报出来只会变成噪音让人忽略真正的告警。
            $dangerous = $target -and (Test-Path $target) -and
                         ($target -like "$Root*") -and ($target -notlike '*\.cache\*')
            $hits += [PSCustomObject]@{
                Link      = $_.FullName
                Target    = $target
                Dangerous = [bool]$dangerous
            }
        }
}

$bad = @($hits | Where-Object { $_.Dangerous })

if ($hits.Count -eq 0) {
    Write-Host "OK: 没有发现任何 junction" -ForegroundColor Green
    exit 0
}

foreach ($h in $hits) {
    $tag = if ($h.Dangerous) { '[危险]' } else { '[无害]' }
    $color = if ($h.Dangerous) { 'Red' } else { 'DarkGray' }
    Write-Host "$tag $($h.Link)" -ForegroundColor $color
    Write-Host "        -> $($h.Target)" -ForegroundColor $color
}

if ($bad.Count -eq 0) {
    Write-Host "OK: $($hits.Count) 个 junction 全部指向仓库外 (缓存等), 无风险" -ForegroundColor Green
    exit 0
}

if ($Fix) {
    foreach ($h in $bad) {
        try {
            # 非递归删除: 对 junction 只摘掉 reparse point 本身, target 一个文件都不会动。
            # ⛔ 绝不能用 Remove-Item -Recurse / rmdir /S —— 那会穿透过去把 target 删空,
            #    正是本脚本要防的事故本身。
            [System.IO.Directory]::Delete($h.Link, $false)
            Write-Host "已摘链 $($h.Link)" -ForegroundColor Yellow
        } catch {
            Write-Host "摘链失败 $($h.Link) :: $($_.Exception.Message)" -ForegroundColor Red
        }
    }
    Write-Host "完成。被指向的 worktree 需要自己重新 npm install。" -ForegroundColor Cyan
    exit 0
}

Write-Host ""
Write-Host "发现 $($bad.Count) 个指向仓库工作区的 junction —— 谁 git worktree remove --force 掉左边那个," -ForegroundColor Red
Write-Host "右边 target 的 node_modules 就会被递归删空 (2026-08-09/10 已真实发生两次)。" -ForegroundColor Red
Write-Host "摘链: pwsh -File scripts/check-worktree-junctions.ps1 -Fix" -ForegroundColor Yellow
exit 1
