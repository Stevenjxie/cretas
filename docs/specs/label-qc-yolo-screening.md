# Label QC: YOLO 初筛 + VL 复核

**状态**: 待部署
**日期**: 2026-07-29

---

## 1. 为什么改

现有 `LabelQcAnalyzer` 把每张照片切成 8 个重叠切片，每片调一次视觉大模型（35s 超时、2 并发）。
成本与延迟都由 VL 调用量决定。

本方案在 VL 前面加一层本地 YOLO 初筛：**只有 YOLO 判定可疑的托盘才送 VL 复核**。
VL 仍是缺陷判定的权威，调用量从每张 8 次降到约 1–2 次。

## 2. 核心设计：把"检测缺失"翻转成"检测存在 + 规则推断"

直接训练模型定位"缺失的标签"会失败——那是要求检测一个不存在的物体，而真实缺标样本
只有 5 张。翻转后：

```
阶段1  tray detector    检测托盘        (真实训练样本充足)
阶段2  label detector   检测白标/彩标    (真实训练样本上千)
阶段3  归属过滤 + 规则   托盘内没检到白标 → 缺白标
```

模型只学"标签长什么样"，这有大量真实样本；"缺标"由规则推断，不需要缺标样本训练。

**归属过滤是必需的**：托盘 crop 带 14% padding，邻居托盘的标签常出现在画面里。
只统计中心落在本托盘框内的标签。实测该过滤把合成召回集上的正确率从 58% 提到 85%，
而目标托盘误报率不变（0）。

## 3. 参数与依据

全部来自 60 张合成缺标照片 + 30 张母图（542 个真实托盘）的实测扫描。

| 参数 | 值 | 依据 |
|---|---|---|
| `tray_conf` | 0.60 | 该阈值下 100% 照片检出数落在 18±3（18 = 装盘规格） |
| `label_conf` | 0.25 | 初筛召回最高档（90%），同召回下误报最低 |
| `pad_ratio` | 0.14 | 收紧到 0 反而更差（51→49 正确），padding 提供的上下文有用 |
| `own_labels_only` | true | 关闭后正确率 85%→58% |

## 4. 实测表现

| | 类别正确 | 母图托盘 | 目标盒误报 |
|---|---|---|---|
| PyTorch 基线 | 53/60 (88%) | 543 | 1 (0.18%) |
| ONNX + OpenCV letterbox | 53/60 (88.3%) | 543 | 2 (0.37%) |
| ONNX + PIL letterbox | 55/60 (91.7%) | 542 | 1 (0.185%) |

生产代码复现了基线。**PIL 不劣于 OpenCV**，因此不新增 opencv 依赖：
代码 cv2 优先、PIL 回退，两条路径都已验证。

CPU 单张耗时约 3s（本机 Ryzen 7 5700X，含 18 个托盘的逐一标签推理）。

## 5. ⚠️ 已知限制

1. **召回侧无法验证**。真实确认的缺标照片只有 5 张，n=5 上的召回率没有统计意义。
   合成召回集给出 88–92%，但合成填充比真实裸露膜面平滑（实测纹理只有 31%），
   所以这是**上界**而非真实值。
2. **YOLO 漏检的托盘 VL 看不到**。这是初筛架构的固有代价，已知并接受。
   缓解手段是把每次初筛结果落库，用生产数据持续评估。
3. 误报侧可信：正常照片 216 个真实托盘上误报 1.4%，母图 542 个托盘上目标盒误报 0.18%。

## 6. 响应契约

保持与现有 `LabelQcAnalyzer` 一致，Java service / RN / web-admin 无需改动：

```
{verdict, candidates[{candidateId, label, confidence, bbox, evidence, sourceTiles}],
 model, promptVersion, imageWidth, imageHeight, tilesAnalyzed}
```

新增字段（下游可忽略，用于评估与再训练）：

- `screeningMode`: `yolo-screen+vl-review` / `yolo-screen-only` / `vl-only-fallback`
- `screening`: 每个托盘的框、置信、是否检到白标/彩标、初筛判定、被过滤掉的邻居标签数，
  以及 VL 复核的确认/否决/未复核计数。

`screening.trays[].index` 与 `candidates[].sourceTiles` 对应，
人工纠正时可精确定位到具体托盘，供后续训练使用。

## 7. 降级行为

| 情况 | 行为 |
|---|---|
| `LABEL_QC_SCREENING=0` | 直接用 VL-only，与今天完全一致 |
| ONNX 模型文件缺失 | 运行时回退 VL-only，`screeningMode=vl-only-fallback` |
| onnxruntime / 依赖缺失 | 装配阶段回退 VL-only |
| 单个托盘 VL 复核失败/超时 | **保留初筛判定**，不因复核失败把可疑托盘判为正常 |
| VL 返回 UNJUDGEABLE | 保留初筛判定并降低置信，不当作正常 |
| 托盘 crop 过小无法判断 | 判为可疑交人工，不静默判正常 |

## 8. 配置

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `LABEL_QC_SCREENING` | `1` | 总开关 |
| `LABEL_QC_VL_REVIEW` | `1` | 关掉则只用 YOLO 判定，不调 VL |
| `LABEL_QC_MODEL_DIR` | `label_qc/models` | ONNX 模型目录 |
| `LABEL_QC_TRAY_CONF` | `0.60` | 托盘检测阈值 |
| `LABEL_QC_LABEL_CONF` | `0.25` | 标签检测阈值 |
| `LABEL_QC_ONNX_THREADS` | `2` | onnxruntime 线程数 |

验证端点：`GET /api/label-qc/screening-status` 返回当前实际生效的 analyzer 与模型加载状态。

## 9. 模型训练与导出

**训练只能在本地**（生产服务器无 GPU）。推理是 CPU ONNX，服务器不需要 torch。

```
本地 GPU 训练 (ultralytics YOLO11s)
  → model.export(format="onnx", imgsz=..., nms=True, opset=12, simplify=True)
  → tray.onnx / label.onnx
  → 上传到服务器 label_qc/models/
```

模型文件不进 git（各约 36 MB），由部署流程上传。

当前模型：
- `tray.onnx` — YOLO11s，imgsz 960，34 张人工确认照片 / 601 个托盘框
- `label.onnx` — YOLO11s，imgsz 640，100 个托盘 crop / 200 个标签框

## 10. 纠正数据如何回流

`screening` 字段落库后，人工在复核台的纠正即可对齐到具体托盘：

1. 生产持续积累「初筛判定 vs VL 判定 vs 人工最终判定」三方对比
2. 定期导出被人工推翻的样本（尤其**人工判缺标但初筛判正常**的漏检样本）
3. 本地重训 → 导出 ONNX → 部署新模型

这是半自动闭环，不是在线学习。漏检样本是当前最稀缺、最有价值的数据——
上线本身就是解决"真实缺标样本只有 5 张"的手段。
