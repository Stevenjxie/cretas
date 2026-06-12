import json
import shlex
import subprocess
import sys
import time
from datetime import date, timedelta
from decimal import Decimal


BASE = "http://127.0.0.1:10010/api/mobile"
SSH_HOST = "root@47.100.235.168"
FACTORY = "F006"
PWD = "123456"
TS = str(int(time.time()))
TODAY = str(date.today())
TOMORROW = str(date.today() + timedelta(days=1))
PT_ZZB = "1d7fbd73-8797-4933-83f1-46413a45992d"
SUPERVISOR_ID = 1552
SUPPLIER = "844d8c69-ed89-4a7f-b850-1749456d0777"
WH_RAW = "6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e"
MAT_RAW = "RMT_1777690082465"
MARK = "DEMO-GOLD-RERUN-" + TS


results = []
created = {}
raw_sql = {}
responses = {}


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
        print(json.dumps(extra, ensure_ascii=False)[:2000])


def ssh(cmd, stdin=None, timeout=80):
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


def req(method, path, token=None, params=None, body=None, timeout=80, label=None):
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
        url += "?" + "&".join(f"{k}={v}" for k, v in params.items())
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
        parsed = text[:2000]
    if label:
        responses[label] = {"method": method, "path": path, "status": code, "body": parsed}
    return code, parsed


def login(username):
    code, body = req("POST", "/auth/unified-login", body={"username": username, "password": PWD}, label="login_" + username)
    token = body.get("data", {}).get("token") if isinstance(body, dict) else None
    log("login " + username, code == 200 and bool(token), f"HTTP {code}")
    return token


def sql(query, label):
    one_line = " ".join(query.split())
    cmd = "PGPASSWORD=cretas123 psql -U cretas_user -h127.0.0.1 -d cretas_prod_db -Atc " + repr(one_line)
    out = ssh(cmd, timeout=60).strip()
    raw_sql[label] = {"query": one_line, "output": out}
    print("--- SQL " + label + " ---")
    print(out)
    return out


def pick_customer():
    out = sql(
        "select id from customers where factory_id='F006' and deleted_at is null order by created_at desc nulls last limit 1",
        "pick_customer",
    )
    return out.splitlines()[0].strip() if out else None


def create_material_batch(admin, suffix, qty, unit_price):
    batch_no = f"{MARK}-MAT-{suffix}"
    payload = {
        "batchNumber": batch_no,
        "materialTypeId": MAT_RAW,
        "supplierId": SUPPLIER,
        "receiptDate": TODAY,
        "receiptQuantity": str(qty),
        "quantityUnit": "kg",
        "unitPrice": str(unit_price),
        "warehouseId": WH_RAW,
        "notes": f"{MARK} material {suffix}",
    }
    code, body = req("POST", f"/{FACTORY}/processing/material-receipt", admin, body=payload, label="material_receipt_" + suffix)
    mb_id = data_of(body).get("id")
    log("create priced material " + suffix, code == 200 and bool(mb_id), f"HTTP {code} batch={mb_id}", body)
    return {"id": mb_id, "batchNumber": batch_no, "qty": str(qty), "unitPrice": str(unit_price)} if mb_id else None


def create_sales_order(admin, customer_id, suffix, qty):
    body = {
        "customerId": customer_id,
        "orderDate": TODAY,
        "requiredDeliveryDate": TOMORROW,
        "defaultTaxRate": 13,
        "remark": f"{MARK} SO {suffix}",
        "items": [
            {
                "productTypeId": PT_ZZB,
                "productName": "DEMO-GOLD zhangzhongbao",
                "quantity": str(qty),
                "unit": "kg",
                "unitPrice": "88",
                "taxRate": 13,
                "remark": f"{MARK} cost target {suffix}",
            }
        ],
    }
    code, resp = req("POST", f"/{FACTORY}/sales/orders", admin, body=body, label="create_so_" + suffix)
    order_id = data_of(resp).get("id")
    order_no = data_of(resp).get("orderNumber")
    log("create SO " + suffix, code == 200 and bool(order_id), f"HTTP {code} order={order_id} no={order_no}", resp)
    if not order_id:
        return None
    for step, path, body2 in [
        ("confirm", f"/{FACTORY}/sales/orders/{order_id}/confirm", None),
        ("submit", f"/{FACTORY}/sales/orders/{order_id}/submit-for-review", None),
        ("finance", f"/{FACTORY}/sales/orders/{order_id}/finance-approve", {"notes": f"{MARK} finance {suffix}"}),
    ]:
        code, resp = req("POST", path, admin, body=body2, label=f"{step}_so_{suffix}")
        log(f"{step} SO {suffix}", code == 200, f"HTTP {code}", resp)
        if code != 200:
            return None
    return order_id


