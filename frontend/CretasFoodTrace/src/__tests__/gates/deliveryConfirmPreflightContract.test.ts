/**
 * 闸 —— 发货确认页必须**进屏就知道**批次分配了没有，而不是等后端 409。
 *
 * <h2>🔴 为什么有这道闸（2026-08-18 真机实测）</h2>
 * 仓管在 Mi 11 Pro 上点「确认并扣库存」，后端回：
 *
 * ```
 * 409 发货行 210（产品：叮咚好食光卤猪蹄(去大骨) 200g）未完成批次分配，无法确认发货
 * ```
 *
 * 而**这个事实在进屏那一刻就在手里** —— `getDeliveryDetail` 每一行都返回
 * `finishedGoodsBatchId`，未分配时为 null。本屏只读了 `deliveredQuantity` / `unit`，
 * 没读它，于是「该事先拦住的」变成了「提交后才报错」。
 *
 * <h2>⚠️ 顺带记一条差点写出去的错误结论</h2>
 * 第一次排查时我截图**晚了 10 秒**，红色 Snackbar 已经自动消失，
 * 我据此差点写成「前端吞掉了 409」——**那是错的**：
 * `submitConfirm` 的 catch 一直原样透传后端 message（源码注释里甚至引用了这条 409）。
 * 是**我的仪器错过了它**，不是 App 吞了。
 * ⇒ 判「界面有没有反馈」要在动作后 1~2 秒内取样；Snackbar 几秒就自动消失。
 *
 * <h2>口径</h2>
 * 这道闸扫源码结构（读没读那个字段 / 有没有拦 / 有没有把原因显示出来），
 * 它证明不了「用户真的看到了」——那要真机走一遍。
 */
import fs from 'fs';
import path from 'path';

const SCREEN = path.resolve(
  __dirname,
  '../../screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx',
);

function src(): string {
  return fs.readFileSync(SCREEN, 'utf8');
}

/** 剥注释 —— 注释里提到某个字段不等于代码真的读了它。 */
function code(): string {
  return src()
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '');
}

describe('发货确认前置校验契约', () => {
  it('阳性对照: 文件读得到, 且确实是那一屏', () => {
    // 没有这一条, 下面的 toContain 在读不到文件时会一起失效, 而闸看起来仍然正常。
    expect(fs.existsSync(SCREEN)).toBe(true);
    const s = code();
    expect(s.length).toBeGreaterThan(2000);
    expect(s).toContain('confirmDelivery');   // 形状对照: 这屏确实在做发货确认
  });

  it('必须读详情返回的 finishedGoodsBatchId —— 事实进屏就在手里', () => {
    expect(code()).toContain('finishedGoodsBatchId');
  });

  it('未分配的行必须挡住提交, 不能让人点了才被后端 409 挡回来', () => {
    const s = code();
    expect(s).toContain('unallocated');
    // 提交判据里必须真的用上它 —— 只算出来不用等于没拦
    const canSubmitLine = s.split('\n').find((l) => l.includes('const canSubmit'));
    expect(canSubmitLine).toBeDefined();
    expect(canSubmitLine).toContain('unallocated');
  });

  it('必须把「为什么点不动」显示出来 —— 只 disable 按钮是另一种点不动', () => {
    const s = code();
    // 🔴 这一条才是本闸的重点: 光挡住而不说原因, 用户仍然在问「这个为什么点不动」
    expect(s).toContain('未分配批次');
    expect(s).toContain('分配批次');          // 指出该去哪做
    expect(s).toContain('wh-confirm-unallocated');  // 有可定位的 testID
  });

  it('错误分支仍然原样透传后端 message（别在加前置校验时把它改坏）', () => {
    const s = code();
    // 阴性对照: 不许把后端原话替换成通用文案
    expect(s).not.toContain("'操作失败'");
    expect(s).toContain('data?.message');
  });
});
