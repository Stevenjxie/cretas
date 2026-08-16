/**
 * B-1 载体布局闸 —— 断言**渲染后**四列都在 420px 手机屏内看得到。
 *
 * ## 为什么必须单独有这一条
 *
 * `b1_daily_table_gate.py` 读的是 **markdown 文本**：四列齐全、分段正确、
 * 限定语都在 —— 10/10 全绿。DOM 里 `th` 也确实是 4 个。
 * **而在 420px 宽的手机上，最右边的「毛利」列被挤出屏外，要横滑才看得到。**
 * 实测：菜品列 212px（全表 55%）、表格实际宽 485px、可见 385px。
 *
 * ⇒ 文本闸、DOM 闸、CI、md5、prod 真跑 —— 五样全绿，唯独看不见。
 *   本仓前科同形：8 张 markdown 表格上线后并成一坨，四个 PR + 两轮 85/85 + CI 全绿。
 *
 * ## 它量的是什么
 *
 * ⛔ 不量「有没有 4 个 th」（那是文本闸已经管的）。
 * 量的是**最后一列的右边界有没有超出可视区** —— 也就是老板不横滑能不能看到毛利。
 *
 * ## 阳性对照（硬约束 9）
 *
 * 主断言是阴性的（「没有超出」）。⇒ 同一次跑里必须证明这个测量**能**发现超出：
 * 把容器压到 200px，断言它**报出超出**。报不出来说明测量器坏了，主读数无意义。
 */
import { chromium } from 'playwright';

const URL = 'http://localhost:5199/mobile-ai/rest/preview-answer.html';
const PHONE_WIDTH = 420;   // 常见手机逻辑宽度；iPhone SE 375 / 15 Pro 393 / Plus 428

async function measure(page, frameWidth) {
  await page.evaluate((w) => {
    document.querySelector('.frame').style.maxWidth = w + 'px';
  }, frameWidth);
  await page.waitForTimeout(60);
  return page.evaluate(() => {
    const t = document.querySelector('#out table');
    const ths = [...t.querySelectorAll('th')];
    const last = ths[ths.length - 1];
    const tRect = t.getBoundingClientRect();
    const lastRect = last.getBoundingClientRect();
    return {
      列数: ths.length,
      最后一列: last.textContent.trim(),
      可见宽: Math.round(t.clientWidth),
      表格实际宽: Math.round(t.scrollWidth),
      // 超出量 > 1px 才算（亚像素抖动不算）
      超出: Math.round(lastRect.right - tRect.right),
      各列宽: ths.map((th) => ({ 列: th.textContent.trim(), 宽: Math.round(th.getBoundingClientRect().width) })),
    };
  });
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 900, height: 1400 } });
await page.goto(URL, { waitUntil: 'networkidle' });

const main = await measure(page, PHONE_WIDTH);
console.log(`[主读数 ${PHONE_WIDTH}px]`, JSON.stringify(main, null, 0));
for (const c of main.各列宽) console.log(`   ${c.列}: ${c.宽}px`);

// 阳性对照先跑 —— 测量器活着, 主读数才有意义
const control = await measure(page, 200);
console.log(`\n[阳性对照 200px] 超出 ${control.超出}px —— 能测出超出: ${control.超出 > 1}`);
if (!(control.超出 > 1)) {
  console.log('⛔ 把容器压到 200px 都测不出超出 ⇒ 测量器坏了, 主读数作废。');
  await browser.close();
  process.exit(2);
}

await browser.close();
const ok = main.列数 === 4 && main.超出 <= 1;
console.log('='.repeat(70));
if (!ok) {
  console.log(`🔴 ${PHONE_WIDTH}px 下「${main.最后一列}」列超出 ${main.超出}px —— 老板不横滑看不到它。`);
  process.exit(1);
}
console.log(`✅ ${PHONE_WIDTH}px 下四列全部可见，无需横滑`);
