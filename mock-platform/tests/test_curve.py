from mock_platform.world.curve import daily_minute_quota, minute_weight


def test_午市晚市是双峰且凌晨无客流():
    assert minute_weight("mall", 3 * 60) == 0.0            # 凌晨 3 点
    lunch = minute_weight("mall", 12 * 60 + 30)
    dinner = minute_weight("mall", 19 * 60)
    afternoon = minute_weight("mall", 15 * 60 + 30)
    assert lunch > afternoon > 0
    assert dinner > afternoon


def test_商场店晚市比社区店更陡():
    mall = minute_weight("mall", 19 * 60) / minute_weight("mall", 12 * 60 + 30)
    community = minute_weight("community", 19 * 60) / minute_weight("community", 12 * 60 + 30)
    assert mall > community


def test_分钟配额求和精确等于当日单量():
    quota = daily_minute_quota("flagship", 200)
    assert len(quota) == 1440
    assert sum(quota) == 200
    assert all(q >= 0 for q in quota)
    assert quota[3 * 60] == 0


def test_营业时段内没有死区_下午必须有零星客流():
    """🔴 模块顶部承诺「14:00-17:00 客流走低但不为 0」, 但 2026-07-29 实测
    发现只有两个钟形时下午权重小到被整数量化抹成 0 —— 承诺没兑现, 死区只是
    从"窗口判断造成"变成了"权重太小造成"。加基础客流权重才真正做到。

    ⚠️ 这条在**接近生产的单量**下断言。低单量(如 200/店/天)时下午每分钟
    应得远小于 1 单, 整数配额下必然是 0 —— 那是低单量的固有限制, 不是 bug。
    """
    quota = daily_minute_quota("mall", 2000)
    hourly = [sum(quota[h * 60:(h + 1) * 60]) for h in range(24)]
    for h in range(11, 21):
        assert hourly[h] > 0, f"{h}:00 是死区, 全天分布={hourly[11:21]}"


def test_双峰形状仍然清晰_没被基础客流压平():
    """基础客流不能大到把午晚市高峰抹平, 否则曲线就不像真实门店了。"""
    quota = daily_minute_quota("mall", 2000)
    hourly = [sum(quota[h * 60:(h + 1) * 60]) for h in range(24)]
    lunch, dinner, afternoon = hourly[12], hourly[19], hourly[15]
    assert dinner > lunch, "商场店晚市该比午市高(下班人流)"
    assert lunch >= afternoon * 3, f"午市 {lunch} 相对下午 {afternoon} 峰形不明显"
    assert dinner >= afternoon * 4, f"晚市 {dinner} 相对下午 {afternoon} 峰形不明显"


def test_营业时段外仍然恒为0():
    """加了基础客流之后, 非营业时段必须仍然是 0 —— 基础客流是"营业中的
    零星单", 不是"24 小时都有人"。"""
    quota = daily_minute_quota("mall", 2000)
    assert sum(quota[:11 * 60]) == 0, "开业前不该有单"
    assert sum(quota[21 * 60:]) == 0, "打烊后不该有单"
