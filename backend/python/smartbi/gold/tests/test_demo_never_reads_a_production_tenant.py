"""演示账号的数据源**只能**是生成数据的租户，绝不是真客户的生产租户。

## 为什么有这道闸（2026-08-17 逐环实测，⛔ 每一环都跑过，没有一环是推的）

```
① POST /api/mobile/auth/demo-login   —— 在 JwtAuthInterceptor 的**免鉴权**白名单里
② cretas.demo.enabled                —— prod 活进程无覆盖，走 jar 默认 true
③ cretas.demo.rest.factory-id        —— 同上，默认 DEMO_REST
④ DEMO_GOLD_TENANT_ALIASES           —— DEMO_REST → RES_3101_009
⑤ RES_3101_009 的租户名 = QHJ_PROD   —— 625,764 笔 POS、跨 2025-01-01 至 2026-08-16
                                        门店「青花椒南方百联店 / 大丸百货店 /
                                        徐汇光启城店」= 真实存在的店
```

⇒ 一条免密端点接到了**真客户的营收、门店排行、毛利、客单价**上。

⚠️ 当时那行别名的注释称 RES_3101_009 是「the rich demo factory」——
   **描述与事实不符**，而它一直没人核。Java 侧另有一句「2026-08-05 停用、
   fallback 默认值同步清空」，同样**是过期的**（打包的 properties 仍默认开启）。
▎两处都是「照注释下结论」——而否定它们的东西，一个在租户表里，一个在 jar 里。

## owner 裁定（2026-08-17）：**只用 MOCK_REST**

演示不再借道任何真客户租户。

## 这道闸钉什么

判据必须是**机械可判**的 —— 「是不是真客户」机器判不了，但
「演示数据源是不是在生成租户白名单里」判得了。

⚠️ 白名单要窄：多加一个就要有人回答「那个租户的门店名是不是真实存在的店」。
"""
from smartbi.api.gold_reads import DEMO_GOLD_TENANT_ALIASES
from smartbi.gold.restaurant.restaurant_ops_router import (
    _DEMO_GOLD_TENANT,
    demo_data_factory_for_code,
)

#: 允许被演示账号借道的租户 —— **只能是生成数据的**。
#: ⛔ 往里加之前先回答: 那个租户的门店名是不是真实存在的店?
_GENERATED_TENANTS = frozenset({"MOCK_REST"})

#: 已知的**生产**租户，任何演示路径都不许指向它们。
#: RES_3101_009 的租户名就叫 QHJ_PROD。
_PRODUCTION_TENANTS = frozenset({"RES_3101_009"})


class TestNoDemoPathPointsAtProduction:
    def test_ops_router_demo_tenant_is_not_production(self):
        assert _DEMO_GOLD_TENANT not in _PRODUCTION_TENANTS, (
            f"演示数据源指向生产租户 {_DEMO_GOLD_TENANT!r} —— "
            f"免鉴权的 /auth/demo-login 会读到真客户的经营数据"
        )

    def test_ops_router_demo_tenant_is_generated_or_absent(self):
        assert _DEMO_GOLD_TENANT is None or _DEMO_GOLD_TENANT in _GENERATED_TENANTS, (
            f"演示数据源 {_DEMO_GOLD_TENANT!r} 不在生成租户白名单 "
            f"{sorted(_GENERATED_TENANTS)} 里 —— 先回答「那个租户的门店名是不是真店」"
        )

    def test_gold_reads_alias_targets_are_not_production(self):
        offenders = {k: v for k, v in DEMO_GOLD_TENANT_ALIASES.items()
                     if v in _PRODUCTION_TENANTS}
        assert not offenders, (
            f"gold_reads 的演示别名指向生产租户: {offenders} —— "
            f"这正是 2026-08-17 发现的那条链"
        )

    def test_gold_reads_alias_targets_are_generated(self):
        offenders = {k: v for k, v in DEMO_GOLD_TENANT_ALIASES.items()
                     if v not in _GENERATED_TENANTS}
        assert not offenders, (
            f"gold_reads 的演示别名指向非生成租户: {offenders}"
        )


class TestTurningTheRemapOffDoesNotLoseTheTenant:
    """⛔ 「不重映射」必须是**原样返回**，不能变成 None。

    少了这条，关掉映射的写法很容易写成 `return _DEMO_GOLD_TENANT`（= None），
    而 None 往下传到 RLS 上会读到空 —— **「空」和「这家店没数据」长得一模一样**
    （形态 A¹⁰：兜底把「我不知道」翻译成「是 0」）。
    """

    def test_demo_rest_keeps_its_own_id(self):
        for scoped in (False, True):
            got = demo_data_factory_for_code(
                "RESTAURANT_OPS_SALES_SUMMARY", "DEMO_REST", store_scoped=scoped)
            assert got == "DEMO_REST", f"store_scoped={scoped} 时租户变成了 {got!r}"

    def test_store_name_lookup_keeps_its_own_id(self):
        """`code is None` 那条（门店名解析）同样不许把租户弄丢。"""
        assert demo_data_factory_for_code(
            None, "DEMO_REST", store_scoped=True) == "DEMO_REST"

    def test_other_tenants_untouched(self):
        """阴性对照：非演示租户本来就不该被这个机制碰。"""
        for fid in ("MOCK_REST", "RES_3101_009", "R_XMX_CHAIN"):
            assert demo_data_factory_for_code(
                "RESTAURANT_OPS_SALES_SUMMARY", fid) == fid
            assert demo_data_factory_for_code(None, fid, store_scoped=True) == fid
