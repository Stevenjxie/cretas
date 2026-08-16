/**
 * B-5 闸 · 「接下来可以问什么」在**这个前端**真的渲染得出来。
 *
 * ## 他哪句话要求了这个
 *
 * 「就可能会问，**为什么今天卖的这么少啊**？」+ 目标「让老板打开之后能自己
 * 做出一个决定」。⇒ 下一步该问什么，比再多给一个数更接近「决定」。
 *
 * ## ⛔ 卡里那条前提是错的，先订正
 *
 * 卡说 `suggested_followups` 「**同样零消费端**」。实测不是：
 *   · web-admin        ✅ 消费（`restaurant-chat.ts:32/75`，渲染成「继续追问」）
 *   · mobile-rest-ai   ❌ 不消费（`src/` 里 followups 命中 0）
 *   · 阳性对照：同目录 `message` 命中 84 / `charts` 7 ⇒ 搜对了地方
 * ⇒ 精确说法是「**老板那条路上不显示**」，⛔ 不是「没人消费」。
 *
 * ## 这条闸量什么
 *
 * ⛔ 不量「api.ts 里有没有 normalizeFollowups」（那是读代码）。
 * 量的是：喂一个**真实形状**的后端响应，页面上能不能点出那几个追问。
 * 三种条目形状（裸串 / {question} / {label}）都要出得来 —— 只认一种键
 * 会静默丢掉另外两种。
 *
 * ## 阳性/阴性对照（硬约束 9）
 *
 * · 阳性：后端给了 3 条 ⇒ 页面上必须**恰好**出现 3 个按钮
 * · 阴性：后端一条都不给 ⇒ 页面上**一个都不许有**（否则是我写死的假芯片）
 */
import { chromium } from 'playwright';

const URL = 'http://localhost:5211/mobile-ai/rest/followup-preview.html';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 420, height: 900 } });
await page.goto(URL, { waitUntil: 'networkidle' });

const got = await page.evaluate(() => window.__probe);
console.log('[读数]', JSON.stringify(got, null, 0));

await browser.close();

const expected = ['为什么今天卖的这么少', '哪道菜拖了后腿', '跟上周比差在哪'];
const ok =
  got.withFollowups.length === 3 &&
  expected.every((q) => got.withFollowups.includes(q)) &&
  got.withoutFollowups.length === 0;

console.log('='.repeat(70));
console.log(`[阳性对照] 给 3 条 -> 页面出 ${got.withFollowups.length} 个按钮`);
console.log(`[阴性对照] 一条不给 -> 页面出 ${got.withoutFollowups.length} 个按钮（必须 0）`);
if (got.withoutFollowups.length !== 0) {
  console.log('⛔ 后端没给却出了芯片 ⇒ 那是写死的假芯片，主读数无意义。');
  process.exit(2);
}
if (!ok) {
  console.log('🔴 三种条目形状没有全部渲染出来:', got.withFollowups);
  process.exit(1);
}
console.log('✅ 三种形状（裸串 / {question} / {label}）都渲染出来了');
