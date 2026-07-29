from mock_platform.callback import build_signature


def test_签名对相同输入稳定():
    a = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    b = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    assert a == b and len(a) == 64


def test_body变了签名就变():
    a = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    b = build_signature(b'{"maxSeq":6}', "1785300000", "n1", "sec")
    assert a != b


def test_nonce变了签名就变_防重放():
    a = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    b = build_signature(b'{"maxSeq":5}', "1785300000", "n2", "sec")
    assert a != b


def test_密钥变了签名就变():
    a = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "sec")
    b = build_signature(b'{"maxSeq":5}', "1785300000", "n1", "other")
    assert a != b
