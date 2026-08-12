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

### 金属工作区 ROI 与台外真实缺标

工作区 ROI 是独立于 tray MARK 和 label MARK 的第三层人工真值。不得删除台外托盘框，也不得把
台外缺白标/彩标样本改成正常或训练负样本。全图 tray/label 检出继续保留；ROI 只负责把结果分成：

- `inside_work_area`：托盘中心位于人工四点多边形内，进入主计数和主缺标告警；
- `outside_work_area`：托盘中心位于多边形外，保留为真实缺标训练样本和独立的次级召回统计；
- `unknown_work_area`：工作台不在图中或无法可靠判断。不得静默归入台内，也不得据此自动发布。

在 tray 框已完成 `reviewed=true, source=human` 后，用独立入口标四点透视多边形：

```powershell
B:\anaconda3\python.exe tools\vision-lab\work_area_annotator_local.py `
  --queue D:\CretasVisionLab\tray-queues\tray-active-<timestamp> `
  --port 8774
```

该入口只新增 `work-area-human/*.json` 和显示缓存；`annotations-human/`、label 标注、manifest、原图与
保护集记录均只读。服务端固定校验四个有序角点、归一化坐标、非自交和最小面积，并以托盘中心点
计算台内/台外数量。青色 tray 框在页面上只读；黄色多边形只表示本次主工作台可用台面。存在后方
金属架、蓝筐或台外堆叠时不要把它们圈入主台面，但其托盘与缺标真值仍完整保留。

ROI 数据不足以独立验证轻量分割模型时只能停在人工数据阶段；不得下载新权重、用粗矩形或启发式
结果冒充生产 ROI。后续候选必须同时报告台内主门禁、台外缺标非回退和 `unknown_work_area` 数量。

人工确认完成后先只运行 ROI 审计。该命令要求每张都存在 `reviewed=true, source=human` 的四点
非自交多边形，重新核对源图、打包图、tray 标注和 ROI 文件哈希，并在 `receipts/` 新增台内、
台外与 unknown 数量回执；缺一张或存在无法判断项就失败，不启动训练：

```powershell
B:\anaconda3\python.exe tools\vision-lab\tray_workflow.py `
  --config D:\CretasVisionLab\config.json `
  --work-area-queue D:\CretasVisionLab\tray-queues\tray-active-<timestamp> `
  --audit-work-area-only
```

候选评估只从独立 `work-area-human/` sidecar 读取 ROI，不向受保护 7+20 manifest 添加字段。评估时
必须显式传入覆盖保护集图片且源图 SHA 匹配的人工 ROI 目录；缺失和无法判断都记入
`unknown_work_area` 并阻断发布：

```powershell
B:\anaconda3\python.exe tools\vision-lab\tray_workflow.py `
  --config D:\CretasVisionLab\config.json `
  --candidate-receipt D:\CretasVisionLab\models\registry\<candidate>\training-receipt.json `
  --work-area-annotations D:\CretasVisionLab\evaluation\work-area-human `
  --queue D:\CretasVisionLab\tray-queues\tray-active-<round-1>
```

用于 ROI 模型训练的 8 张队列不能自动当作 7+20 的 ROI 真值；两者 photo ID/SHA 不匹配时，
评估会把保护集记录保留为 unknown，而不是静默当作台内。

首轮 8 张可以用完全离线、随机初始化的小分割模型做“数据是否足够”的研究门禁。该实验按任务
留一验证，并和只使用位置先验的平均掩码基线比较；它不保存权重、不读取保护集、不写 registry，
结果也不能授权部署：

```powershell
B:\anaconda3\python.exe tools\vision-lab\work_area_roi_experiment.py `
  --queue D:\CretasVisionLab\tray-queues\tray-active-<round-1> `
  --queue D:\CretasVisionLab\tray-queues\work-area-active-<round-2> `
  --queue D:\CretasVisionLab\tray-queues\work-area-active-<round-3> `
  --runtime-root D:\CretasVisionLab `
  --epochs 480 `
  --coordinate-channels `
  --normalized-blocks `
  --center-loss-weight 0.05 `
  --base-channels 8 `
  --unet-depth 4 `
  --folds 8 `
  --device cuda
