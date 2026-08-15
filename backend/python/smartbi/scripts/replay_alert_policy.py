"""回放跑批的告警分级 —— **纯函数，⛔ 无任何 import 期副作用**。

## 为什么单独一个模块

它本来写在 `replay_equivalence_probe.py` 里，而那个模块在**模块级**调
`bootstrap_probe(FACTORY)`（设租户 ContextVar、改 `sys.path`、准备连接池）。
于是任何 `from ... import alert_for` 都会顺带执行那一整套。

实测代价（2026-08-15）：测试文件只是 import 了它，同一进程里后面的
`test_tenant_ctx_plumbing` / `test_capability_calculator_unit` **7 条当场变红** ——
单独跑 9 passed，一起跑 7 failed。

▎**判定逻辑要能被单独 import。** 跟 IO / 自举 / 全局状态放在一起，
▎它就只能连着那一整套一起加载，而那一整套会改别人的世界。

## 三态（硬约束 4）

    rc=0  全等价
    rc=1  有 ②执行不等价 / ③执行失败 —— 读数有效，且指向缺陷
    rc=2  这次没量到东西。**三个成因，处置完全不同**：
          (a) eligible_stored=0   存量按设计全部失效（旧格式，等人逐条盖章）
                                  → **不是故障**，⛔ 不告警
          (b) eligible_stored>0 却 0 条回放 → 仪器坏了（A 遍撬棍失效）→ 告警
          (c) positive_control=0 / 表里 0 行 → 格式门坏 / plan_version 不符 → 告警

🔴 (a) 为什么闭嘴（owner 2026-08-15 裁定 ①）：08-13 起 prod 每天落的都是 (a)，
而告警一律喊「阳性对照未通过」—— `positive_control` 明明是 1。
**一个天天误报的告警最终会被忽略，而它拖下水的是所有告警的可信度**（形态 E）。
"""
from __future__ import annotations


def alert_for(rc: int, *, positive_control, eligible_stored, stored_total) -> str:
    """这次跑批该不该喊、喊什么。返回空串 = 不喊。

    ⛔ 四个成因必须各说各的 —— 压成一句正是被误报咬了三天的那个形状。
    """
    if rc == 0:
        return ""
    if rc != 2:
        return "REPLAY EQUIV DRIFT — 有条目不再等价(指纹**可能没变**)"
    # rc == 2 的三个成因
    if not positive_control:
        return ("REPLAY EQUIV INSTRUMENT DEAD — 合成阳性对照没通过, "
                "格式门本身坏了; 本次读数作废")
    if not stored_total:
        return ("REPLAY EQUIV INSTRUMENT DEAD — 晋升表 0 行(plan_version 对不上), "
                "本次读数作废")
    if eligible_stored:
        return (f"REPLAY EQUIV INSTRUMENT DEAD — 有 {eligible_stored} 条合格存量"
                "却一条都没回放, A 遍撬棍没打开晋升闸; 本次读数作废")
    # (a): eligible_stored == 0 —— 按设计如此, ⛔ 不喊。
    return ""
