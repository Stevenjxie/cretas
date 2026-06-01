import { describe, it, expect } from 'vitest';
import { menuConfig, type MenuItem } from '../menuConfig';

function findGroup(path: string): MenuItem | undefined {
  return menuConfig.find((m) => m.path === path);
}

describe('menuConfig — baseline structure (pre-merge)', () => {
  it('exports a non-empty menuConfig array', () => {
    expect(Array.isArray(menuConfig)).toBe(true);
    expect(menuConfig.length).toBeGreaterThan(0);
  });

  it('每个顶级项有 path/title/module', () => {
    for (const item of menuConfig) {
      expect(item.path, `item missing path`).toBeTruthy();
      expect(item.title, `${item.path} missing title`).toBeTruthy();
      expect(item.module, `${item.path} missing module`).toBeTruthy();
    }
  });

  it('当前存在 /restaurant 组 (回归基线)', () => {
    expect(findGroup('/restaurant')).toBeDefined();
  });
});
