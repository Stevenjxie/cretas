import json
import os
import sys
import time
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from docs.audits.liushanmen import gold_production_rerun_runner as r


r.TS = str(int(time.time()))
r.MARK = os.environ.get("MS_MARK_PREFIX", "DEMO-MS-") + r.TS
r.BASE = os.environ.get("MS_BASE", r.BASE)
r.results = []
r.created = {}
r.raw_sql = {}
r.responses = {}


def q4(value):
    return Decimal(str(value)).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)


def assert_log(name, ok, detail="", extra=None):
    r.log(name, ok, detail, extra)
    return ok


def submit_input(admin, batch_id, task_id, label, qty, mat=None, source_wip_no=None):
    return r.submit_input(admin, admin, batch_id, task_id, label, qty, mat=mat, source_wip_no=source_wip_no)


def submit_output(admin, batch_id, task_id, label, qty, output_kind="FINISHED", semi_code=None, source_wip_no=None, input_qty=None):
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
    if source_wip_no:
        body["sourceWipNo"] = source_wip_no
    if input_qty is not None:
        body["inputQuantity"] = str(input_qty)
        body["inputUnit"] = "kg"
    return r.submit_report(admin, admin, batch_id, task_id, "OUTPUT", label, **body)


def create_so_plan_batch(admin, customer_id, suffix, order_qty, plan_qty=None):
    order_id = r.create_sales_order(admin, customer_id, suffix, str(order_qty))
    if not order_id:
        return None, None, None, []
    plan_id = r.create_plan(admin, order_id, suffix, str(plan_qty or order_qty))
    if not plan_id:
        return order_id, None, None, []
    batch_id, tasks = r.create_batch_and_tasks(admin, plan_id, suffix, str(plan_qty or order_qty))
    return order_id, plan_id, batch_id, tasks


def create_secondary_batch(admin, wip_id, suffix, qty):
    plan_id = r.create_secondary_plan(admin, wip_id, suffix, str(qty))
    if not plan_id:
        return None, None, []
    batch_id, tasks = r.create_batch_and_tasks(admin, plan_id, suffix, str(qty))
    return plan_id, batch_id, tasks


