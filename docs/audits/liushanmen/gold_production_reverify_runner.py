import json
import sys
import time
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from docs.audits.liushanmen import gold_production_rerun_runner as r


r.TS = str(int(time.time()))
r.MARK = "DEMO-GOLD-REVERIFY-" + r.TS
r.results = []
r.created = {}
r.raw_sql = {}
r.responses = {}


evidence = {
    "marker": r.MARK,
    "assertions": {},
    "created": r.created,
    "raw_sql": r.raw_sql,
    "responses": r.responses,
}


def record(key, ok, detail="", extra=None):
    evidence["assertions"][key] = {"ok": bool(ok), "detail": detail, "extra": extra}
    r.log(key, ok, detail, extra)
    return ok


def parse_cost(line):
    if not line or "<null>" in line:
        return None
    return Decimal(line.split("|")[-1])


def create_so_plan_batch(admin, customer_id, suffix, qty):
    order_id = r.create_sales_order(admin, customer_id, suffix, str(qty))
    if not order_id:
        return None, None, None, []
    plan_id = r.create_plan(admin, order_id, suffix, str(qty))
    if not plan_id:
        return order_id, None, None, []
    batch_id, tasks = r.create_batch_and_tasks(admin, plan_id, suffix, str(qty))
    return order_id, plan_id, batch_id, tasks


def create_semi_stage(admin, worker, customer_id, suffix, qty, unit_price, semi_code):
    mat = r.create_material_batch(admin, suffix + "-MAT", str(qty), str(unit_price))
    order_id, plan_id, batch_id, tasks = create_so_plan_batch(admin, customer_id, suffix, qty)
    if not (mat and batch_id and tasks):
        return None
    r.submit_input(worker, admin, batch_id, tasks[0]["id"], suffix + "_input", str(qty), mat=mat)
    r.submit_output(worker, admin, batch_id, tasks[-1]["id"], suffix + "_output_semi", str(qty), output_kind="SEMI", semi_code=semi_code)
    wip = r.wip_by_code(semi_code, suffix + "_wip")
    return {
        "mat": mat,
        "order_id": order_id,
        "plan_id": plan_id,
        "batch_id": batch_id,
        "tasks": tasks,
        "semi_code": semi_code,
        "wip": wip,
    }


def create_secondary_plan(admin, wip_id, suffix, qty):
    body = {
        "wipId": int(wip_id),
        "quantity": str(qty),
        "productTypeId": r.PT_ZZB,
        "plannedDate": r.TODAY,
    }
    code, resp = r.req("POST", f"/{r.FACTORY}/processing/secondary-plan", admin, body=body, label="secondary_" + suffix)
    data = r.data_of(resp)
    plan_id = data.get("id")
    plan_no = data.get("planNumber")
    r.log("create secondary plan " + suffix, code == 200 and bool(plan_id), f"HTTP {code} plan={plan_id} planNo={plan_no}", resp)
    return code, resp, plan_id, plan_no


