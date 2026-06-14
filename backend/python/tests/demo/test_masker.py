from scripts.demo.masker import Masker

def test_same_input_same_output():
    m = Masker()
    assert m.person("张伟") == m.person("张伟")      # deterministic
    assert m.company("青花椒餐饮") == m.company("青花椒餐饮")

def test_different_input_different_output():
    m = Masker()
    assert m.person("张伟") != m.person("李娜")

def test_brand_token_scrubbed_from_freetext():
    m = Masker()
    out = m.freetext("青花椒门店备注：客户王芳要求加辣")
    assert "青花椒" not in out
    assert "王芳" not in out or out == "(示例备注)"  # scrub strategy: replace whole field

def test_cuisine_substitutes_spice_brand():
    m = Masker()
    assert m.cuisine("招牌青花椒鱼") == "招牌藤椒鱼"  # spice kept as neutral pepper

def test_cuisine_scrubs_factory_client_brands():
    m = Masker()
    # client brand removed from product name
    assert "叮咚好食光" not in m.cuisine("叮咚好食光卤猪蹄（去大骨）")
    assert m.cuisine("叮咚好食光卤猪蹄（去大骨）") == "卤猪蹄（去大骨）"
    # retailer brand in remark -> 客户 (readable)
    assert "盒马" not in m.cuisine("盒马春节备货")
    assert "永辉" not in m.cuisine("永辉3月预订-草稿")

def test_cuisine_strips_customer_parenthetical():
    m = Masker()
    assert m.cuisine("墨鱼圈 (永辉超市)") == "墨鱼圈"
    assert m.cuisine("恒尔冷冻猪蹄 (级联测试客户606188)") == "恒尔冷冻猪蹄"
    assert m.cuisine("卤猪蹄（去大骨） (上海海壹佰米网络科技有限公司)") == "卤猪蹄（去大骨）"

def test_cuisine_cleans_test_cruft():
    m = Masker()
    assert m.cuisine("带鱼段_updated") == "带鱼段"
    assert m.cuisine("集成测试产品") == "什锦海鲜拼盘"
    assert m.cuisine("测试产品B4新版") == "黄鱼片精装"

def test_cuisine_keeps_clean_names():
    m = Masker()
    assert m.cuisine("黄鱼片") == "黄鱼片"  # no brand -> unchanged
    assert m.cuisine("干式熟成鸡（半只）") == "干式熟成鸡（半只）"  # spec paren kept

def test_phone_format():
    m = Masker()
    p = m.phone("13800001111")
    assert len(p) == 11 and p.startswith("1") and p != "13800001111"

def test_numbers_never_touched():
    m = Masker()
    # masker only handles identity strings; numbers are not its job (engine skips numeric cols)
    assert not hasattr(m, "amount")