```

2026-08-12 首轮 8 个独立任务的实测结果为：模型平均 IoU `0.8280`，相对位置基线提高
`0.0327`，但最差托盘中心分类准确率仅 `0.7037`、最差台外召回仅 `0.1111`，合计错分
`23` 个中心点（位置基线错分 `5` 个）。因此结论为
`insufficient_image_conditioned_roi_evidence`：只能说明图像中存在可学习信号，不能说明 8 张足以
训练生产 ROI。旧 tray 训练和 7+20 候选评估继续暂停；需要新增 ROI 人工样本时，必须先报告
精确数量、任务/SKU、源图 SHA/ID、pHash，以及保护集和旧 MARK 排除结果，再建立独立队列。

第二轮 24 张于 2026-08-12 完成人工复核：`/api/stats` 为 24/24，四点多边形全部可判断，台内
`388`、台外 `72`、unknown `0`；累计 32 个独立任务为台内 `532`、台外 `142`、unknown `0`。
模型容量与泛化必须分开判定：先加 `--fit-diagnostic` 关闭训练扰动，确认模型可以拟合全部人工
真值；再运行上面的任务级 8 折。拟合通过不等于可部署，8 折通过也仍需锁定独立测试集。

32 张实测容量门禁最差 IoU `0.986`，中心分类、台内召回和台外召回均为 `1.0`；但 8 折只有
平均 IoU `0.8479`、最差 `0.6285`，合计 `13` 个托盘中心错分，最差台外召回为 `0`。相对位置
基线的平均 IoU 增益虽为 `0.1030`，仍不满足离线门禁，因此不得保存模型、恢复旧 tray 训练或
启动 7+20 候选。下一轮应只收集交叉验证困难视角的人工 ROI，不再随机扩充同源普通画面。

第三轮困难视角 16 张完成后，审计新增台内 `272`、台外 `50`、unknown `0`，累计 48 张为台内
`804`、台外 `192`、unknown `0`。由于多边形 IoU 很高时边界附近的托盘中心仍可能错分，训练损失
可用 `--center-loss-weight 0.05` 加入人工托盘中心监督；权重 `1.0` 的实测会破坏掩码拟合，已否决。
权重 `0.05` 的 48 张容量门禁达到最差 IoU `0.969`、996 个中心零错分及台内/台外召回 `1.0`；
这仍只是容量证据，必须重新通过任务级 8 折。

两层模型的 48 张 8 折只有平均 IoU `0.8631`、最差 `0.6439`、`23` 个中心错分和最差台外
召回 `0`。四层、base channel `8` 的全局视野模型先通过容量门禁（平均 IoU `0.9925`、最差
`0.9874`、中心零错分），但相同 8 折仍只有平均 IoU `0.8613`、最差 `0.6368`、`19` 个中心
错分和最差台外召回 `0`。因此结论继续为 `insufficient_image_conditioned_roi_evidence`。
当前 132 图数据集中，失败更明显的 SKU 0013/0014 已无未标独立任务；0015/0016 虽还有任务，
但不能替代失败 SKU 的证据。不得继续用同任务重复照片、盲目调参或不匹配 SKU 扩标。

四点坐标全局回归和四角热图也必须按同一任务级 8 折门禁验证。全局回归虽然容量门禁最差 IoU
`0.9858` 且中心零错分，但 8 折只有平均 IoU `0.8177`、最差 `0.5114`、`47` 个中心错分，
最差台外召回 `0`。四角热图容量拟合达到平均 IoU `0.9951`、最差 `0.9816`；按生产的精确
归一化点在多边形内契约复算为中心零错分，但 8 折出现 `3` 个非法多边形，平均 IoU `0.8059`、
`61` 个中心错分，继续为 `insufficient_image_conditioned_roi_evidence`。这些实验均随机初始化、
不保存权重、不读取保护集、不写 registry/生产；不得用容量拟合结果替代泛化证据。

第四轮计划只使用剩余 0015/0016 来源补充视觉多样性，不代表补齐 0013/0014 证据。132 图池中
排除三轮已完成 ROI 对应的 `72` 行、旧 MARK 对应的 `9` 行后有 `51` 行、`32` 个独立任务；
在每任务一图、与保护集/已完成轮次/本轮 pHash 距离均大于 `10` 的规则下，最大可成立数量为
`18`（0015 `9`、0016 `9`），第 `19` 张无法满足多样性约束。计划回执
`work-area-roi-plan-20260811T185439808100Z.json` 的 SHA256 为
`6a23d72d91b547c38f071ca65722fd3bed2eac73c808f381079fc31e50e8b00f`；独立队列为
`work-area-active-20260811T185515823075Z`，构建后 `/api/stats` 初始为 `0/18`。完成后必须重新
服务端核验 18/18 和逐文件 reviewed/source/四点非自交，再决定是否重跑累计 66 张的离线门禁。

第四轮已完成服务端 18/18 和逐文件审计，新增台内 `303`、台外 `61`、unknown `0`；累计 66 个
任务为台内 `1107`、台外 `253`、unknown `0`，审计回执为
`work-area-audit-20260811T190400752959Z.json`。66 张四角热图容量拟合达到平均/最差 IoU
`0.9973`/`0.9835`、所有托盘中心零错分；任务级 8 折却仍只有平均/最差 IoU `0.8134`/`0`、
`98` 个中心错分、`3` 个非法多边形，最差台内与台外召回均为 `0`。回执
`work-area-roi-corner-experiment-20260811T194100822073Z.json` 的 SHA256 为
`301229e5d62cd0dead3918638331de89c2438ebed874a4433ccaae8408f07dd5`。因此 66 张仍不足以训练生产
ROI 模型，未保存权重、未写 registry、未授权部署。

补齐 0013/0014 证据时，原始数据库中的新任务还没有人工 tray 真值，必须先做托盘外框复核，
不能直接用生产检测框生成 ROI。`work_area_raw_tray_plan.py` 以只读 SQLite 打开数据库，排除所有
既有 manifest 的 photo/task/SHA、保护集 exact 与 pHash Hamming `<=10` 近重复；每任务只选一图，
按 SKU 均衡并记录完整 task/SKU/SHA/ID/pHash 绑定。计划器只写 receipt，不建队列、不写 MARK：

```powershell
B:\anaconda3\python.exe tools\vision-lab\work_area_raw_tray_plan.py `
  --database D:\CretasVisionLab\state\vision.db `
  --existing-manifest <每个既有 tray、ROI 和旧 label MARK manifest；可重复> `
  --protected-holdout D:\CretasVisionLab\evaluation\protected-holdout.json `
  --sku-code CPLIUSHANMEN0013 --sku-code CPLIUSHANMEN0014 `
  --count-per-sku 15 `
  --runtime-root D:\CretasVisionLab
