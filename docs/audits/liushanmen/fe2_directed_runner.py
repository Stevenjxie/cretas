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
TS = time.strftime("%Y%m%d%H%M%S")
TODAY = str(date.today())
TOMORROW = str(date.today() + timedelta(days=1))
PRODUCT_ID = "1d7fbd73-8797-4933-83f1-46413a45992d"
SUPPLIER = "844d8c69-ed89-4a7f-b850-1749456d0777"
WH_RAW = "6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e"
MAT_RAW = "RMT_1777690082465"
MARK = "DEMO-FE2-" + TS

created = {"mark": MARK}
results = []
raw_sql = {}
responses = {}


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


def req(method, path, token=None, params=None, body=None, label=None, timeout=80):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    parts = ["curl", "-sS", "-w", "\nHTTP_STATUS:%{http_code}", "-X", method]
    for key, value in headers.items():
        parts.extend(["-H", f"{key}: {value}"])
    if body is not None:
        parts.extend(["--data-binary", "@-"])
    url = BASE + path
    if params:
        url += "?" + "&".join(f"{k}={v}" for k, v in params.items())
    parts.append(url)
    data = json.dumps(body, ensure_ascii=False) if body is not None else None
    out = ssh(" ".join(shlex.quote(p) for p in parts), data, timeout=timeout)
    marker = "\nHTTP_STATUS:"
    if marker in out:
        text, status = out.rsplit(marker, 1)
        code = int(status.strip()[:3])
    else:
        text, code = out, 0
    try:
        parsed = json.loads(text) if text else None
    except Exception:
        parsed = text[:3000]
    if label:
        responses[label] = {"method": method, "path": path, "status": code, "body": parsed}
    return code, parsed


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
        print(json.dumps(extra, ensure_ascii=False)[:1500])
    return ok


def login(username):
    code, body = req("POST", "/auth/unified-login", body={"username": username, "password": PWD}, label="login_" + username)
    token = body.get("data", {}).get("accessToken") or body.get("data", {}).get("token") if isinstance(body, dict) else None
    user_id = body.get("data", {}).get("userId") if isinstance(body, dict) else None
    role = body.get("data", {}).get("role") if isinstance(body, dict) else None
    log("login " + username, code == 200 and bool(token), f"HTTP {code} userId={user_id} role={role}")
    return {"token": token, "userId": user_id, "role": role, "username": username}


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
    payload = {
        "batchNumber": f"{MARK}-MAT-{suffix}",
        "materialTypeId": MAT_RAW,
        "supplierId": SUPPLIER,
        "receiptDate": TODAY,
        "receiptQuantity": str(qty),
        "quantityUnit": "kg",
        "unitPrice": str(unit_price),
        "warehouseId": WH_RAW,
        "notes": f"{MARK} material {suffix}",
    }
    code, body = req("POST", f"/{FACTORY}/processing/material-receipt", admin["token"], body=payload, label="material_" + suffix)
    mb_id = data_of(body).get("id")
    log("create material " + suffix, code == 200 and bool(mb_id), f"HTTP {code} id={mb_id}", body)
    return {"id": mb_id, "batchNumber": payload["batchNumber"], "qty": str(qty), "unitPrice": str(unit_price)} if mb_id else None


def create_sales_order(admin, customer_id, suffix, qty, amount=88):
    body = {
        "customerId": customer_id,
        "orderDate": TODAY,
        "requiredDeliveryDate": TOMORROW,
        "defaultTaxRate": 13,
        "remark": f"{MARK} SO {suffix}",
        "items": [{
            "productTypeId": PRODUCT_ID,
            "productName": "DEMO-FE2 zhangzhongbao",
            "quantity": str(qty),
            "unit": "kg",
            "unitPrice": str(amount),
            "taxRate": 13,
            "remark": f"{MARK} item {suffix}",
        }],
    }
    code, body_resp = req("POST", f"/{FACTORY}/sales/orders", admin["token"], body=body, label="so_create_" + suffix)
    order_id = data_of(body_resp).get("id")
    order_no = data_of(body_resp).get("orderNumber")
    log("create SO " + suffix, code == 200 and bool(order_id), f"HTTP {code} order={order_no}/{order_id}", body_resp)
    return {"id": order_id, "number": order_no} if order_id else None


def submit_so_for_finance(admin, order, suffix):
    for step, path, body in [
        ("confirm", f"/{FACTORY}/sales/orders/{order['id']}/confirm", None),
        ("submit finance", f"/{FACTORY}/sales/orders/{order['id']}/submit-for-review", None),
    ]:
        code, resp = req("POST", path, admin["token"], body=body, label=f"so_{step}_{suffix}".replace(" ", "_"))
        if not log(step + " SO " + suffix, code == 200, f"HTTP {code}", resp):
            return False
    return True


