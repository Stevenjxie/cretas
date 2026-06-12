import json
import shlex
import subprocess
import sys
import time
from datetime import date, timedelta


BASE = "http://127.0.0.1:10010/api/mobile"
SSH_HOST = "root@47.100.235.168"
FACTORY = "F006"
PWD = "123456"
TS = str(int(time.time()))

SUPPLIER = "844d8c69-ed89-4a7f-b850-1749456d0777"
WH_RAW = "6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e"
WH_LOGISTICS = "78339e2d-d34c-4b38-b4f9-977fd4a631c2"
MAT_RAW = "RMT_1777690082465"  # frozen pork tongue
MAT_RAW_NAME = "cold pork tongue"
PRODUCT_ZZB = "1d7fbd73-8797-4933-83f1-46413a45992d"
CUSTOMER = "1a99316b-d8f9-4059-9493-6041ae2a54d1"

results = []
created = {}


def req(method, path, token=None, body=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, ensure_ascii=False)
    parts = ["curl", "-sS", "-w", "\nHTTP_STATUS:%{http_code}", "-X", method]
    for k, v in headers.items():
        parts += ["-H", f"{k}: {v}"]
    if data is not None:
        parts += ["--data-binary", "@-"]
    parts.append(BASE + path)
    cmd = " ".join(shlex.quote(p) for p in parts)
    r = subprocess.run(
        ["ssh", SSH_HOST, cmd],
        input=data,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=45,
    )
    out = r.stdout
    marker = "\nHTTP_STATUS:"
    if marker in out:
        text, status = out.rsplit(marker, 1)
        code = int(status.strip()[:3])
    else:
        text, code = out, 0
    try:
        parsed = json.loads(text) if text else None
    except Exception:
        parsed = text[:1000]
    return code, parsed


def rec(name, ok, detail, extra=None):
    row = {"name": name, "ok": bool(ok), "detail": detail, "extra": extra}
    results.append(row)
    print(("PASS" if ok else "FAIL") + " " + name + " :: " + detail)
    if extra is not None:
        print(json.dumps(extra, ensure_ascii=False)[:1200])


def login(username):
    code, body = req("POST", "/auth/unified-login", body={"username": username, "password": PWD})
    token = body.get("data", {}).get("token") if isinstance(body, dict) else None
    rec("login " + username, code == 200 and bool(token), f"HTTP {code}")
    return token


def remote_sql(query):
    one_line = " ".join(query.split())
    cmd = "PGPASSWORD=cretas123 psql -U cretas_user -h127.0.0.1 -d cretas_prod_db -Atc " + repr(one_line)
    r = subprocess.run(
        ["ssh", SSH_HOST, cmd],
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=30,
    )
    return r.stdout.strip()


def data_id(body):
    if not isinstance(body, dict):
        return None
    data = body.get("data") or {}
    return data.get("id") or data.get("orderId") or data.get("receiveId")


def create_procurement_chain(procurement_token, warehouse_token):
    today = str(date.today())
    po_body = {
        "supplierId": SUPPLIER,
        "orderDate": today,
        "expectedDeliveryDate": str(date.today() + timedelta(days=1)),
        "remark": "DEMO-GOLD-RN procurement role PO " + TS,
        "items": [{
            "materialTypeId": MAT_RAW,
            "materialName": MAT_RAW_NAME,
            "quantity": 3,
            "unit": "kg",
            "unitPrice": 12.34,
            "taxRate": 9
        }]
    }
    code, body = req("POST", f"/{FACTORY}/purchase/orders", procurement_token, po_body)
    po_id = data_id(body)
    created["po_id"] = po_id
    rec("procurement creates PO", code == 200 and bool(po_id), f"HTTP {code} id={po_id}", body)

    if po_id:
        for label, path in [
            ("procurement submits PO", f"/{FACTORY}/purchase/orders/{po_id}/submit"),
            ("procurement approves PO", f"/{FACTORY}/purchase/orders/{po_id}/approve"),
        ]:
            c, b = req("POST", path, procurement_token, {})
            rec(label, c == 200, f"HTTP {c}", b)

        receive_body = {
            "purchaseOrderId": po_id,
            "supplierId": SUPPLIER,
            "receiveDate": today,
            "warehouseId": WH_RAW,
            "remark": "DEMO-GOLD-RN warehouse true inbound " + TS,
            "items": [{
                "materialTypeId": MAT_RAW,
                "materialName": MAT_RAW_NAME,
                "receivedQuantity": 2,
                "unit": "kg",
                "unitPrice": 12.34,
                "qcResult": "PASS"
            }]
        }
        c, b = req("POST", f"/{FACTORY}/purchase/receives", warehouse_token, receive_body)
        receive_id = data_id(b)
        created["receive_id"] = receive_id
        rec("warehouse creates receive", c == 200 and bool(receive_id), f"HTTP {c} id={receive_id}", b)
        if receive_id:
            c2, b2 = req("POST", f"/{FACTORY}/purchase/receives/{receive_id}/confirm", warehouse_token, {})
            rec("warehouse confirms receive", c2 == 200, f"HTTP {c2}", b2)


