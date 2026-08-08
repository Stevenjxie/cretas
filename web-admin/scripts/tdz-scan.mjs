/**
 * 扫「同步回调里引用了后面才声明的块作用域变量」—— 运行期 TDZ ReferenceError。
 *
 * ## 为什么需要它: TypeScript 结构性地看不见这一类
 * `const x` 写在 `arr.forEach(() => { x[k] = v })` 之后时, 引用在闭包里,
 * 编译器假定闭包延后执行, 不报 TS2448。但 forEach 是**同步**的, 运行期直接抛
 * `ReferenceError: Cannot access 'x' before initialization`。
 *
 * 2026-08-08 真机事故: ProductProcessWorkflowEditor.vue 的 `packagingBindingsByOutput`
 * 正是这个形状, 异常被外层 catch 吞成一行 console.error ⇒ **整个 BOM 浮层加载全灭**,
 * 辅料/包材 cell 全空、hydrate 从不执行, 「改克数产生新工艺版本」在真机上恒定是断的。
 * 而既有单测读的是 .vue 的**源码文本**并用正则断言, 对运行期行为完全沉默。
 *
 * ## 判据按【行为】不按【词】
 * 「声明之前被引用」本身是合法的常见写法(事件处理器里写外面后声明的 let、
 * 函数参数遮蔽同名变量), 无差别报会淹死人、闸迟早被关掉。所以只报这一种形状:
 *   引用位于**同步迭代方法**(forEach/map/...)的回调里, 且该调用整体位于声明之前。
 * 这正是「一定会在声明之前执行」的那一类, 不掺任何猜测。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import ts from 'typescript';

/** 同步执行的数组迭代方法 —— 回调一定在本条语句内跑完。setTimeout/Promise.then 不在此列。 */
const SYNC_ITERATORS = new Set([
  'forEach', 'map', 'filter', 'reduce', 'reduceRight', 'some', 'every',
  'find', 'findIndex', 'findLast', 'findLastIndex', 'flatMap', 'sort',
]);

function collectFiles(root) {
  const out = [];
  (function walk(dir) {
    for (const name of readdirSync(dir)) {
      if (name === 'node_modules' || name === 'dist' || name === '.git') continue;
      const p = join(dir, name);
      if (statSync(p).isDirectory()) walk(p);
      else if (/\.(ts|vue)$/.test(name)) out.push(p);
    }
  })(root);
  return out;
}

function scriptOf(path) {
  const raw = readFileSync(path, 'utf-8');
  if (!path.endsWith('.vue')) return { text: raw, lineOffset: 0 };
  const m = raw.match(/<script[^>]*>/);
  if (!m) return null;
  const start = m.index + m[0].length;
  const end = raw.indexOf('</script>', start);
  if (end < 0) return null;
  // .vue 报行号要还原成**整个文件**的行号, 否则拿着报告去看代码会对不上。
  const lineOffset = raw.slice(0, start).split('\n').length - 1;
  return { text: raw.slice(start, end), lineOffset };
}

