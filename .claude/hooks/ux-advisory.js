#!/usr/bin/env node
// .claude/hooks/ux-advisory.js
// PostToolUse hook: fires after Edit/Write on any file.
// Prints UX advisory checklist if file is a .tsx in a P1 low-literacy-user screen path.

const P1_PATHS = [
  'screens/processing/',
  'screens/warehouse/',
  'screens/quality-inspector/',
];

function getFilePath() {
  try {
    const raw = process.env.CLAUDE_TOOL_INPUT || '{}';
    const input = JSON.parse(raw);
    return typeof input.file_path === 'string' ? input.file_path : '';
  } catch {
    return '';
  }
}

function isP1Path(filePath) {
  const normalized = filePath.replace(/\\/g, '/');
  return P1_PATHS.some((p) => normalized.includes(p));
}

const filePath = getFilePath();

if (!filePath.endsWith('.tsx') || !isP1Path(filePath)) {
  process.exit(0);
}

const shortPath = filePath.replace(/\\/g, '/').split('/screens/')[1] || filePath;

process.stdout.write(`
⚠️  UX Advisory [低技术素养用户屏幕: screens/${shortPath}]
检查以下项目（advisory，不阻断提交）：

触摸交互
□ 所有可点击元素 ≥ 44×44pt
□ 相邻可点击元素间距 ≥ 8px
□ 点击后 80-150ms 内有视觉反馈（ripple/opacity）

上下文显示（fool-proof Rule 2）
□ 写操作 dialog 标题包含：产品名 + 批次号/单据号
□ 关键计划数字可见（计划数量、已处理量）

边界防呆（fool-proof Rule 1）
□ 数量输入有 max 限制 + 当前可用量显示
□ 超限时提交按钮 disabled，不依赖提交后报错

Dead-end 检查（fool-proof Rule 5）
□ 错误状态有 next action 按钮
□ 空状态有引导操作，不留死路

来源：ui-ux-pro-max + fool-proof-design.md
`);
