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

def test_phone_format():
    m = Masker()
    p = m.phone("13800001111")
    assert len(p) == 11 and p.startswith("1") and p != "13800001111"

def test_numbers_never_touched():
    m = Masker()
    # masker only handles identity strings; numbers are not its job (engine skips numeric cols)
    assert not hasattr(m, "amount")
