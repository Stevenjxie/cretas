# Gold WeChat Report Closure - 2026-06-12

Environment: prod F006, RN App evidence from real device run, SQL via SSH to `127.0.0.1:10010` / prod DB.
Sources:
- Ground truth summary: `docs/qa-audits/2026-06-03-liushanmen-6.1-6.3-field-process-conformance-audit.md`
- Image source: `六扇门工厂数据、/6.1-6.3/群内图片/`
- OCR artifacts: `2026-06-12-ocr-filelist.txt` (287 image paths), `2026-06-12-ocr-transcript.jsonl` (252 OCR rows)
- RN operator evidence: `2026-06-12-rn-operator-ocr-e2e.md`
- Production cost blocker: `2026-06-12-gold-production-deep.md`

## Verdict

🟠 PARTIAL / not deep-closed.

RN App real-photo upload and two different operator submissions are closed at SQL level:

- `f006_moyun` submitted report `505` with real image URL and annotation.
- `f006_weizj` submitted report `506` with real image URL and labor segment.

But the requested "6.1-6.3 WeChat real numbers -> production chain -> finished goods -> cost SQL closure" is not fully closed. The blocker is not OCR: the current F006 two-point costing path does not roll material input task cost into final output task cost, so the chain cannot honestly prove cost closure. See production blocker: SO `SO-20260612-0008`, batch `1989`, reports `509/510/511`, `sales_order_items.cost_unit_price` remains null.

## Ground Truth Used

From the existing 6.1-6.3 audit:

```text
Daily order quantities:
牛腱80g: 6.1=1912, 6.2=159, 6.3=1473
猪舌120g: 6.1=1780, 6.2=513, 6.3=625
掌中宝120g: 6.1=498, 6.2=305, 6.3=334

Product process chains:
猪舌: 修油 -> 滚揉(保水) -> 焯水 -> 去舌苔 -> 熟制(卤制) -> 气调(分切装盒)
牛腱: 修油 -> 滚揉 -> 焯水 -> 熟制 -> 气调
掌中宝: 水解化冻 -> 焯水 -> 油炸 -> 熟制伴汁 -> 气调

WeChat report format:
产品名 + 秤照毛重 + 桶/皮重 + 净重计算 + 时间戳 + 装盒照
Example format: (393.5 - 64 = 329.5kg)
```

OCR was used only to locate and sanity-check image content. It was not used to auto-write business data. The JSONL contains many low-confidence or encoding-garbled rows, so business assertions below rely on RN/SQL readback and the audited ground truth document.

## RN + SQL Evidence

Already closed in the RN operator run:

```text
report 505
batch=DEMO-Y-66882
worker=f006_moyun
report_kind=INPUT
task=330
input_quantity=8.00 kg
photos=["https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/images/yield-evidence/2026/06/12/6a4236858c5242b2_.jpg"]
photo_annotations=[{"label":"称重投入"}]
status=SUBMITTED

report 506
batch=DEMO-X-66881
worker=f006_weizj
report_kind=SEGMENT
task=337
labor_segments=[{"startTime":"07:00","endTime":"15:00","headcount":1,"note":"DEMO-FRIDAY-weizj-oil-fry-ocr-6.2"}]
photos=["https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/images/yield-evidence/2026/06/12/782809d94e7d4a30_.jpg"]
status=SUBMITTED
```

Gold production deep run proved the current blocker with real F006 two-point reports:

```text
SO SO-20260612-0008 item 549 cost_unit_price=<null>
Batch 1989 DEMO-GOLD-WITHDRAW-1781225994
INPUT report 509 task=365 input=0.50 material_cost=0.50
OUTPUT report 510 task=366 output=10.00 material_cost=<null> labor_cost=<null>
OUTPUT report 511 task=366 output=8.00 material_cost=<null> labor_cost=<null>
WIP produced=18.00 accumulated_cost=<null> unit_cost=<null>
```

## Honest Closure Matrix

| Target | Result | Evidence |
| --- | --- | --- |
| OCR over real 6.1-6.3 image folder | PASS as assistive artifact | 287 file paths, 252 OCR rows |
| OCR directly drives business data | Not used by design | User clarified RN upload is source of truth |
| Different operator reports | PASS | reports `505` and `506` use `f006_moyun` / `f006_weizj` |
| Real photo persistence | PASS | both reports have OSS `photos` |
| Photo annotation persistence | PARTIAL | report `505` has annotation; report `506` photo exists but `photo_annotations` empty |
| 6.1-6.3 product-specific full chain | NOT CLOSED | product-specific route/carryover not fully configured in live chain |
| Finished goods cost closure | 🔴 OPEN | two-point output task cost remains null |

## Organizer Gate Finding

Do not mark this as "OCR failed" or "RN failed." The real blocker is production cost rollup across F006 two-point tasks. Until input-task material cost rolls into output-task WIP cost, the WeChat real-number chain cannot be deep-closed without fabricating cost values.
