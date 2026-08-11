# 六膳门本地视觉训练闭环

## 结果口径

这套流程会自动下载六膳门已审核照片、去重、检查待标注队列，并在人工确认完成后训练新的标签检测 YOLO。候选模型必须在受保护的真实缺陷集上不丢召回、误报至少下降 5%、PT/ONNX 判定一致且生产链路回放通过，才会自动备份并替换生产 `label.onnx`；失败会自动回滚。

云端 VL 默认关闭，额度上限为 0。本机 YOLO、Grounding DINO 或 LocateAnything 可作为离线辅助老师，但其候选框不是人工真值。

Tray 主动学习使用独立的 `MARK-NEEDS-TRAY-ANNOTATION.json`，不得覆盖标签队列的
`MARK-NEEDS-ANNOTATION.json`。生产 tray ONNX 负责全图预标注；已淘汰的历史 tray v7
只能用于分歧排序。LocateAnything 是可失败跳过的二级 teacher，只允许从固定离线路径
`B:\AIModels\LocateAnything-3B`、固定 revision
`c32291ca5e996f5a7a485845b4f57a233936bba0` 加载，并且只处理不超过 1024 长边的局部 crop；
禁止 4K/2400 全图推理、运行时联网下载、将 proposal 当真值或因 teacher 失败阻塞 MARK。

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

Label-only 队列必须使用现代缺标确认入口；它只监听 loopback，并强制“缺一类时二次人工确认”：

```powershell
B:\anaconda3\python.exe tools\vision-lab\label_annotator_local.py `
  --queue D:\CretasVisionLab\queues\label-side-view-active-<timestamp> `
  --port 8772
```

`tray_annotator_local.py` 与 `label_annotator_local.py` 会按 manifest 互相拒绝错误队列。旧
`label_hard_negative_annotator_legacy_adapter.py` 不支持缺标确认，已禁止作为 label 入口；不要恢复或调用。
现代 label 页面使用不透明的蓝色“白标”标题、绿色“彩标”标题、黑色外沿和类别色粗边框；
选中框改为黄色粗边及不透明手柄，框内仅轻度着色以保留标签细节。canonical 启动器会校验
这些渲染能力，误用仍是细线框的旧页面时直接拒绝启动，避免操作员再次看错类别或漏框。

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
- 存在 `D:\CretasVisionLab\MARK-NEEDS-LABEL-SIDE-VIEW-ANNOTATION.json`：只处理 MARK 指向的独立
  label-side-view 队列和 URL；不要打开或复用旧 29 张 label 难例，也不要把它与 tray MARK 混用。
- MARK 消失：不需要人工操作，流水线会自行训练、评测。
- `D:\CretasVisionLab\receipts\latest-cycle.json`：本轮最终状态。
- `candidate-rejected`：候选未通过完整自动门槛，没有部署，生产模型不变。
- `deployed`：所有门禁通过并已完成健康检查。

## 人工接受不完整召回

默认自动部署仍要求全部门槛通过。若操作员根据完整保护集结果明确接受候选取舍，只能用
`deploy` 子命令显式豁免 `required_full_recall_groups` 的未满召回；哈希/制品漂移、生产回放、
PT/ONNX parity、总缺陷回退、误报、延迟及其他错误一律不可豁免。命令必须同时提供固定 token
和不少于 20 个字符的具体原因，原始 `gate=false`、失败项、原因、旧/新 SHA 与回滚文件会保留
在部署回执中：

```powershell
B:\anaconda3\python.exe tools\vision-lab\vision_lab.py `
  --config D:\CretasVisionLab\config.json deploy `
  --model-receipt D:\CretasVisionLab\models\registry\<model-id>\training-receipt.json `
  --gate-receipt D:\CretasVisionLab\models\registry\<model-id>\promotion-gate.json `
  --operator-override ACCEPT-INCOMPLETE-RECALL `
  --operator-reason "<本次风险接受的具体依据>"
```

该入口仍执行生产现有 SHA 防漂移、备份、暂存文件 SHA、原子替换、服务重启、健康检查和失败
自动回滚；它不会把原始 promotion gate 改写成通过。

## 侧视白标主动学习

仅当完整 tray→label 回放证明“tray 已检出但白标漏检”是独立瓶颈时，才运行
`mine_label_side_view_queue.py plan`。计划阶段只读本地 VisionLab 数据库，候选必须来自人工确认的
`NO_DEFECT` 新来源；240+83+57 已完成来源、旧 29 张队列、保护集 exact ID/SHA 与 pHash 距离
不大于 10 的近重复全部排除，队列内部 pHash 距离不大于 4 的 crop 也排除。先检查 preflight
回执中的数量、来源、去重距离和 `cloud_calls=0, production_writes=0`，再用 `build` 生成队列。

`build` 会以 preflight 的候选 digest、源图 SHA、crop 像素 SHA 和感知哈希重新验证内容，生成
`label-side-view-active-<timestamp>`、独立的 `MARK-NEEDS-LABEL-SIDE-VIEW-ANNOTATION.json` 和
`annotations-human`。生产 YOLO 预框始终是 proposal；只有标注服务器落盘的
`reviewed=true, source=human` 才能进入后续 label 训练。不得因此覆盖 tray MARK、旧 label MARK、
生产模型或原图。

当新侧视正样本使白色纸张、包装边缘等非标签物体的 `white_label` 置信度上升时，不得继续机械追加
同类缺标正样本。改用 `--selection-mode white-confuser-disagreement`，并用
`--candidate-label/--candidate-label-sha256` 将已拒绝候选绑定为只读分歧排序器。该模式只选择候选新增或
显著放大的白标框，回执必须单列候选照片、任务、SKU、精确 SHA/ID 与 pHash 排除结果；排序候选和
预框都不是真值，仍需完整人工复核。保护 7+20 不参与相似度检索、排序或调参。

更换骨干模型只能在同一 reviewed-only 数据、相同 task split、相同离线参数和完整 7+20 门禁下做
公平 A/B；不得下载新权重，也不得用训练集表现选择生产模型。若 hard negative 尚未覆盖已知错误模式，
先补数据再换模型，避免把同一错误学得更自信。

当旧队列需要继续保留为安全阻塞、但新建的独立队列已完成人工确认时，使用显式队列白名单运行
候选闭环；三个参数必须成组使用，避免扫描/清除旧 MARK 或再生成一轮队列：

```powershell
B:\anaconda3\python.exe tools\vision-lab\vision_lab.py --config D:\CretasVisionLab\config.json cycle `
  --skip-collect --preserve-attention-mark --skip-mining `
  --queue-root <reviewed-queue-1> `
  --queue-root <reviewed-queue-2>
```

显式路径缺少 manifest 或重复时会直接失败；启用白名单后不会再展开 `queue_globs`。
训练进程固定设置 `YOLO_OFFLINE=true`、`amp=false` 和 `pretrained=false`；本地 PT 仍作为明确的
`base_model` 加载，但禁止 Ultralytics 的 AMP 自检或版本检查联网下载额外权重。

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