def finance_approve_so(finance, order, suffix):
    code, resp = req("POST", f"/{FACTORY}/sales/orders/{order['id']}/finance-approve", finance["token"], body={"notes": MARK}, label="so_finance_" + suffix)
    return log("finance approve SO " + suffix, code == 200, f"HTTP {code}", resp)


def create_plan(admin, order, suffix, qty):
    body = {
        "productTypeId": PRODUCT_ID,
        "plannedQuantity": str(qty),
        "plannedDate": TODAY,
        "expectedCompletionDate": TOMORROW,
        "sourceOrderId": order["id"] if order else None,
        "sourceOrderIds": [order["id"]] if order else [],
        "sourceType": "CUSTOMER_ORDER" if order else "MANUAL",
        "processName": f"{MARK} process {suffix}",
        "batchDate": TODAY,
        "notes": f"{MARK} plan {suffix}",
        "estimatedWorkers": 2,
        "assignedSupervisorId": 1552,
    }
    code, resp = req("POST", f"/{FACTORY}/production-plans", admin["token"], body=body, label="plan_" + suffix)
    plan_id = data_of(resp).get("id")
    log("create plan " + suffix, code == 200 and bool(plan_id), f"HTTP {code} plan={plan_id}", resp)
    return plan_id


def create_batch_and_tasks(admin, plan_id, suffix, qty):
    body = {
        "batchNumber": f"{MARK}-BATCH-{suffix}",
        "productTypeId": PRODUCT_ID,
        "productionPlanId": plan_id,
        "plannedQuantity": str(qty),
        "quantity": str(qty),
        "unit": "kg",
        "remark": f"{MARK} batch {suffix}",
    }
    code, resp = req("POST", f"/{FACTORY}/processing/batches", admin["token"], body=body, label="batch_" + suffix)
    batch_id = data_of(resp).get("id")
    log("create batch " + suffix, code == 200 and bool(batch_id), f"HTTP {code} batch={batch_id}", resp)
    if not batch_id:
        return None, []
    code, resp = req("POST", f"/{FACTORY}/processing/batches/{batch_id}/start", admin["token"], params={"supervisorId": 1552}, label="batch_start_" + suffix)
    log("start batch " + suffix, code == 200, f"HTTP {code}", resp)
    code, resp = req("POST", f"/{FACTORY}/production/batches/{batch_id}/spawn-tasks", admin["token"], body={"productTypeId": PRODUCT_ID}, label="spawn_" + suffix)
    tasks = resp.get("data") if isinstance(resp, dict) and isinstance(resp.get("data"), list) else []
    log("spawn tasks " + suffix, code == 200 and bool(tasks), f"HTTP {code} tasks={len(tasks)}", tasks)
    return batch_id, tasks


def assign_tasks(admin, tasks, operator_id, suffix):
    assigned = []
    for task in tasks:
        code, resp = req(
            "PUT",
            f"/{FACTORY}/work-process-tasks/{task['id']}",
            admin["token"],
            body={"assignedTo": int(operator_id), "notes": f"{MARK} C5 assign {suffix}"},
            label=f"assign_{suffix}_{task['id']}",
        )
        ok = code == 200
        log(f"assign task {suffix} {task['id']} to operator", ok, f"HTTP {code}", resp)
        if ok:
            assigned.append(data_of(resp))
    return assigned


def get_tasks(admin, batch_id, label):
    code, resp = req("GET", f"/{FACTORY}/production/batches/{batch_id}/work-process-tasks", admin["token"], label="get_tasks_" + label)
    tasks = resp.get("data") if isinstance(resp, dict) and isinstance(resp.get("data"), list) else []
    log("get tasks " + label, code == 200 and bool(tasks), f"HTTP {code} tasks={len(tasks)}", tasks)
    return tasks


def approve_report(approver, report_id, label):
    code, resp = req("PUT", f"/{FACTORY}/process-work-reporting/{report_id}/approve", approver["token"], body={"remark": f"{MARK} approve {label}"}, label="approve_" + label)
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
    code, resp = req("POST", f"/{FACTORY}/production/batches/{batch_id}/reports", worker["token"], body=body, label="submit_" + label)
    rid = data_of(resp).get("reportId") or data_of(resp).get("id")
    log("submit report " + label, code == 200 and bool(rid), f"HTTP {code} report={rid}", resp)
    if rid:
        approve_report(approver, rid, label)
    return rid


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


