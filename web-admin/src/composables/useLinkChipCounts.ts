/**
 * Sprint 6 W3-A — useLinkChipCounts composable.
 *
 * Reusable batch-fetch for inline 3-chip link counter (文件 / 图片 / 合同)
 * across 4 list views (sales / procurement / inventory / voucher).
 *
 * Calls POST /attachments/batch-3chip-counts with an EntityType + entityIds[]
 * and exposes a Map keyed by entityId so the row template can render
 * `<el-tag>文件:N</el-tag> <el-tag>图片:N</el-tag> <el-tag>合同:N</el-tag>`
 * without an N+1 query.
 *
 * Failure path is silent — chip falls back to 0 counts so the list itself
 * is never blocked by a non-critical metadata request.
 *
 * Source: Round 12 §B.6 X2 + Round 13 §3 (HJ baseline 3-chip).
 * Spec: docs/superpowers/specs/2026-05-19-sprint5-h1-attachment-linkcounter-spec.md §3.4
 */
import { ref, type Ref } from 'vue';
import { post } from '@/api/request';

/**
 * Attachment.EntityType values relevant to inline link counter.
 * Backend whitelist (Sprint 6 W3-A migration V20260519_07) includes:
 * SALES_ORDER, PURCHASE_ORDER, INVENTORY, PAYMENT_VOUCHER, INVOICE + others.
 */
export type LinkChipEntityType =
  | 'SALES_ORDER'
  | 'PURCHASE_ORDER'
  | 'INVENTORY'
  | 'PAYMENT_VOUCHER'
  | 'INVOICE'
  | 'CUSTOMER'
  | 'QUALITY_CHECK'
  | 'PRODUCTION_BATCH';

export interface LinkChipCounts {
  fileCount: number;
  imageCount: number;
  contractCount: number;
}

interface BackendLinkChipCountsDTO {
  entityId: string;
  fileCount: number;
  imageCount: number;
  contractCount: number;
}

const ZERO_COUNTS: LinkChipCounts = { fileCount: 0, imageCount: 0, contractCount: 0 };

export function useLinkChipCounts(
  factoryId: Ref<string | null | undefined>,
  entityType: LinkChipEntityType,
) {
  /**
   * Map keyed by entityId — undefined values render as 0 chips per
   * countsFor() helper below. Single batch fetch overwrites the whole map
   * to avoid stale rows leaking across loadData() calls.
   */
  const linkChipCountsMap = ref<Record<string, LinkChipCounts>>({});

  async function fetchLinkChipCounts(entityIds: string[]): Promise<void> {
    if (!factoryId.value || entityIds.length === 0) {
      linkChipCountsMap.value = {};
      return;
    }
    // Backend hard-caps at 500 ids/request — slice defensively to avoid 400.
    const ids = entityIds.length > 500 ? entityIds.slice(0, 500) : entityIds;
    try {
      const res = await post(`/${factoryId.value}/attachments/batch-3chip-counts`, {
        entityType,
        entityIds: ids,
      });
      if (res?.success && Array.isArray(res.data)) {
        const next: Record<string, LinkChipCounts> = {};
        for (const entry of res.data as BackendLinkChipCountsDTO[]) {
          next[entry.entityId] = {
            fileCount: entry.fileCount || 0,
            imageCount: entry.imageCount || 0,
            contractCount: entry.contractCount || 0,
          };
        }
        linkChipCountsMap.value = next;
      }
    } catch {
      // Silent — chip falls back to 0. List itself unaffected.
    }
  }

  function countsFor(entityId: string | number | null | undefined): LinkChipCounts {
    if (entityId === null || entityId === undefined) return ZERO_COUNTS;
    return linkChipCountsMap.value[String(entityId)] || ZERO_COUNTS;
  }

  function totalFor(entityId: string | number | null | undefined): number {
    const c = countsFor(entityId);
    return c.fileCount + c.imageCount + c.contractCount;
  }

  return {
    linkChipCountsMap,
    fetchLinkChipCounts,
    countsFor,
    totalFor,
  };
}