```

当前计划 `work-area-raw-tray-plan-20260811T191537291550Z.json`（SHA256
`2dd4887df39c34073ad1f292a6650aeda80abe860b7e6c33a1ed629d92fc505e`）选中 30 个独立任务，
0013/0014 各 15；最近保护集、既有 ROI、本轮内 pHash 距离分别为 `94`、`98`、`98`。用
`mine_tray_queue.py --raw-work-area-plan ... --raw-work-area-plan-sha256 ...` 构建时会重新验证计划、
全部 manifest 哈希、源图 SHA/pHash 和保护集排除；生产 tray ONNX 只生成 proposal。队列
`tray-active-20260811T194228Z` 含 30 图/30 任务，30 份 `annotations-human` 初始均为
`reviewed=false`，必须逐张完整复核所有托盘外框。只有服务端达到 30/30 并逐文件核验
`reviewed=true, source=human` 后，才能从同一批 tray 真值建立独立四点 ROI 队列。

原始库补样完成托盘复核后，不重新选样，也不伪造普通 ROI 计划。使用原始计划的完整 SHA 和已复核
托盘队列建立同批独立 ROI 队列；入口会再次验证原始计划绑定的既有 manifest/保护集、全部
photo/task/SKU/SHA、源图/打包图、人工托盘框，并拒绝同一原始计划重复建立第二个 ROI 队列：

```powershell
B:\anaconda3\python.exe tools\vision-lab\work_area_roi_queue.py `
  --plan-receipt D:\CretasVisionLab\receipts\work-area-raw-tray-plan-<timestamp>.json `
  --plan-sha256 <完整 64 位 SHA256> `
  --reviewed-tray-queue D:\CretasVisionLab\tray-queues\tray-active-<timestamp> `
  --queue-parent D:\CretasVisionLab\tray-queues `
  --runtime-root D:\CretasVisionLab
```