def run_fast_path(admin, production_mgr, customer_id):
    mat1 = r.create_material_batch(admin, "FP-1", "1.000", "1.00")
    mat2 = r.create_material_batch(admin, "FP-2", "1.000", "2.00")
    order_id, plan_id, batch_id, tasks = create_so_plan_batch(admin, customer_id, "FP", 10)
    r.created["fast_path_order_id"] = order_id
    r.created["fast_path_batch_id"] = batch_id
    if not (mat1 and mat2 and order_id and batch_id and tasks):
        return record("fast_path_preconditions", False, "failed to create SO/material/batch")

    r.submit_input(production_mgr, admin, batch_id, tasks[0]["id"], "fp_first_input", "0.500", mat=mat1)
    r.submit_output(production_mgr, admin, batch_id, tasks[-1]["id"], "fp_first_output", "10", output_kind="FINISHED")
    first = r.poll_order_cost(order_id, True, "fp_cost_after_first")
    first_cost = parse_cost(first)

    code, resp = r.req(
        "POST",
        f"/{r.FACTORY}/processing/batches/{batch_id}/reversal",
        production_mgr,
        body={"reason": r.MARK + " fast-path WHOLE_ORDER reversal", "remark": r.MARK, "reversalScope": "WHOLE_ORDER"},
        label="fp_submit_reversal",
    )
    rev_id = r.data_of(resp).get("id")
    r.created["fast_path_reversal_id"] = rev_id
    r.log("submit fast-path reversal", code == 200 and bool(rev_id), f"HTTP {code} rev={rev_id}", resp)

    after_reversal = r.poll_order_cost(order_id, False, "fp_cost_after_reversal") if rev_id else ""
    reports_sql = r.sql(
        f"select 'report|'||id||'|'||report_kind||'|'||approval_status||'|'||coalesce(deleted_at::text,'<live>') "
        f"from production_reports where batch_id={batch_id} order by id",
        "fp_reports_after_reversal",
    )
    reversal_sql = r.sql(
        f"select 'rev|'||id||'|'||status||'|'||batch_id||'|'||submitted_by||'|'||coalesce(approved_by::text,'<null>')||'|'||coalesce(fast_path::text,'<null>') "
        f"from report_reversal_logs where batch_id={batch_id} order by id",
        "fp_reversal_after_submit",
    )

    tasks2 = r.get_tasks(admin, batch_id, "fp_after_reversal") or tasks
    r.submit_input(production_mgr, admin, batch_id, tasks2[0]["id"], "fp_second_input", "0.500", mat=mat2)
    r.submit_output(production_mgr, admin, batch_id, tasks2[-1]["id"], "fp_second_output", "8", output_kind="FINISHED")
    second = r.poll_order_cost(order_id, True, "fp_cost_after_second")
    second_cost = parse_cost(second)

    all_deleted = bool(reports_sql) and "<live>" not in reports_sql
    ok = (
        first_cost is not None
        and "<null>" in after_reversal
        and second_cost is not None
        and second_cost != first_cost
        and "DONE" in reversal_sql
        and "true" in reversal_sql.lower()
        and all_deleted
    )
    return record(
        "assertion_1_fast_path_reversal",
        ok,
        f"first={first_cost} afterReversal={after_reversal} second={second_cost} allDeleted={all_deleted}",
        {"reports": reports_sql, "reversal": reversal_sql},
    )


def run_secondary_plan(admin, production_mgr, customer_id):
    semi_code = r.MARK + "-SEC-A"
    stage = create_semi_stage(admin, production_mgr, customer_id, "SEC-A", 2, "1.00", semi_code)
    r.created["secondary_source_stage"] = stage
    if not stage or not stage.get("wip"):
        return None, record("assertion_2_secondary_plan", False, "failed to create source WIP")
    wip_id = stage["wip"]["id"]
    code, resp, plan_id, plan_no = create_secondary_plan(admin, wip_id, "SEC-B", "1")
    plan_sql = ""
    if plan_id:
        plan_sql = r.sql(
            f"select id||'|'||plan_number||'|'||coalesce(plan_source_type,'<null>')||'|'||coalesce(secondary_source_wip_id::text,'<null>')||'|'||coalesce(source_order_id,'<null>') "
            f"from production_plans where id='{plan_id}'",
            "secondary_plan_db",
        )
    ok = code == 200 and bool(plan_id) and str(plan_no).startswith("SEC-F006-") and "|SECONDARY|" in plan_sql and f"|{wip_id}|" in plan_sql
    record(
        "assertion_2_secondary_plan",
        ok,
        f"HTTP {code} plan={plan_id} planNo={plan_no}",
        {"response": resp, "sql": plan_sql, "sourceStage": stage},
    )
    return {"source": stage, "plan_id": plan_id, "plan_no": plan_no, "ok": ok}


