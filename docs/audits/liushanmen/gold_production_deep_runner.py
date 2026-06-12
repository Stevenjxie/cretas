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
TODAY = str(date.today())
TOMORROW = str(date.today() + timedelta(days=1))
PT_ZZB = "1d7fbd73-8797-4933-83f1-46413a45992d"
SUPERVISOR_ID = 1552


results = []
created = {}
raw_sql = {}


def data_of(resp):
    if not isinstance(resp, dict):
        return {}
    data = resp.get("data")
    return data if isinstance(data, dict) else {}


def log(name, ok, detail="", extra=None):
    row = {"name": name, "ok": bool(ok), "detail": detail, "extra": extra}
    results.append(row)
    print(("PASS" if ok else "FAIL") + " " + name + " :: " + detail)
    if extra is not None:
        print(json.dumps(extra, ensure_ascii=False)[:1200])


def ssh(cmd, stdin=None, timeout=60):
    p = subprocess.run(
        ["ssh", SSH_HOST, cmd],
        input=stdin,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
    )
    return p.stdout


def req(method, path, token=None, params=None, body=None, timeout=60):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    parts = ["curl", "-sS", "-w", "\nHTTP_STATUS:%{http_code}", "-X", method]
    if body is not None:
        headers["Content-Type"] = "application/json"
    for k, v in headers.items():
        parts.extend(["-H", f"{k}: {v}"])
    if body is not None:
        parts.extend(["--data-binary", "@-"])
    url = BASE + path
    if params:
        query = "&".join(f"{shlex.quote(str(k))}={shlex.quote(str(v))}" for k, v in params.items())
        # Values in this runner are simple; build query before shell quoting the full URL.
        query = "&".join(f"{k}={v}" for k, v in params.items())
        url += "?" + query
    parts.append(url)
    out = ssh(" ".join(shlex.quote(p) for p in parts), json.dumps(body, ensure_ascii=False) if body is not None else None, timeout)
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


def login(username):
    code, body = req("POST", "/auth/unified-login", body={"username": username, "password": PWD})
    token = body.get("data", {}).get("token") if isinstance(body, dict) else None
    log("login " + username, code == 200 and bool(token), f"HTTP {code}")
    return token


def sql(query, label):
    one_line = " ".join(query.split())
    cmd = "PGPASSWORD=cretas123 psql -U cretas_user -h127.0.0.1 -d cretas_prod_db -Atc " + repr(one_line)
    out = ssh(cmd, timeout=45)
    raw_sql[label] = out.strip()
    print("--- SQL " + label + " ---")
    print(out.strip())
    return out.strip()


def pick_customer():
    out = sql(
        "select id from customers where factory_id='F006' and deleted_at is null order by created_at desc nulls last limit 1",
        "pick_customer",
    )
    if not out:
        out = sql("select id from customers where factory_id='F006' order by created_at desc nulls last limit 1", "pick_customer_fallback")
    return out.splitlines()[0].strip() if out else None


def pick_priced_material_batch():
    out = sql(
        """
        select id||'|'||batch_number||'|'||material_type_id||'|'||receipt_quantity||'|'||coalesce(unit_price::text,'')
          from material_batches
         where factory_id='F006'
           and unit_price is not null
           and receipt_quantity > 0
           and status in ('AVAILABLE','IN_STOCK')
         order by created_at desc nulls last
         limit 1
        """,
        "pick_priced_material_batch",
    )
    if not out:
        return None
    parts = out.splitlines()[0].split("|")
    return {"id": parts[0], "batch": parts[1], "materialTypeId": parts[2], "qty": parts[3], "unitPrice": parts[4]}


def create_sales_order(admin, customer_id):
    body = {
        "customerId": customer_id,
        "orderDate": TODAY,
        "requiredDeliveryDate": TOMORROW,
        "defaultTaxRate": 13,
        "remark": "DEMO-GOLD withdrawal self-heal " + TS,
        "items": [
            {
                "productTypeId": PT_ZZB,
                "productName": "DEMO-GOLD zhangzhongbao",
                "quantity": 10,
                "unit": "kg",
                "unitPrice": 88,
                "taxRate": 13,
                "remark": "DEMO-GOLD cost backfill target",
            }
        ],
    }
    code, resp = req("POST", f"/{FACTORY}/sales/orders", admin, body=body)
    order_id = data_of(resp).get("id")
    created["order_id"] = order_id
    log("create SO", code == 200 and bool(order_id), f"HTTP {code} order={order_id}", resp)
    return order_id


def finance_approve(admin, order_id):
    for step, path, body in [
        ("confirm SO", f"/{FACTORY}/sales/orders/{order_id}/confirm", None),
        ("submit SO finance", f"/{FACTORY}/sales/orders/{order_id}/submit-for-review", None),
        ("finance approve SO", f"/{FACTORY}/sales/orders/{order_id}/finance-approve", {"notes": "DEMO-GOLD finance approval"}),
    ]:
        code, resp = req("POST", path, admin, body=body)
        log(step, code == 200, f"HTTP {code}", resp)
        if code != 200:
            return False
    return True


