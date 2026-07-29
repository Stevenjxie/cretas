-- Label QC: 保存 AI 初筛的结构化明细 (盒子框 + 白标/彩标框)。
--
-- 背景: Python 侧 YOLO 初筛除了给出"哪个盒子疑似缺标"的候选之外, 还知道每个盒子
-- 里实际识别到了哪些标签及其坐标。此前 Java 只解析 candidates/verdict/model/
-- promptVersion, 这块明细被整体丢弃, 导致人工复核时只能看到一个盒子框, 看不到
-- "白标在这里、彩标在这里、缺的那个位置是空的"。
--
-- 存原始 JSON 而不是拆表: 这是模型输出的诊断快照, 不参与任何查询/连接/聚合,
-- 只在复核台整体读出来渲染。拆表会引入没有查询需求的三张子表和一堆同步逻辑。
ALTER TABLE label_qc_photos
    ADD COLUMN IF NOT EXISTS screening_detail TEXT;

COMMENT ON COLUMN label_qc_photos.screening_detail IS
    'AI 初筛结构化明细 JSON (托盘框 + 每个托盘内识别到的白标/彩标框及置信度)；仅供复核台渲染与后续训练分析，不参与查询。';