def reverse_batch(submitter, approver, batch_id, suffix):
    code, resp = req(
        "POST",
        f"/{FACTORY}/processing/batches/{batch_id}/reversal",
        submitter["token"],
        body={"reason": f"{MARK} C14 reversal {suffix}", "remark": MARK, "reversalScope": "WHOLE_ORDER"},
        label="reversal_" + suffix,
    )
    log_id = data_of(resp).get("id")
    status = data_of(resp).get("status")
    log("submit reversal " + suffix, code == 200 and bool(log_id), f"HTTP {code} log={log_id} status={status}", resp)
    if not log_id:
        return None
    if status == "DONE":
        return log_id
    code, resp = req("PUT", f"/{FACTORY}/reversals/{log_id}/approve", approver["token"], body={"remark": f"{MARK} approve reversal"}, label="reversal_approve_" + suffix)
    log("approve reversal " + suffix, code == 200, f"HTTP {code}", resp)
    return log_id


def create_po_for_payment(admin, supplier, amount_suffix="OA"):
    body = {
        "supplierId": supplier,
        "purchaseType": "DIRECT",
        "orderDate": TODAY,
        "expectedDeliveryDate": TOMORROW,
        "remark": f"{MARK} PO {amount_suffix}",
        "items": [{
            "materialTypeId": MAT_RAW,
            "materialName": "DEMO-FE2 raw",
            "quantity": "1",
            "unit": "kg",
            "unitPrice": "100",
            "taxRate": 9,
            "remark": MARK,
        }],
    }
    code, resp = req("POST", f"/{FACTORY}/purchase/orders", admin["token"], body=body, label="po_" + amount_suffix)
    po_id = data_of(resp).get("id")
    log("create PO " + amount_suffix, code == 200 and bool(po_id), f"HTTP {code} po={po_id}", resp)
    if not po_id:
        return None
    for step, path in [
        ("submit", f"/{FACTORY}/purchase/orders/{po_id}/submit"),
        ("approve", f"/{FACTORY}/purchase/orders/{po_id}/approve"),
        ("submit finance", f"/{FACTORY}/purchase/orders/{po_id}/submit-for-finance-review"),
    ]:
        code, resp = req("POST", path, admin["token"], body={} if step == "approve" else None, label=f"po_{amount_suffix}_{step}".replace(" ", "_"))
        log(step + " PO " + amount_suffix, code == 200, f"HTTP {code}", resp)
    return po_id


def create_payment_request(admin, finance, po_id):
    code, resp = req("POST", f"/{FACTORY}/payment-requests", admin["token"], body={
        "purchaseOrderId": po_id,
        "supplierId": SUPPLIER,
        "amount": "188.00",
        "paymentMethod": "BANK_TRANSFER",
        "remark": f"{MARK} payment request",
    }, label="payment_create")
    pr_id = data_of(resp).get("id")
    log("create payment request", code == 200 and bool(pr_id), f"HTTP {code} pr={pr_id}", resp)
    if not pr_id:
        return None
    code, resp = req("PUT", f"/{FACTORY}/payment-requests/{pr_id}/submit", admin["token"], label="payment_submit")
    log("submit payment request", code == 200, f"HTTP {code}", resp)
    code, resp = req("PUT", f"/{FACTORY}/payment-requests/{pr_id}/finance-approve", finance["token"], body={"reviewNote": f"{MARK} finance approve for cashier todo"}, label="payment_finance_approve")
    log("finance approve payment for cashier todo", code == 200, f"HTTP {code}", resp)
    return pr_id


def create_setup():
    admin = login("f006_admin")
    production_mgr = login("f006_production_mgr")
    operator = login("f006_moyun")
    finance = login("f006_finance_mgr")
    cashier = login("f006_cashier")
    if not all(x["token"] for x in [admin, production_mgr, operator, finance, cashier]):
        sys.exit(2)
    customer_id = pick_customer()
    log("precondition customer", bool(customer_id), str(customer_id))
    if not customer_id:
        sys.exit(3)

    mat_rn = create_material_batch(admin, "RN", "20.000", "1.50")
    order = create_sales_order(admin, customer_id, "RN", "10", amount=88)
    if not (mat_rn and order and submit_so_for_finance(admin, order, "RN") and finance_approve_so(finance, order, "RN")):
        sys.exit(4)
    plan_id = create_plan(admin, order, "RN", "10")
    batch_id, tasks = create_batch_and_tasks(admin, plan_id, "RN", "10")
    assigned = assign_tasks(admin, tasks, operator["userId"], "RN")
    created.update({
        "rn_material_batch": mat_rn,
        "rn_order": order,
        "rn_plan_id": plan_id,
        "rn_batch_id": batch_id,
        "rn_tasks": assigned or tasks,
        "operator_user_id": operator["userId"],
    })

    po_id = create_po_for_payment(admin, SUPPLIER, "OA")
    payment_id = create_payment_request(admin, finance, po_id) if po_id else None
    created.update({"oa_po_id": po_id, "oa_payment_request_id": payment_id})

    return admin, production_mgr, operator, finance, cashier, customer_id


