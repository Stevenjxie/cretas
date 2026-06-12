# 2026-06-12 RN Operator + OCR E2E

## 结论

本轮用真机 RN App、F006 prod 真实账号、真实群内报工图片完成两名不同 operator 的报工验证。OCR 只用于辅助识别图片里的报工内容和选择照片，不替代 RN 上传路径；最终提交仍通过 RN App 写入 prod。

| 项 | 结果 | 深度 | 证据 |
| --- | --- | --- | --- |
| 莫云 `f006_moyun` 投入报工 | PASS | deep | RN 提交成功 + SQL `production_reports.id=505` |
| 魏振江 `f006_weizj` 时段报工 | PASS/WARN | deep | RN 提交成功 + SQL `production_reports.id=506` |
| 多 operator 区分 | PASS | deep | SQL worker 分别为 `f006_moyun` / `f006_weizj` |
| 真实图片上传 | PASS | deep | 两条记录均有 OSS `photos` |
| 时段报工图片标签 | WARN | medium | RN 已加图，但 `photo_annotations` 为空 |

## SQL 回读

```text
id=505 factory=F006 batch=DEMO-Y-66882 worker=f006_moyun/莫云
report_kind=INPUT task=330 process_order=1 input_quantity=8.00 kg
material_batch_refs=[{"unit":"kg","quantity":8,"materialBatchId":"eef65627-8dc8-4c32-86c1-8812fcf6ea15"}]
photos=["https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/images/yield-evidence/2026/06/12/6a4236858c5242b2_.jpg"]
photo_annotations=[{"url":"...6a4236858c5242b2_.jpg","label":"称重投入"}]
status=SUBMITTED created_at=2026-06-12 01:48:23

id=506 factory=F006 batch=DEMO-X-66881 worker=f006_weizj/魏振江
report_kind=SEGMENT task=337 process_order=3 total_workers=1 total_work_minutes=480
labor_segments=[{"note":"DEMO-FRIDAY-weizj-oil-fry-ocr-6.2","endTime":"15:00","headcount":1,"startTime":"07:00"}]
photos=["https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/images/yield-evidence/2026/06/12/782809d94e7d4a30_.jpg"]
photo_annotations=<empty>
status=SUBMITTED created_at=2026-06-12 01:58:45
```

## 截图证据

- 莫云投入提交后：[rn-moyun-after-dialog-dismiss.png](./rn-moyun-after-dialog-dismiss.png)
- 魏振江提交前：[rn-weizj-before-segment-submit.png](./rn-weizj-before-segment-submit.png)
- 魏振江提交后：[rn-weizj-after-segment-submit.png](./rn-weizj-after-segment-submit.png)

## 问题

1. `WARN` 时段报工证据标签未持久化：魏振江时段报工已上传图片，但 SQL `photo_annotations` 为空。投入报工同类字段可持久化为“称重投入”，说明问题可能只在时段报工标签交互或提交 payload。
2. `expected` 投入/时段报工后工序任务仍为 `PENDING`：这两步不是完工出成提交，不应把 `actual_quantity` 和 `completed_by` 提前写满。

## OCR 说明

OCR 的作用是把 6.1-6.3 群内照片里的“掌中宝焯水前/后、炸制后、时间段、人员”等信息转成可人工核对的线索。本轮没有让 OCR 直写业务数据，业务数据以 RN 表单提交和 SQL readback 为准。
