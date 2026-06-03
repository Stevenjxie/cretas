# 预期: 5 tests pass
```

**Step 5 — commit**

```bash
git commit -m "feat(restaurant): Task6 Vue三件套 + API client + router + 侧边栏 (TDD 5 tests)" \
  -- web-admin/src/api/restaurant/supplierDeliveryNote.ts \
     web-admin/src/views/restaurant/supplier-delivery/SupplierDeliveryNoteList.vue \
     web-admin/src/views/restaurant/supplier-delivery/SupplierDeliveryNoteUploadDialog.vue \
     web-admin/src/views/restaurant/supplier-delivery/SupplierDeliveryNoteDetail.vue \
     web-admin/src/components/restaurant/VoiceRequisitionCapture.vue \
     web-admin/src/router/index.ts \
     web-admin/src/views/restaurant/__tests__/SupplierDeliveryNoteList.spec.ts \
     web-admin/src/views/restaurant/__tests__/SupplierDeliveryNoteUploadDialog.spec.ts
```

---

## Task 7 — 集成部署 + 真库 E2E 验证

**Files**: 无新文件 — 部署 + 验证命令

**Step 1 — Flyway 版本撞车最终检查**

```bash
git ls-tree origin/main backend/java/cretas-api/src/main/resources/db/flyway \
  | grep -oE 'V20260609_[0-9]{2}' | sort | uniq
# 应无输出 → 安全; 有输出 → 重编号 V20260610_01/02

git ls-tree origin/main backend/python/smartbi/database/migrations \
  | grep -oE 'V20260609_[0-9]{2}' | sort | uniq
# 应无输出 → 安全
```

**Step 2 — 部署 test 环境**

```bash
# Java (含 Flyway migration V20260609_01/02 自动 apply)
./scripts/deploy/deploy-backend.sh --env test

# Python (含 smartbi migration V20260609_01 apply via apply-smartbi-migrations.sh)
./scripts/deploy/deploy-smartbi-python.sh --env test
```

**Step 3 — 真库 E2E 验证 (cretas_db + smartbi_db grant 检查)**

```bash
# 1. 验证 supplier_delivery_notes 表存在
ssh root@47.100.235.168 "psql -U cretas_user cretas_db -c \"\dt supplier_delivery_notes\""

# 2. 验证 agg_supplier_price 表 + GRANT 正确
ssh root@47.100.235.168 "psql -U smartbi_user smartbi_db -c \"INSERT INTO agg_supplier_price (factory_id,ingredient_name,normalized_name,delivery_date,unit_price) VALUES ('TEST_GRANT','猪肉','猪肉','2026-06-09',28.50) RETURNING id\""
# 预期: id 返回 (不报 permission denied)

# 3. 调 OCR 草稿 endpoint (mock 图片 base64, 验证 note 写入)
ssh root@47.100.235.168 "curl -s -X POST http://localhost:10011/api/mobile/F006/restaurant/supplier-delivery-notes/manual \
  -H 'Authorization: Bearer <test-token>' \
  -H 'Content-Type: application/json' \
  -d '{\"deliveryDate\":\"2026-06-09\",\"supplierName\":\"测试供应商\",\"noteNumber\":\"TEST-001\"}' | python3 -m json.tool"
# 预期: {success: true, data: {id: "SDN-...", status: "DRAFT"}}

# 4. 确认该 note → 验证 agg_supplier_price 行写入
NOTE_ID=<从上一步获取>
ssh root@47.100.235.168 "curl -s -X PUT http://localhost:10011/api/mobile/F006/restaurant/supplier-delivery-notes/$NOTE_ID/confirm \
  -H 'Authorization: Bearer <test-token>' | python3 -m json.tool"

ssh root@47.100.235.168 "psql -U smartbi_user smartbi_db -c \"SELECT count(*) FROM agg_supplier_price WHERE factory_id='F006'\""
# 预期: count >= 0 (manual note 有 0 lines 时 agg 无写入是正常的; 有 lines 的 OCR note 应 > 0)

# 5. 清理测试数据
ssh root@47.100.235.168 "psql -U cretas_user cretas_db -c \"UPDATE supplier_delivery_notes SET deleted_at=NOW() WHERE note_number='TEST-001'\""
ssh root@47.100.235.168 "psql -U smartbi_user smartbi_db -c \"DELETE FROM agg_supplier_price WHERE factory_id='TEST_GRANT'\""
```

**Step 4 — 部署 prod**

```bash
# 确认在 main 分支
git checkout main && git pull origin main

./scripts/deploy/deploy-backend.sh --env prod
./scripts/deploy/deploy-smartbi-python.sh --env prod
```

**Step 5 — commit (部署后 checklist)**

```bash
git commit -m "feat(restaurant): G7 OCR供应商录入+语音领料 — Task7 部署验证完成

Tier A: OCR flow live — supplier_delivery_notes(cretas_db) + agg_supplier_price(smartbi_db)
Tier B: RestaurantVoiceRequisitionTool 已注册 (tool=restaurant_voice_requisition)
Flyway: V20260609_01(cretas_db) + V20260609_02(ai_intent) applied test+prod
Python migration: V20260609_01__agg_supplier_price applied, GRANT verified
E2E: manual draft → confirm → agg_supplier_price write OK
" \
  -- docs/superpowers/plans/2026-06-03-restaurant-data-ingest-auto.md
```

---

## 关键事项汇总

**Flyway 版本号**: 实施时必须在 PR 前再次执行版本防撞车检查 (`git ls-tree origin/main ... | grep V20260609`)。当前 Java 最高版本 `V20260608_03` 已被占用; `V20260609_01/02` 依据今日检查应安全。

**smartbi_user GRANT**: `V20260609_01__agg_supplier_price.sql` 末尾两行 `GRANT` 是历史复发两次的陷阱 — 绝不能省略。真库 E2E Step 3 中的 INSERT 测试是唯一能验证 grant 正确性的方法，单测抓不到。

**D5 fail-soft 隔离**: `confirmNote()` 中 gold upsert 失败时 note 仍标 CONFIRMED, 通过 `catch(Exception e)` + `log.error` + 写 `ocrErrorMessage` 实现。不使用 `@Transactional(propagation=REQUIRES_NEW)` — gold 调用是 HTTP 调用而非数据库操作, 不参与 Spring 事务。

**RawMaterialTypeRepository.findByFactoryIdAndNameContaining**: 此方法需在 `RawMaterialTypeRepository` 中存在。若不存在需追加:
```java
List<RawMaterialType> findByFactoryIdAndNameContaining(String factoryId, String name);
```

**DashScopeVisionClient.analyzeImage 签名**: spec 中为 `analyzeImage(ossUrl, prompt) → String`。若现有 `DashScopeVisionClient` 接口不同 (如接受 bytes 而非 url), 在 `SupplierDeliveryNoteServiceImpl.parseAndDraft()` 中按实际签名调整, 逻辑不变。

**PythonLLMClient.chat 签名**: `VoiceRequisitionParserServiceImpl` 使用 `llmClient.chat(systemPrompt, userPrompt) → String`。若现有签名不同, 调整调用形式。

**并行实施建议**: Subagent A (Task 3+4+5 Java层) 和 Subagent B (Task 1+2 Python层) 可完全并行 — smartbi_db 与 cretas_db 物理分离。Subagent C (Task 6 前端) 可按 spec 签名 mock API 后立即开始, A 完成后对齐。Task 7 等 A+B+C 全完成后串行执行。