def create_plan_and_batch(admin, order_id):
    plan_body = {
        "productTypeId": PT_ZZB,
        "plannedQuantity": 10,
        "plannedDate": TODAY,
        "expectedCompletionDate": TOMORROW,
        "sourceOrderId": order_id,
        "sourceOrderIds": [order_id],
        "sourceType": "CUSTOMER_ORDER",
        "processName": "DEMO-GOLD cost backfill process",
        "batchDate": TODAY,
        "notes": "DEMO-GOLD withdrawal self-heal plan " + TS,
        "estimatedWorkers": 2,
        "assignedSupervisorId": SUPERVISOR_ID,
    }
    code, resp = req("POST", f"/{FACTORY}/production-plans", admin, body=plan_body)
    plan_id = data_of(resp).get("id")
    created["plan_id"] = plan_id
    log("create production plan", code == 200 and bool(plan_id), f"HTTP {code} plan={plan_id}", resp)
    if not plan_id:
        return None, None, []

    batch_body = {
        "batchNumber": "DEMO-GOLD-WITHDRAW-" + TS,
        "productTypeId": PT_ZZB,
        "productionPlanId": plan_id,
        "plannedQuantity": 10,
        "quantity": 10,
        "unit": "kg",
        "remark": "DEMO-GOLD withdrawal self-heal batch " + TS,
    }
    code, resp = req("POST", f"/{FACTORY}/processing/batches", admin, body=batch_body)
    batch_id = data_of(resp).get("id")
    created["batch_id"] = batch_id
    log("create production batch", code == 200 and bool(batch_id), f"HTTP {code} batch={batch_id}", resp)
    if not batch_id:
        return plan_id, None, []

    code, resp = req("POST", f"/{FACTORY}/processing/batches/{batch_id}/start", admin, params={"supervisorId": SUPERVISOR_ID})
    log("start production batch", code == 200, f"HTTP {code}", resp)

    code, resp = req("POST", f"/{FACTORY}/production/batches/{batch_id}/spawn-tasks", admin, body={"productTypeId": PT_ZZB})
    tasks = resp.get("data") if isinstance(resp, dict) else []
    created["tasks"] = tasks
    log("spawn work process tasks", code == 200 and bool(tasks), f"HTTP {code} tasks={len(tasks) if tasks else 0}", tasks)
    return plan_id, batch_id, tasks or []


def approve_report(approver, report_id):
    code, resp = req("PUT", f"/{FACTORY}/process-work-reporting/{report_id}/approve", approver)
    log("approve report " + str(report_id), code == 200, f"HTTP {code}", resp)
    return code == 200


def submit_input_report(worker, approver, batch_id, task_id, material_batch, label):
    body = {
        "workProcessTaskId": task_id,
        "reportKind": "INPUT",
        "inputQuantity": "0.5",
        "inputUnit": "kg",
        "materialBatchRefs": [{"materialBatchId": material_batch["id"], "quantity": "0.5", "unit": "kg"}],
        "evidenceImages": ["https://oss.example.invalid/DEMO-GOLD-" + TS + "-" + label + ".jpg"],
        "photoAnnotations": [
            {
                "url": "https://oss.example.invalid/DEMO-GOLD-" + TS + "-" + label + ".jpg",
                "label": "weigh-input",
                "note": "DEMO-GOLD synthetic input marker for API deep",
            }
        ],
        "remark": "DEMO-GOLD " + label + " input report",
        "businessDate": TODAY,
    }
    code, resp = req("POST", f"/{FACTORY}/production/batches/{batch_id}/reports", worker, body=body)
    report_id = None
    if isinstance(resp, dict):
        report_id = data_of(resp).get("reportId") or data_of(resp).get("id")
    created[label + "_report_id"] = report_id
    log("submit input report " + label, code == 200 and bool(report_id), f"HTTP {code} report={report_id}", resp)
    if report_id:
        approve_report(approver, report_id)
    return report_id


def submit_output_report(worker, approver, batch_id, task_id, material_batch, output_qty, label):
    body = {
        "workProcessTaskId": task_id,
        "reportKind": "OUTPUT",
        "outputKind": "FINISHED",
        "outputQuantity": str(output_qty),
        "outputUnit": "kg",
        "workMinutes": 120,
        "workerCount": 2,
        "markComplete": True,
        "evidenceImages": ["https://oss.example.invalid/DEMO-GOLD-" + TS + "-" + label + ".jpg"],
        "photoAnnotations": [
            {
                "url": "https://oss.example.invalid/DEMO-GOLD-" + TS + "-" + label + ".jpg",
                "label": "weigh-output",
                "note": "DEMO-GOLD synthetic evidence marker for API deep",
            }
        ],
        "remark": "DEMO-GOLD " + label + " output report",
        "businessDate": TODAY,
    }
    code, resp = req("POST", f"/{FACTORY}/production/batches/{batch_id}/reports", worker, body=body)
    report_id = None
    if isinstance(resp, dict):
        data = data_of(resp)
        report_id = data.get("reportId") or data.get("id")
    created[label + "_report_id"] = report_id
    log("submit output report " + label, code == 200 and bool(report_id), f"HTTP {code} report={report_id}", resp)
    if report_id:
        approve_report(approver, report_id)
    return report_id