def create_plan(admin, order_id, suffix, qty):
    body = {
        "productTypeId": PT_ZZB,
        "plannedQuantity": str(qty),
        "plannedDate": TODAY,
        "expectedCompletionDate": TOMORROW,
        "sourceOrderId": order_id,
        "sourceOrderIds": [order_id],
        "sourceType": "CUSTOMER_ORDER",
        "processName": f"{MARK} process {suffix}",
        "batchDate": TODAY,
        "notes": f"{MARK} plan {suffix}",
        "estimatedWorkers": 2,
        "assignedSupervisorId": SUPERVISOR_ID,
    }
    code, resp = req("POST", f"/{FACTORY}/production-plans", admin, body=body, label="create_plan_" + suffix)
    plan_id = data_of(resp).get("id")
    log("create plan " + suffix, code == 200 and bool(plan_id), f"HTTP {code} plan={plan_id}", resp)
    return plan_id


def create_secondary_plan(admin, wip_id, suffix, qty):
    body = {"wipId": int(wip_id), "quantity": str(qty), "productTypeId": PT_ZZB, "plannedDate": TODAY}
    code, resp = req("POST", f"/{FACTORY}/processing/secondary-plan", admin, body=body, label="create_secondary_" + suffix)
    plan_id = data_of(resp).get("id")
    log("create secondary plan " + suffix, code == 200 and bool(plan_id), f"HTTP {code} plan={plan_id}", resp)
    return plan_id


def create_batch_and_tasks(admin, plan_id, suffix, qty):
    body = {
        "batchNumber": f"{MARK}-BATCH-{suffix}",
        "productTypeId": PT_ZZB,
        "productionPlanId": plan_id,
        "plannedQuantity": str(qty),
        "quantity": str(qty),
        "unit": "kg",
        "remark": f"{MARK} batch {suffix}",
    }
    code, resp = req("POST", f"/{FACTORY}/processing/batches", admin, body=body, label="create_batch_" + suffix)
    batch_id = data_of(resp).get("id")
    log("create batch " + suffix, code == 200 and bool(batch_id), f"HTTP {code} batch={batch_id}", resp)
    if not batch_id:
        return None, []
    code, resp = req("POST", f"/{FACTORY}/processing/batches/{batch_id}/start", admin, params={"supervisorId": SUPERVISOR_ID}, label="start_batch_" + suffix)
    log("start batch " + suffix, code == 200, f"HTTP {code}", resp)
    code, resp = req("POST", f"/{FACTORY}/production/batches/{batch_id}/spawn-tasks", admin, body={"productTypeId": PT_ZZB}, label="spawn_tasks_" + suffix)
    tasks = resp.get("data") if isinstance(resp, dict) and isinstance(resp.get("data"), list) else []
    log("spawn tasks " + suffix, code == 200 and bool(tasks), f"HTTP {code} tasks={len(tasks)}", tasks)
    return batch_id, tasks


def get_tasks(admin, batch_id, label):
    code, resp = req("GET", f"/{FACTORY}/production/batches/{batch_id}/work-process-tasks", admin, label="get_tasks_" + label)
    tasks = resp.get("data") if isinstance(resp, dict) and isinstance(resp.get("data"), list) else []
    log("get tasks " + label, code == 200 and bool(tasks), f"HTTP {code} tasks={len(tasks)}", tasks)
    return tasks