托盘人工真值只包含能够可靠画出完整外框的真实托盘。画面边缘只露出局部、被裁切到无法确定完整
外框的托盘不得靠猜测补框；它也不会因此成为一个可用于 `outside_work_area` 召回的样本。可完整
判断的台外托盘仍必须保留并按中心点分类。

若一个已完成批次只有一张 ROI 因工作区边界不可见而明确标为 `unjudgeable`，后续同 SKU 补样
通过人工 tray 和 ROI 审计后，不得改写原批次或删除该 unknown 证据。应建立新的有效集合，将其余
judgeable 行与补样复制到独立队列，并记录两个源 manifest、被替换 ROI 和新 ROI 的 SHA：

```powershell
B:\anaconda3\python.exe tools\vision-lab\work_area_effective_queue.py `
  --base-queue D:\CretasVisionLab\tray-queues\work-area-active-<base> `
  --replacement-queue D:\CretasVisionLab\tray-queues\work-area-active-<replacement> `
  --replace-stem <明确 unjudgeable 的 packed stem> `
  --queue-parent D:\CretasVisionLab\tray-queues `
  --runtime-root D:\CretasVisionLab
```

该入口只允许“一张人工 unjudgeable → 一张同 SKU、人工 judgeable”的替换，拒绝其他
unjudgeable、重复 photo/task/SHA/stem 和任何源文件漂移。派生队列只用于审计/实验，源队列与
原始 unknown sidecar 保持不变。

2026-08-12 的派生有效集合与前四轮组成 `96` 图/任务，正式审计为 inside `1596`、outside
`296`、unknown `0`。首轮四角热图容量拟合仅剩一个中心错分：照片
`085f6e10-42fb-4380-a8b1-b4c8992143ee` 的第 11 个托盘中心距人工多边形边界约 `0.12px`，预测
边界偏移不到 `1px` 即翻转 side。人工四点和托盘框保持不变；实验 v2 增加与生产
`tray_center_in_polygon` 契约一致的可微中心侧别裕量损失，不以矩形或启发式替代 ROI。

v2 容量拟合通过：平均/最差 IoU `0.9957/0.9795`、中心错分 `0`、inside/outside 最低召回
`1.0`、非法多边形 `0`；回执 `work-area-roi-corner-fit-20260812T031109570146Z.json`，SHA256
`9c9b74ab5a2c00860b2ada78a3dc6d882fbb43010593bed5d5858024ac30ff26`。但独立任务级 8 折仍失败：
平均/最差 IoU `0.8185/0`、中心错分 `129`、最差中心准确率 `0.0526`、inside/outside 最低召回
均为 `0`、非法多边形 `1`；回执 `work-area-roi-corner-experiment-20260812T035652390852Z.json`，
SHA256 `fad43b1f89b598278a2df833416ca13c1649dac5ae758a7aea4bd9f6c8371088`。结论仍为
`insufficient_image_conditioned_roi_evidence`：不得运行完整 7+20、保存权重、恢复旧 YOLO 或部署；
下一步应回到图像条件 ROI 方法/数据覆盖设计，而不是继续盲目调同一损失权重。

对上述 v2 8 折进行独立 SHA 绑定的几何失败审计，记录到 `20` 次单角点大偏移、`10` 次预测
面积塌缩、`5` 次相邻角点塌缩和 `1` 个非法多边形；0013/0014 分别有 `49/39` 个中心错分。
回执 `work-area-roi-failure-audit-20260812T042058836490Z.json` 的 SHA256 为
`1c75536bdaaab402aca28ccebc9367638b7bacbdb4aa82d17751f686815a3a37`。最差画面共同包含大面积
托盘遮挡、第二张金属台或输送设备及明显相机视角变化，说明主失败是角点身份和目标工作台实例
选择，不是普通像素误差或单一 SKU 外观问题。

为验证全局台面实例监督，新增的离线多任务模型只从同一人工四点真值栅格化训练辅助掩码，最终
仍输出四点多边形；它不使用检测框、粗矩形或启发式 ROI。结合固定中心侧别裕量后，96 图容量
拟合通过：平均/最差 IoU `0.9947/0.9839`、中心错分 `0`、inside/outside 最低召回 `1.0`，回执
`work-area-roi-multitask-fit-20260812T043716860546Z.json`（SHA256
`a768e0737833c8c1e2ad39eee381e4d540f4421f2457c724c07fb11174372710`）。但独立 8 折反而退化为
平均/最差 IoU `0.7886/0`、中心错分 `176`、inside/outside 最低召回 `0`、非法多边形 `8`；
0014 单独产生 `92` 个中心错分和 `5` 个非法多边形。CV 回执
`work-area-roi-multitask-experiment-20260812T052238260911Z.json`（SHA256
`ea286a49c8fd4c412974dc38352428ee6d725b57da18ef1817bc4d920d5909ac`），失败审计回执
`work-area-roi-failure-audit-20260812T052250532016Z.json`（SHA256
`252a3299dec7581471c9b1fda5a063083f6faad7884d55508e4abffe75d681dd`）。因此掩码辅助路线停止，不再
追加训练实验；下一步必须改变实例/角点表达或取得独立相机/台面覆盖证据，不能继续调当前损失。

新增人工轮次必须先运行只读计划器。推荐第二轮新增 `24` 张（四个现有 SKU 各 `6` 张），使累计
ROI 达到 `32` 张；这是下一轮数据收集量，不是生产充分性声明。计划器逐一验证候选源图/打包图
SHA 和 tray 人工真值，按 photo/task 排除当前 ROI 与旧 label MARK，按 ID/task/SHA 及 pHash
Hamming 距离 `<=10` 排除保护集，队列内保持任务唯一并做 pHash 多样化。它只写 receipt，不建
队列或 MARK：

```powershell
B:\anaconda3\python.exe tools\vision-lab\work_area_roi_plan.py `
  --dataset-manifest D:\CretasVisionLab\datasets\tray-332909713eaf\manifest.json `
  --current-queue D:\CretasVisionLab\tray-queues\tray-active-<round-1> `
  --current-queue D:\CretasVisionLab\tray-queues\work-area-active-<round-2> `
  --old-mark-manifest D:\CretasVisionLab\queues\label-active-20260810T171817Z\manifest.json `
  --protected-holdout D:\CretasVisionLab\evaluation\protected-holdout.json `
  --runtime-root D:\CretasVisionLab `
  --count 24
```