def run_api_repairs(admin, production_mgr, operator, customer_id):
    # C14 fast-path self-heal: same responsible user submits reversal after reports.
    mat1 = create_material_batch(admin, "C14-1", "1.000", "1.00")
    mat2 = create_material_batch(admin, "C14-2", "1.000", "2.00")
    order = create_sales_order(admin, customer_id, "C14", "10", amount=88)
    if not (mat1 and mat2 and order and submit_so_for_finance(admin, order, "C14")):
        return
    finance = login("f006_finance_mgr")
    finance_approve_so(finance, order, "C14")
    plan_id = create_plan(admin, order, "C14", "10")
    batch_id, tasks = create_batch_and_tasks(admin, plan_id, "C14", "10")
    if tasks:
        submit_report(production_mgr, admin, batch_id, tasks[0]["id"], "INPUT", "c14_first_input", inputQuantity="0.500", inputUnit="kg", materialBatchRefs=[{"materialBatchId": mat1["id"], "quantity": "0.500", "unit": "kg"}])
        submit_report(production_mgr, admin, batch_id, tasks[-1]["id"], "OUTPUT", "c14_first_output", outputKind="FINISHED", outputQuantity="10", outputUnit="kg", markComplete=True)
        first = poll_order_cost(order["id"], True, "c14_cost_first")
        reverse_batch(production_mgr, production_mgr, batch_id, "C14_FAST")
        cleared = poll_order_cost(order["id"], False, "c14_cost_after_fast_reversal")
        tasks2 = get_tasks(admin, batch_id, "c14_after_reversal") or tasks
        submit_report(production_mgr, admin, batch_id, tasks2[0]["id"], "INPUT", "c14_second_input", inputQuantity="0.500", inputUnit="kg", materialBatchRefs=[{"materialBatchId": mat2["id"], "quantity": "0.500", "unit": "kg"}])
        submit_report(production_mgr, admin, batch_id, tasks2[-1]["id"], "OUTPUT", "c14_second_output", outputKind="FINISHED", outputQuantity="8", outputUnit="kg", markComplete=True)
        second = poll_order_cost(order["id"], True, "c14_cost_second")
        log("C14 fast-path clear and backfill", "<null>" not in first and "<null>" in cleared and "<null>" not in second and first != second, f"first={first} cleared={cleared} second={second}")
        created.update({"c14_order": order, "c14_batch_id": batch_id})

    # C12 and B4: real SEMI output, secondary plan, same-code weighted average.
    mat_a = create_material_batch(admin, "C12-A", "2.000", "1.00")
    mat_b = create_material_batch(admin, "B4-B", "2.000", "3.00")
    order2 = create_sales_order(admin, customer_id, "C12", "10", amount=88)
    if mat_a and mat_b and order2 and submit_so_for_finance(admin, order2, "C12"):
        finance_approve_so(finance, order2, "C12")
        plan1 = create_plan(admin, order2, "C12-1", "10")
        batch1, tasks1 = create_batch_and_tasks(admin, plan1, "C12-1", "10")
        semi_code = f"{MARK}-SEMI"
        if tasks1:
            submit_report(production_mgr, admin, batch1, tasks1[0]["id"], "INPUT", "c12_input", inputQuantity="1.000", inputUnit="kg", materialBatchRefs=[{"materialBatchId": mat_a["id"], "quantity": "1.000", "unit": "kg"}])
            submit_report(production_mgr, admin, batch1, tasks1[-1]["id"], "OUTPUT", "c12_output_both", outputKind="BOTH", outputQuantity="5", outputUnit="kg", semiOutputQuantity="5", semiOutputUnit="kg", semiCode=semi_code, markComplete=True, byproducts=[{"name": "DEMO-FE2副产物", "quantity": "0.2", "unit": "kg"}])
            wip = wip_by_code(semi_code, "c12_wip_after_both")
            plan2 = create_secondary_plan(admin, wip["id"], "C12-SECONDARY", "2") if wip else None
            log("C12 secondary plan created", bool(plan2), f"wip={wip} plan2={plan2}")
            plan_wgt = create_plan(admin, None, "B4-SECOND", "10")
            batch_wgt, tasks_wgt = create_batch_and_tasks(admin, plan_wgt, "B4-SECOND", "10")
            before = wip_by_code(semi_code, "b4_before_second")
            if tasks_wgt:
                submit_report(production_mgr, admin, batch_wgt, tasks_wgt[0]["id"], "INPUT", "b4_second_input", inputQuantity="1.000", inputUnit="kg", materialBatchRefs=[{"materialBatchId": mat_b["id"], "quantity": "1.000", "unit": "kg"}])
                submit_report(production_mgr, admin, batch_wgt, tasks_wgt[-1]["id"], "OUTPUT", "b4_second_output_same_code", outputKind="SEMI", outputQuantity="5", outputUnit="kg", semiOutputQuantity="5", semiOutputUnit="kg", semiCode=semi_code, markComplete=True)
            after = wip_by_code(semi_code, "b4_after_second")
            ok = False
            if before and after and before["unitCost"] != "<null>" and after["unitCost"] != "<null>":
                old_qty = Decimal(before["available"])
                old_uc = Decimal(before["unitCost"])
                new_qty = Decimal("5")
                new_uc = Decimal("0.6000")
                expected = ((old_qty * old_uc) + (new_qty * new_uc)) / (old_qty + new_qty)
                ok = abs(Decimal(after["unitCost"]) - expected.quantize(Decimal("0.0001"))) <= Decimal("0.0001")
            log("B4 same-code weighted average", ok, f"before={before} after={after}")
            created.update({"c12_batch_id": batch1, "c12_semi_code": semi_code, "c12_secondary_plan": plan2, "b4_batch_id": batch_wgt})


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


