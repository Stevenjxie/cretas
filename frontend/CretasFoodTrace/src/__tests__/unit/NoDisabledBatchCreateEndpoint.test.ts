import * as fs from 'fs';
import * as path from 'path';

/**
 * 棘轮：没有屏可以再调 `materialBatchApiClient.createBatch`。
 *
 * `POST /material-batches` 在后端标着 `@Deprecated`，直接抛
 * 409「普通批次页面已关闭无来源入库与续入」，hint 指向
 * 「仓储待收货 / 客供料 / 调拨 / 退货 / 盘点 / 受控调整；期初建账使用独立入口」。
 *
 * 2026-08-15 实测：`MaterialReceiptScreen` / `MaterialReceiptAIScreen` 都在调它，
 * 而 AI 那个是**用户点得到的**（生产 Tab → ProcessingDashboard → 批次管理 →「AI智能入库」）。
 * 两个屏已删、入口已改。这条闸防止它们以任何形式回来。
 *
 * ⚠️ 自保：先断言扫到足够多的文件 —— 「一个都没扫到」是这类闸最像「一切正常」的坏法。
 */
describe('棘轮: 不许再调已停用的 createBatch', () => {
  const SRC = path.join(__dirname, '..', '..');

  const collect = (dir: string, acc: string[] = []): string[] => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name);
      if (e.isDirectory()) {
        if (e.name === '__tests__' || e.name === 'node_modules') continue;
        collect(full, acc);
      } else if (e.name.endsWith('.ts') || e.name.endsWith('.tsx')) {
        acc.push(full);
      }
    }
    return acc;
  };

  it('screens/ 与 components/ 里没有 createBatch 调用', () => {
    const files = collect(SRC).filter(
      (f) => f.includes(`${path.sep}screens${path.sep}`) || f.includes(`${path.sep}components${path.sep}`),
    );

    // 仪器自检：扫不到文件时下面的断言恒真
    expect(files.length).toBeGreaterThan(200);

    /**
     * 已知欠账 —— 本轮之外, 待 owner 拍板后处理。列在这里是为了让闸能挡住**新增**,
     * 而不是假装它们不存在。
     *
     *  · CreateBatchScreen        —— **可达**: 生产看板那个「原料入库」主按钮就是它
     *    (ProcessingDashboard:275) + WorkTypeListScreen:161。新建走 createBatch → 409;
     *    编辑走 updateBatch, 是好的。
     *  · WHInboundCreateScreen    —— 注册在 WHInboundStackNavigator 里, 但**没有任何地方跳它**。
     */
    const KNOWN_DEBT = [
      `screens${path.sep}processing${path.sep}CreateBatchScreen.tsx`,
      `screens${path.sep}warehouse${path.sep}inbound${path.sep}WHInboundCreateScreen.tsx`,
    ];

    const offenders: string[] = [];
    for (const f of files) {
      const src = fs.readFileSync(f, 'utf-8');
      // 剥注释：闸不该把自己的说明文字也数进去
      const code = src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/.*/g, ' ');
      if (/materialBatchApiClient\s*\.\s*createBatch\s*\(/.test(code)) {
        const rel = path.relative(SRC, f);
        if (!KNOWN_DEBT.includes(rel)) offenders.push(rel);
      }
    }

    expect(offenders).toEqual([]);

    // 欠账清单必须**准确** —— 少一条说明有人删了屏没更新这里,
    // 多一条说明它其实已经修好了, 两种都该让闸红。
    const stillOwed = KNOWN_DEBT.filter((d) =>
      fs.existsSync(path.join(SRC, d))
      && /materialBatchApiClient\s*\.\s*createBatch\s*\(/.test(fs.readFileSync(path.join(SRC, d), 'utf-8')),
    );
    expect(stillOwed).toEqual(KNOWN_DEBT);
  });

  it('已删的两个入库屏不存在', () => {
    for (const gone of ['screens/processing/MaterialReceiptScreen.tsx', 'screens/processing/MaterialReceiptAIScreen.tsx']) {
      expect(fs.existsSync(path.join(SRC, gone))).toBe(false);
    }
  });
});