多轮之后必须重复传入每个已完成队列，使 photo/task 与 pHash 排除覆盖全部人工历史。若交叉验证
已定位困难图，可重复传 `--focus-photo-id <photo-id>`，只用困难图 pHash 邻域对候选进行标注排序；
该排序不是 ROI 真值、不是生产启发式，也不会生成伪多边形。

必须先向操作员报告 receipt 内的完整 24 行 task/SKU、SHA/ID、pHash 和排除距离；只有随后明确
继续时才根据同一 receipt 的 SHA 绑定结果建立新的独立 `work-area-human/` 队列。

收到继续指令后，用计划 receipt 的完整 SHA 建队列；构建器会再次核对计划绑定的 dataset、当前
ROI、旧 MARK 与保护集 manifest，并逐张复核源图/打包图 SHA 和人工 tray 标注。它只复制只读
上下文，空的 `work-area-human/` 是唯一后续写入目录：

```powershell
B:\anaconda3\python.exe tools\vision-lab\work_area_roi_queue.py `
  --plan-receipt D:\CretasVisionLab\receipts\work-area-roi-plan-<timestamp>.json `
  --plan-sha256 <完整 64 位 SHA256> `
  --queue-parent D:\CretasVisionLab\tray-queues `
  --runtime-root D:\CretasVisionLab
```

构建完成后先核对 build receipt、`queue_count/task_count`、四个 SKU 数量、24 份图片和 24 份
`annotations-human`，确认 `work-area-human` 为 0，再将 8774 切换到新队列。不得覆盖上一轮队列。

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
同时满足：台内主缺标召回/覆盖和误报门禁、台外缺标不相对生产回退、unknown 为 0、整体缺陷
召回不回退、正常图误报改善、延迟合格、PT/ONNX 一致性不差于生产模型。台外根因样本
`df1f6029-389d-45b5-995e-be19b2f5b943` 必须单列身份、tray 覆盖和命中结果；它不再冒充台内主告警，
但也不得相对生产结果回退。任一项失败都只写淘汰回执，不修改生产模型。

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
