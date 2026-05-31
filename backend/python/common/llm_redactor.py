from __future__ import annotations
"""
LLM 出境脱敏器 (P0 — 数据主权).

卖点/护城河是"客户数据不喂公有 AI", 但系统会把 SmartBI 数据发给 DashScope(通义)
做 LLM 洞察叙述 + 意图兜底。本模块在 prompt 出境前把客户名/门店名/菜品名/PII 等
可识别身份信息替换成稳定占位符 (门店A / 客户B / 电话C ...), LLM 只看到占位 + 数字,
拿回结果后再把占位还原成真名展示给用户 —— 数字/分析 0 损失, 真名不出境。

设计要点:
- **中文专名正则抓不到** (人名/店名/菜品名)。唯一可靠手段 = 从 DataFrame 命中敏感字段
  的列预抽全部值, 在文本里做精确字符串替换 (长值优先, 防 '青花椒' 误伤 '青花椒大融城店')。
  见 `extract_sensitive_values_from_df` + `RedactionScope`。
- PII (手机/邮箱/身份证) 用正则兜底, 适用于自由文本 (用户问题口述)。
- 占位稳定: 同一明文在一次请求内始终映射同一占位 (跨 provider fallback retry 不变)。
- 还原: LLM 输出含占位 → `restore_*` 用 placeholder_map 还原真名。

choke point (common/llm_router.call_chain / call_chain_stream) 在发出前调用
`redact_payload_for_egress`; prompt 构建方 (smartbi insights generator) 用
`redaction_scope()` + `register_df_in_scope` 注册 df 真名, 输出时 `restore_*`。
"""
import contextvars
import hashlib
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

try:  # pandas only needed for the df extraction helper; keep import soft.
    import pandas as pd  # type: ignore
except Exception:  # pragma: no cover
    pd = None  # type: ignore


# ── 结构化字段名单 (精确 key, 小写) → 占位类型前缀 ──────────────────────────
_SENSITIVE_KEYS: Dict[str, str] = {
    "customername": "客户", "customer_name": "客户", "buyername": "客户", "buyer_name": "客户",
    "clientname": "客户", "client_name": "客户",
    "suppliername": "供应商", "supplier_name": "供应商", "vendorname": "供应商", "vendor_name": "供应商",
    "storename": "门店", "store_name": "门店", "shopname": "门店", "shop_name": "门店",
    "branchname": "门店", "branch_name": "门店", "merchantname": "商户", "merchant_name": "商户",
    "brandname": "品牌", "brand_name": "品牌",
    "companyname": "公司", "company_name": "公司", "factoryname": "工厂", "factory_name": "工厂",
    "contactname": "联系人", "contact_name": "联系人", "operatorname": "操作员", "operator_name": "操作员",
    "username": "用户", "user_name": "用户", "realname": "姓名", "real_name": "姓名",
    "employeename": "员工", "employee_name": "员工", "managername": "负责人", "manager_name": "负责人",
    "owner": "负责人",
    "phone": "电话", "mobile": "电话", "tel": "电话", "telephone": "电话",
    "address": "地址", "addr": "地址", "email": "邮箱", "idcard": "证件", "id_card": "证件",
    # 菜品/产品单店专名 → 占位 (品类 category 不在名单, 保留)
    "dishname": "菜品", "dish_name": "菜品", "productname": "产品", "product_name": "产品",
    "itemname": "商品", "item_name": "商品", "goodsname": "商品", "goods_name": "商品", "sku_name": "商品",
}