def create_secondary_plan(admin, wip_id, suffix, qty):
    code, resp = req("POST", f"/{FACTORY}/processing/secondary-plan", admin["token"], body={
        "wipId": int(wip_id),
        "quantity": str(qty),
        "productTypeId": PRODUCT_ID,
        "plannedDate": TODAY,
    }, label="secondary_" + suffix)
    plan_id = data_of(resp).get("id")
    log("create secondary plan " + suffix, code == 200 and bool(plan_id), f"HTTP {code} plan={plan_id}", resp)
    return plan_id


def final_sql():
    sql(
        f"""
        select 'sales|'||order_number||'|'||status||'|'||coalesce(remark,'')
          from sales_orders where factory_id='F006' and remark like '{MARK}%'
         order by created_at;
        select 'plan|'||id||'|'||coalesce(status::text,'')||'|'||coalesce(notes,'')
          from production_plans where factory_id='F006' and notes like '{MARK}%'
         order by created_at;
        select 'batch|'||id||'|'||batch_number||'|'||coalesce(status::text,'')||'|'||coalesce(remark,'')
          from production_batches where factory_id='F006' and batch_number like '{MARK}%'
         order by created_at;
        select 'task|'||id||'|'||production_batch_id||'|'||process_order||'|'||coalesce(status::text,'')||'|'||coalesce(assigned_to::text,'<null>')
          from work_process_tasks where production_batch_id in (select id from production_batches where batch_number like '{MARK}%')
         order by production_batch_id,id;
        select 'report|'||id||'|'||batch_id||'|'||report_kind||'|'||coalesce(approval_status::text,'')||'|'||coalesce(input_quantity::text,'<null>')||'|'||coalesce(output_quantity::text,'<null>')||'|'||coalesce(output_kind,'<null>')||'|'||coalesce(deleted_at::text,'<live>')
          from production_reports where batch_id in (select id from production_batches where batch_number like '{MARK}%')
         order by batch_id,id;
        select 'wip|'||id||'|'||batch_id||'|'||intermediate_batch_no||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')
          from semi_finished_inventory where intermediate_batch_no like '{MARK}%'
         order by id;
        select 'payment|'||id||'|'||request_number||'|'||status||'|'||coalesce(remark,'')
          from payment_requests where factory_id='F006' and remark like '{MARK}%'
         order by created_at;
        """,
        "final_readback",
    )


def main():
    admin, production_mgr, operator, finance, cashier, customer_id = create_setup()
    run_api_repairs(admin, production_mgr, operator, customer_id)
    final_sql()
    print("\n=== CREATED ===")
    print(json.dumps(created, ensure_ascii=False, indent=2))
    print("\n=== RESULTS ===")
    print(json.dumps(results, ensure_ascii=False, indent=2))
    print("\n=== RAW_SQL ===")
    print(json.dumps(raw_sql, ensure_ascii=False, indent=2))
    print("\n=== RESPONSES ===")
    print(json.dumps(responses, ensure_ascii=False, indent=2)[:30000])


if __name__ == "__main__":
    main()