def create_sales_chain(sales_token):
    so_body = {
        "customerId": CUSTOMER,
        "orderDate": str(date.today()),
        "requiredDeliveryDate": str(date.today() + timedelta(days=2)),
        "remark": "DEMO-GOLD-RN sales role SO " + TS,
        "items": [{
            "productTypeId": PRODUCT_ZZB,
            "quantity": 5,
            "unit": "box",
            "unitPrice": 58,
            "taxRate": 13,
            "destWarehouseName": "DEMO-GOLD RN role warehouse",
            "destWarehouseCode": "DEMO-RN"
        }]
    }
    c, b = req("POST", f"/{FACTORY}/sales/orders", sales_token, so_body)
    so_id = data_id(b)
    created["so_id"] = so_id
    rec("sales creates SO", c == 200 and bool(so_id), f"HTTP {c} id={so_id}", b)
    if so_id:
        c2, b2 = req("POST", f"/{FACTORY}/sales/orders/{so_id}/confirm", sales_token, {})
        rec("sales confirms SO", c2 == 200, f"HTTP {c2}", b2)


def negative_checks(tokens):
    # 1. Low role viewer cannot cancel a sales order.
    so_id = created.get("so_id")
    if so_id and tokens.get("viewer"):
        c, b = req("POST", f"/{FACTORY}/sales/orders/{so_id}/cancel", tokens["viewer"], {})
        rec("foolproof viewer cannot cancel SO", c == 403, f"HTTP {c}", b)

    # 2. MR direct receipt must require warehouseId.
    mr = {
        "batchNumber": "DEMO-GOLD-RN-MR-BAD-" + TS,
        "materialTypeId": MAT_RAW,
        "supplierId": SUPPLIER,
        "receiptDate": str(date.today()),
        "receiptQuantity": 1,
        "quantityUnit": "kg",
        "unitPrice": 1,
        "notes": "DEMO-GOLD-RN missing warehouse negative"
    }
    c, b = req("POST", f"/{FACTORY}/processing/material-receipt", tokens["production"], mr)
    msg = b.get("message", "") if isinstance(b, dict) else str(b)
    rec("foolproof MR missing warehouse explicit", c == 400 and "仓库" in msg, f"HTTP {c} message={msg}", b)

    # 3. Already approved disposal cannot be approved again.
    c, b = req("PUT", f"/{FACTORY}/disposal-records/3/approve", tokens["warehouse"], {
        "approverId": 1554,
        "approverName": "DEMO-GOLD-RN"
    })
    msg = b.get("message", "") if isinstance(b, dict) else str(b)
    rec("foolproof disposal approve idempotent", c in (400, 409) and "已审批" in msg, f"HTTP {c} message={msg}", b)

    # 4. Stocktake should be gated before month-end.
    st = {"warehouseId": WH_LOGISTICS, "periodMonth": str(date.today())[:7], "notes": "DEMO-GOLD-RN stocktake gate " + TS}
    c, b = req("POST", f"/{FACTORY}/stocktakes", tokens["warehouse"], st)
    msg = b.get("message", "") if isinstance(b, dict) else str(b)
    rec("foolproof stocktake month-end gate", c in (400, 409) and ("29" in msg or "月底" in msg), f"HTTP {c} message={msg}", b)

    # 5. Operator cannot report task assigned to another user.
    pr = {
        "workProcessTaskId": 366,
        "reportKind": "OUTPUT",
        "outputQuantity": 1,
        "unit": "kg",
        "reportTime": str(date.today()) + "T09:00:00",
        "notes": "DEMO-GOLD-RN unassigned operator guard"
    }
    c, b = req("POST", f"/{FACTORY}/production/batches/1989/reports", tokens["operator"], pr)
    msg = b.get("message", "") if isinstance(b, dict) else str(b)
    rec("foolproof operator cannot report unassigned task", c == 403 and "无权" in msg, f"HTTP {c} message={msg}", b)


def sql_evidence():
    po = created.get("po_id") or ""
    receive = created.get("receive_id") or ""
    so = created.get("so_id") or ""
    q = f"""
    select 'po|'||id||'|'||order_number||'|'||status||'|'||coalesce(remark,'')
      from purchase_orders where id='{po}'
    union all
    select 'receive|'||id||'|'||receive_number||'|'||status||'|'||coalesce(warehouse_id,'')
      from purchase_receive_records where id='{receive}'
    union all
    select 'batch|'||mb.id||'|'||mb.batch_number||'|'||mb.status||'|'||coalesce(mb.warehouse_id,'')
      from material_batches mb
      join purchase_receive_items pri on pri.material_batch_id = mb.id
     where pri.receive_record_id='{receive}'
    union all
    select 'so|'||id||'|'||order_number||'|'||status||'|'||coalesce(remark,'')
      from sales_orders where id='{so}';
    """
    print("SQL_EVIDENCE_START")
    print(remote_sql(q))
    print("SQL_EVIDENCE_END")


def main():
    tokens = {
        "procurement": login("f006_procurement_mgr"),
        "warehouse": login("f006_warehouse_mgr"),
        "sales": login("f006_sales_mgr"),
        "viewer": login("f006_viewer"),
        "operator": login("f006_moyun"),
        "production": login("f006_production_mgr"),
    }
    if not all(tokens.values()):
        rec("all role logins", False, "one or more tokens missing")
        sys.exit(2)
    create_procurement_chain(tokens["procurement"], tokens["warehouse"])
    create_sales_chain(tokens["sales"])
    negative_checks(tokens)
    sql_evidence()
    print("SUMMARY_JSON_START")
    print(json.dumps({"created": created, "results": results}, ensure_ascii=False, indent=2))
    print("SUMMARY_JSON_END")


if __name__ == "__main__":
    main()