# 列名 substring 匹配 (在 lower+去分隔符 上找)。英文。
_SENSITIVE_COL_SUBSTR_EN: List[Tuple[str, str]] = [
    ("customername", "客户"), ("buyername", "客户"), ("clientname", "客户"), ("customer", "客户"),
    ("suppliername", "供应商"), ("vendorname", "供应商"), ("supplier", "供应商"),
    ("storename", "门店"), ("shopname", "门店"), ("branchname", "门店"), ("merchantname", "商户"),
    ("brandname", "品牌"), ("companyname", "公司"), ("factoryname", "工厂"),
    ("contactname", "联系人"), ("contactperson", "联系人"), ("operatorname", "操作员"),
    ("realname", "姓名"), ("employeename", "员工"), ("managername", "负责人"),
    ("dishname", "菜品"), ("productname", "产品"), ("itemname", "商品"), ("goodsname", "商品"), ("skuname", "商品"),
    ("phone", "电话"), ("mobile", "电话"), ("telephone", "电话"),
    ("address", "地址"), ("email", "邮箱"), ("idcard", "证件"),
]
# 列名 substring 匹配。中文 (在原串上找)。
_SENSITIVE_COL_SUBSTR_ZH: List[Tuple[str, str]] = [
    ("客户名", "客户"), ("往来单位", "客户"), ("购货单位", "客户"), ("买方", "客户"), ("客户", "客户"),
    ("供应商", "供应商"), ("供货商", "供应商"), ("销货单位", "供应商"), ("卖方", "供应商"),
    ("门店", "门店"), ("店名", "门店"), ("店铺", "门店"), ("分店", "门店"), ("网点", "门店"), ("商户", "商户"),
    ("品牌", "品牌"), ("公司名", "公司"), ("企业名", "公司"), ("工厂名", "工厂"), ("厂名", "工厂"),
    ("联系人", "联系人"), ("负责人", "负责人"), ("经办人", "联系人"), ("业务员", "联系人"),
    ("收货人", "联系人"), ("送货人", "联系人"), ("姓名", "姓名"), ("操作员", "操作员"), ("用户名", "用户"), ("员工", "员工"),
    ("电话", "电话"), ("手机", "电话"), ("联系方式", "电话"), ("地址", "地址"), ("邮箱", "邮箱"), ("身份证", "证件"),
    ("菜品名", "菜品"), ("菜名", "菜品"), ("品名", "菜品"), ("产品名", "产品"), ("商品名", "商品"), ("货品名", "商品"),
]
# 出现这些词的列 = 类别/枚举, 不是身份标识 → 不脱敏 (避免把 VIP/普通/牛肉 这类通用词占位掉)。
_CATEGORY_EXCLUDE = ["类型", "类别", "分类", "级别", "等级", "状态", "属性",
                     "category", "type", "level", "status", "grade", "kind"]