def approve(approver, report_id, label):
    code, resp = req("PUT", f"/{FACTORY}/process-work-reporting/{report_id}/approve", approver, body={"remark": f"{MARK} approve {label}"}, label="approve_" + label)
    log("approve report " + label, code == 200, f"HTTP {code} report={report_id}", resp)
    return code == 200


def submit_report(worker, approver, batch_id, task_id, kind, label, **kwargs):
    body = {
        "workProcessTaskId": task_id,
        "reportKind": kind,
        "businessDate": TODAY,
        "remark": f"{MARK} {label}",
        "evidenceImages": [f"https://oss.example.invalid/{MARK}-{label}.jpg"],
        "photoAnnotations": [{"url": f"https://oss.example.invalid/{MARK}-{label}.jpg", "label": label, "note": MARK}],
    }
    body.update(kwargs)
    code, resp = req("POST", f"/{FACTORY}/production/batches/{batch_id}/reports", worker, body=body, label="submit_" + label)
    rid = data_of(resp).get("reportId") or data_of(resp).get("id")
    log("submit report " + label, code == 200 and bool(rid), f"HTTP {code} report={rid}", resp)
    if rid:
        approve(approver, rid, label)
    return rid


def submit_input(worker, approver, batch_id, task_id, label, qty, mat=None, source_wip_no=None):
    body = {"inputQuantity": str(qty), "inputUnit": "kg"}
    if mat:
        body["materialBatchRefs"] = [{"materialBatchId": mat["id"], "quantity": str(qty), "unit": "kg"}]
    if source_wip_no:
        body["sourceWipNo"] = source_wip_no
    return submit_report(worker, approver, batch_id, task_id, "INPUT", label, **body)


def submit_output(worker, approver, batch_id, task_id, label, qty, output_kind="FINISHED", semi_code=None):
    body = {
        "outputKind": output_kind,
        "outputQuantity": str(qty),
        "outputUnit": "kg",
        "markComplete": True,
    }
    if output_kind in ("SEMI", "BOTH"):
        body["semiOutputQuantity"] = str(qty)
        body["semiOutputUnit"] = "kg"
        body["semiCode"] = semi_code
    return submit_report(worker, approver, batch_id, task_id, "OUTPUT", label, **body)


def poll_order_cost(order_id, expect_value, label, timeout_s=35):
    out = ""
    for _ in range(timeout_s):
        out = sql(
            f"select so.order_number||'|'||so.status||'|'||soi.id||'|'||coalesce(soi.cost_unit_price::text,'<null>') "
            f"from sales_orders so join sales_order_items soi on soi.sales_order_id=so.id "
            f"where so.id='{order_id}' order by soi.id",
            label,
        )
        has_value = bool(out.strip()) and "<null>" not in out
        if has_value == expect_value:
            return out
        time.sleep(1)
    return out


def reverse_batch(submitter, approver, batch_id):
    code, resp = req(
        "POST",
        f"/{FACTORY}/processing/batches/{batch_id}/reversal",
        submitter,
        body={"reason": f"{MARK} WHOLE_ORDER reversal", "remark": MARK, "reversalScope": "WHOLE_ORDER"},
        label="submit_reversal",
    )
    log_id = data_of(resp).get("id")
    status = data_of(resp).get("status")
    log("submit WHOLE_ORDER reversal", code == 200 and bool(log_id), f"HTTP {code} log={log_id} status={status}", resp)
    if not log_id:
        return False
    if status == "DONE":
        return True
    code, resp = req("PUT", f"/{FACTORY}/reversals/{log_id}/approve", approver, body={"remark": f"{MARK} approve reversal"}, label="approve_reversal")
    ok = code == 200 or (isinstance(resp, dict) and data_of(resp).get("status") == "DONE")
    log("approve WHOLE_ORDER reversal", ok, f"HTTP {code}", resp)
    return ok


def extract_cost(sql_out):
    if not sql_out or "<null>" in sql_out:
        return None
    return Decimal(sql_out.split("|")[-1])


