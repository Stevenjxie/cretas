import * as fs from 'fs';
import * as path from 'path';

/**
 * 闸：收货凭证必须在 **confirmReceive 之前** 上传。
 *
 * 2026-08-16 端到端走查实测撞出来的：后端 `PurchaseServiceImpl` 在 `confirmReceive` 里
 * **无条件**要求该入库单已有 `PURCHASE_RECEIPT` 附件，否则
 *   409「确认收货前必须上传供应商供货单或收货凭证」(`PURCHASE_RECEIPT_ATTACHMENT_REQUIRED`)
 * 该闸 2026-07-22 (#1577) 加入，**没有工厂级开关**。
 *
 * 而本屏自 2026-05-17 (#794) 起把照片上传排在 confirm **之后**：
 *   1. createReceive → 2. confirmReceive → 3. 抄码日志 → 4. 上传照片
 * ⇒ 第 2 步必然 409，抛异常后第 4 步根本执行不到，照片永远传不上去。
 * **RN 扫码入库因此 100% 失败，持续 25 天** —— 没人发现是因为这条链在生产上没人走
 * （F006 在 2026-08-15 之前一张采购单都没有）。
 *
 * ⚠️ web-admin 一直是对的（附件数为 0 时禁用「确认收货入库」按钮）——
 * 又一次「同一条规则两处实现，只漏了 RN 那处」。
 *
 * 守三件事：
 *   ① 上传出现在 confirmReceive **之前**（顺序，不只是「有调用」）
 *   ② 一张都没传成功时不去 confirm（否则必然 409）
 *   ③ 没拍照时在提交前就拦住，而不是让人填完再被拒
 *
 * ⚠️ 剥注释后再断言 —— 上面这段说明里就写着这些标识符。
 */
describe('闸: 收货凭证必须在 confirm 之前上传', () => {
  const SRC = path.join(__dirname, '..', '..', '..');
  const SCREEN = 'screens/warehouse/inbound/WHReceiptCreateScreen.tsx';

  const code: string = fs
    .readFileSync(path.join(SRC, SCREEN), 'utf-8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/.*/g, ' ');

  const UPLOAD = 'attachmentApi.uploadAndRegister';
  const CONFIRM = 'purchaseApiClient.confirmReceive';
  const CREATE = 'purchaseApiClient.createReceive';

  it('仪器自检: 三个调用都还在这一屏里', () => {
    // 任何一个不在了, 下面的「谁在前」就没有意义(会变成恒真式)
    expect(code).toContain(CREATE);
    expect(code).toContain(UPLOAD);
    expect(code).toContain(CONFIRM);
    expect(code.split(UPLOAD).length - 1).toBe(1);   // 只该有一处上传, 别改成两处都传
  });

  it('🔴 上传在 confirm 之前, 且在 create 之后(附件要挂在入库单 id 上)', () => {
    const iCreate = code.indexOf(CREATE);
    const iUpload = code.indexOf(UPLOAD);
    const iConfirm = code.indexOf(CONFIRM);

    expect(iCreate).toBeGreaterThan(-1);
    expect(iUpload).toBeGreaterThan(iCreate);
    expect(iConfirm).toBeGreaterThan(iUpload);
  });

  it('🔴 一张都没传成功时不去 confirm', () => {
    // 不判断具体写法, 只要求「零成功」这个条件出现在 confirm 之前
    const iGuard = code.indexOf('photosUploaded === 0');
    const iConfirm = code.indexOf(CONFIRM);
    expect(iGuard).toBeGreaterThan(-1);
    expect(iConfirm).toBeGreaterThan(iGuard);
  });

  it('没拍照时在提交前就拦住, 而不是让人填完再被 409 拒', () => {
    expect(code).toMatch(/photos\.length\s*===\s*0/);
  });
});
