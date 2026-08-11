# 六膳门本地视觉训练闭环

## 结果口径

这套流程会自动下载六膳门已审核照片、去重、检查待标注队列，并在人工确认完成后训练新的标签检测 YOLO。候选模型必须在受保护的真实缺陷集上不丢召回、误报至少下降 5%、PT/ONNX 判定一致且生产链路回放通过，才会自动备份并替换生产 `label.onnx`；失败会自动回滚。

云端 VL 默认关闭，额度上限为 0。本机 YOLO、Grounding DINO 或 LocateAnything 可作为离线辅助老师，但其候选框不是人工真值。

Tray 主动学习使用独立的 `MARK-NEEDS-TRAY-ANNOTATION.json`，不得覆盖标签队列的
`MARK-NEEDS-ANNOTATION.json`。生产 tray ONNX 负责全图预标注；已淘汰的历史 tray v7
只能用于分歧排序。LocateAnything 是可失败跳过的二级 teacher，只允许从固定离线路径
`B:\AIModels\LocateAnything-3B`、固定 revision
`c32291ca5e996f5a7a485845b4f57a233936bba0` 加载，并且只处理不超过 640 长边的局部 crop；
禁止 4K/2400 全图推理、运行时联网下载、将 proposal 当真值或因 teacher 失败阻塞 MARK。

固定目录首次就绪时，必须按“已验证 D snapshot 复制到 B → 封印关键文件 SHA-256 → 离线复核 →
明确局部 crop smoke”的顺序执行。`smoke` 强制要求像素 crop，不提供全图入口：

```powershell
B:\anaconda3\python.exe tools\vision-lab\locateanything_teacher_admin.py seal `
  --model-path B:\AIModels\LocateAnything-3B `
  --revision c32291ca5e996f5a7a485845b4f57a233936bba0

B:\anaconda3\python.exe tools\vision-lab\locateanything_teacher_admin.py verify `
  --model-path B:\AIModels\LocateAnything-3B

B:\anaconda3\python.exe tools\vision-lab\locateanything_teacher_admin.py smoke `
  --model-path B:\AIModels\LocateAnything-3B `
  --image <local-readonly-image> `
  --crop <x0,y0,x1,y1> `
  --prompt all `
  --receipt D:\CretasVisionLab\receipts\locateanything-crop-smoke.json
```

若 B 盘目录不可创建或不可读，必须停止并由操作员修复该目录权限；不得改用 D 缓存冒充稳定路径，
不得联网重下。新队列优先保留约三分之一给经过多提示支持的 teacher-only proposal，其余继续覆盖
蓝筐、边缘、孤立、遮挡和堆叠场景；该配额仍只是人工复核采样，不是真值。

连续候选若都卡在蓝筐顶部孤立托盘，可对未使用且非保护集的本地副本启用
`mine_tray_queue.py --prefer-blue-basket`。它只把确定性的蓝色区域当场景排序特征，不生成
托盘真值；最多先占队列三分之二，其余仍由边缘、孤立、遮挡与模型分歧补足。扫描已完成
但在 manifest 写入前失败时，可用 `--reuse-selection-from` 复用完整 photo ID，避免重复推理。

本地 tray 标注页只监听回环地址：

```powershell
B:\anaconda3\python.exe tools\vision-lab\tray_annotator_local.py `
  --queue D:\CretasVisionLab\tray-queues\tray-active-<timestamp> `
  --port 8765
```

每张修正完成后必须点“这张没问题”或按空格；只有页面显示 `已确认 24/24`，对应
`annotations-human/*.json` 才会写入 `reviewed=true, source=human`。仅翻到下一张不会被
流水线当作人工真值。连续轮次必须核对页面顶部的完整队列名；服务端保存返回非 200 时
页面会直接弹错并停止“确认”，不得用浏览器内存里的旧页面计数代替服务端 `/api/stats`。
框体使用可透视内容的深青蓝半透明填充，选中时改为更深的黄色；边线保持细线，拖拽热区保留但
不绘制方块手柄。

人工确认完成后运行 tray 候选闭环：

```powershell
B:\anaconda3\python.exe tools\vision-lab\tray_workflow.py `
  --config D:\CretasVisionLab\config.json
```

已有多轮 `reviewed=true` 的人工队列时，可以重复传入 `--queue` 建立累计数据集；每个队列仍会
独立重验，数据集按任务级拆分，并拒绝跨队列重复 stem。例如：

```powershell
B:\anaconda3\python.exe tools\vision-lab\tray_workflow.py `
  --config D:\CretasVisionLab\config.json `
  --queue D:\CretasVisionLab\tray-queues\tray-active-<round-1> `
  --queue D:\CretasVisionLab\tray-queues\tray-active-<round-2>
```

训练配置支持可选的 `tray_active_learning.training.freeze`（默认 `10`）。它只控制冻结的
YOLO 层数，不改变保护集、人工真值或部署门禁；不得依据保护集结果反复搜索该参数。

该入口先重验源图、打包图、人工标注与保护集哈希，再按任务拆分训练/验证集，训练轻量
YOLO、导出 ONNX，并用真实生产 label 模型回放受保护的 7 张缺陷与 20 张正常图。候选必须
同时满足：缺陷召回不回退、新盲测 2/2、根因样本 tray 覆盖且命中、正常图误报改善、延迟
合格、PT/ONNX 一致性不差于生产模型。任一项失败都只写淘汰回执，不修改生产模型。

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