def run_withdrawal(admin, production_mgr, customer_id):
    mat1 = create_material_batch(admin, "SELF-1", "1.000", "1.00")
    mat2 = create_material_batch(admin, "SELF-2", "1.000", "2.00")
    order_id = create_sales_order(admin, customer_id, "SELF", "10")
    created["self_order_id"] = order_id
    if not (mat1 and mat2 and order_id):
        return False
    plan_id = create_plan(admin, order_id, "SELF", "10")
    batch_id, tasks = create_batch_and_tasks(admin, plan_id, "SELF", "10")
    created["self_plan_id"] = plan_id
    created["self_batch_id"] = batch_id
    if not tasks:
        return False
    input_task, output_task = tasks[0]["id"], tasks[-1]["id"]
    submit_input(production_mgr, admin, batch_id, input_task, "self_first_input", "0.500", mat=mat1)
    submit_output(production_mgr, admin, batch_id, output_task, "self_first_output", "10", output_kind="FINISHED")
    first = poll_order_cost(order_id, True, "self_cost_after_first")
    first_cost = extract_cost(first)
    log("self-heal first value non-null", first_cost is not None, str(first))
    reverse_ok = reverse_batch(production_mgr, admin, batch_id)
    null_out = poll_order_cost(order_id, False, "self_cost_after_reversal") if reverse_ok else ""
    log("self-heal reversal clears null", "<null>" in null_out, str(null_out))
    tasks2 = get_tasks(admin, batch_id, "self_after_reversal") or tasks
    input_task2, output_task2 = tasks2[0]["id"], tasks2[-1]["id"]
    submit_input(production_mgr, admin, batch_id, input_task2, "self_second_input", "0.500", mat=mat2)
    submit_output(production_mgr, admin, batch_id, output_task2, "self_second_output", "8", output_kind="FINISHED")
    second = poll_order_cost(order_id, True, "self_cost_after_second")
    second_cost = extract_cost(second)
    log("self-heal second value non-null and changed", second_cost is not None and first_cost is not None and second_cost != first_cost, f"first={first_cost} second={second_cost}")
    sql(
        f"select 'report|'||id||'|'||report_kind||'|'||approval_status||'|'||coalesce(deleted_at::text,'<live>')||'|'||coalesce(material_cost::text,'<null>')||'|'||coalesce(output_quantity::text,'<null>') "
        f"from production_reports where batch_id={batch_id} order by id",
        "self_reports_final",
    )
    sql(
        f"select 'wip|'||id||'|'||intermediate_batch_no||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')||'|'||status "
        f"from semi_finished_inventory where batch_id={batch_id} order by id",
        "self_wip_final",
    )
    return first_cost is not None and "<null>" in null_out and second_cost is not None and second_cost != first_cost


def wip_by_code(code, label):
    out = sql(
        f"select id||'|'||intermediate_batch_no||'|'||produced_quantity||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')||'|'||coalesce(accumulated_cost::text,'<null>') "
        f"from semi_finished_inventory where factory_id='F006' and intermediate_batch_no='{code}' and deleted_at is null order by id desc limit 1",
        label,
    )
    if not out:
        return None
    p = out.split("|")
    return {"id": p[0], "code": p[1], "produced": p[2], "available": p[3], "unitCost": p[4], "accumulatedCost": p[5]}