def run_weighted(admin, production_mgr, customer_id):
    semi_code = r.MARK + "-WGT"
    mat1 = r.create_material_batch(admin, "WGT-1", "1000.000", "1.00")
    mat2 = r.create_material_batch(admin, "WGT-2", "1000.000", "3.00")
    if not (mat1 and mat2):
        return record("assertion_3_weighted_average", False, "failed material creation")

    order1, plan1, batch1, tasks1 = create_so_plan_batch(admin, customer_id, "WGT1", 1000)
    if batch1 and tasks1:
        r.submit_input(production_mgr, admin, batch1, tasks1[0]["id"], "wgt_first_input", "1000.000", mat=mat1)
        r.submit_output(production_mgr, admin, batch1, tasks1[-1]["id"], "wgt_first_output", "1000", output_kind="SEMI", semi_code=semi_code)

    first = r.wip_by_code(semi_code, "wgt_after_first")

    order2, plan2, batch2, tasks2 = create_so_plan_batch(admin, customer_id, "WGT2", 1000)
    if batch2 and tasks2:
        r.submit_input(production_mgr, admin, batch2, tasks2[0]["id"], "wgt_second_input", "1000.000", mat=mat2)
        r.submit_output(production_mgr, admin, batch2, tasks2[-1]["id"], "wgt_second_output", "1000", output_kind="SEMI", semi_code=semi_code)

    after = r.wip_by_code(semi_code, "wgt_after_second")
    txn_sql = r.sql(
        f"select 'txn|'||t.id||'|'||t.txn_type||'|'||t.quantity||'|'||coalesce(t.unit_cost_at_txn::text,'<null>')||'|'||coalesce(t.report_id::text,'<null>') "
        f"from semi_finished_inventory_transactions t join semi_finished_inventory s on s.id=t.semi_finished_id "
        f"where s.intermediate_batch_no='{semi_code}' and t.txn_type='IN' order by t.id",
        "wgt_in_txns",
    )
    sfi_sql = r.sql(
        f"select id||'|'||intermediate_batch_no||'|'||produced_quantity||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')||'|'||coalesce(accumulated_cost::text,'<null>') "
        f"from semi_finished_inventory where intermediate_batch_no='{semi_code}'",
        "wgt_sfi_final",
    )
    unit_cost = Decimal(after["unitCost"]) if after and after.get("unitCost") != "<null>" else None
    report_ids = [line.split("|")[-1] for line in txn_sql.splitlines() if line.startswith("txn|")]
    ok = (
        first is not None
        and after is not None
        and unit_cost == Decimal("2.0000")
        and len(report_ids) == 2
        and len(set(report_ids)) == 2
    )
    return record(
        "assertion_3_weighted_average",
        ok,
        f"first={first} after={after} expected=2.0000 reportIds={report_ids}",
        {"txnSql": txn_sql, "sfiSql": sfi_sql, "batch1": batch1, "batch2": batch2},
    )


