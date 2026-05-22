#!/bin/bash
# cleanup-sprint-10-test-data.sh
# 删除 Sprint 10 E2E 测试期间在 prod 创建的所有测试数据.
# 调用: ./scripts/test/cleanup-sprint-10-test-data.sh [loop-N|all]
# 默认 all.
#
# 数据 tag: ai_invocation_metadata @> '{"testRun": true, "source": "sprint-10-loop-N"}'
# Loop 4 审批: approval_workflow_instances.context_json (已有列)

set -euo pipefail

LOOP="${1:-all}"

case "$LOOP" in
    all|loop-1|loop-2|loop-3|loop-4|loop-5) ;;
    *) echo "Unknown loop: $LOOP. Use loop-1..loop-5 or 'all'."; exit 1 ;;
esac

WHERE_AI="ai_invocation_metadata @> '{\"testRun\": true}'"
WHERE_CTX="context_json @> '{\"testRun\": true}'"
if [ "$LOOP" != "all" ]; then
    WHERE_AI="ai_invocation_metadata @> '{\"testRun\": true, \"source\": \"sprint-10-${LOOP}\"}'"
    WHERE_CTX="context_json @> '{\"testRun\": true, \"source\": \"sprint-10-${LOOP}\"}'"
fi

echo "🧹 Cleaning Sprint 10 test data: loop=$LOOP"

ssh root@47.100.235.168 "PGPASSWORD=cretas123 psql -h localhost -U cretas_user -d cretas_prod_db <<SQL
BEGIN;
DELETE FROM sales_delivery_items WHERE delivery_record_id IN
    (SELECT id FROM sales_delivery_records WHERE ${WHERE_AI});
DELETE FROM sales_delivery_records WHERE ${WHERE_AI};
DELETE FROM purchase_receive_items WHERE receive_record_id IN
    (SELECT id FROM purchase_receive_records WHERE ${WHERE_AI});
DELETE FROM purchase_receive_records WHERE ${WHERE_AI};
DELETE FROM purchase_order_items WHERE purchase_order_id IN
    (SELECT id FROM purchase_orders WHERE ${WHERE_AI});
DELETE FROM purchase_orders WHERE ${WHERE_AI};
DELETE FROM production_batches WHERE ${WHERE_AI};
DELETE FROM approval_history WHERE workflow_instance_id IN
    (SELECT id FROM approval_workflow_instances WHERE ${WHERE_CTX});
DELETE FROM approval_workflow_instances WHERE ${WHERE_CTX};
COMMIT;
SELECT 'Cleanup done for loop=$LOOP';
SQL
"

echo "✅ Cleanup complete"
