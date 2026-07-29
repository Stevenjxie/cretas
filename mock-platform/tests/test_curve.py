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