_PII_PATTERNS: List[Tuple[str, "re.Pattern"]] = [
    ("证件", re.compile(r"\b\d{17}[\dXx]\b")),
    ("邮箱", re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")),
    ("电话", re.compile(r"\b1[3-9]\d{9}\b")),
    ("电话", re.compile(r"\b0\d{2,3}-?\d{7,8}\b")),
]

# 一个值长度超过这个上限就不当作"专名"替换 (避免把整段叙述/JSON 误当一个名字)。
_MAX_NAME_LEN = 40
# 单列最多预抽多少个 distinct 值进 known_values (内存上限)。
_MAX_DISTINCT_PER_COL = 5000


@dataclass
class RedactionResult:
    text: str = ""
    placeholder_map: Dict[str, str] = field(default_factory=dict)  # placeholder -> real
    redacted_field_names: List[str] = field(default_factory=list)
    redacted_count: int = 0

    @property
    def did_redact(self) -> bool:
        return self.redacted_count > 0


class PlaceholderAllocator:
    """稳定占位: 同一 (类型, 明文) → 同一占位 (类型前缀 + 字母序号)。

    A3: 一次请求内复用同一实例 → provider fallback retry 间占位不变。
    全部方法同步无 await → 在 asyncio 单线程下对 gather 子协程是原子的, 无竞态。
    """

    _L = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    def __init__(self) -> None:
        self._by_value: Dict[Tuple[str, str], str] = {}
        self._counters: Dict[str, int] = {}

    def alloc(self, type_label: str, value: str) -> str:
        key = (type_label, value)
        if key in self._by_value:
            return self._by_value[key]
        idx = self._counters.get(type_label, 0)
        letter = self._L[idx % 26]
        suffix = "" if idx < 26 else str(idx // 26)
        ph = f"{type_label}{letter}{suffix}"
        self._counters[type_label] = idx + 1
        self._by_value[key] = ph
        return ph


def stable_factory_alias(factory_id: str) -> str:
    return f"FACTORY_{hashlib.sha256(factory_id.encode()).hexdigest()[:6]}"


# ── 文本/结构化脱敏原语 ─────────────────────────────────────────────────────
def redact_text(
    text: str,
    *,
    allocator: PlaceholderAllocator,
    factory_id: Optional[str] = None,
    known_values: Optional[Dict[str, str]] = None,
) -> RedactionResult:
    """脱敏单段文本: (1) 已知敏感值精确替换 [长优先] (2) factory_id 占位 (3) PII 正则。"""
    if not text:
        return RedactionResult(text=text or "")
    pmap: Dict[str, str] = {}
    count = 0
    out = text

    # (1) 已知敏感值 (中文专名核心轨道) — 长值优先, 防 '青花椒' 误伤 '青花椒大融城店'
    if known_values:
        for original in sorted(known_values, key=len, reverse=True):
            if original and original in out:
                ph = known_values[original]
                count += out.count(original)
                out = out.replace(original, ph)
                pmap[ph] = original

    # (2) factory_id
    if factory_id and factory_id in out:
        alias = stable_factory_alias(factory_id)
        count += out.count(factory_id)
        out = out.replace(factory_id, alias)
        pmap[alias] = factory_id

    # (3) PII 正则兜底
    for type_label, pat in _PII_PATTERNS:
        def _sub(m: "re.Match") -> str:
            nonlocal count
            ph = allocator.alloc(type_label, m.group(0))
            pmap[ph] = m.group(0)
            count += 1
            return ph

        out = pat.sub(_sub, out)

    return RedactionResult(text=out, placeholder_map=pmap, redacted_count=count)


def redact_dict(
    obj: Any, *, allocator: PlaceholderAllocator, factory_id: Optional[str] = None,
) -> Tuple[Any, RedactionResult]:
    """结构化 dict/list 递归脱敏: 命中 _SENSITIVE_KEYS 的字段值 → 占位。"""
    result = RedactionResult()

    def _walk(node: Any) -> Any:
        if isinstance(node, dict):
            out = {}
            for k, v in node.items():
                klow = str(k).lower()
                tl = _SENSITIVE_KEYS.get(klow) or _SENSITIVE_KEYS.get(
                    klow.replace("-", "").replace("_", "")
                )
                if tl and isinstance(v, str) and v.strip():
                    ph = allocator.alloc(tl, v)
                    result.placeholder_map[ph] = v
                    result.redacted_field_names.append(str(k))
                    result.redacted_count += 1
                    out[k] = ph
                else:
                    out[k] = _walk(v)
            return out
        if isinstance(node, list):
            return [_walk(x) for x in node]
        return node

    return _walk(obj), result


def redact_payload(
    payload: Dict[str, Any], *,
    allocator: PlaceholderAllocator,
    factory_id: Optional[str] = None,
    known_values: Optional[Dict[str, str]] = None,
) -> Tuple[Dict[str, Any], RedactionResult]:
    """对 OpenAI-style payload 的 messages content 逐段脱敏 (str 或 [{text}] 两种 shape)。"""
    merged = RedactionResult()
    new_payload = {**payload}
    new_messages: List[Any] = []
    for msg in payload.get("messages", []):
        new_msg = {**msg}
        content = msg.get("content")
        if isinstance(content, str):
            r = redact_text(content, allocator=allocator, factory_id=factory_id, known_values=known_values)
            new_msg["content"] = r.text
            merged.placeholder_map.update(r.placeholder_map)
            merged.redacted_count += r.redacted_count
        elif isinstance(content, list):
            parts = []
            for part in content:
                if isinstance(part, dict) and isinstance(part.get("text"), str):
                    r = redact_text(part["text"], allocator=allocator, factory_id=factory_id, known_values=known_values)
                    parts.append({**part, "text": r.text})
                    merged.placeholder_map.update(r.placeholder_map)
                    merged.redacted_count += r.redacted_count
                else:
                    parts.append(part)
            new_msg["content"] = parts
        new_messages.append(new_msg)
    new_payload["messages"] = new_messages
    return new_payload, merged


def restore_text(text: str, placeholder_map: Dict[str, str]) -> str:
    """LLM 输出占位还原回真名。长占位优先 (门店A1 先于 门店A, 防子串误伤)。"""
    if not text or not placeholder_map:
        return text
    for ph in sorted(placeholder_map, key=len, reverse=True):
        text = text.replace(ph, placeholder_map[ph])
    return text


def restore_obj(obj: Any, placeholder_map: Dict[str, str]) -> Any:
    """递归还原 dict/list 中所有字符串字段 (用于结构化 insight 输出)。"""
    if not placeholder_map:
        return obj
    if isinstance(obj, str):
        return restore_text(obj, placeholder_map)
    if isinstance(obj, dict):
        return {k: restore_obj(v, placeholder_map) for k, v in obj.items()}
    if isinstance(obj, list):
        return [restore_obj(x, placeholder_map) for x in obj]
    return obj


# ── DataFrame 敏感列 → 已知值抽取 ───────────────────────────────────────────
def sensitive_type_for_column(col_name: Any) -> Optional[str]:
    """返回该列名对应的脱敏类型前缀, 非敏感列返回 None。"""
    s = str(col_name).strip()
    low = s.lower()
    low_stripped = low.replace("-", "").replace("_", "").replace(" ", "")
    # 类别/枚举列 → 不脱敏 (即便含 '客户' 等词, 如 '客户类型')
    if any(x in s or x in low for x in _CATEGORY_EXCLUDE):
        return None
    if low_stripped in _SENSITIVE_KEYS or low in _SENSITIVE_KEYS:
        return _SENSITIVE_KEYS.get(low_stripped) or _SENSITIVE_KEYS.get(low)
    for pat, tl in _SENSITIVE_COL_SUBSTR_EN:
        if pat in low_stripped:
            return tl
    for pat, tl in _SENSITIVE_COL_SUBSTR_ZH:
        if pat in s:
            return tl
    return None


def extract_sensitive_values_from_df(df: "pd.DataFrame") -> Dict[str, List[str]]:
    """从 df 的敏感列预抽全部 distinct 值, 返回 {类型前缀: [值...]}。

    只看 object/string dtype 列 (实体名不会是数值)。数字/日期/类别列不动。
    """
    out: Dict[str, List[str]] = {}
    if pd is None or df is None or getattr(df, "empty", True):
        return out
    for col in df.columns:
        tl = sensitive_type_for_column(col)
        if not tl:
            continue
        try:
            series = df[col]
            if series.dtype != object and str(series.dtype) != "string":
                continue
            seen = out.setdefault(tl, [])
            for v in series.dropna().unique().tolist():
                sv = str(v).strip()
                if sv and 0 < len(sv) <= _MAX_NAME_LEN and sv not in seen:
                    seen.append(sv)
                    if len(seen) >= _MAX_DISTINCT_PER_COL:
                        break
        except Exception:
            continue
    return {k: v for k, v in out.items() if v}


# ── 请求级脱敏 scope (ContextVar) ───────────────────────────────────────────
# choke point (llm_router) 读它; prompt 构建方 (insights generator) 写它。
# 镜像 common/llm_metrics 已有的 _llm_caller / _llm_factory ContextVar 模式。
@dataclass
class RedactionMeta:
    sanitized: bool = False
    redacted_count: int = 0
    redacted_fields: List[str] = field(default_factory=list)


class RedactionScope:
    """一次 insight 请求的共享脱敏上下文: allocator + known_values + 累计 placeholder_map。"""

    def __init__(self) -> None:
        self.allocator = PlaceholderAllocator()
        self.known_values: Dict[str, str] = {}        # real -> placeholder
        self.placeholder_map: Dict[str, str] = {}      # placeholder -> real (for restore)
        self.redacted_count: int = 0
        self.redacted_fields: List[str] = []

    def register_values(self, values_by_type: Dict[str, List[str]]) -> None:
        for type_label, vals in values_by_type.items():
            for v in vals:
                if isinstance(v, str) and v.strip() and v not in self.known_values:
                    ph = self.allocator.alloc(type_label, v)
                    self.known_values[v] = ph
                    self.placeholder_map[ph] = v

    def register_df(self, df: "pd.DataFrame") -> None:
        vbt = extract_sensitive_values_from_df(df)
        if vbt:
            self.register_values(vbt)
            for tl in vbt:
                if tl not in self.redacted_fields:
                    self.redacted_fields.append(tl)


_redaction_scope: contextvars.ContextVar[Optional[RedactionScope]] = contextvars.ContextVar(
    "llm_redaction_scope", default=None
)


class redaction_scope:
    """上下文管理器 (用于纯 await 的非流式路径, set+reset 安全)。"""

    def __init__(self) -> None:
        self._token: Optional[contextvars.Token] = None
        self.scope: Optional[RedactionScope] = None

    def __enter__(self) -> RedactionScope:
        self.scope = RedactionScope()
        self._token = _redaction_scope.set(self.scope)
        return self.scope

    def __exit__(self, *_exc) -> None:
        if self._token is not None:
            _redaction_scope.reset(self._token)


def begin_redaction_scope_no_reset() -> RedactionScope:
    """为流式路径设置 scope 但不取 reset token (镜像 _set_llm_caller —— 异步生成器
    跨 task 边界, reset 会报 'Token created in different Context'; 请求级 task 隔离负责清理)。"""
    scope = RedactionScope()
    _redaction_scope.set(scope)
    return scope


def current_redaction_scope() -> Optional[RedactionScope]:
    return _redaction_scope.get()


def register_df_in_scope(df: "pd.DataFrame") -> None:
    scope = _redaction_scope.get()
    if scope is not None:
        scope.register_df(df)


def restore_in_scope(value: Any) -> Any:
    scope = _redaction_scope.get()
    if scope is None or not scope.placeholder_map:
        return value
    return restore_obj(value, scope.placeholder_map)


def redact_payload_for_egress(payload: Dict[str, Any]) -> Tuple[Dict[str, Any], RedactionMeta]:
    """choke point 调用: 用当前 scope (若有) + factory + PII 脱敏 payload。

    - scope 活跃 (insight 路径): 用 scope.allocator + scope.known_values, 把命中的
      占位累计回 scope.placeholder_map 供输出还原。
    - 无 scope (Java 意图 / chat 等): 临时 allocator, 仅 PII + factory, 不还原。
    factory_id 从 common.llm_metrics._llm_factory ContextVar 读 (已有)。
    """
    try:
        from common.llm_metrics import _llm_factory
        factory_id = _llm_factory.get()
    except Exception:
        factory_id = None

    scope = _redaction_scope.get()
    allocator = scope.allocator if scope is not None else PlaceholderAllocator()
    known = scope.known_values if scope is not None else None

    new_payload, red = redact_payload(
        payload, allocator=allocator, factory_id=factory_id, known_values=known
    )
    if scope is not None:
        scope.placeholder_map.update(red.placeholder_map)
        scope.redacted_count += red.redacted_count

    meta = RedactionMeta(
        sanitized=red.did_redact or bool(scope and scope.known_values),
        redacted_count=red.redacted_count,
        redacted_fields=list(scope.redacted_fields) if scope else [],
    )
    return new_payload, meta


class StreamRestorer:
    """流式输出占位实时还原。占位可能被切到两个 chunk → 缓冲一个尾窗再还原。"""

    def __init__(self, placeholder_map: Dict[str, str], tail: int = 16) -> None:
        # Hold the live reference (not a copy) — the choke point may add PII
        # placeholders after construction; restore must see them.
        self._pmap = placeholder_map if placeholder_map is not None else {}
        self._buf = ""
        self._tail = tail

    def push(self, chunk: str) -> str:
        if not self._pmap:
            return chunk
        self._buf += chunk or ""
        if len(self._buf) <= self._tail:
            return ""
        emit = self._buf[: -self._tail]
        self._buf = self._buf[-self._tail:]
        return restore_text(emit, self._pmap)

    def flush(self) -> str:
        out = restore_text(self._buf, self._pmap) if self._pmap else self._buf
        self._buf = ""
        return out
