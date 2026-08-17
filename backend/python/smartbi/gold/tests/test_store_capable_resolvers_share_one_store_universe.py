"""能按门店分组的 resolver，门店宇宙必须是同一个 —— 否则两个回答对不上账。

## 缺陷（2026-08-17，我自己当天造的）

给渠道构成加了「各门店渠道构成」表之后，同一个 `DEMO_REST` 账号：

```
「哪家店外卖占比最高」(store_scoped=True)  -> RES_3101_009, 30 家店
「外卖占了几成」      (store_scoped=False) -> DEMO_REST,    27 家店
```

两套门店、两套营收。而 `demo_data_factory_for_code` 的 docstring 写着它存在的
理由**正是防这个**：

> so their store universe agrees with the Java-native ranking tools (previously
> the rank said one store was #1 while the sales summary praised a store from a
> different data space)

⚠️ 形状是「机制在、没接上」：`store_scoped` 这个参数存在的全部意义，就是让调用方
声明「我这个答案是按门店的」。我加门店表时**没有人告诉租户解析这件事**。

▎**加能力时要同时问一句：它有没有进入某个「必须一致」的族。**

## 为什么是棘轮而不是通则

建表当天存量里 `BUSINESS_OPTIMIZATION` / `STAFFING_ADVICE` 也能按门店分组，
却不在映射表里。写成硬通则 ⇒ **第一天就红** ⇒ 这道闸会被关掉（本仓形态 E：
宁可窄而可信，不要宽而被关掉）。所以冻结存量、只许变短。
"""
from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS
from smartbi.gold.restaurant.restaurant_ops_router import (
    _DEMO_GOLD_MAPPED_CODES,
    _STORE_CAPABLE_BUT_NOT_DEMO_MAPPED,
    demo_data_factory_for_code,
)

_DEMO = "DEMO_REST"


def _store_capable():
    return {code for code, dims in _RESOLVER_DIMENSIONS.items() if "store" in dims}


class TestEveryStoreCapableResolverIsAccountedFor:
    def test_no_unregistered_store_capable_resolver(self):
        """新增一个能按门店分组的 resolver ⇒ 要么进映射表，要么显式登记。

        ⛔ 不做决定的后果是**静默**的：那个答案的门店宇宙与排行/汇总不同，
           两个数都「对」，只是不是同一批店 —— 没有任何报错。
        """
        undecided = _store_capable() - set(_DEMO_GOLD_MAPPED_CODES) \
            - set(_STORE_CAPABLE_BUT_NOT_DEMO_MAPPED)
        assert not undecided, (
            f"这些 resolver 能按门店分组，却既不在 `_DEMO_GOLD_MAPPED_CODES`、"
            f"也没登记进 `_STORE_CAPABLE_BUT_NOT_DEMO_MAPPED`: {sorted(undecided)}\n"
            f"⇒ 二选一：进映射表（门店宇宙与排行/汇总一致），"
            f"或登记为已知不一致并写明理由。"
        )

    def test_the_exception_list_is_a_ratchet(self):
        """存量豁免只许变短。⚠️ 这条钉的是**方向**，不是数量本身。"""
        # 2026-08-17: 2 -> 3 -> 5。后厨三张表(损耗/盘点/领料)当天先后变成
        # store-capable, 各自走了闸给的「显式登记并写理由」那一支
        # (理由见常量注释: 进映射表会让 DEMO_REST 改读一个**同样没有门店**
        #  的租户, 只会更空)。⚠️ 每次上调都必须像这样带**具名理由**,
        # 否则这个上限就成了橡皮筋。
        # 🔑 解冻条件已写死: demo 那条管线(`seed_demo_rest_ops.py`)改成门店
        #    感知之后, 这三条要重新裁一次 —— 那时名单应当变短。
        assert len(_STORE_CAPABLE_BUT_NOT_DEMO_MAPPED) <= 5, (
            f"存量豁免涨到 {len(_STORE_CAPABLE_BUT_NOT_DEMO_MAPPED)} 个: "
            f"{sorted(_STORE_CAPABLE_BUT_NOT_DEMO_MAPPED)} —— "
            f"它在变成一张「反正也没人看」的名单"
        )

    def test_the_exception_list_has_no_stale_entries(self):
        """已经进了映射表的，就该从豁免名单里去掉（反向棘轮）。"""
        stale = set(_STORE_CAPABLE_BUT_NOT_DEMO_MAPPED) & set(_DEMO_GOLD_MAPPED_CODES)
        assert not stale, f"这些既在映射表又在豁免名单里: {sorted(stale)}"

    def test_the_exception_list_only_lists_store_capable_codes(self):
        """名单里不许躺着已经不能按门店分组的死条目。"""
        not_store_capable = set(_STORE_CAPABLE_BUT_NOT_DEMO_MAPPED) - _store_capable()
        assert not not_store_capable, (
            f"这些已经不能按门店分组了，该从豁免名单删掉: {sorted(not_store_capable)}"
        )


class TestChannelMixNowSharesTheStoreUniverse:
    def test_channel_mix_reads_the_seeded_gold_tenant(self):
        """本次缺陷的直接回归：渠道构成必须和排行/汇总读同一个租户。"""
        assert demo_data_factory_for_code(
            "RESTAURANT_OPS_CHANNEL_MIX", _DEMO) == demo_data_factory_for_code(
            "RESTAURANT_OPS_SALES_SUMMARY", _DEMO), (
            "渠道构成与销售汇总落到了不同租户 —— 同一个账号会看到两套门店"
        )

    def test_it_no_longer_depends_on_how_the_question_was_phrased(self):
        """缺陷的长相是「同一个 resolver，问法不同 ⇒ 租户不同」。

        `store_scoped` 由问句推出来，⛔ 不该决定读哪个租户的门店宇宙。
        """
        scoped = demo_data_factory_for_code(
            "RESTAURANT_OPS_CHANNEL_MIX", _DEMO, store_scoped=True)
        plain = demo_data_factory_for_code(
            "RESTAURANT_OPS_CHANNEL_MIX", _DEMO, store_scoped=False)
        assert scoped == plain, (
            f"问法不同就换了租户: store_scoped=True -> {scoped!r}, "
            f"False -> {plain!r} —— 老板会看到两套门店数"
        )

    def test_non_demo_tenants_are_untouched(self):
        """阴性对照：只有 DEMO_REST 走这条重映射，别的租户一个字都不动。

        少了这条，上面「两次一致」可能只是因为函数对谁都返回同一个值。
        """
        for fid in ("RES_3101_009", "R_XMX_CHAIN", "R_GML_DEMO"):
            assert demo_data_factory_for_code(
                "RESTAURANT_OPS_CHANNEL_MIX", fid, store_scoped=True) == fid
            assert demo_data_factory_for_code(
                "RESTAURANT_OPS_SALES_SUMMARY", fid) == fid