def run_multistage_fix(admin, customer_id):
    mat_a = r.create_material_batch(admin, "RAW-A", "10.000", "1.00")
    mat_b = r.create_material_batch(admin, "RAW-B", "5.000", "2.00")
    if not (mat_a and mat_b):
        return False

    order_id, plan1, batch1, tasks1 = create_so_plan_batch(admin, customer_id, "CHAIN", 10, 10)
    r.created.update({"ms_order_id": order_id, "ms_plan1": plan1, "ms_batch1": batch1})
    if not (order_id and plan1 and batch1 and tasks1):
        return False

    code_a = f"{r.MARK}-SEMI-A"
    submit_input(admin, batch1, tasks1[0]["id"], "ms_a_input_raw", "10.000", mat=mat_a)
    submit_output(admin, batch1, tasks1[-1]["id"], "ms_a_output_semi", "10", output_kind="SEMI", semi_code=code_a)
    wip_a = r.wip_by_code(code_a, "ms_fix_wip_a")
    if not wip_a or wip_a["unitCost"] == "<null>":
        return assert_log("Gap B precondition semi A unit cost", False, str(wip_a))

    plan2, batch2, tasks2 = create_secondary_batch(admin, wip_a["id"], "CHAIN-B", 5)
    r.created.update({"ms_plan2": plan2, "ms_batch2": batch2})
    if not (plan2 and batch2 and tasks2):
        return False

    code_b = f"{r.MARK}-SEMI-B"
    submit_input(admin, batch2, tasks2[0]["id"], "ms_b_input_wip_a", "5.000", source_wip_no=code_a)
    submit_input(admin, batch2, tasks2[0]["id"], "ms_b_input_raw", "5.000", mat=mat_b)
    submit_output(
        admin,
        batch2,
        tasks2[-1]["id"],
        "ms_b_output_semi",
        "5",
        output_kind="SEMI",
        semi_code=code_b,
        source_wip_no=code_a,
        input_qty="5.000",
    )
    wip_b = r.wip_by_code(code_b, "ms_fix_wip_b")

    # Finish FG to exercise downstream order cost chain.
    plan3, batch3, tasks3 = create_secondary_batch(admin, wip_b["id"], "CHAIN-FG", 5) if wip_b else (None, None, [])
    r.created.update({"ms_plan3": plan3, "ms_batch3": batch3})
    if plan3 and batch3 and tasks3:
        submit_input(admin, batch3, tasks3[0]["id"], "ms_fg_input_wip_b", "5.000", source_wip_no=code_b)
        submit_output(admin, batch3, tasks3[-1]["id"], "ms_fg_output_finished", "5", output_kind="FINISHED", source_wip_no=code_b, input_qty="5.000")

    r.poll_order_cost(order_id, True, "ms_fix_order_cost_after_fg", timeout_s=25)

    api_code, api_body = r.req("GET", f"/{r.FACTORY}/sales/orders/{order_id}/multi-stage-cost", admin, label="ms_fix_multi_stage_cost")
    data = r.data_of(api_body)
    stages = data.get("stages") if isinstance(data, dict) else []

    plan_sql = r.sql(
        f"select 'plan|'||id||'|'||plan_number||'|'||coalesce(plan_source_type,'<null>')||'|'||coalesce(source_order_id,'<null>')||'|'||coalesce(secondary_source_wip_id::text,'<null>') "
        f"from production_plans where id in ('{plan1}','{plan2}','{plan3}') order by created_at",
        "ms_fix_plans",
    )
    sfi_sql = r.sql(
        f"select 'sfi|'||id||'|'||batch_id||'|'||intermediate_batch_no||'|'||produced_quantity||'|'||consumed_quantity||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')||'|'||coalesce(accumulated_cost::text,'<null>') "
        f"from semi_finished_inventory where id in ({wip_a['id']},{wip_b['id']}) order by id",
        "ms_fix_sfi",
    )
    txn_sql = r.sql(
        f"select 'txn|'||t.id||'|'||s.intermediate_batch_no||'|'||t.txn_type||'|'||t.source_type||'|'||t.quantity||'|'||coalesce(t.unit_cost_at_txn::text,'<null>')||'|'||coalesce(t.report_id::text,'<null>') "
        f"from semi_finished_inventory_transactions t join semi_finished_inventory s on s.id=t.semi_finished_id "
        f"where s.id in ({wip_a['id']},{wip_b['id']}) order by t.id",
        "ms_fix_txns",
    )
    reports_sql = r.sql(
        f"select 'report|'||batch_id||'|'||id||'|'||report_kind||'|'||approval_status||'|'||coalesce(input_quantity::text,'<null>')||'|'||coalesce(output_quantity::text,'<null>')||'|'||coalesce(material_cost::text,'<null>')||'|'||coalesce(source_wip_no,'<null>')||'|'||coalesce(semi_code,'<null>') "
        f"from production_reports where batch_id in ({batch1},{batch2},{batch3}) order by batch_id,id",
        "ms_fix_reports",
    )

    expected_b = None
    gap_b_ok = False
    if wip_b and wip_b["unitCost"] != "<null>":
        expected_b = q4((Decimal(wip_a["unitCost"]) * Decimal("5.000") + Decimal("10.0000")) / Decimal("5.000"))
        gap_b_ok = q4(wip_b["unitCost"]) == expected_b
    gap_a_ok = api_code == 200 and data.get("stageCount", 0) >= 2 and "SECONDARY" in plan_sql and "|<null>|" not in "\n".join(
        line for line in plan_sql.splitlines() if "|SECONDARY|" in line
    )

    assert_log("Gap B semi B unit cost formula", gap_b_ok, f"semiA={wip_a} semiB={wip_b} expectedB={expected_b}", {"sfi": sfi_sql, "txns": txn_sql, "reports": reports_sql})
    assert_log("Gap A multi-stage endpoint and secondary source order", gap_a_ok, f"HTTP {api_code} stageCount={data.get('stageCount')} plans={plan_sql}", api_body)
    return gap_a_ok and gap_b_ok


