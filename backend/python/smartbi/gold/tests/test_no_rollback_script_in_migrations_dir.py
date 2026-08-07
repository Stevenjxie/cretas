"""迁移目录里不许出现回滚脚本 —— 它会把自己当成下一条迁移应用掉。

🔴 2026-08-08 prod 实测: 回滚脚本被命名成 `V20261101_10__ROLLBACK.sql` 放进
   `smartbi/database/migrations/`。`scripts/migrations/apply-smartbi-migrations.sh`
   是按 **`V*` glob** 发现文件的, 于是部署时:

       [migrations] applying V20261101_10__mock_rest_cashier_staff_and_backfill.sql
       [migrations] V20261101_10 applied in 7333ms
       [migrations] applying V20261101_10__ROLLBACK.sql          ← 它也被当成迁移
       [migrations] applied: 2

   数据写进去、紧接着被自己撤销, 而部署**全绿**(DEPLOY_EXIT=0)。
   ⇒ **把回滚脚本放进自动应用的目录, 等于给自己写了一条撤销自己的迁移。**

判据: 一个目录是「按模式自动执行」的, 往里放任何东西之前先问
      「这个模式会不会把它也捞走」。文件名里写 ROLLBACK 不构成任何保护 ——
      glob 不读语义。

回滚脚本的正确位置是 `scripts/migrations/`, 手动执行, 文件名不带 V 前缀。
"""
from pathlib import Path

import smartbi.database as _db

MIGRATIONS_DIR = Path(_db.__file__).parent / "migrations"

#: 与 apply-smartbi-migrations.sh 的发现模式一致 —— 改那边要同步改这里。
AUTO_APPLIED_GLOB = "V*.sql"

#: ⚠️ 刻意**不收** `revert` / `undo`: 用一条**新的前向迁移**去撤销上一条的效果是
#:    合法模式(仓里 `V20260929_01__revert_chart_insight_cross_factory.sql` 就是),
#:    它会被正常应用一次并留在台账里。第一版把 `revert` 也收进来, 当场误伤了它 ——
#:    **名字里有「撤销」的意思, 不等于它是个不该被自动执行的脚本。**
#:    真正的危险特征在下一条测试里: 动台账。
_ROLLBACK_MARKERS = ("rollback", "回滚")


def test_no_rollback_script_is_auto_applied():
    """⛔ 自动应用目录里不许有名字直白写着 ROLLBACK 的文件。

    这条只是便宜的第一道 —— 承重的是下面那条按内容判的。
    """
    offenders = [
        p.name
        for p in MIGRATIONS_DIR.glob(AUTO_APPLIED_GLOB)
        if any(marker in p.name.lower() for marker in _ROLLBACK_MARKERS)
    ]
    assert not offenders, (
        f"这些文件会被部署自动执行掉: {offenders} —— "
        "回滚脚本要放 scripts/migrations/ 且文件名不带 V 前缀"
    )


def test_no_migration_undoes_itself_by_content():
    """⛔ 光看文件名不够: 内容上在撤销别的迁移的, 同样不能放这里。

    判据取「删除 smartbi_migrations 台账行」—— 正常迁移**从不**动台账,
    那是运行器的事。会动台账的只可能是回滚脚本。
    """
    offenders = []
    for path in MIGRATIONS_DIR.glob(AUTO_APPLIED_GLOB):
        text = path.read_text(encoding="utf-8", errors="ignore").lower()
        if "delete from smartbi_migrations" in text.replace("\n", " "):
            offenders.append(path.name)
    assert not offenders, (
        f"这些迁移会删自己的台账行(回滚脚本的特征): {offenders}"
    )
