"""Restaurant management playbook — L1 of the deep-analysis enhancement.

Customer ask (Sheet 2026-7-20, spec docs/prd/2026-07-22-经营分析深度增强):
diagnostic answers say WHERE the problem is; decision makers also want the
industry-standard WAYS to fix it, presented for them to choose from.

L1 design constraints (deliberate):
- Content is hand-curated, generic restaurant-management methodology.  No
  fabricated statistics, no invented expert citations, no live web search —
  every claim must hold without a source lookup.
- Never auto-appended to diagnostic answers.  The same customer sheet ranks
  answer accuracy above advisory breadth, so playbooks are served only when
  explicitly asked ("XX的行业参考做法"), e.g. via the follow-up chips.
- Every playbook states applicability preconditions, risks, and a minimal
  validation step, mirroring the fail-closed answer style.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional, Tuple

PLAYBOOK_CODE = "RESTAURANT_OPS_PLAYBOOK"

# Phrases that make a query an explicit playbook request.
PLAYBOOK_TRIGGERS: Tuple[str, ...] = (
    "行业参考做法", "行业做法", "行业打法", "参考做法", "行业通行做法",
)


@dataclass(frozen=True)
class Playbook:
    title: str
    keywords: Tuple[str, ...]
    scene: str
    practices: Tuple[str, ...]
    preconditions: str
    risks: str
    validation: str


_PLAYBOOKS: Dict[str, Playbook] = {
    "margin": Playbook(
        title="毛利率偏低 / 毛利异常",
        keywords=("毛利", "毛利率", "利润"),
        scene="整体或单品毛利率低于预期，或毛利波动无法解释。",
        practices=(
            "菜单工程四象限：按「销量×毛利」把菜品分为明星（保）、耕马（提价或降本）、谜题（营销拉动）、瘦狗（评估下架），逐象限定动作。",
            "BOM 校准：抽 5-10 道主力菜实称用量与理论配方对比，偏差超过 5% 先修配方或出品标准，再谈定价。",
            "采购比价：对成本占比前 20% 的食材做三家比价与周价格跟踪，锁价或改采购节奏。",
            "定价带检查：对标商圈同类菜品价格带，毛利率低但价格已到带顶的菜品应从成本侧解决而非提价。",
        ),
        preconditions="有可信的菜品成本卡（BOM）与销售明细；成本覆盖率过低时先补数据再做菜单工程。",
        risks="只压成本易伤出品与复购；提价前未看价格带易掉客流；瘦狗菜可能承担引流或搭售角色，不宜直接下架。",
        validation="选 1-2 家门店、3-5 道菜执行 7-14 天，对比毛利率与销量变化，再决定推广。",
    ),
    "wastage": Playbook(
        title="食材损耗偏高",
        keywords=("损耗", "浪费", "报损"),
        scene="盘点损耗金额或比例持续偏高，或某类食材反复报损。",
        practices=(
            "损耗四分法：把损耗拆为边角料、过期报废、制作失误、盘点差异四类，分别对应切配标准、备货量、培训、盘点流程四种解法。",
            "称重抽查：对损耗 Top 食材做开档/收档称重记录一周，定位损耗发生环节。",
            "先进先出与效期标签：高危食材（水产、叶菜、预制半成品）强制贴效期标签并按效期出库。",
            "备货挂钩销售预测：按近 4 周同星期销量的均值设定日备货量，替代厨师长经验估备。",
        ),
        preconditions="有分食材的报损记录与定期盘点数据；无记录时先建立一周的称重台账。",
        risks="备货压得过低会导致高峰缺货与顾客流失；抽查若只做一天易被单日波动误导。",
        validation="盯住损耗 Top 3 食材的周损耗金额，连续两周收窄且无缺货投诉才算有效。",
    ),
    "slow_dish": Playbook(
        title="慢销 / 滞销菜品处置",
        keywords=("慢销", "滞销", "卖不动", "菜品优化"),
        scene="部分菜品销量长期垫底，占用备料与菜单空间。",
        practices=(
            "先判角色再动刀：确认慢销菜是否承担引流、搭售、招牌记忆点角色（看连带订单与套餐出现率），纯瘦狗才考虑下架。",
            "位置与呈现试验：菜单栏目位、图片、描述文案调整通常先于降价——低成本且可逆。",
            "套餐带动：把高毛利慢销菜与明星菜组套餐，验证套餐售价与毛利后小范围推。",
            "下架走灰度：先在 1-2 家店停售观察连带影响，再决定全线下架。",
        ),
        preconditions="有按菜品的销量与连带数据；新品上市不足 4 周不适用（数据未稳定）。",
        risks="误删带流量的菜会伤客群；频繁改菜单增加后厨与点单培训成本。",
        validation="调整后 2 周对比该菜销量、所在套餐销量与整单毛利，三者至少两项改善才保留改动。",
    ),
    "store_gap": Playbook(
        title="门店业绩落后 / 门店差距",
        keywords=("门店", "分店", "店铺", "业绩最好", "业绩差"),
        scene="同品牌门店间营收或毛利差距明显，落后门店原因不明。",
        practices=(
            "五因子拆解：把差距拆为客流、客单价、折扣率、菜品结构、时段结构五个因子，先定位主因子再行动。",
            "标杆复制：取头部门店的时段排班、主推菜组合、外摆/线上运营动作清单，落后店逐项对照执行。",
            "属地因素剥离：商圈客群、面积、租金结构不同的门店不直接对比绝对值，看同比与人效坪效。",
            "整改一店一单：每店只定 2-3 个当期动作并指定负责人，避免长清单无人闭环。",
        ),
        preconditions="有分店的营收、订单、折扣数据；至少 4 周数据避免单周波动误判。",
        risks="跨商圈硬性对标会打击门店士气并引发错误动作；一次压多个整改点通常全部落空。",
        validation="整改后 4 周看主因子指标（如客单价）相对自身基线的变化，而非直接对标头部店。",
    ),
    "slow_serving": Playbook(
        title="出餐慢 / 上菜慢",
        keywords=("出餐", "上菜", "出餐慢", "等餐"),
        scene="顾客等餐时间长、差评提及上菜慢，或高峰期出餐堆积。",
        practices=(
            "先打点再改流程：对 Top 10 菜品记录点单→出餐时间一周，区分是集中下单、备料不足还是工序瓶颈。",
            "峰前备料清单：按时段销量预测把可预制工序（切配、腌制、酱汁）前移到峰前完成。",
            "工序并行化：识别串行等待（如一个灶眼排队），通过设备/岗位调整改并行。",
            "菜单侧限流：高峰时段对制作超长的菜品做限量或引导替代，保整体出餐节奏。",
        ),
        preconditions="需要至少一周的出餐时间打点数据；无打点数据时先做记录，不要凭印象改流程。",
        risks="盲目加人不解决工序瓶颈；预制过多会推高损耗与出品新鲜度风险。",
        validation="改动后对比同时段平均出餐时长与超时单占比，连续一周改善再固化。",
    ),
    "ticket_size": Playbook(
        title="客单价偏低",
        keywords=("客单价", "客单"),
        scene="客流正常但客单价低于同类，或营收增长依赖单量。",
        practices=(
            "加购点位设计：在点单动线上设置小食、饮品、加料的明确加购提示（菜单角标、套餐差价）。",
            "套餐阶梯：设双人/多人套餐并控制套餐毛利率不低于单点组合，引导升单。",
            "高毛利小食搭配：用低制作成本小食做「加 X 元换购」，验证换购率与毛利后推广。",
            "服务话术标准化：结账前固定一句加购提示，话术只推 1 个品，避免报菜单式推销。",
        ),
        preconditions="有客单价与品类结构数据；客流下滑期不适用（先解决客流）。",
        risks="过度推销伤体验；套餐定价失守会拉低整体毛利。",
        validation="两周内看客单价、加购率与差评中「推销」类反馈，客单升且推销差评不升为有效。",
    ),
    "review": Playbook(
        title="差评 / 口碑偏弱",
        keywords=("差评", "评价", "口碑"),
        scene="平台低星占比升高或某类投诉集中。",
        practices=(
            "差评四类归因：出品、服务、环境、等待各配固定责任人与整改动作，逐条闭环而非统一道歉。",
            "高频差评菜清单：被点名 2 次以上的菜品进入周会复盘，明确改配方、改出品或下架。",
            "低星回访：对低星订单 24 小时内回访补救，平台回复率保持高位。",
            "好评引导合规化：只在体验完成后自然引导，不做诱导性返利（平台处罚风险）。",
        ),
        preconditions="能拿到分平台、分门店的评价明细；样本过少的新店先看原文不看比例。",
        risks="只刷好评不改根因会在爆单期集中反噬；补救话术模板化会被顾客识别。",
        validation="四周看低星占比与被点名菜品重复率，两者同时下降才算根因改善。",
    ),
    "stocktake": Playbook(
        title="盘点差异 / 库存不准",
        keywords=("盘点", "盘亏", "盘盈", "库存差"),
        scene="账实差异反复出现，或个别食材盘差集中。",
        practices=(
            "盘点分级：高值高损食材日盘、常规食材周盘、包材月盘，代替一刀切全量盘点。",
            "差异三查：盘差先查收货计量、领用登记、报损漏记三个环节，再怀疑丢失。",
            "双人复核：盘差 Top 品类由厨师长与店长交叉复盘一次，排除口径问题。",
            "口径固化：统一「毛重/净重、含包装/不含」的计量口径并写入盘点单模板。",
        ),
        preconditions="有盘点记录与领用/报损台账；无台账先补流程再谈差异分析。",
        risks="盘点频率加码会占用高峰人力；把口径问题当丢失处理会伤团队信任。",
        validation="连续两轮盘点差异金额收窄、Top 品类不重复出现为有效。",
    ),
    "staffing": Playbook(
        title="人效 / 排班优化",
        keywords=("排班", "人效", "人手", "用工"),
        scene="人力成本占比偏高，或高峰缺人低峰闲置并存。",
        practices=(
            "峰谷排班：按半小时颗粒的单量曲线排班，高峰加临时工/小时工而非全班次加人。",
            "多能工培养：前厅收银/传菜、后厨切配/打荷交叉培训，低峰互补岗位。",
            "工时对齐营业结构：外卖占比高的时段前厅减员后厨保员。",
            "人效看单量不看营收：用「每工时处理单量」衡量，剔除客单价波动干扰。",
        ),
        preconditions="有分时段单量数据与排班表；员工数过少的小店优化空间有限。",
        risks="压工时过狠导致高峰体验崩塌与员工流失，隐性成本高于工资节省。",
        validation="两周对比每工时单量与高峰出餐时长，人效升且出餐不变慢为有效。",
    ),
    "revenue_drop": Playbook(
        title="营收下滑",
        keywords=("营收下滑", "营业额下降", "生意差", "下滑"),
        scene="营收连续走低，原因不明。",
        practices=(
            "量价拆解：营收=单量×客单价，先定位掉的是单量还是客单，动作完全不同。",
            "新老客拆解：会员/平台数据区分新客获取与老客复购哪个走弱。",
            "时段与渠道定位：分堂食/外卖、午市/晚市定位下滑集中点，避免全店大动作。",
            "外因核对：先排除商圈施工、天气、节假日错位等外因，再改内部经营。",
        ),
        preconditions="至少 8 周的分渠道分时段数据；单周下滑不构成趋势。",
        risks="未定位就打折促销会直接损毛利且难以退出；把节假日错位当趋势会误判。",
        validation="锁定主因子后只对该因子做动作，两周看该因子是否回升。",
    ),
}


def match_playbook_topic(query: str) -> Optional[str]:
    """Topic slug when the query explicitly asks for industry practice."""
    if not query:
        return None
    if not any(trigger in query for trigger in PLAYBOOK_TRIGGERS):
        return None
    for slug, book in _PLAYBOOKS.items():
        if any(kw in query for kw in book.keywords):
            return slug
    return "__menu__"


def playbook_menu_text() -> str:
    topics = "、".join(book.title for book in _PLAYBOOKS.values())
    return (
        "可以查看以下主题的行业参考做法：" + topics + "。"
        "请在提问中带上具体主题，例如「食材损耗偏高的行业参考做法」。"
    )


def render_playbook(slug: str) -> Optional[str]:
    book = _PLAYBOOKS.get(slug)
    if not book:
        return None
    practices = "\n".join(f"{i}. {p}" for i, p in enumerate(book.practices, 1))
    return (
        f"### {book.title} — 行业参考做法\n"
        f"适用场景：{book.scene}\n\n"
        f"{practices}\n\n"
        f"适用前提：{book.preconditions}\n"
        f"主要风险：{book.risks}\n"
        f"如何验证有效：{book.validation}\n\n"
        "以上为行业通行方法整理，供结合本店数据挑选；具体数字以您店内诊断结果为准。"
    )


async def resolve_playbook(smartbi_pool, factory_id: str, **kwargs):
    """Resolver for RESTAURANT_OPS_PLAYBOOK. Static curated content — no DB
    reads, no tenant data, so it can never leak numbers or cross tenants."""
    from smartbi.gold.restaurant_ops_router import OpsAnswer

    query = str(kwargs.get("query") or "")
    slug = match_playbook_topic(query) or "__menu__"
    if slug == "__menu__":
        return OpsAnswer(
            code=PLAYBOOK_CODE,
            title="行业参考做法",
            answer_text=playbook_menu_text(),
            charts=[], kpis=[],
            meta={"playbook": "menu"},
        )
    text = render_playbook(slug)
    return OpsAnswer(
        code=PLAYBOOK_CODE,
        title=f"{_PLAYBOOKS[slug].title} — 行业参考做法",
        answer_text=text or playbook_menu_text(),
        charts=[], kpis=[],
        meta={"playbook": slug},
    )