def run_multistage(admin, production_mgr, customer_id):
    mat_a = create_material_batch(admin, "MS-A", "2.000", "1.00")
    mat_b = create_material_batch(admin, "MS-B", "2.000", "2.00")
    mat_c = create_material_batch(admin, "MS-C", "2.000", "3.00")
    order_id = create_sales_order(admin, customer_id, "MS", "10")
    created["ms_order_id"] = order_id
    if not (mat_a and mat_b and mat_c and order_id):
        return False

    plan1 = create_plan(admin, order_id, "MS1", "10")
    batch1, tasks1 = create_batch_and_tasks(admin, plan1, "MS1", "10")
    code_a = f"{MARK}-SEMI-A"
    submit_input(production_mgr, admin, batch1, tasks1[0]["id"], "ms1_input_raw", "1.000", mat=mat_a)
    submit_output(production_mgr, admin, batch1, tasks1[-1]["id"], "ms1_output_semi_a", "10", output_kind="SEMI", semi_code=code_a)
    wip_a = wip_by_code(code_a, "ms_wip_a")
    log("multi-stage WIP A has unit cost", bool(wip_a and wip_a["unitCost"] != "<null>"), str(wip_a))
    if not wip_a:
        return False

    plan2 = create_secondary_plan(admin, wip_a["id"], "MS2", "5")
    batch2, tasks2 = create_batch_and_tasks(admin, plan2, "MS2", "5")
    code_b = f"{MARK}-SEMI-B"
    submit_input(production_mgr, admin, batch2, tasks2[0]["id"], "ms2_input_wip_a", "5.000", source_wip_no=code_a)
    submit_input(production_mgr, admin, batch2, tasks2[0]["id"], "ms2_input_raw", "0.500", mat=mat_b)
    submit_output(production_mgr, admin, batch2, tasks2[-1]["id"], "ms2_output_semi_b", "5", output_kind="SEMI", semi_code=code_b)
    wip_b = wip_by_code(code_b, "ms_wip_b")
    log("multi-stage WIP B has unit cost", bool(wip_b and wip_b["unitCost"] != "<null>"), str(wip_b))
    if not wip_b:
        return False

    plan3 = create_secondary_plan(admin, wip_b["id"], "MS3", "2")
    batch3, tasks3 = create_batch_and_tasks(admin, plan3, "MS3", "2")
    submit_input(production_mgr, admin, batch3, tasks3[0]["id"], "ms3_input_wip_b", "2.000", source_wip_no=code_b)
    submit_input(production_mgr, admin, batch3, tasks3[0]["id"], "ms3_input_raw", "0.250", mat=mat_c)
    submit_output(production_mgr, admin, batch3, tasks3[-1]["id"], "ms3_output_finished", "10", output_kind="FINISHED")
    poll_order_cost(order_id, True, "ms_order_cost_after_final")

    code, body = req("GET", f"/{FACTORY}/sales/orders/{order_id}/multi-stage-cost", admin, label="multi_stage_cost")
    responses["multi_stage_cost_final"] = {"status": code, "body": body}
    data = data_of(body)
    stages = data.get("stages") if isinstance(data, dict) else None
    stage_count = data.get("stageCount") if isinstance(data, dict) else None
    log("multi-stage endpoint returns >=3 stages", code == 200 and isinstance(stages, list) and len(stages) >= 3, f"HTTP {code} stageCount={stage_count}", body)
    sql(
        f"select 'plan|'||id||'|'||coalesce(plan_source_type,'<null>')||'|'||coalesce(source_order_id,'<null>')||'|'||coalesce(secondary_source_wip_id::text,'<null>') "
        f"from production_plans where id in ('{plan1}','{plan2}','{plan3}') order by created_at",
        "ms_plans",
    )
    sql(
        f"select 'sfi|'||id||'|'||batch_id||'|'||intermediate_batch_no||'|'||produced_quantity||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')||'|'||coalesce(accumulated_cost::text,'<null>') "
        f"from semi_finished_inventory where batch_id in ({batch1},{batch2},{batch3}) order by id",
        "ms_sfi",
    )
    return code == 200 and isinstance(stages, list) and len(stages) >= 3


