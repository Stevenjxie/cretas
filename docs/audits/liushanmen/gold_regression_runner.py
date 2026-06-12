import json
import random
import string
import subprocess
import sys
import time
from datetime import date

import shlex


BASE = "http://127.0.0.1:10010/api/mobile"
SSH_HOST = "root@47.100.235.168"
FACTORY = "F006"
PWD = "123456"
TS = str(int(time.time()))
SUPPLIER = "844d8c69-ed89-4a7f-b850-1749456d0777"
WH_RAW = "6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e"
MAT_PACK = "RMT_1777441647310"
MAT_PACK_NAME = "吸塑盒2014-3.5"
MAT_RAW = "RMT_1777690082465"

results = []
created = {}


def req(method, path, token=None, **kwargs):
    headers = kwargs.pop("headers", {})
    if token:
        headers["Authorization"] = "Bearer " + token
    if "json" in kwargs:
        headers.setdefault("Content-Type", "application/json")
        data = json.dumps(kwargs["json"], ensure_ascii=False)
    else:
        data = None

    parts = [
        "curl",
        "-sS",
        "-w",
        "\nHTTP_STATUS:%{http_code}",
        "-X",
        method,
    ]
    for k, v in headers.items():
        parts += ["-H", f"{k}: {v}"]
    if data is not None:
        parts += ["--data-binary", "@-"]
    parts.append(BASE + path)
    remote_cmd = " ".join(shlex.quote(p) for p in parts)
    r = subprocess.run(
        ["ssh", SSH_HOST, remote_cmd],
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
        status_code = int(status.strip()[:3])
    else:
        text = out
        status_code = 0
    try:
        body = json.loads(text) if text else None
    except Exception:
        body = text[:500]
    return status_code, body


def rec(name, ok, detail, extra=None):
    results.append({"name": name, "ok": ok, "detail": detail, "extra": extra})
    print(("PASS" if ok else "FAIL") + " " + name + " :: " + detail)
    if extra is not None:
        print(json.dumps(extra, ensure_ascii=False)[:1000])


def login(user):
    code, body = req("POST", "/auth/unified-login", json={"username": user, "password": PWD})
    tok = body.get("data", {}).get("token") if isinstance(body, dict) else None
    rec("login " + user, code == 200 and bool(tok), f"HTTP {code}")
    return tok


def remote_sql(query):
    one_line = " ".join(query.split())
    cmd = "PGPASSWORD=cretas123 psql -U cretas_user -h127.0.0.1 -d cretas_prod_db -Atc " + repr(one_line)
    p = subprocess.run(
        ["ssh", SSH_HOST, cmd],
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=30,
    )
    return p.stdout


def main():
    admin = login("f006_admin")
    cashier = login("f006_cashier")
    if not admin:
        sys.exit(2)

    receive_payload = {
        "supplierId": SUPPLIER,
        "receiveDate": str(date.today()),
        "warehouseId": WH_RAW,
        "remark": "DEMO-GOLD #774 confirm-500 final regression " + TS,
        "items": [
            {
                "materialTypeId": MAT_PACK,
                "materialName": MAT_PACK_NAME,
                "receivedQuantity": "2",
                "unit": "件",
                "unitPrice": "0.55",
                "qcResult": "PASS",
                "remark": "DEMO-GOLD packaging BOM unit fix confirm",
            }
        ],
    }
    code, body = req("POST", f"/{FACTORY}/purchase/receives", admin, json=receive_payload)
    receive_id = body.get("data", {}).get("id") if isinstance(body, dict) else None
    created["receive_id"] = receive_id
    rec("#774 create receive", code == 200 and bool(receive_id), f"HTTP {code} receive_id={receive_id}", body)
    if receive_id:
        code2, body2 = req("POST", f"/{FACTORY}/purchase/receives/{receive_id}/confirm", admin)
        rec("#774 confirm receive no 500", code2 == 200, f"HTTP {code2}", body2)

    mr_batch = "DEMO-GOLD-MR-OK-" + TS
    mr_payload = {
        "batchNumber": mr_batch,
        "materialTypeId": MAT_RAW,
        "supplierId": SUPPLIER,
        "receiptDate": str(date.today()),
        "receiptQuantity": "1.25",
        "quantityUnit": "g",
        "unitPrice": "0.03",
        "warehouseId": WH_RAW,
        "notes": "DEMO-GOLD #774 MR-500 final regression",
    }
    code, body = req("POST", f"/{FACTORY}/processing/material-receipt", admin, json=mr_payload)
    mr_id = body.get("data", {}).get("id") if isinstance(body, dict) else None
    created["mr_ok_id"] = mr_id
    created["mr_ok_batch"] = mr_batch
    rec("#774 material-receipt with warehouse", code == 200 and bool(mr_id), f"HTTP {code} id={mr_id}", body)

    mr_bad = "DEMO-GOLD-MR-NOWH-" + TS
    bad_payload = dict(mr_payload)
    bad_payload.pop("warehouseId", None)
    bad_payload["batchNumber"] = mr_bad
    bad_payload["notes"] = "DEMO-GOLD #775 MR-400 final regression"
    code, body = req("POST", f"/{FACTORY}/processing/material-receipt", admin, json=bad_payload)
    msg = json.dumps(body, ensure_ascii=False) if isinstance(body, dict) else str(body)
    rec(
        "#775 material-receipt without warehouse explicit 400",
        code == 400 and "请指定入库仓库" in msg,
        f"HTTP {code} contains 请指定入库仓库={'请指定入库仓库' in msg}",
        body,
    )

    if cashier:
        code, body = req("GET", f"/{FACTORY}/payment-requests/approved", cashier)
        data = body.get("data") if isinstance(body, dict) else None
        first = data[0] if isinstance(data, list) and data else None
        has_bank = bool(first and first.get("bankName") and first.get("bankAccount"))
        rec(
            "#776 cashier approved payment bank info",
            code == 200 and has_bank,
            "HTTP {} first.bankName={} bankAccount={}".format(
                code, first.get("bankName") if first else None, first.get("bankAccount") if first else None
            ),
            first or body,
        )

    code, body = req(
        "PUT",
        f"/{FACTORY}/disposal-records/3/approve",
        admin,
        json={"approverId": 1309, "approverName": "DEMO-GOLD"},
    )
    msg = json.dumps(body, ensure_ascii=False) if isinstance(body, dict) else str(body)
    rec(
        "#777 disposal idempotency reapprove rejected",
        code in (400, 409) and "已审批" in msg,
        f"HTTP {code} contains 已审批={'已审批' in msg}",
        body,
    )

    mat_id = None
    for attempt in range(10):
        code2 = "".join(random.choice(string.ascii_uppercase + string.digits) for _ in range(2))
        mat_payload = {
            "code": code2,
            "name": f"DEMO-GOLD-标签前缀原料-{TS}-{attempt}",
            "category": "原料",
            "unit": "kg",
            "storageType": "dry",
            "notes": "DEMO-GOLD #777 label prefix fallback no primaryCode",
        }
        code, body = req("POST", f"/{FACTORY}/raw-material-types", admin, json=mat_payload)
        if code == 200 and isinstance(body, dict) and body.get("data", {}).get("id"):
            mat_id = body["data"]["id"]
            break
    created["label_material_id"] = mat_id
    created["label_material_code"] = code2 if mat_id else None
    rec("#777 create material with no primaryCode source", bool(mat_id), f"HTTP {code} material={mat_id}", body)

    if mat_id:
        batch = "DEMO-GOLD-LABEL-" + TS
        payload = {
            "batchNumber": batch,
            "materialTypeId": mat_id,
            "supplierId": SUPPLIER,
            "receiptDate": str(date.today()),
            "receiptQuantity": "1",
            "quantityUnit": "kg",
            "unitPrice": "1",
            "warehouseId": WH_RAW,
            "notes": "DEMO-GOLD #777 label prefix batch",
        }
        code, body = req("POST", f"/{FACTORY}/processing/material-receipt", admin, json=payload)
        batch_id = body.get("data", {}).get("id") if isinstance(body, dict) else None
        created["label_batch_id"] = batch_id
        created["label_batch_number"] = batch
        rec("#777 create batch for label", code == 200 and bool(batch_id), f"HTTP {code} batch_id={batch_id}", body)
        if batch_id:
            code, body = req("POST", f"/{FACTORY}/labels/material-batch/{batch_id}", admin)
            label = body.get("data", {}) if isinstance(body, dict) else {}
            label_code = label.get("labelCode")
            created["label_code"] = label_code
            rec(
                "#777 label prefix YL/RL/BC/WL not MA",
                code in (200, 201) and bool(label_code) and label_code.startswith("YL"),
                f"HTTP {code} labelCode={label_code}",
                body,
            )

    sql = f"""
    select 'receive|'||id||'|'||receive_number||'|'||status||'|'||coalesce(warehouse_id,'')
      from purchase_receive_records where id='{created.get("receive_id", "")}';
    select 'mr_ok|'||id||'|'||batch_number||'|'||status||'|'||coalesce(warehouse_id,'')
      from material_batches where id='{created.get("mr_ok_id", "")}';
    select 'label_mat|'||id||'|'||code||'|'||coalesce(primary_code,'<null>')||'|'||category
      from raw_material_types where id='{created.get("label_material_id", "")}';
    select 'label|'||id||'|'||label_code||'|'||batch_id
      from labels where label_code='{created.get("label_code", "")}';
    select 'pay_api_db|'||pr.id||'|'||pr.status||'|'||coalesce(s.bank_name,'')||'|'||coalesce(s.bank_account,'')
      from payment_requests pr left join suppliers s on s.id=pr.supplier_id
      where pr.factory_id='F006' and pr.status='APPROVED'
      order by pr.approved_at asc nulls last limit 3;
    """
    print("\n--- SQL READBACK ---")
    print(remote_sql(sql))
    print("\n--- JSON SUMMARY ---")
    print(json.dumps({"created": created, "results": results}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
