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
 * 四种条目形状（裸串 / {question} / {label}旧协议 / {label,question}新协议）
 * 都要出得来 —— 只认一种键会静默丢掉另外几种。
 *
 * ## 2026-08-16 i3 回归 · 第二层守卫
 *
 * 后端把条目拆成 {label, question}（label 给人看的短词, question 是
 * 可独立发送的完整句 —— 裸词会撞服务端计划缓存, 见 fix-i3-report.md）。
 * `normalizeFollowups` 一度把两者塌成一个字符串, 结果 chip 上显示的是
 * 整句 question 而不是短词 label。⇒ 这条闸必须同时守两条腿, 缺一条都
 * 测不出这次回归:
 *   (a) chip **显示的文本** 是 label
 *   (b) 点击 chip **实际发送** 的值是 question
 * 只守 (a) 不守 (b)：可能显示对了但点击仍然发全句（撞缓存的那个缺陷复发）。
 * 只守 (b) 不守 (a)：可能发送对了但界面仍然显示整句（视觉回归复发）。
 *
 * ## 阳性/阴性对照（硬约束 9）
 *
 * · 阳性：后端给了 4 条 ⇒ 页面上必须**恰好**出现 4 个按钮
 * · 阴性：后端一条都不给 ⇒ 页面上**一个都不许有**（否则是我写死的假芯片）
 * · label≠question 的对照（第 4 条）：断言两者**不相等**——否则「显示的是
 *   label」这条断言可能只是巧合过（label 和 question 恰好长得一样）。
 */
import { chromium } from 'playwright';

const URL = 'http://localhost:5211/mobile-ai/rest/followup-preview.html';

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 420, height: 900 } });
await page.goto(URL, { waitUntil: 'networkidle' });

const probe = await page.evaluate(() => window.__probe);
console.log('[读数·结构化条目]', JSON.stringify(probe.withFollowups, null, 0));
console.log('[读数·显示文本]', JSON.stringify(probe.displayedText, null, 0));
console.log('[读数·阴性]', JSON.stringify(probe.withoutFollowups, null, 0));

let failed = false;
let instrumentDead = false;

// --- 阴性对照 (先跑, 见 B-1 闸同款先后顺序) ---
if (probe.withoutFollowups.length !== 0) {
  console.log('⛔ 后端没给却出了芯片 ⇒ 那是写死的假芯片，主读数无意义。');
  instrumentDead = true;
}

// --- 阳性对照: 恰好 4 条, 四种形状都归一化成功 ---
const expected = [
  { label: '为什么今天卖的这么少', question: '为什么今天卖的这么少' },
  { label: '哪道菜拖了后腿', question: '哪道菜拖了后腿' },
  { label: '跟上周比差在哪', question: '跟上周比差在哪' },
  { label: '本月', question: '本月哪个菜卖得好' },
];
if (probe.withFollowups.length !== 4) {
  console.log(`🔴 四种条目形状没有全部归一化成功: 期望 4 条, 实得 ${probe.withFollowups.length} 条`);
  failed = true;
} else {
  expected.forEach((exp, i) => {
    const got = probe.withFollowups[i];
    if (got.label !== exp.label || got.question !== exp.question) {
      console.log(`🔴 第 ${i} 条不匹配: 期望 ${JSON.stringify(exp)}, 实得 ${JSON.stringify(got)}`);
      failed = true;
    }
  });
}

// --- (a) chip 显示的文本是 label, 不是 question ---
const expectedDisplay = expected.map((e) => e.label);
const displayOk = JSON.stringify(probe.displayedText) === JSON.stringify(expectedDisplay);
if (!displayOk) {
  console.log(`🔴 chip 显示文本不是 label: 期望 ${JSON.stringify(expectedDisplay)}, 实得 ${JSON.stringify(probe.displayedText)}`);
  failed = true;
}

// --- 对照: 第 4 条 label ≠ question, 否则上一条断言可能是巧合过 ---
const fourth = expected[3];
if (fourth.label === fourth.question) {
  console.log('⛔ 第 4 条 label 与 question 相同 —— 显示断言测不出回归, 对照本身失效。');
  instrumentDead = true;
} else {
  console.log(`[对照] 第 4 条 label(${fourth.label}) ≠ question(${fourth.question}): 能测出「显示成 question」这类回归`);
}