export function scanForSyncCallbackTdz(root) {
  const findings = [];
  for (const file of collectFiles(root)) {
    const src = scriptOf(file);
    if (!src) continue;
    const sf = ts.createSourceFile(file, src.text, ts.ScriptTarget.ES2022, true, ts.ScriptKind.TS);

    // 任何**绑定**了这个名字的地方都算一次: 变量声明、函数/箭头参数、catch 形参、解构。
    // 名字被绑定 >1 次 ⇒ 可能存在遮蔽 ⇒ 跳过(宁可漏报也不误报, 误报会让闸被关掉)。
    const bindCount = new Map();
    const bump = (name) => bindCount.set(name, (bindCount.get(name) || 0) + 1);
    const decls = [];
    // 标识符只遍历一遍并按名字归档 —— 否则「每个声明各扫一次全文」在本仓是 O(声明数×节点数),
    // 实测整仓 25 秒, 那种速度的闸迟早被人从测试集里摘掉。
    const idsByName = new Map();
    (function collect(node) {
      if (ts.isParameter(node) && ts.isIdentifier(node.name)) bump(node.name.text);
      if (ts.isBindingElement(node) && ts.isIdentifier(node.name)) bump(node.name.text);
      if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name)) {
        bump(node.name.text);
        const flags = ts.getCombinedNodeFlags(node);
        if ((flags & ts.NodeFlags.Let) || (flags & ts.NodeFlags.Const)) {
          decls.push({ name: node.name.text, pos: node.name.getStart(sf), node });
        }
      }
      if (ts.isIdentifier(node)) {
        const list = idsByName.get(node.text);
        if (list) list.push(node);
        else idsByName.set(node.text, [node]);
      }
      ts.forEachChild(node, collect);
    })(sf);

    for (const decl of decls) {
      if (bindCount.get(decl.name) !== 1) continue;
      for (const node of idsByName.get(decl.name) || []) {
        if (node.getStart(sf) >= decl.pos) continue;
        const parent = node.parent;
        if (ts.isPropertyAccessExpression(parent) && parent.name === node) continue;
        if (ts.isPropertyAssignment(parent) && parent.name === node) continue;
        const call = syncPathToDeclScope(node, decl.node, decl.pos, sf);
        if (!call) continue;
        findings.push({
          file: relative(root, file).replace(/\\/g, '/'),
          name: decl.name,
          method: call.expression.name.text,
          usedLine: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1 + src.lineOffset,
          declLine: sf.getLineAndCharacterOfPosition(decl.pos).line + 1 + src.lineOffset,
        });
      }
    }
  }
  const seen = new Set();
  return findings.filter((f) => {
    const k = `${f.file}:${f.name}:${f.usedLine}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

/** 最近的函数边界(找不到就是模块顶层 SourceFile) —— 用来判「是不是同一次执行」。 */
function enclosingFunction(node) {
  for (let cur = node.parent; cur; cur = cur.parent) {
    if (
      ts.isFunctionDeclaration(cur) || ts.isFunctionExpression(cur) || ts.isArrowFunction(cur)
      || ts.isMethodDeclaration(cur) || ts.isConstructorDeclaration(cur)
      || ts.isGetAccessor(cur) || ts.isSetAccessor(cur) || ts.isSourceFile(cur)
    ) return cur;
  }
  return null;
}

/**
 * 从引用点走回声明所在的函数体, 沿途**每一层**函数边界都必须是同步迭代回调,
 * 且每一层的调用都位于声明之前。全程满足才算「必定先于声明执行」。
 *
 * ⛔ 必须走整条链, 不能只看最内层那一跳。真实事故正是**嵌套**两层 forEach
 *  (recipeResponses.forEach → targets.filter(...).forEach → 引用), 只判一跳会漏掉它 ——
 *  我第一版就是这么写的, 结果闸对着活缺陷报绿。
 *
 * 反过来, 只要中间夹了一层**不是**同步迭代回调的函数(事件处理器、setTimeout、
 * Promise.then、普通具名函数), 就说明执行时机不确定, 不报。
 */
function syncPathToDeclScope(refNode, declNode, declPos, sf) {
  const declScope = enclosingFunction(declNode);
  let outermost = null;
  for (let cur = refNode.parent; cur; cur = cur.parent) {
    if (cur === declScope) return outermost;
    if (ts.isFunctionExpression(cur) || ts.isArrowFunction(cur)) {
      const call = cur.parent;
      const isSyncCallback = ts.isCallExpression(call)
        && call.arguments.includes(cur)
        && ts.isPropertyAccessExpression(call.expression)
        && SYNC_ITERATORS.has(call.expression.name.text);
      if (!isSyncCallback) return null;
      if (call.getStart(sf) >= declPos) return null;
      outermost = call;
      cur = call;
      continue;
    }
    // 走到别的函数种类(具名函数/方法/类)还没碰到声明作用域 ⇒ 执行时机不确定。
    if (
      ts.isFunctionDeclaration(cur) || ts.isMethodDeclaration(cur)
      || ts.isConstructorDeclaration(cur) || ts.isGetAccessor(cur) || ts.isSetAccessor(cur)
    ) return null;
  }
  return null;
}

if (process.argv[1] && process.argv[1].endsWith('tdz-scan.mjs')) {
  const findings = scanForSyncCallbackTdz(process.argv[2]);
  console.log(JSON.stringify({ count: findings.length, findings }, null, 2));
  process.exit(findings.length ? 1 : 0);
}
