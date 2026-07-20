const INTERNAL_IDENTIFIER = /\b(?:[A-Za-z][A-Za-z0-9]*)(?:_[A-Za-z0-9]+)+\b/g;
const API_PATH = /\/api\/[A-Za-z0-9_./?=&%-]+/g;
const TOOL_EXPLANATION = /(?:通过|经由)?\s*(?:调用|使用)\s*[^，。；\n]{0,80}?(?:工具|接口|数据表)(?:来|进行|获取|查询)?/g;

/**
 * Final presentation guard for restaurant AI answers.
 *
 * The server remains the primary enforcement point. This renderer guard keeps
 * cached/legacy answers from exposing implementation identifiers to customers.
 */
export function sanitizeCustomerAiText(input: string): string {
  if (!input) return '';
  const cleaned = input
    .replace(TOOL_EXPLANATION, '')
    .replace(API_PATH, '')
    .replace(INTERNAL_IDENTIFIER, '')
    .replace(/\bGold\b/gi, '')
    .replace(/\bmaterialize\b/gi, '数据准备')
    .replace(/\bETL\b/gi, '数据整理')
    .replace(/\bLLM\b/gi, '智能分析')
    .replace(/\bJSON\b/gi, '数据格式')
    .replace(/\bPOS\b/gi, '收银')
    .replace(/(?:内部)?意图(?:代码)?\s*[：:]?\s*/g, '')
    .replace(/(?:来源|读取自|查询自)\s*(?=[，。；\n])/g, '')
    .replace(/[ \t]+([，。；：])/g, '$1')
    .replace(/^[ \t]*[，；：]+/gm, '')
    .replace(/^[\s，。；：、]*(?:(?:来源|内部意图|意图|与|和|来自)[\s，。；：、]*)+$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return cleaned || '分析已完成，请查看业务结果。';
}
