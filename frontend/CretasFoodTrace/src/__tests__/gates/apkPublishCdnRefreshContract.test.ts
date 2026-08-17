/**
 * 闸 —— APK 发布必须刷**版本化文件名**的 CDN 缓存, 而不只是 latest 别名。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-17, 同一晚踩了两次)</h2>
 * `deploy-apk.sh` 原来的 CDN 刷新步骤有两个洞:
 *
 * 1. **只刷 `cretas-latest.apk` 别名**, 注释写着「版本化文件名是新对象, 无需刷新」。
 *    这句话在**同版本号重发**时不成立 —— 那是同名覆盖, CDN 边缘照旧返回旧对象。
 *    实测: 源站 OSS 已是新包(122302626 字节), 而 CDN 仍返回旧包(122521709 字节)。
 * 2. **只在 `aliyun` CLI 存在时刷**, 没装就整段跳过。而发布机上就没装 ⇒ 从来没刷过。
 *
 * 后果不是"慢一点生效", 是**用户下到的仍然是旧包** —— 那晚旧包一启动就崩,
 * 所以「换掉了坏包」这件事本身会变成假的。
 *
 * ⚠️ 真正救场的是紧随其后的 Content-Length 比对闸: 它两次都正确地报红。
 *    这道闸不替代它 —— 这道闸管"别让它红", 那道闸管"红了别放过"。
 */
import fs from 'fs';
import path from 'path';

const REPO = path.resolve(__dirname, '../../../../../');
const DEPLOY_APK = path.join(REPO, 'scripts/deploy/deploy-apk.sh');
const REFRESH_PY = path.join(REPO, 'scripts/deploy/cdn-refresh.py');

/** 只保留会被执行的行 —— 注释里提到不算数。 */
function codeLines(file: string): string[] {
  return fs
    .readFileSync(file, 'utf8')
    .split('\n')
    .map((l) => l.replace(/#.*$/, '').trim())
    .filter(Boolean);
}

describe('APK 发布 CDN 刷新契约', () => {
  it('两个脚本都要在 (阳性对照 —— 读不到文件时下面的断言会变成恒真)', () => {
    expect(fs.existsSync(DEPLOY_APK)).toBe(true);
    expect(fs.existsSync(REFRESH_PY)).toBe(true);
    // 再证明确实读到了内容, 而不是读到空文件
    expect(codeLines(DEPLOY_APK).length).toBeGreaterThan(100);
  });

  it('必须刷版本化文件名 ($CDN_URL), 不能只刷 latest 别名', () => {
    // 这一条就是那晚的洞: 同版本号重发 = 同名覆盖 = CDN 一定要刷。
    // ⚠️ 第一版这条写成「同一行里同时出现 CDN_URL 和 refresh 调用」, 结果对着**正确的**
    //    实现报红 —— 因为实现是 `for _u in "$CDN_URL" "…/$LATEST_ALIAS"` 循环, 两者不在同一行。
    //    ⇒ 闸要钉的是**刷新目标里包含版本化 URL**这件事, 不是它长成哪种语法。
    const code = codeLines(DEPLOY_APK);
    // 形态 A: 刷新目标列表里同时有版本化 URL 和别名 (现在的 for 循环写法)
    const targetsBoth = code.some((l) => l.includes('CDN_URL') && l.includes('LATEST_ALIAS'));
    // 形态 B: 直接把 $CDN_URL 传给刷新调用 (两次独立调用的写法)
    const passesVersionedUrl = code.some(
      (l) => /(refresh_cdn_path|RefreshObjectCaches)/.test(l) && /\$\{?CDN_URL\}?/.test(l),
    );
    // ⚠️ 这条 OR 的第二支第一版写成「refresh 调用 ≥2 次 **且** 任意一行提到 CDN_URL」——
    //    而 `CDN_URL=...` 赋值行永远在, 于是它退化成恒真式: 把刷新改回「只刷 latest」
    //    之后闸**纹丝不动**。是变异对照把它抓出来的(变异已确认落进代码: 同行计数 1→0)。
    //    ⇒ 判据必须落在「$CDN_URL 有没有被**传给刷新**」上, 不是「它在文件里出现过」。
    expect(targetsBoth || passesVersionedUrl).toBe(true);
  });

  it('没有 aliyun CLI 时必须有退路, 不能整段跳过', () => {
    // 发布机上就没装 aliyun CLI —— 「没装就跳过」等于这一步从来没跑过。
    const src = fs.readFileSync(DEPLOY_APK, 'utf8');
    expect(src).toContain('cdn-refresh.py');
  });

  it('cdn-refresh.py 必须是三态 (rc=2「这次没量到」要与 rc=0 分开)', () => {
    // 本仓硬约束 4: 两态会把「没量到」折叠进「没问题」。
    // 实测 rc=2 可达: USERPROFILE 指向不存在的目录 → CDN=NO_MEASUREMENT rc=2。
    const src = fs.readFileSync(REFRESH_PY, 'utf8');
    expect(src).toContain('return 2');
    expect(src).toContain('NO_MEASUREMENT');
    // 阳性对照: 成功路径要断言返回体里真有 RefreshTaskId,
    // 否则「HTTP 200」会被当成「刷了」。
    expect(src).toContain('RefreshTaskId');
  });
});