def run_multistage(admin, production_mgr, customer_id):
    order_id = r.create_sales_order(admin, customer_id, "MS", "2")
    if not order_id:
        return record("assertion_4_multi_stage_cost", False, "failed to create order")
    plan1 = r.create_plan(admin, order_id, "MS1", "10")
    batch1, tasks1 = r.create_batch_and_tasks(admin, plan1, "MS1", "10") if plan1 else (None, [])
    mat1 = r.create_material_batch(admin, "MS-RAW1", "10.000", "1.00")
    code_a = r.MARK + "-MS-A"
    if not (batch1 and tasks1 and mat1):
        return record("assertion_4_multi_stage_cost", False, "failed stage 1 setup")
    r.submit_input(production_mgr, admin, batch1, tasks1[0]["id"], "ms1_input_raw", "10.000", mat=mat1)
    r.submit_output(production_mgr, admin, batch1, tasks1[-1]["id"], "ms1_output_a", "10", output_kind="SEMI", semi_code=code_a)
    wip_a = r.wip_by_code(code_a, "ms_wip_a")
    if not wip_a:
        return record("assertion_4_multi_stage_cost", False, "stage A WIP missing")

    code, resp, plan2, _ = create_secondary_plan(admin, wip_a["id"], "MS2", "5")
    batch2, tasks2 = r.create_batch_and_tasks(admin, plan2, "MS2", "5") if plan2 else (None, [])
    mat2 = r.create_material_batch(admin, "MS-RAW2", "5.000", "2.00")
    code_b = r.MARK + "-MS-B"
    if not (batch2 and tasks2 and mat2):
        return record("assertion_4_multi_stage_cost", False, f"stage 2 setup failed HTTP={code}", resp)
    r.submit_input(production_mgr, admin, batch2, tasks2[0]["id"], "ms2_input_wip_a", "5.000", source_wip_no=code_a)
    r.submit_input(production_mgr, admin, batch2, tasks2[0]["id"], "ms2_input_raw", "5.000", mat=mat2)
    r.submit_output(production_mgr, admin, batch2, tasks2[-1]["id"], "ms2_output_b", "5", output_kind="SEMI", semi_code=code_b)
    wip_b = r.wip_by_code(code_b, "ms_wip_b")
    if not wip_b:
        return record("assertion_4_multi_stage_cost", False, "stage B WIP missing")

    code, resp, plan3, _ = create_secondary_plan(admin, wip_b["id"], "MS3", "2")
    batch3, tasks3 = r.create_batch_and_tasks(admin, plan3, "MS3", "2") if plan3 else (None, [])
    mat3 = r.create_material_batch(admin, "MS-RAW3", "2.000", "3.00")
    if not (batch3 and tasks3 and mat3):
        return record("assertion_4_multi_stage_cost", False, f"stage 3 setup failed HTTP={code}", resp)
    r.submit_input(production_mgr, admin, batch3, tasks3[0]["id"], "ms3_input_wip_b", "2.000", source_wip_no=code_b)
    r.submit_input(production_mgr, admin, batch3, tasks3[0]["id"], "ms3_input_raw", "2.000", mat=mat3)
    r.submit_output(production_mgr, admin, batch3, tasks3[-1]["id"], "ms3_output_fg", "2", output_kind="FINISHED")

    r.poll_order_cost(order_id, True, "ms_order_cost_after_final")
    api_code, body = r.req("GET", f"/{r.FACTORY}/sales/orders/{order_id}/multi-stage-cost", admin, label="ms_multi_stage_cost")
    data = r.data_of(body)
    stages = data.get("stages") if isinstance(data, dict) else []
    unit_costs = [Decimal(str(s.get("outputUnitCost"))) for s in stages if s.get("outputUnitCost") is not None]
    sfi_sql = r.sql(
        f"select 'sfi|'||id||'|'||batch_id||'|'||intermediate_batch_no||'|'||produced_quantity||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')||'|'||coalesce(accumulated_cost::text,'<null>') "
        f"from semi_finished_inventory where batch_id in ({batch1},{batch2},{batch3}) order by id",
        "ms_sfi_final",
    )
    plan_sql = r.sql(
        f"select 'plan|'||id||'|'||plan_number||'|'||coalesce(plan_source_type,'<null>')||'|'||coalesce(source_order_id,'<null>')||'|'||coalesce(secondary_source_wip_id::text,'<null>') "
        f"from production_plans where id in ('{plan1}','{plan2}','{plan3}') order by created_at",
        "ms_plans_final",
    )
    increasing = len(unit_costs) >= 3 and all(unit_costs[i] < unit_costs[i + 1] for i in range(len(unit_costs) - 1))
    ok = api_code == 200 and len(stages) >= 3 and increasing
    return record(
        "assertion_4_multi_stage_cost",
        ok,
        f"HTTP {api_code} stageCount={data.get('stageCount')} unitCosts={unit_costs}",
        {"response": body, "sfiSql": sfi_sql, "planSql": plan_sql, "orderId": order_id},
    )


def main():
    admin = r.login("f006_admin")
    production_mgr = r.login("f006_production_mgr")
    cashier = r.login("f006_cashier")
    record("login_preconditions", bool(admin and production_mgr and cashier), "admin/production_mgr/cashier login")
    customer_id = r.pick_customer()
    record("customer_precondition", bool(customer_id), str(customer_id))
    if not (admin and production_mgr and customer_id):
        print(json.dumps(evidence, ensure_ascii=False, indent=2))
        return 2

    run_fast_path(admin, production_mgr, customer_id)
    run_secondary_plan(admin, production_mgr, customer_id)
    run_weighted(admin, production_mgr, customer_id)
    run_multistage(admin, production_mgr, customer_id)

    print("\n=== REVERIFY_EVIDENCE ===")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