// --- (b) 点击 chip 实际发送的值是 question, 不是 label ---
const sent = await page.evaluate((i) => window.__clickFollowupAndGetSent(i), 3);
console.log(`[读数·点击发送] 点第 4 个 chip(label=本月) -> 实际发送 "${sent}"`);
if (sent !== fourth.question) {
  console.log(`🔴 点击发送的不是 question: 期望 "${fourth.question}", 实得 "${sent}"`);
  failed = true;
}

// --- chip 不能被撑成近满宽的块 ---
// ⚠️ ①里四条 label 现在全是短词/短句, 天然不会撑爆 —— 用它们做宽度对照
// 是恒真式(测不出"没加 max-width"这类回归)。真正会撑爆的形状是③:
// 旧协议 {question} 没配 label 时, label 退回一整句长话。
const widths = await page.evaluate(() => {
  return [...document.querySelectorAll('#with .followup-chip')].map((b) => Math.round(b.getBoundingClientRect().width));
});
const stress = await page.evaluate(() => {
  const chip = document.querySelector('#stress .followup-chip');
  const unbounded = document.getElementById('stress-unbounded');
  return {
    chipWidth: chip ? Math.round(chip.getBoundingClientRect().width) : null,
    unboundedWidth: unbounded ? Math.round(unbounded.getBoundingClientRect().width) : null,
    text: chip ? chip.textContent : null,
  };
});
console.log(`[读数·chip 宽度①]`, JSON.stringify(widths));
console.log(`[读数·chip 宽度③压力]`, JSON.stringify(stress));
const FRAME_WIDTH = 420 - 32; // .frame 左右各 16px padding
const MAX_PILL_WIDTH = 225; // style.css max-width:220px + 1px 边框*2, 留 3px 容差

// 阳性对照: ③那句话本身必须长到「不加约束就会超宽」, 否则下面「chip 没超宽」
// 这条断言可能只是因为文本本来就短, 测量器根本没被逼出超宽的情况。
if (stress.unboundedWidth === null || stress.unboundedWidth <= MAX_PILL_WIDTH) {
  console.log(`⛔ ③压力文本无约束宽度只有 ${stress.unboundedWidth}px, 不够长, 测不出"没加 max-width"这类回归。`);
  instrumentDead = true;
} else {
  console.log(`[对照] ③压力文本无约束会有 ${stress.unboundedWidth}px (> ${MAX_PILL_WIDTH}px) —— 长度足以暴露"没加 max-width"`);
}

widths.forEach((w, i) => {
  if (w > MAX_PILL_WIDTH) {
    console.log(`🔴 第 ${i} 个 chip 宽 ${w}px > ${MAX_PILL_WIDTH}px —— 被撑成近满宽的块了(不再像药丸)`);
    failed = true;
  }
});
if (stress.chipWidth === null) {
  console.log('🔴 ③压力 chip 没渲染出来');
  failed = true;
} else if (stress.chipWidth > MAX_PILL_WIDTH) {
  console.log(`🔴 ③压力 chip 宽 ${stress.chipWidth}px > ${MAX_PILL_WIDTH}px —— 长句 label 撑爆了 chip(缺 max-width/ellipsis)`);
  failed = true;
} else if (stress.chipWidth >= FRAME_WIDTH - 4) {
  console.log(`🔴 ③压力 chip 宽 ${stress.chipWidth}px 已接近整行 ${FRAME_WIDTH}px —— 这正是修复要消灭的形状`);
  failed = true;
}

await browser.close();

console.log('='.repeat(70));
if (instrumentDead) {
  console.log('⛔ 仪器自身对照未通过 —— 主读数作废。');
  process.exit(2);
}
if (failed) {
  console.log('🔴 至少一条断言失败 —— 见上方 🔴 标记的行。');
  process.exit(1);
}
console.log('✅ 四种形状都归一化成功；chip 显示 label、点击发 question；chip 宽度未被撑成满宽块。');
