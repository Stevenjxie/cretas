"""疑问词退役：门店抽取器按**封闭集合**判，不再靠黑名单穷举。

owner 裁定(2026-08-17)：「疑问词这种东西不能彻底解决的，这个东西以 llm 为主」
「然后疑问词直接退役」。这一层是 **LLM 之后的正则配对层** —— 它的活是把
模型判出来的范围配到真门店上，不是去理解句子含义。

缺陷长相(prod 实测)：
    「哪几家店在拖后腿」 → 没有找到名为「哪几家店」的门店
原黑名单列了 哪家/哪个/哪些/有没有，**唯独没有「哪几家」**。补一个词只会
等下一个变体(哪间/哪位/哪一家)。

⚠️ 阳性对照用的是 **prod `dim_store` 里的真店名**(2026-08-17 逐租户
   set_config 读出来的 198 个中抽样)，⛔ 不用我自己编的名字 ——
   编的名字证明不了「真目录里没有含疑问词的店名」。
"""
from smartbi.gold.restaurant.restaurant_ops_router import (
    _INTERROGATIVE_MARKERS,
    extract_store_mentions,
)


# 从 prod dim_store 实读(R_GML_DEMO / RES_3101_009)。这些**必须**被抽出来。
REAL_STORE_NAMES = (
    "桂满陇（五角场万达店）",
    "桂满陇（北京世纪金源店）",
    "山居满陇(北京三里屯店）",
    "桂满陇(中海环宇max店)",
)


class TestQuestionFragmentsAreNotStoreNames:
    def test_the_prod_failure_no_longer_extracts_a_store(self):
        """「哪几家店」是疑问片段, 不是店名 —— 这是 2026-08-17 的线上原话。"""
        assert extract_store_mentions("哪几家店在拖后腿") == []

    def test_variants_the_old_blacklist_would_have_missed(self):
        """封闭集合的价值在于**变体不用逐个补**。

        这些词老黑名单一个都没有(它只有 哪家/哪个/哪些/有没有)。
        """
        for query in (
            "哪几家店在拖后腿",
            "哪间店卖得最好",
            "哪一家店营业额最低",
            "什么店的毛利最高",
            "多少家店在亏钱",
        ):
            assert extract_store_mentions(query) == [], (
                f"{query!r} 里没有店名, 却抽出了 {extract_store_mentions(query)!r}"
            )

    def test_the_old_blacklist_words_still_blocked(self):
        """退役不是放松 —— 原来挡得住的现在照样挡得住。"""
        for query in ("上个月哪家店营收最高", "哪个门店客单价最高", "哪些店在亏"):
            assert extract_store_mentions(query) == []


class TestRealStoreNamesStillWork:
    """阳性对照。少了这一条, 上面全绿可能只是因为抽取器**什么都不抽**。"""

    def test_real_prod_store_names_are_still_extracted(self):
        for name in REAL_STORE_NAMES:
            got = extract_store_mentions(f"{name}这个月生意怎么样")
            assert got, f"真店名 {name!r} 一个都没抽出来 —— 抽取器被打死了"
            assert any(name in g or g in name for g in got), (
                f"真店名 {name!r} 抽成了 {got!r}"
            )

    def test_no_real_store_name_contains_an_interrogative_marker(self):
        """把「198 个真店名零误伤」这条实测钉住。

        ⚠️ 这条**不是**恒真式: 只要将来有店名叫「什么什么店」, 它就会红,
           而那正是我们想知道的时刻(那时封闭集合这条路就要重新设计)。
        """
        for name in REAL_STORE_NAMES:
            hits = [m for m in _INTERROGATIVE_MARKERS if m in name]
            assert not hits, f"真店名 {name!r} 含疑问词 {hits} —— 封闭集合会误伤它"


class TestTheCriterionIsClosedNotABlacklist:
    def test_marker_set_stays_small_and_closed(self):
        """疑问词是有限封闭集合。**变长**说明有人在把它当黑名单用。

        ⛔ 想加词之前先问: 它是疑问词, 还是又一个「不是店名的名词」?
           后者是无界集合, 属于另一层的问题。
        """
        assert len(_INTERROGATIVE_MARKERS) <= 8, (
            f"封闭集合涨到 {len(_INTERROGATIVE_MARKERS)} 个: "
            f"{_INTERROGATIVE_MARKERS} —— 它在退化成黑名单"
        )

    def test_the_single_char_marker_is_what_covers_the_variants(self):
        """「哪」这一个字盖住 哪家/哪个/哪几家/哪间/哪一家 全部变体。

        钉住它, 免得有人「为了精确」把它换回 哪家|哪个 —— 那就退回缺陷本身。
        """
        assert "哪" in _INTERROGATIVE_MARKERS
