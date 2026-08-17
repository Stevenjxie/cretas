#!/bin/bash
# 变异对照跑之前的一道闸 —— 工作树必须干净。
#
# ## 为什么有这个脚本（2026-08-18，同一个错误一天内第三次）
#
# 变异对照的标准套路是：
#     python mutate.py M1 && pytest ... ; git checkout -- <file>
#
# 那个 `git checkout --` **会连带冲掉我在跑变异之前对同一文件做的未提交编辑**。
# 实测三次：
#   ① 冲掉了整个实现（~5 处 Edit），只剩未跟踪的测试文件幸存，全部重做
#   ② 冲掉之后我把随后那次「4 failed」误判成「变异与回归并发污染」——
#      归因错了，干净重跑仍然红，真因是别的
#   ③ 冲掉了一段刚写的「诚实登记」注释，commit 只带上了测试文件
#
# ▎规则写在硬约束里（「变异对照前先 commit」），我照样犯了三次。
# ▎⇒ 决心不构成约束力，做成会拒绝执行的闸。
#
# ## 用法
#
#     ./scripts/mutation-guard.sh && python mutate.py M1 && pytest ...
#
# 或者在变异脚本里第一行调它。⛔ 不要加 `|| true`。
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

DIRTY="$(git status --porcelain)"
if [ -n "$DIRTY" ]; then
    echo "⛔ 变异对照拒绝执行：工作树不干净。" >&2
    echo "" >&2
    echo "   变异循环里的 \`git checkout -- <file>\` 会把下面这些未提交的改动" >&2
    echo "   一起冲掉 —— 2026-08-18 一天内实测三次，其中一次整个实现重做。" >&2
    echo "" >&2
    echo "$DIRTY" >&2
    echo "" >&2
    echo "   先 ./scripts/safe-commit.sh 提交，再跑变异。" >&2
    exit 1
fi

echo "[mutation-guard] ✓ 工作树干净，变异跑完后的 git checkout 不会冲掉任何东西"
