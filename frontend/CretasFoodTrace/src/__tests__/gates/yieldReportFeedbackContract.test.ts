/**
 * 闸 —— 报工页的两条「用户会照着做事」的判断句必须站得住。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 真机走查实测)</h2>
 *
 * <h3>(a) 成功是唯一没有反馈的分支 ⇒ 同一段工时被记了两遍</h3>
 * `handleSubmitSegment` 里, 校验不过 / 提交失败 / 网络异常 每一条路径都 `appAlert`,
 * **唯独成功只是静默清空表单**。已报时段列表虽然会多一行, 但那行在屏幕外看不到。
 * 实测: 我以为没提交又点了一次, 库里出现两条一模一样的 SEGMENT ——
 *
 * ```
 * 23824 SEGMENT  00:31:14
 * 23825 SEGMENT  00:31:43   ← 同一时段(07:00~15:00, 1人), 后端照单全收
 * ```
 *
 * 工人手一抖, 这一段的工时和人工成本就翻倍。
 * ⚠️ `submitWithIdem` 的幂等键**每次成功后就轮换** —— 它防的是"网络重试", 不是"内容重复"。
 *
 * <h3>(b) 界面说的「损耗」和后端量的「损耗」不是同一件事</h3>
 * 界面(完工出成页)原文案: `损耗 (自动) = 投入 2kg − 产出 1kg = 1kg`
 * 后端提交后返回: `物料平衡偏差 50% (投入 2, 产出 1, 副产物 0, 损耗 0, 留样 0) — 请核对`
 *
 * 查库确证 (软删记录仍在): INPUT/OUTPUT 两条报工的 `waste_quantity` **都是 NULL** ——
 * 后端根本没把差额记成损耗, 它把这个缺口当成**未说明的物料平衡偏差**。
 * 而源码里那句注释「损耗由后端从 投入−产出 自动计算」**是假的**, 正是它养出了误导文案。
 *
 * ⇒ 后端不自动归类是**对的**(50% 的缺口自动判成损耗更糟), 所以改的是文案。
 *
 * <h2>口径</h2>
 * 这道闸扫的是**源码文本**, 它证明不了"用户真的看到了" —— 那要真机走一遍。
 * 它守的是**这两处不被人不小心改回去**。
 */
import fs from 'fs';
import path from 'path';

const SCREEN = path.resolve(
  __dirname,
  '../../screens/processing/YieldStepReportScreen.tsx',
);

function src(): string {
  return fs.readFileSync(SCREEN, 'utf8');
}

/** 剥掉 `//` 与 `/* *\/` 注释 —— 注释里提到某个文案不等于它还会渲染出去。 */
function stripComments(s: string): string {
  return s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

/** 取 handleSubmitSegment 的函数体 (到下一个 `const handle` 为止)。 */
function submitSegmentBody(): string {
  const s = src();
  const start = s.indexOf('const handleSubmitSegment');
  expect(start).toBeGreaterThan(-1);
  const rest = s.slice(start + 10);
  const next = rest.indexOf('\n  const handle');
  return next > -1 ? rest.slice(0, next) : rest;
}

describe('报工页反馈与口径契约', () => {
  it('文件读得到且拿得到 handleSubmitSegment 函数体 (阳性对照)', () => {
    // 🔴 没有这一条, 下面所有 toContain 在读不到文件时会一起变成恒假/恒真, 而闸看起来仍然正常。
    expect(fs.existsSync(SCREEN)).toBe(true);
    const body = submitSegmentBody();
    expect(body.length).toBeGreaterThan(500);
    // 形状对照: 函数体里必须真的有提交调用, 否则我截到的是别的东西
    expect(body).toContain('submitWithIdem');
  });

  it('(a1) 提交本段成功后必须给反馈 —— 成功不能是唯一静默的分支', () => {
    const body = submitSegmentBody();
    // 成功路径 = refetchYield 之后、catch 之前那一段
    const afterRefetch = body.slice(body.indexOf('await refetchYield()'));
    const beforeCatch = afterRefetch.slice(0, afterRefetch.indexOf('} catch'));
    expect(beforeCatch).toContain('本段已记录');
    // 反馈里要带上记了什么(时间/人数), 否则工人分不清记的是哪一段
    expect(beforeCatch).toContain('seg.startTime');
    expect(beforeCatch).toContain('seg.headcount');
  });

  it('(a2) 同一时段重复提交要先问一句, 不能静默接受', () => {
    const body = submitSegmentBody();
    // 按 (起, 止, 人数) 三元组比已报时段
    expect(body).toContain('laborSegments');
    expect(body).toContain('s.startTime');
    expect(body).toContain('s.endTime');
    expect(body).toContain('s.headcount');
    expect(body).toContain('这一段好像已经记过了');
    // 必须留一条"确实又干了一段"的出路, 否则合法的重复时段会被彻底堵死
    expect(body).toContain('仍要再记一段');
  });

  it('(a3) force 参数必须严格比 true —— onPress 会把事件对象传进来', () => {
    // ⚠️ 写成 `if (forceDuplicate)` 的话, 每次点按钮都会被当成"已确认重复",
    //    守卫直接失效 —— 而它**看起来**仍然存在。
    const body = submitSegmentBody();
    expect(body).toContain('=== true');
  });

  it('(b) 差额文案不能再叫「损耗」—— 后端把它记成未说明的物料平衡偏差', () => {
    // ⚠️ 必须先剥注释再断言"旧文案已消失": 第一版没剥, 结果闸红在**我自己写的注释**上 ——
    //    那条注释里引用了旧文案作为记录。这是本仓形态 A⁗「grep 把 docstring 也数进去」
    //    的又一例; 而注释**应该**保留旧文案, 所以该改的是闸不是注释。
    const s = stripComments(src());
    // 阴性对照: 那两句原文案必须从**会渲染出去的串**里消失
    expect(s).not.toContain('损耗 (自动) =');
    expect(s).not.toContain('损耗 = 已投入');
    // 阳性: 换成如实描述 + 告诉工人怎么说明去向
    expect(s).toContain('未说明去向');
    expect(s).toContain('物料平衡偏差');
  });
});