def run_weighted_regression(admin, customer_id):
    mat1 = r.create_material_batch(admin, "WGT-1", "1000.000", "1.00")
    mat2 = r.create_material_batch(admin, "WGT-2", "1000.000", "3.00")
    if not (mat1 and mat2):
        return False
    code = f"{r.MARK}-WGT"

    order1, plan1, batch1, tasks1 = create_so_plan_batch(admin, customer_id, "WGT1", 1000, 1000)
    submit_input(admin, batch1, tasks1[0]["id"], "wgt_first_input", "1000.000", mat=mat1)
    submit_output(admin, batch1, tasks1[-1]["id"], "wgt_first_output", "1000", output_kind="SEMI", semi_code=code)

    order2, plan2, batch2, tasks2 = create_so_plan_batch(admin, customer_id, "WGT2", 1000, 1000)
    submit_input(admin, batch2, tasks2[0]["id"], "wgt_second_input", "1000.000", mat=mat2)
    submit_output(admin, batch2, tasks2[-1]["id"], "wgt_second_output", "1000", output_kind="SEMI", semi_code=code)
    wip = r.wip_by_code(code, "ms_fix_wgt_final")

    txn_sql = r.sql(
        f"select 'txn|'||t.id||'|'||t.txn_type||'|'||t.quantity||'|'||coalesce(t.unit_cost_at_txn::text,'<null>')||'|'||coalesce(t.report_id::text,'<null>') "
        f"from semi_finished_inventory_transactions t join semi_finished_inventory s on s.id=t.semi_finished_id "
        f"where s.intermediate_batch_no='{code}' order by t.id",
        "ms_fix_wgt_txns",
    )
    sfi_sql = r.sql(
        f"select id||'|'||intermediate_batch_no||'|'||produced_quantity||'|'||available_quantity||'|'||coalesce(unit_cost::text,'<null>')||'|'||coalesce(accumulated_cost::text,'<null>') "
        f"from semi_finished_inventory where intermediate_batch_no='{code}'",
        "ms_fix_wgt_sfi",
    )
    report_ids = [line.split("|")[-1] for line in txn_sql.splitlines() if line.startswith("txn|")]
    ok = bool(wip and wip["unitCost"] != "<null>" and q4(wip["unitCost"]) == Decimal("2.0000") and len(report_ids) == 2 and len(set(report_ids)) == 2)
    assert_log("semi B same-code weighted regression", ok, f"wip={wip} reportIds={report_ids}", {"sfi": sfi_sql, "txns": txn_sql})
    return ok


def main():
    admin = r.login("f006_admin")
    production_mgr = r.login("f006_production_mgr")
    assert_log("login preconditions", bool(admin and production_mgr), "f006_admin/f006_production_mgr")
    customer_id = r.pick_customer()
    assert_log("customer precondition", bool(customer_id), str(customer_id))
    if not (admin and production_mgr and customer_id):
        return 2

    ok_ms = run_multistage_fix(admin, customer_id)
    ok_wgt = run_weighted_regression(admin, customer_id)
    print("\n=== MS_FIX_EVIDENCE ===")
    print(json.dumps({"marker": r.MARK, "created": r.created, "results": r.results, "raw_sql": r.raw_sql, "responses": r.responses}, ensure_ascii=False, indent=2))
    return 0 if ok_ms and ok_wgt else 1


if __name__ == "__main__":
    raise SystemExit(main())
