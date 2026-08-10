# 六膳门本地视觉训练闭环

## 结果口径

这套流程会自动下载六膳门已审核照片、去重、检查待标注队列，并在人工确认完成后训练新的标签检测 YOLO。候选模型必须在受保护的真实缺陷集上不丢召回、误报至少下降 5%、PT/ONNX 判定一致且生产链路回放通过，才会自动备份并替换生产 `label.onnx`；失败会自动回滚。

云端 VL 默认关闭，额度上限为 0。本机 YOLO、Grounding DINO 或 LocateAnything 可作为离线辅助老师，但其候选框不是人工真值。

## 操作员只需要关注什么

- 存在 `D:\CretasVisionLab\attention\MARK-NEEDS-ANNOTATION.json`：打开 `http://127.0.0.1:8792` 完成图片确认。
- MARK 消失：不需要人工操作，流水线会自行训练、评测。
- `D:\CretasVisionLab\receipts\latest-cycle.json`：本轮最终状态。
- `candidate-rejected`：新模型没有变好，没有部署，生产模型不变。
- `deployed`：所有门禁通过并已完成健康检查。

## 首次初始化

1. 将 `tools/vision-lab/config.liushanmen.example.json` 复制到本机 gitignored 的 `D:\CretasVisionLab\config.json`，核对模型与 holdout 路径。
2. 设置 `CRETAS_REPO_ROOT` 为当前干净的 exact-main 工作区。
3. 注册当前生产模型：

   ```powershell
   B:\anaconda3\python.exe tools\vision-lab\vision_lab.py --config D:\CretasVisionLab\config.json register-production --artifact D:\Temp\cretas-liushanmen-qc-synthetic-v2-20260728\export\label-v1-lowfloor.onnx --model-id label-v1-lowfloor-production
   ```

4. 先运行 `scan-queues` 与 `cycle --skip-collect`；确认 MARK、回执和零生产写。
5. 合入并从 clean exact-main 复核后，执行 `scripts\windows\liushanmen-vision-lab-install.ps1 -Apply -DisableLegacyTasks`。

旧任务只禁用，不删除。生产照片只读下载，原图不覆盖；训练数据、模型和回执只写入 `D:\CretasVisionLab`。
