/**
 * AI 问答页默认数据源选择逻辑 (#8, 2026-06-02).
 *
 * 历史 bug: AIQuery.vue onMounted 选默认数据源时, 若最新的非评价上传超过 1 小时,
 * 会"回退"到最近的评价文件 (评价下载...xlsx). 对青花椒 (qhj) 这类餐饮租户, 评价文件
 * 只有评分/口碑, 没有营收/时间序列 → 销售/营收/趋势类问题就跑错数据集.
 *
 * 修复 (本模块): 默认永远优先 **非评价** 上传 (POS / 销售 / 财务数据), 只有在
 * 完全没有非评价上传时才回退到评价文件. 同名文件中再优先 **完整** 文件 (避开
 * `_part1` / `(1)` 等分卷文件).
 *
 * 注: 后端问题路由是另一处 sibling 修复 — 趋势/同比/畅销 类问题无论选中哪个文件都会
 * 走 gold 层. 本模块只保证 **展示/选中** 的默认数据源合理 (非评价、完整), 让依赖
 * 文件的问题 + UI 上下文正确.
 */
import type { UploadHistoryItem } from '@/api/smartbi';

/** 评价类文件关键词 (文件名含其一即判为评价文件). */
export const REVIEW_KEYWORDS = ['评价', '评论', '大众点评', '美团评价', '评分', 'review', 'comment'];

/** 文件名是否为评价类 (大小写不敏感). */
export function isReviewFile(d: Pick<UploadHistoryItem, 'fileName' | 'originalFileName'>): boolean {
  const name = (d.fileName || d.originalFileName || '').toLowerCase();
  return REVIEW_KEYWORDS.some(kw => name.includes(kw.toLowerCase()));
}

/** 文件名是否像"分卷/分片"文件 (完整文件应优先于分卷). */
export function isPartFile(d: Pick<UploadHistoryItem, 'fileName' | 'originalFileName'>): boolean {
  const name = (d.fileName || d.originalFileName || '');
  // _part1 / _part2 / -part-3 / part 1 等; (1)/(2) 复制副本; 第1部分/分卷2.
  return /(?:[_\-\s]?part[\s_\-]?\d+)|(?:\(\d+\)\s*(?:\.[a-z0-9]+)?$)|(?:第\s*\d+\s*部分)|(?:分卷\s*\d+)/i.test(name);
}

/** 上传时间戳 (ms), 兼容多种字段命名; 缺失返回 0. */
function ts(d: UploadHistoryItem): number {
  const row = d as unknown as Record<string, unknown>;
  const t = (row.createdAt || row.created_at || row.uploadTime || row.upload_time) as string | undefined;
  return t ? new Date(t).getTime() : 0;
}

/** 去掉分卷/副本后缀, 取文件基名 (用于"同一份数据的完整 vs 分卷"判断). */
function baseName(d: Pick<UploadHistoryItem, 'fileName' | 'originalFileName'>): string {
  let name = (d.fileName || d.originalFileName || '').toLowerCase();
  // 去扩展名
  name = name.replace(/\.[a-z0-9]+$/i, '');
  // 去分卷/副本标记
  name = name
    .replace(/[_\-\s]?part[\s_\-]?\d+/gi, '')
    .replace(/\(\d+\)\s*$/g, '')
    .replace(/第\s*\d+\s*部分/g, '')
    .replace(/分卷\s*\d+/g, '');
  return name.trim();
}

/**
 * 默认数据源排序比较器: 完整文件优先于分卷, 再按新近度, 再按行数.
 * (用于在"已确定的优先组"内排序选 top1.)
 */
function compareForDefault(a: UploadHistoryItem, b: UploadHistoryItem): number {
  // 1. 完整文件优先于分卷文件
  const aPart = isPartFile(a) ? 1 : 0;
  const bPart = isPartFile(b) ? 1 : 0;
  if (aPart !== bPart) return aPart - bPart; // 非分卷 (0) 排前
  // 2. 新近度 (越新越前)
  const dt = ts(b) - ts(a);
  if (dt !== 0) return dt;
  // 3. 行数 (越多越前) — 同名分卷之间也用行数兜底"更完整"
  return (b.rowCount || 0) - (a.rowCount || 0);
}

/**
 * 选默认数据源.
 *
 * 规则 (优先级从高到低):
 *   1. 排除 `[自动同步]` 文件 (除非全部都是).
 *   2. **非评价文件优先**: 只要存在任何非评价上传, 就只在非评价集合里选;
 *      完全没有非评价上传时才回退到评价文件.
 *   3. 在选定的集合内: 完整文件优先于分卷 (`_part1`/`(1)`...), 再按新近度, 再按行数.
 *
 * @param deduped 已去重的上传列表 (deduplicateUploads 的输出).
 * @returns 选中的上传项, 或 null (列表为空).
 */
export function pickDefaultDataSource(deduped: UploadHistoryItem[]): UploadHistoryItem | null {
  if (!Array.isArray(deduped) || deduped.length === 0) return null;

  // 1. 优先非自动同步
  const nonAutoSync = deduped.filter(d => {
    const name = d.fileName || d.originalFileName || '';
    return !name.startsWith('[自动同步]');
  });
  const pool = nonAutoSync.length > 0 ? nonAutoSync : deduped;

  // 2. 非评价文件优先; 没有非评价时才用评价文件
  const nonReview = pool.filter(d => !isReviewFile(d));
  const candidates = nonReview.length > 0 ? nonReview : pool;

  // 3. 完整 > 分卷 > 新近 > 行数
  // 若选中的是分卷但同名有完整文件 (compareForDefault 已把完整排前), top1 即完整文件.
  const sorted = [...candidates].sort(compareForDefault);
  return sorted[0] ?? null;
}

// re-export baseName for potential reuse / testability without widening default API surface
export { baseName as _baseNameForTest };
