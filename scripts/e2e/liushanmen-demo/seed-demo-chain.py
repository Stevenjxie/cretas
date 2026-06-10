#!/usr/bin/env python3
"""
六扇门 ERP-lite Demo 造数链 — seed-demo-chain.py
==================================================
周五客户 demo 数据准备脚本。幂等可重跑。

使用方式:
  python seed-demo-chain.py                   # 默认: SSH localhost:10020 (green prod)
  python seed-demo-chain.py --gateway         # 走 139.196.165.140:10010 (nginx 网关)
  python seed-demo-chain.py --dry-run         # 只打印请求, 不发送
  python seed-demo-chain.py --cleanup-only    # 仅打印清理指引(不造数)

每步 PASS/FAIL 打印到 stdout。
所有创建的 ID 写入 run-{timestamp}.json, 供 cleanup-demo-chain.py 使用。

依赖: Python 3.9+ 标准库 (subprocess, json, datetime) + requests (pip install requests)
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
from datetime import datetime

# ─────────────────────────────────────────────────────────
# 步骤断言收集器 (模块级, log_step 写入, save_run 持久化)
# ─────────────────────────────────────────────────────────
_STEP_LOG: list = []  # 每元素: {step_id, name, result, assertion, http_status, entity_id, timestamp}

# ─────────────────────────────────────────────────────────
# 配置
# ─────────────────────────────────────────────────────────
FACTORY_ID = "F006"
USERNAME = "f006_admin"
PASSWORD = "123456"
DEMO_MARK = "DEMO-0610"
DEMO_DATE = "2026-06-10"

# 连接模式
MODE_SSH = "ssh"       # SSH localhost:10020 (绕过安全组, 走本地转发)
MODE_GATEWAY = "gateway"  # 走 139.196.165.140:10010 (nginx 网关)

# SSH 转发配置 (MODE_SSH 时使用)
SSH_HOST = "root@47.100.235.168"
SSH_LOCAL_PORT = 10020
SSH_REMOTE_PORT = 10020

# 产品/物料 master data — 脚本启动时自动查询并填充
# 若查不到则报 WARN 并跳过依赖步骤
MASTER = {
    "product_type_id_zhangzhongbao": None,  # 掌中宝
    "material_type_id_xigurou": None,        # 膝软骨
    "customer_id": None,                     # 第一个客户
    "supplier_id": None,                     # 第一个供应商
    "material_batch_id": None,               # 造数后的入库批次
    "supervisor_id": None,                   # 监工用户 ID (Integer, 用于 batch start)
    "semi_code": None,                       # 半成品 SKU code (若有)
}

# ─────────────────────────────────────────────────────────
# 工具函数
# ─────────────────────────────────────────────────────────

def ts():
    return datetime.now().strftime("%H:%M:%S")

def log_step(name, result, detail="", http_status=None, entity_id=None):
    """Log step result to stdout AND persist assertion record in _STEP_LOG.

    Args:
        name:        Step label e.g. "A-2 create sales order"
        result:      True = PASS, False = FAIL
        detail:      Free-text detail (printed to stdout, used as assertion description)
        http_status: HTTP status code if known (int or str)
        entity_id:   Created entity ID if applicable
    """
    icon = "PASS" if result else "FAIL"
    now = ts()
    print(f"[{now}] [{icon}] {name}{(': ' + str(detail)[:120]) if detail else ''}")

    # Derive step_id from leading token like "A-2" or "B-3" in the name
    step_id_match = re.match(r'^([A-Z]-\d+[a-z]?)\b', name)
    step_id = step_id_match.group(1) if step_id_match else name.split()[0]

    _STEP_LOG.append({
        "step_id": step_id,
        "name": name,
        "result": "PASS" if result else "FAIL",
        "assertion": str(detail)[:200] if detail else "",
        "http_status": http_status,
        "entity_id": str(entity_id) if entity_id is not None else None,
        "timestamp": now,
    })

def log_warn(msg):
    print(f"[{ts()}] [WARN] {msg}")

def log_info(msg):
    print(f"[{ts()}] [INFO] {msg}")


class ApiClient:
    """
    API 客户端。支持两种连接模式:
    - MODE_SSH: 通过 subprocess ssh 命令调用 curl (绕过安全组)
    - MODE_GATEWAY: 直接 requests 调用 139 网关
    """

    def __init__(self, mode=MODE_SSH, dry_run=False):
        self.mode = mode
        self.dry_run = dry_run
        self.token = None
        self.base = f"http://localhost:{SSH_LOCAL_PORT}" if mode == MODE_SSH else f"http://139.196.165.140:10010"
        self.run_id = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.run_data = {"run_id": self.run_id, "created": [], "errors": [], "steps": [], "summary": {}}

    def _do_request(self, method, path, body=None, params=None):
        """执行 HTTP 请求。SSH 模式通过本地 curl 发送。"""
        url = f"{self.base}{path}"
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        if self.dry_run:
            log_info(f"DRY-RUN {method} {url} body={json.dumps(body)[:200] if body else 'none'}")
            return {"success": True, "data": {"id": "DRY_RUN_ID", "token": "DRY_RUN_TOKEN"}, "message": "dry-run"}

        if self.mode == MODE_SSH:
            return self._ssh_curl(method, url, headers, body, params)
        else:
            return self._direct_request(method, url, headers, body, params)

    def _ssh_curl(self, method, url, headers, body, params):
        """通过 SSH 在服务器本地执行 curl，避免安全组拦截。"""
        header_args = " ".join([f'-H "{k}: {v}"' for k, v in headers.items()])
        body_arg = ""
        if body:
            body_str = json.dumps(body, ensure_ascii=False).replace("'", "'\\''")
            body_arg = f"-d '{body_str}'"
        param_str = ""
        if params:
            param_str = "?" + "&".join([f"{k}={urllib.parse.quote(str(v), safe='')}" for k, v in params.items()])
        # 在服务器本地直接 curl localhost
        local_url = url.replace(f"http://localhost:{SSH_LOCAL_PORT}", f"http://localhost:{SSH_REMOTE_PORT}")
        cmd = f"curl -sS -X {method} '{local_url}{param_str}' {header_args} {body_arg} --max-time 30"
        try:
            result = subprocess.run(
                ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10",
                 SSH_HOST, cmd],
                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=45
            )
            if result.returncode != 0:
                return {"success": False, "message": f"SSH error: {result.stderr[:200]}"}
            return json.loads(result.stdout)
        except subprocess.TimeoutExpired:
            return {"success": False, "message": "SSH timeout"}
        except json.JSONDecodeError as e:
            return {"success": False, "message": f"JSON parse error: {e}"}
        except Exception as e:
            return {"success": False, "message": str(e)}

    def _direct_request(self, method, url, headers, body, params):
        """直接 HTTP 请求 (需要 requests 库)。"""
        try:
            import requests as req
            r = req.request(method, url, headers=headers,
                            json=body, params=params, timeout=30)
            return r.json()
        except ImportError:
            log_warn("requests 库未安装: pip install requests; 切换到 SSH 模式")
            self.mode = MODE_SSH
            return self._ssh_curl(method, url, headers, body, params)
        except Exception as e:
            return {"success": False, "message": str(e)}

    def get(self, path, params=None):
        return self._do_request("GET", path, params=params)

    def post(self, path, body=None):
        return self._do_request("POST", path, body=body)

    def put(self, path, body=None):
        return self._do_request("PUT", path, body=body)

    def record(self, entity_type, entity_id, extra=None):
        """记录已创建实体, 供清理脚本使用。"""
        entry = {"type": entity_type, "id": entity_id, "created_at": ts()}
        if extra:
            entry.update(extra)
        self.run_data["created"].append(entry)

    def record_error(self, step, msg):
        self.run_data["errors"].append({"step": step, "error": msg, "at": ts()})

    def save_run(self):
        # Flush module-level step log into run_data
        self.run_data["steps"] = list(_STEP_LOG)
        total = len(_STEP_LOG)
        passed = sum(1 for s in _STEP_LOG if s["result"] == "PASS")
        failed = total - passed
        self.run_data["summary"] = {"total": total, "passed": passed, "failed": failed}

        run_file = os.path.join(os.path.dirname(__file__), f"run-{self.run_id}.json")
        with open(run_file, "w", encoding="utf-8") as f:
            json.dump(self.run_data, f, ensure_ascii=False, indent=2)
        log_info(f"Run data saved: {run_file}")
        log_info(f"Step summary: {passed}/{total} PASS, {failed} FAIL")
        return run_file


# ─────────────────────────────────────────────────────────
# 步骤函数 — 每步独立, 失败不中断全链
# ─────────────────────────────────────────────────────────

def step_login(api: ApiClient) -> bool:
    """A-1 认证"""
    resp = api.post(f"/api/mobile/auth/unified-login", {
        "username": USERNAME,
        "password": PASSWORD,
        "factoryId": FACTORY_ID
    })
    ok = resp.get("success") and resp.get("data", {}).get("token")
    if ok:
        api.token = resp["data"]["token"]
        log_step("A-1 unified-login", True, f"token length={len(api.token)}")
    else:
        log_step("A-1 unified-login", False, resp.get("message", "no token"))
    return bool(ok)


def step_resolve_master_data(api: ApiClient):
    """A-0 查询 master data IDs (产品/物料/客户/供应商)"""
    # 查销售订单取 customerId — 用 /sales/orders (page=1-based) 而非 /by-status
    resp = api.get(f"/api/mobile/{FACTORY_ID}/sales/orders",
                   params={"page": 1, "size": 5})
    if resp.get("success"):
        content = resp.get("data", {}).get("content") or []
        if content:
            MASTER["customer_id"] = content[0].get("customerId")
    log_step("A-0 resolve customerId", bool(MASTER["customer_id"]), MASTER["customer_id"])

    # 查产品类型找掌中宝 — /product-types 支持 keyword 过滤, page=1-based
    resp = api.get(f"/api/mobile/{FACTORY_ID}/product-types",
                   params={"keyword": "掌中宝", "page": 1, "size": 10})
    if resp.get("success"):
        content = resp.get("data", {}).get("content") or []
        for item in content:
            if "掌中宝" in (item.get("name") or ""):
                MASTER["product_type_id_zhangzhongbao"] = item.get("id")
                break
        if not MASTER["product_type_id_zhangzhongbao"] and content:
            MASTER["product_type_id_zhangzhongbao"] = content[0].get("id")
    log_step("A-0 resolve 掌中宝 productTypeId", bool(MASTER["product_type_id_zhangzhongbao"]),
             MASTER["product_type_id_zhangzhongbao"])

    # 查原料类型找膝软骨 — keyword 参数不过滤,需全量扫描 (page=1-based)
    resp = api.get(f"/api/mobile/{FACTORY_ID}/raw-material-types",
                   params={"page": 1, "size": 50})
    if resp.get("success"):
        content = resp.get("data", {}).get("content") or []
        for item in content:
            if "膝软骨" in (item.get("name") or ""):
                MASTER["material_type_id_xigurou"] = item.get("id")
                break
    log_step("A-0 resolve 膝软骨 materialTypeId", bool(MASTER["material_type_id_xigurou"]),
             MASTER["material_type_id_xigurou"])

    # 查供应商 — /suppliers (page=1-based)
    resp = api.get(f"/api/mobile/{FACTORY_ID}/suppliers",
                   params={"page": 1, "size": 5})
    if resp.get("success"):
        content = resp.get("data", {}).get("content") or []
        if content:
            MASTER["supplier_id"] = content[0].get("id")
    log_step("A-0 resolve supplierId", bool(MASTER["supplier_id"]), MASTER["supplier_id"])

    # 查用户列表取 supervisorId (Integer, 用于 batch start @RequestParam)
    resp_u = api.get(f"/api/mobile/{FACTORY_ID}/users", params={"page": 1, "size": 50})
    if resp_u.get("success"):
        users = resp_u.get("data", {}).get("content") or []
        # 优先找 admin, 其次 factory_manager, 再次第一个用户
        for u in users:
            if "admin" in (u.get("username") or "").lower() and u.get("factoryId") == FACTORY_ID:
                MASTER["supervisor_id"] = u.get("id")
                break
        if not MASTER.get("supervisor_id"):
            for u in users:
                if u.get("roleCode") in ("factory_super_admin", "factory_manager", "line_leader"):
                    MASTER["supervisor_id"] = u.get("id")
                    break
        if not MASTER.get("supervisor_id") and users:
            MASTER["supervisor_id"] = users[0].get("id")
    log_step("A-0 resolve supervisorId", bool(MASTER.get("supervisor_id")), MASTER.get("supervisor_id"))


def step_create_sales_order(api: ApiClient) -> str | None:
    """A-2 销售订单"""
    if not MASTER.get("customer_id") or not MASTER.get("product_type_id_zhangzhongbao"):
        log_warn("A-2 SKIP: 缺少 customerId 或 productTypeId")
        return None
    resp = api.post(f"/api/mobile/{FACTORY_ID}/sales/orders", {
        "customerId": MASTER["customer_id"],
        "orderDate": DEMO_DATE,
        "deliveryDate": "2026-06-14",
        "remark": f"{DEMO_MARK} 周五演示掌中宝订单",
        "items": [{
            "productTypeId": MASTER["product_type_id_zhangzhongbao"],
            "quantity": 200,
            "unit": "kg",
            "unitPrice": 68.00
        }]
    })
    ok = resp.get("success")
    order_id = resp.get("data", {}).get("id") or resp.get("data", {}).get("orderId") if ok else None
    order_num = resp.get("data", {}).get("orderNumber", "") if ok else ""
    log_step("A-2 create sales order", ok, f"id={order_id} num={order_num}")
    if ok and order_id:
        api.record("SALES_ORDER", order_id, {"orderNumber": order_num})
    else:
        api.record_error("A-2", resp.get("message", "unknown"))
    return order_id


def step_check_margin(api: ApiClient) -> bool:
    """A-3 毛利红线校验"""
    if not MASTER.get("product_type_id_zhangzhongbao"):
        log_warn("A-3 SKIP: 缺 productTypeId")
        return False
    resp = api.post(f"/api/mobile/{FACTORY_ID}/sales/check-margin", {
        "productTypeId": MASTER["product_type_id_zhangzhongbao"],
        "quotedPrice": 68.00
    })
    ok = resp.get("success")
    passed = resp.get("data", {}).get("passed") if ok else None
    log_step("A-3 check-margin", ok, f"passed={passed}")
    return ok


def step_finance_approve_sales(api: ApiClient, order_id: str) -> bool:
    """A-4 销售订单: 确认 → 提交审批 → 财务审批
    状态机: DRAFT → confirm → CONFIRMED → submit-for-review → PENDING_FINANCE → finance-approve → FINANCE_APPROVED
    """
    if not order_id:
        log_warn("A-4 SKIP: 无 order_id")
        return False
    # confirm (DRAFT -> CONFIRMED)
    r0 = api.post(f"/api/mobile/{FACTORY_ID}/sales/orders/{order_id}/confirm")
    ok0 = r0.get("success")
    log_step("A-4 so confirm", ok0, r0.get("message", "")[:80])
    # submit for review (CONFIRMED -> PENDING_FINANCE)
    r1 = api.post(f"/api/mobile/{FACTORY_ID}/sales/orders/{order_id}/submit-for-review")
    ok1 = r1.get("success")
    log_step("A-4 submit-for-review", ok1, r1.get("message", ""))
    if not ok1 and "already" not in r1.get("message", "").lower():
        api.record_error("A-4-submit", r1.get("message", ""))
    # finance-approve
    r2 = api.post(f"/api/mobile/{FACTORY_ID}/sales/orders/{order_id}/finance-approve",
                  {"remark": f"{DEMO_MARK} 财务审批"})
    ok2 = r2.get("success")
    log_step("A-4 finance-approve", ok2, r2.get("message", ""))
    if not ok2:
        api.record_error("A-4-approve", r2.get("message", ""))
    return ok2


def step_create_purchase_order(api: ApiClient) -> str | None:
    """A-5 采购单 — 膝软骨 (PO qty=150kg, 收货220kg > 150*1.3=195 → 触发超收异常)"""
    if not MASTER.get("supplier_id"):
        log_warn("A-5 SKIP: 缺 supplierId")
        return None
    body = {
        "supplierId": MASTER["supplier_id"],
        "orderDate": DEMO_DATE,
        "remark": f"{DEMO_MARK} 膝软骨采购",
        "items": [{
            "quantity": 150,        # 故意设小: 收货220 > 150×1.3=195 → 超收异常
            "unit": "kg",
            "unitPrice": 32.00,
            "taxRate": 9
        }]
    }
    if MASTER.get("material_type_id_xigurou"):
        body["items"][0]["materialTypeId"] = MASTER["material_type_id_xigurou"]
    else:
        log_warn("A-5: 未找到膝软骨 materialTypeId，跳过 materialTypeId 字段")
    resp = api.post(f"/api/mobile/{FACTORY_ID}/purchase/orders", body)
    ok = resp.get("success")
    po_id = resp.get("data", {}).get("id") if ok else None
    log_step("A-5 create purchase order", ok, f"id={po_id}")
    if ok and po_id:
        api.record("PURCHASE_ORDER", po_id)
    else:
        api.record_error("A-5", resp.get("message", "unknown"))
    return po_id


def step_approve_purchase_order(api: ApiClient, po_id: str) -> bool:
    """A-6 采购单审批"""
    if not po_id:
        log_warn("A-6 SKIP: 无 po_id")
        return False
    r1 = api.post(f"/api/mobile/{FACTORY_ID}/purchase/orders/{po_id}/submit")
    ok1 = r1.get("success")
    log_step("A-6 po submit", ok1, r1.get("message", ""))
    r2 = api.post(f"/api/mobile/{FACTORY_ID}/purchase/orders/{po_id}/approve",
                  {"remark": f"{DEMO_MARK} 审批通过"})
    ok2 = r2.get("success")
    log_step("A-6 po approve", ok2, r2.get("message", ""))
    if not ok2:
        api.record_error("A-6", r2.get("message", ""))
    return ok2


def step_receive_purchase(api: ApiClient, po_id: str) -> str | None:
    """A-7 采购入库 190kg (触发超收异常)
    PO qty=150kg, 最大可收 150×1.3=195kg。
    190 > 150 → 入库确认后后端自动生成超收异常单。
    注: 220 > 195 会被后端拒绝, 故改为 190 (仍超 PO qty 但在硬限 195 内)。
    """
    if not po_id:
        log_warn("A-7 SKIP: 无 po_id")
        return None
    # 查 PO 详情拿 item id, materialTypeId, supplierId (receive DTO 必填)
    po_detail = api.get(f"/api/mobile/{FACTORY_ID}/purchase/orders/{po_id}")
    po_data = po_detail.get("data", {})
    items = po_data.get("items") or []
    item_id = items[0].get("id") if items else None
    material_type_id = items[0].get("materialTypeId") if items else None
    supplier_id_from_po = po_data.get("supplierId") or MASTER.get("supplier_id")

    receive_body = {
        "purchaseOrderId": po_id,
        "supplierId": supplier_id_from_po,
        "receiveDate": DEMO_DATE,
        "remark": f"{DEMO_MARK} 入库 厂号F001 产地山东",
        "items": [{
            "materialTypeId": material_type_id,
            "receivedQuantity": 190,    # 190 > 150 (PO qty) → 超收; 190 ≤ 195 (max) → 不被拒绝
            "unit": "kg",
            "sourceFactoryCode": "F001",
            "originPlace": "山东"
        }]
    }
    if item_id:
        receive_body["items"][0]["purchaseOrderItemId"] = item_id

    resp = api.post(f"/api/mobile/{FACTORY_ID}/purchase/receives", receive_body)
    ok = resp.get("success")
    recv_id = resp.get("data", {}).get("id") if ok else None
    log_step("A-7 purchase receive 190kg (超收演示)", ok, f"id={recv_id}")
    if ok and recv_id:
        api.record("PURCHASE_RECEIVE", recv_id)
    else:
        api.record_error("A-7", resp.get("message", "unknown"))
    return recv_id


def step_confirm_receive(api: ApiClient, recv_id: str) -> bool:
    """A-8 确认入库"""
    if not recv_id:
        log_warn("A-8 SKIP: 无 recv_id")
        return False
    resp = api.post(f"/api/mobile/{FACTORY_ID}/purchase/receives/{recv_id}/confirm")
    ok = resp.get("success")
    log_step("A-8 confirm receive", ok, resp.get("message", ""))
    # 确认后查 materialBatchId (用于盘点)
    detail = api.get(f"/api/mobile/{FACTORY_ID}/purchase/receives/{recv_id}")
    batch_items = detail.get("data", {}).get("items") or []
    if batch_items:
        MASTER["material_batch_id"] = batch_items[0].get("materialBatchId") or batch_items[0].get("batchId")
    log_info(f"A-8 material_batch_id resolved: {MASTER['material_batch_id']}")
    return ok


def step_check_exception(api: ApiClient, po_id: str) -> str | None:
    """A-9 查采购异常单"""
    if not po_id:
        log_warn("A-9 SKIP: 无 po_id")
        return None
    resp = api.get(f"/api/mobile/{FACTORY_ID}/purchase-exceptions",
                   params={"purchaseOrderId": po_id, "page": 1, "size": 10})
    data = resp.get("data")
    if isinstance(data, list):
        items = data
    elif isinstance(data, dict):
        items = data.get("content") or []
    else:
        items = []
    exception_id = None
    if items:
        exception_id = items[0].get("id")
        log_step("A-9 get purchase exception", True, f"id={exception_id} type={items[0].get('exceptionType')}")
        api.record("PURCHASE_EXCEPTION", exception_id)
    else:
        log_step("A-9 get purchase exception", False, "空列表(超收未自动生成异常单或入库未确认)")
        api.record_error("A-9", "no exception found")
    return exception_id


def step_decide_exception(api: ApiClient, exception_id: str) -> bool:
    """A-10 异常决策 ACCEPT_OVER"""
    if not exception_id:
        log_warn("A-10 SKIP: 无 exception_id")
        return False
    resp = api.post(f"/api/mobile/{FACTORY_ID}/purchase-exceptions/{exception_id}/decide", {
        "decision": "ACCEPT_OVER",
        "remark": f"{DEMO_MARK} 接受超收"
    })
    ok = resp.get("success")
    log_step("A-10 decide exception ACCEPT_OVER", ok, resp.get("message", ""))
    if not ok:
        api.record_error("A-10", resp.get("message", ""))
    return ok


def step_create_payment_request(api: ApiClient, po_id: str) -> str | None:
    """A-11 付款申请"""
    if not po_id or not MASTER.get("supplier_id"):
        log_warn("A-11 SKIP: 缺 po_id 或 supplierId")
        return None
    resp = api.post(f"/api/mobile/{FACTORY_ID}/payment-requests", {
        "purchaseOrderId": po_id,
        "supplierId": MASTER["supplier_id"],
        "amount": 7040.00,
        "currency": "CNY",
        "paymentMethod": "BANK_TRANSFER",
        "dueDate": "2026-06-17",
        "remark": f"{DEMO_MARK} 膝软骨 220kg×32元"
    })
    ok = resp.get("success")
    pr_id = resp.get("data", {}).get("id") if ok else None
    log_step("A-11 create payment request", ok, f"id={pr_id}")
    if ok and pr_id:
        api.record("PAYMENT_REQUEST", pr_id)
    else:
        api.record_error("A-11", resp.get("message", "unknown"))
    return pr_id


def step_process_payment_request(api: ApiClient, pr_id: str) -> bool:
    """A-12 付款申请 submit → finance-approve → mark-paid"""
    if not pr_id:
        log_warn("A-12 SKIP: 无 pr_id")
        return False
    r1 = api.put(f"/api/mobile/{FACTORY_ID}/payment-requests/{pr_id}/submit")
    log_step("A-12 pr submit", r1.get("success"), r1.get("message", ""))

    r2 = api.put(f"/api/mobile/{FACTORY_ID}/payment-requests/{pr_id}/finance-approve",
                 {"remark": f"{DEMO_MARK} 财务审批"})
    log_step("A-12 pr finance-approve", r2.get("success"), r2.get("message", ""))

    r3 = api.put(f"/api/mobile/{FACTORY_ID}/payment-requests/{pr_id}/mark-paid",
                 {"paymentDate": DEMO_DATE, "remark": f"{DEMO_MARK} 已付款"})
    ok = r3.get("success")
    log_step("A-12 pr mark-paid", ok, r3.get("message", ""))
    if not ok:
        api.record_error("A-12", r3.get("message", ""))
    return ok


def step_create_production_plan(api: ApiClient, order_id: str) -> str | None:
    """A-13 生产计划"""
    if not MASTER.get("product_type_id_zhangzhongbao"):
        log_warn("A-13 SKIP: 缺 productTypeId")
        return None
    body = {
        "plannedQuantity": 200,
        "unit": "kg",
        "plannedDate": DEMO_DATE,   # 后端要求 plannedDate 而非 startDate/endDate
        "remark": f"{DEMO_MARK} 掌中宝生产计划",
        "productTypeId": MASTER["product_type_id_zhangzhongbao"]
    }
    if order_id:
        body["salesOrderId"] = order_id
    resp = api.post(f"/api/mobile/{FACTORY_ID}/production-plans", body)
    ok = resp.get("success")
    plan_id = resp.get("data", {}).get("id") if ok else None
    log_step("A-13 create production plan", ok, f"id={plan_id}")
    if ok and plan_id:
        api.record("PRODUCTION_PLAN", plan_id)
    else:
        api.record_error("A-13", resp.get("message", "unknown"))
    return plan_id


def step_create_batch(api: ApiClient, plan_id: str, label: str = "X") -> str | None:
    """A-14/A-16 转批次"""
    import time as _time
    batch_num = f"DEMO-{label}-{int(_time.time()) % 100000}"
    body = {
        "batchNumber": batch_num,
        "productTypeId": MASTER.get("product_type_id_zhangzhongbao", ""),
        "plannedQuantity": 200,
        "unit": "kg",
        "remark": f"{DEMO_MARK} 批次{label}-{'留给RN报工' if label == 'X' else '脚本演示'}"
    }
    if plan_id:
        body["productionPlanId"] = plan_id
    resp = api.post(f"/api/mobile/{FACTORY_ID}/processing/batches", body)
    ok = resp.get("success")
    batch_id = resp.get("data", {}).get("id") if ok else None
    batch_num = resp.get("data", {}).get("batchNumber", "") if ok else ""
    log_step(f"A-14/16 create batch-{label}", ok, f"id={batch_id} num={batch_num}")
    if ok and batch_id:
        api.record(f"BATCH_{label}", batch_id, {"batchNumber": batch_num})
    else:
        api.record_error(f"A-batch-{label}", resp.get("message", "unknown"))
    return batch_id


def step_start_batch(api: ApiClient, batch_id: str, label: str = "X",
                     supervisor_id: int = None) -> bool:
    """A-15 开工 — supervisorId 是 @RequestParam (Integer), 需通过 params 传而非 body"""
    if not batch_id:
        log_warn(f"A-15 SKIP batch-{label}: 无 batch_id")
        return False
    params = {}
    if supervisor_id:
        params["supervisorId"] = supervisor_id
    resp = api._do_request("POST",
                           f"/api/mobile/{FACTORY_ID}/processing/batches/{batch_id}/start",
                           params=params)
    ok = resp.get("success")
    log_step(f"A-15 start batch-{label}", ok, resp.get("message", ""))
    return ok


def step_check_output_options(api: ApiClient, batch_id: str) -> dict:
    """A-16a 查 output-options (SP1)"""
    if not batch_id:
        return {}
    resp = api.get(f"/api/mobile/{FACTORY_ID}/processing/batches/{batch_id}/output-options")
    ok = resp.get("success")
    data = resp.get("data", {})
    log_step("A-16a output-options", ok, str(data)[:100])
    return data if ok else {}


def step_spawn_work_process_tasks(api: ApiClient, batch_id: str, label: str = "Y") -> int | None:
    """A-16b-pre: 为批次 spawn 工序任务 (yield 报工需要 workProcessTaskId)
    POST /api/mobile/{factoryId}/production/batches/{batchId}/spawn-tasks
    body: {"productTypeId": "<string>"}
    返回第一个 task 的 id (Long)
    """
    if not batch_id:
        log_warn(f"A-16b-pre SKIP batch-{label}: 无 batch_id")
        return None
    product_type_id = MASTER.get("product_type_id_zhangzhongbao")
    if not product_type_id:
        log_warn("A-16b-pre SKIP: 缺 productTypeId，无法 spawn 工序任务")
        return None
    resp = api.post(f"/api/mobile/{FACTORY_ID}/production/batches/{batch_id}/spawn-tasks",
                    {"productTypeId": str(product_type_id)})
    ok = resp.get("success")
    tasks = resp.get("data") or []
    first_task_id = tasks[0].get("id") if tasks else None
    log_step(f"A-16b-pre spawn tasks batch-{label}", ok,
             f"spawned={len(tasks)} firstTaskId={first_task_id}")
    if not ok:
        api.record_error(f"A-16b-pre-{label}", resp.get("message", "unknown"))
    return first_task_id


def step_yield_report_dual_output(api: ApiClient, batch_id: str, output_options: dict,
                                   work_process_task_id: int = None) -> str | None:
    """A-16b 报工 (双产出 BOTH 或降级 FINISHED)
    YieldReportRequest 必须传 workProcessTaskId (Long).
    需先调用 step_spawn_work_process_tasks 获取 task id.
    """
    if not batch_id:
        log_warn("A-16b SKIP: 无 batch_id")
        return None
    kinds = output_options.get("availableOutputKinds") or []
    output_kind = "BOTH" if "BOTH" in kinds or "SEMI" in kinds else "FINISHED"
    body = {
        "reportKind": "OUTPUT",
        "outputKind": output_kind,
        "outputQuantity": 160,
        "outputUnit": "kg",
        "workerCount": 5,
        "workMinutes": 480,
        "remark": f"{DEMO_MARK} 双产出报工"
    }
    if work_process_task_id:
        body["workProcessTaskId"] = work_process_task_id
    if output_kind in ("BOTH", "SEMI") and output_options.get("semiCode"):
        body["semiOutputQuantity"] = 15
        body["semiOutputUnit"] = "kg"
        body["semiCode"] = output_options["semiCode"]
        MASTER["semi_code"] = output_options["semiCode"]
    resp = api.post(f"/api/mobile/{FACTORY_ID}/production/batches/{batch_id}/reports", body)
    ok = resp.get("success")
    report_id = resp.get("data", {}).get("reportId") or resp.get("data", {}).get("id") if ok else None
    log_step(f"A-16b yield report ({output_kind})", ok, f"reportId={report_id}")
    if ok and report_id:
        api.record("YIELD_REPORT_Y", report_id)
    else:
        api.record_error("A-16b", resp.get("message", "unknown"))
    return report_id


def step_submit_reversal(api: ApiClient, batch_id: str) -> str | None:
    """A-17 整单撤回申请 (SP2)"""
    if not batch_id:
        log_warn("A-17 SKIP: 无 batch_id")
        return None
    resp = api.post(f"/api/mobile/{FACTORY_ID}/processing/batches/{batch_id}/reversal", {
        "reversalScope": "FULL_BATCH",
        "reason": "DEMO_TEST",
        "remark": f"{DEMO_MARK} 整单撤回演示"
    })
    ok = resp.get("success")
    log_id = resp.get("data", {}).get("id") if ok else None
    log_step("A-17 submit reversal", ok, f"logId={log_id}")
    if ok and log_id:
        api.record("REVERSAL_LOG", log_id)
    else:
        api.record_error("A-17", resp.get("message", "unknown"))
    return log_id


def step_approve_reversal(api: ApiClient, log_id: str) -> bool:
    """A-18 审批撤回"""
    if not log_id:
        log_warn("A-18 SKIP: 无 log_id")
        return False
    resp = api.put(f"/api/mobile/{FACTORY_ID}/reversals/{log_id}/approve",
                   {"remark": f"{DEMO_MARK} 审批撤回"})
    ok = resp.get("success")
    log_step("A-18 approve reversal", ok, resp.get("message", ""))
    return ok


def step_stocktake(api: ApiClient) -> str | None:
    """A-19 发起盘点 → 录差异 → 预览 → 提交
    GAP: 后端业务规则要求盘点只能在月底(29日后)发起。
         今天 2026-06-10 无法绕过此限制 — 标记 GAP，跳过创建。
    """
    # 先查是否已有进行中盘点
    list_resp = api.get(f"/api/mobile/{FACTORY_ID}/stocktakes", params={"page": 1, "size": 5})
    if list_resp.get("success"):
        content = list_resp.get("data", {}).get("content") or []
        in_progress = [s for s in content if s.get("status") in ("DRAFT", "IN_PROGRESS", "PENDING")]
        if in_progress:
            st_id = in_progress[0].get("id")
            log_step("A-19 create stocktake", True, f"复用已有盘点 id={st_id}")
            api.record("STOCKTAKE", st_id)
            return st_id

    # 尝试创建 — 使用正确字段 (warehouseId + periodMonth)
    warehouse_id = "78339e2d-d34c-4b38-b4f9-977fd4a631c2"  # 物流仓 (from inventory/warehouses)
    period_month = DEMO_DATE[:7]  # "2026-06"
    resp = api.post(f"/api/mobile/{FACTORY_ID}/stocktakes", {
        "warehouseId": warehouse_id,
        "periodMonth": period_month,
        "notes": f"{DEMO_MARK} 月底盘点演示"
    })
    ok = resp.get("success")
    st_id = resp.get("data", {}).get("id") if ok else None
    log_step("A-19 create stocktake", ok, f"id={st_id}" if ok else resp.get("message", "")[:80])
    if not ok or not st_id:
        msg = resp.get("message", "unknown")
        api.record_error("A-19-create", msg)
        if "29" in msg or "月底" in msg:
            log_warn("A-19 GAP: 后端限制月底(29日后)才能发起盘点 — 标记 GAP，跳过后续盘点步骤")
        return None
    api.record("STOCKTAKE", st_id)

    # 录差异 (若有 material_batch_id)
    if MASTER.get("material_batch_id"):
        # 先查当前数量
        batch_info = api.get(f"/api/mobile/{FACTORY_ID}/purchase/receives/{MASTER['material_batch_id']}")
        current_qty = batch_info.get("data", {}).get("quantity", 50)
        actual_qty = max(0, current_qty - 5)
        r2 = api.put(f"/api/mobile/{FACTORY_ID}/stocktakes/{st_id}/items", {
            "items": [{"materialBatchId": MASTER["material_batch_id"], "actualQuantity": actual_qty}]
        })
        log_step("A-19 update stocktake items", r2.get("success"), r2.get("message", ""))
    else:
        log_warn("A-19: 无 material_batch_id，跳过录差异步骤")

    # 查差异预览
    r3 = api.get(f"/api/mobile/{FACTORY_ID}/stocktakes/{st_id}/diff-preview")
    log_step("A-19 diff-preview", r3.get("success"), str(r3.get("data", ""))[:100])

    # 提交审批
    r4 = api.post(f"/api/mobile/{FACTORY_ID}/stocktakes/{st_id}/submit")
    log_step("A-19 submit stocktake", r4.get("success"), r4.get("message", ""))
    return st_id


def step_wastage_report(api: ApiClient) -> str | None:
    """A-20 创建报损单
    字段说明(来自 CreateWastageReportRequest DTO):
      - trackType: WAREHOUSE | FACTORY (枚举)
      - materialBatchId: 批次ID (必填, @NotBlank)
      - wastageReason: EXPIRED | DAMAGED | CONTAMINATED | THEFT | OTHER (枚举, 必填)
      - wastageQty: 数量 (必填, >0)
      - photoUrls: List<String> (必填, 至少1张)
      - warehouseId: 可选
      - rawMaterialTypeId: 可选
      - reasonDetail / notes: 可选文本
    """
    if not MASTER.get("material_batch_id"):
        log_warn("A-20 SKIP: 无 material_batch_id (采购入库未成功), 跳过报损单")
        return None
    body = {
        "trackType": "FACTORY",
        "materialBatchId": MASTER["material_batch_id"],
        "wastageReason": "DAMAGED",
        "wastageQty": 2,
        # photoUrls 是后端 String 字段 (非 List), 传逗号分隔字符串
        "photoUrls": "https://cretas-media.oss-cn-shanghai.aliyuncs.com/demo/placeholder.jpg",
        "reasonDetail": f"{DEMO_MARK} 运输损耗演示",
        "notes": f"{DEMO_MARK} 报损单演示"
    }
    resp = api.post(f"/api/mobile/{FACTORY_ID}/wastage-reports", body)
    ok = resp.get("success")
    wr_id = resp.get("data", {}).get("id") if ok else None
    log_step("A-20 create wastage report", ok, f"id={wr_id}")
    if not ok or not wr_id:
        api.record_error("A-20", resp.get("message", "unknown"))
        return None
    api.record("WASTAGE_REPORT", wr_id)
    r2 = api.post(f"/api/mobile/{FACTORY_ID}/wastage-reports/{wr_id}/submit")
    log_step("A-20 submit wastage report", r2.get("success"), r2.get("message", ""))
    return wr_id


def step_rd_request(api: ApiClient) -> str | None:
    """A-21 研发报价任务 (SP10)"""
    body = {
        "title": f"{DEMO_MARK} 掌中宝新规格研发",
        "targetCost": 58.00,
        "remark": f"{DEMO_MARK} 研发报价任务演示"
    }
    if MASTER.get("product_type_id_zhangzhongbao"):
        body["productTypeId"] = MASTER["product_type_id_zhangzhongbao"]
    resp = api.post(f"/api/mobile/{FACTORY_ID}/rd/requests", body)
    ok = resp.get("success")
    rd_id = resp.get("data", {}).get("id") if ok else None
    log_step("A-21 create rd request", ok, f"id={rd_id}")
    if ok and rd_id:
        api.record("RD_REQUEST", rd_id)
    else:
        api.record_error("A-21", resp.get("message", "unknown"))
    return rd_id


def step_scan_label(api: ApiClient, batch_id: str) -> bool:
    """A-22 扫码查询 (SP4)
    Labels 用 labelCode (e.g. LB-F006-20260610...) 扫码, 非 batchNumber。
    流程: 先 POST /labels 给 batchId 创建标签, 拿到 labelCode, 再 GET /labels/scan/{labelCode}。
    """
    if not batch_id or batch_id == "DRY_RUN_ID":
        log_warn("A-22 SKIP: 无有效 batch_id")
        return False
    # 创建标签
    r_create = api.post(f"/api/mobile/{FACTORY_ID}/labels", {
        "batchId": int(batch_id) if str(batch_id).isdigit() else batch_id,
        "type": "BATCH"
    })
    ok_create = r_create.get("success")
    label_code = r_create.get("data", {}).get("labelCode") if ok_create else None
    log_step("A-22 create label", ok_create, f"labelCode={label_code}")
    if not label_code:
        api.record_error("A-22-create", r_create.get("message", "unknown"))
        return False
    api.record("LABEL", r_create.get("data", {}).get("id"), {"labelCode": label_code})
    # 扫码
    resp = api.get(f"/api/mobile/{FACTORY_ID}/labels/scan/{label_code}")
    ok = resp.get("success")
    log_step("A-22 scan label", ok, f"labelCode={label_code}")
    return ok


# ─────────────────────────────────────────────────────────
# 主流程
# ─────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="六扇门 Demo 造数链")
    parser.add_argument("--gateway", action="store_true", help="走 139 nginx 网关 (默认 SSH 直连)")
    parser.add_argument("--dry-run", action="store_true", help="只打印请求不发送")
    parser.add_argument("--cleanup-only", action="store_true", help="打印清理指引后退出")
    args = parser.parse_args()

    if args.cleanup_only:
        print("清理指引: 运行 cleanup-demo-chain.py --run-file run-{ts}.json")
        sys.exit(0)

    mode = MODE_GATEWAY if args.gateway else MODE_SSH
    log_info(f"连接模式: {mode} | dry-run: {args.dry_run} | FACTORY: {FACTORY_ID}")

    api = ApiClient(mode=mode, dry_run=args.dry_run)

    print("\n" + "="*60)
    print(f" 六扇门 ERP-lite Demo 造数链  {DEMO_MARK}")
    print("="*60 + "\n")

    # A-1: 登录 (必须成功才继续)
    if not step_login(api):
        log_warn("登录失败，中止造数。检查服务器连接和账号密码。")
        api.save_run()
        sys.exit(1)

    # A-0: 查 master data
    step_resolve_master_data(api)

    # A-2: 销售订单
    order_id = step_create_sales_order(api)

    # A-3: 毛利红线校验
    step_check_margin(api)

    # A-4: 财务审批
    step_finance_approve_sales(api, order_id)

    # A-5 ~ A-12: 采购到付款链
    po_id = step_create_purchase_order(api)
    step_approve_purchase_order(api, po_id)
    recv_id = step_receive_purchase(api, po_id)
    step_confirm_receive(api, recv_id)
    exception_id = step_check_exception(api, po_id)
    step_decide_exception(api, exception_id)
    pr_id = step_create_payment_request(api, po_id)
    step_process_payment_request(api, pr_id)

    # A-13 ~ A-15: 生产计划 → 批次 X (留给 RN)
    plan_id = step_create_production_plan(api, order_id)
    batch_x_id = step_create_batch(api, plan_id, label="X")
    batch_x_num = None
    for r in api.run_data["created"]:
        if r["type"] == "BATCH_X":
            batch_x_num = r.get("batchNumber")
    step_start_batch(api, batch_x_id, label="X", supervisor_id=MASTER.get("supervisor_id"))

    # A-16: 批次 Y 报工(双产出演示)
    batch_y_id = step_create_batch(api, plan_id, label="Y")
    step_start_batch(api, batch_y_id, label="Y", supervisor_id=MASTER.get("supervisor_id"))
    output_options = step_check_output_options(api, batch_y_id)
    # 先 spawn 工序任务 (yield report 必须有 workProcessTaskId)
    task_id_y = step_spawn_work_process_tasks(api, batch_y_id, label="Y")
    step_yield_report_dual_output(api, batch_y_id, output_options, work_process_task_id=task_id_y)

    # A-17 ~ A-18: 整单撤回
    log_id = step_submit_reversal(api, batch_y_id)
    step_approve_reversal(api, log_id)

    # A-19: 盘点
    step_stocktake(api)

    # A-20: 报损
    step_wastage_report(api)

    # A-21: 研发报价
    step_rd_request(api)

    # A-22: 扫码 (batch_x_id → create label → scan labelCode)
    step_scan_label(api, batch_x_id)

    # 保存结果
    run_file = api.save_run()

    print("\n" + "="*60)
    print(f"造数完成。BATCH_X_ID={batch_x_id} batchNum={batch_x_num} (留给 RN f006_moyun 报工)")
    print(f"产品: 掌中宝 | 工序任务: 已 spawn (水解化冻→后续各道)")
    print(f"Run file: {run_file}")
    errors = len(api.run_data["errors"])
    created = len(api.run_data["created"])
    print(f"Created: {created} entities | Errors: {errors}")
    print("="*60)


if __name__ == "__main__":
    main()