def poll_cost(order_id, expect_value, label, timeout_s=20):
    for _ in range(timeout_s):
        out = sql(
            f"""
            select so.order_number||'|'||so.status||'|'||soi.id||'|'||coalesce(soi.cost_unit_price::text,'<null>')
              from sales_orders so
              join sales_order_items soi on soi.sales_order_id=so.id
             where so.id='{order_id}'
             order by soi.id
            """,
            label,
        )
        has_value = "<null>" not in out and bool(out.strip())
        if has_value == expect_value:
            return out
        time.sleep(1)
    return out


def reverse_batch(submitter, approver, batch_id):
    code, resp = req(
        "POST",
        f"/{FACTORY}/processing/batches/{batch_id}/reversal",
        submitter,
        body={"reversalScope": "FULL_BATCH", "reason": "DEMO_GOLD_SELF_HEAL", "remark": "DEMO-GOLD withdrawal self-heal"},
    )
    log_id = data_of(resp).get("id")
    created["reversal_log_id"] = log_id
    log("submit reversal", code == 200 and bool(log_id), f"HTTP {code} log={log_id}", resp)
    if not log_id:
        return False
    code, resp = req("PUT", f"/{FACTORY}/reversals/{log_id}/approve", approver, body={"remark": "DEMO-GOLD approve reversal"})
    log("approve reversal", code == 200, f"HTTP {code}", resp)
    return code == 200


def main():
    admin = login("f006_admin")
    production_mgr = login("f006_production_mgr")
    worker = login("f006_moyun")
    if not (admin and production_mgr and worker):
        sys.exit(2)

    customer_id = pick_customer()
    material_batch = pick_priced_material_batch()
    log("preconditions", bool(customer_id and material_batch), f"customer={customer_id} material={material_batch}")
    if not customer_id or not material_batch:
        sys.exit(3)

    order_id = create_sales_order(admin, customer_id)
    if not order_id or not finance_approve(admin, order_id):
        sys.exit(4)

    plan_id, batch_id, tasks = create_plan_and_batch(admin, order_id)
    if not batch_id or not tasks:
        sys.exit(5)
    input_task = tasks[0]["id"]
    output_task = tasks[-1]["id"]
    created["input_task_id"] = input_task
    created["output_task_id"] = output_task

    submit_input_report(production_mgr, admin, batch_id, input_task, material_batch, "first")
    report1 = submit_output_report(production_mgr, admin, batch_id, output_task, material_batch, 10, "first")
    if report1:
        first_sql = poll_cost(order_id, True, "cost_after_first_report")
        log("cost backfilled after first report", "<null>" not in first_sql and bool(first_sql), first_sql)

    if reverse_batch(production_mgr, admin, batch_id):
        null_sql = poll_cost(order_id, False, "cost_after_reversal")
        log("cost cleared after reversal", "<null>" in null_sql, null_sql)

    # Re-read task list after reversal; the task may have been reset or recreated.
    code, resp = req("GET", f"/{FACTORY}/production/batches/{batch_id}/work-process-tasks", admin)
    tasks2 = resp.get("data") if isinstance(resp, dict) else []
    if tasks2:
        output_task2 = tasks2[-1]["id"]
    else:
        output_task2 = output_task
    created["output_task_id_after_reversal"] = output_task2
    report2 = submit_output_report(production_mgr, admin, batch_id, output_task2, material_batch, 8, "second")
    if report2:
        second_sql = poll_cost(order_id, True, "cost_after_second_report")
        first_value = raw_sql.get("cost_after_first_report", "")
        log(
            "cost re-backfilled after second report",
            "<null>" not in second_sql and second_sql != first_value,
            "first=" + first_value + " second=" + second_sql,
        )

    sql(
        f"""
        select 'batch|'||id||'|'||batch_number||'|'||status||'|'||coalesce(actual_quantity::text,'')
          from production_batches where id={batch_id};
        select 'report|'||id||'|'||approval_status||'|'||report_kind||'|'||coalesce(output_quantity::text,'')||'|'||coalesce(deleted_at::text,'')
          from production_reports where batch_id={batch_id} order by id;
        select 'wip|'||id||'|'||intermediate_batch_no||'|'||available_quantity||'|'||coalesce(unit_cost::text,'')||'|'||status
          from semi_finished_inventory where batch_id={batch_id} order by id;
        select 'rev|'||id||'|'||status||'|'||batch_id||'|'||submitted_by||'|'||coalesce(approved_by::text,'')
          from report_reversal_logs where batch_id={batch_id} order by id;
        """,
        "final_batch_readback",
    )

    print("\n=== CREATED ===")
    print(json.dumps(created, ensure_ascii=False, indent=2))
    print("\n=== RESULTS ===")
    print(json.dumps(results, ensure_ascii=False, indent=2))
    print("\n=== RAW_SQL ===")
    print(json.dumps(raw_sql, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
