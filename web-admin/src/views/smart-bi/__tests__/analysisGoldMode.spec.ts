import { describe, it, expect } from 'vitest';
import {
  shouldDefaultToGold,
  isPartialUploadName,
  pickDefaultBatchIndex,
} from '../analysisGoldMode';

describe('analysisGoldMode — 传Excel分析 餐饮 gold 默认 (#1/#3)', () => {
  describe('shouldDefaultToGold', () => {
    it('RESTAURANT → true (gold 租户默认 gold)', () => {
      expect(shouldDefaultToGold('RESTAURANT')).toBe(true);
    });
    it('FACTORY → false (制造业用上传分析)', () => {
      expect(shouldDefaultToGold('FACTORY')).toBe(false);
    });
    it('null / undefined / 空 → false (不默认 gold, 不破坏非餐饮)', () => {
      expect(shouldDefaultToGold(null)).toBe(false);
      expect(shouldDefaultToGold(undefined)).toBe(false);
      expect(shouldDefaultToGold('')).toBe(false);
    });
  });

  describe('isPartialUploadName', () => {
    it('qhj_pos_2025_part4.csv → 分片 (真实 qhj 文件)', () => {
      expect(isPartialUploadName('qhj_pos_2025_part4.csv')).toBe(true);
    });
    it('多种分隔/大小写形式 → 分片', () => {
      expect(isPartialUploadName('sales_part_2.xlsx')).toBe(true);
      expect(isPartialUploadName('data-part3')).toBe(true);
      expect(isPartialUploadName('report Part 1.csv')).toBe(true);
      expect(isPartialUploadName('POS_PART10.csv')).toBe(true);
    });
    it('完整文件名 → 非分片', () => {
      expect(isPartialUploadName('qhj_pos_2025_full.csv')).toBe(false);
      expect(isPartialUploadName('财务报表.xlsx')).toBe(false);
      expect(isPartialUploadName('department.csv')).toBe(false);
    });
    it('不把 "part" 作为普通单词的一部分误判 (departure / apartment)', () => {
      expect(isPartialUploadName('departure_log.csv')).toBe(false);
      expect(isPartialUploadName('apartment.xlsx')).toBe(false);
    });
    it('null / undefined / 空 → false', () => {
      expect(isPartialUploadName(null)).toBe(false);
      expect(isPartialUploadName(undefined)).toBe(false);
      expect(isPartialUploadName('')).toBe(false);
    });
  });

  describe('pickDefaultBatchIndex', () => {
    it('优先选第一个完整批次 (跳过分片)', () => {
      // 最近优先排序: [part4, part3, full, part1]
      expect(pickDefaultBatchIndex([
        'qhj_pos_2025_part4.csv',
        'qhj_pos_2025_part3.csv',
        'qhj_pos_2025_full.csv',
        'qhj_pos_2025_part1.csv',
      ])).toBe(2);
    });
    it('全是分片 → 回落到 0 (不丢失数据, 仍显示最新分片)', () => {
      expect(pickDefaultBatchIndex([
        'qhj_pos_2025_part4.csv',
        'qhj_pos_2025_part3.csv',
      ])).toBe(0);
    });
    it('全是完整文件 → 0 (保留最近优先顺序)', () => {
      expect(pickDefaultBatchIndex(['财务报表.xlsx', '销售明细.csv'])).toBe(0);
    });
    it('空列表 → 0', () => {
      expect(pickDefaultBatchIndex([])).toBe(0);
    });
  });
});