def run_weighted(admin, production_mgr):
    mat_old = create_material_batch(admin, "WGT-OLD", "2.000", "1.00")
    mat_new = create_material_batch(admin, "WGT-NEW", "2.000", "3.00")
    if not (mat_old and mat_new):
        return False
    # Two SEMI outputs with the same semiCode exercise the moving-average branch directly.
    plan1 = create_plan(admin, None, "WGT1", "1000")
    # If the normal plan endpoint rejects null source order, fall back to a real SO-owned plan.
    if not plan1:
        cid = pick_customer()
        oid = create_sales_order(admin, cid, "WGT", "1000")
        plan1 = create_plan(admin, oid, "WGT1", "1000")
    batch1, tasks1 = create_batch_and_tasks(admin, plan1, "WGT1", "1000")
    code = f"{MARK}-WGT"
    submit_input(production_mgr, admin, batch1, tasks1[0]["id"], "wgt_first_input", "1000.000", mat=mat_old)
    submit_output(production_mgr, admin, batch1, tasks1[-1]["id"], "wgt_first_output", "1000", output_kind="SEMI", semi_code=code)
    first = wip_by_code(code, "wgt_after_first")
    # Simulate using 500 of old stock via an INPUT report against source WIP.
    plan_consume = create_secondary_plan(admin, first["id"], "WGT-CONSUME", "500") if first else None
    batch_consume, tasks_consume = create_batch_and_tasks(admin, plan_consume, "WGT-CONSUME", "500") if plan_consume else (None, [])
    if tasks_consume:
        submit_input(production_mgr, admin, batch_consume, tasks_consume[0]["id"], "wgt_consume_old_500", "500.000", source_wip_no=code)
    before_second = wip_by_code(code, "wgt_before_second")
    plan2 = create_plan(admin, None, "WGT2", "1000")
    if not plan2:
        cid = pick_customer()
        oid = create_sales_order(admin, cid, "WGT2", "1000")
        plan2 = create_plan(admin, oid, "WGT2", "1000")
    batch2, tasks2 = create_batch_and_tasks(admin, plan2, "WGT2", "1000")
    submit_input(production_mgr, admin, batch2, tasks2[0]["id"], "wgt_second_input", "1000.000", mat=mat_new)
    submit_output(production_mgr, admin, batch2, tasks2[-1]["id"], "wgt_second_output", "1000", output_kind="SEMI", semi_code=code)
    after_second = wip_by_code(code, "wgt_after_second")
    ok = False
    expected = None
    if before_second and after_second and before_second["unitCost"] != "<null>" and after_second["unitCost"] != "<null>":
        old_qty = Decimal(before_second["available"])
        old_uc = Decimal(before_second["unitCost"])
        new_qty = Decimal("1000")
        new_uc = Decimal("3.0000")
        expected = ((old_qty * old_uc) + (new_qty * new_uc)) / (old_qty + new_qty)
        actual = Decimal(after_second["unitCost"])
        ok = abs(actual - expected.quantize(Decimal("0.0001"))) <= Decimal("0.0001")
    log("weighted moving average old 500 + new 1000", ok, f"before={before_second} after={after_second} expected={expected}")
    return ok


def main():
    admin = login("f006_admin")
    production_mgr = login("f006_production_mgr")
    if not (admin and production_mgr):
        sys.exit(2)
    baseline = sql(
        "select so.order_number||'|'||so.status||'|'||soi.id||'|'||coalesce(soi.cost_unit_price::text,'<null>') "
        "from sales_orders so join sales_order_items soi on soi.sales_order_id=so.id "
        "where so.order_number='SO-20260612-0008'",
        "baseline_so_20260612_0008",
    )
    log("baseline SO-20260612-0008 non-null after #779/#780", bool(baseline and "<null>" not in baseline), baseline)
    customer_id = pick_customer()
    log("precondition customer", bool(customer_id), str(customer_id))
    if not customer_id:
        sys.exit(3)
    run_withdrawal(admin, production_mgr, customer_id)
    run_multistage(admin, production_mgr, customer_id)
    run_weighted(admin, production_mgr)
    print("\n=== CREATED ===")
    print(json.dumps(created, ensure_ascii=False, indent=2))
    print("\n=== RESULTS ===")
    print(json.dumps(results, ensure_ascii=False, indent=2))
    print("\n=== RAW_SQL ===")
    print(json.dumps(raw_sql, ensure_ascii=False, indent=2))
    print("\n=== RESPONSES ===")
    print(json.dumps(responses, ensure_ascii=False, indent=2)[:20000])


if __name__ == "__main__":
    main()
