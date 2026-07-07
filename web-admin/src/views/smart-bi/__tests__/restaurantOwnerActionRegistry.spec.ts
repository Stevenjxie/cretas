import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  inferOwnerActionScenario,
  OWNER_ACTION_SCENARIO_TERMS,
  RESTAURANT_OWNER_ACTION_SCENARIOS,
} from '../restaurantOwnerActionRegistry';

function backendDemoScenarios(): string[] {
  const backendFile = resolve(
    process.cwd(),
    '..',
    'backend',
    'python',
    'smartbi',
    'services',
    'restaurant',
    'demo_owner_action_scenarios.py',
  );
  const source = readFileSync(backendFile, 'utf8');
  const block = source.match(/_SCENARIO_PATCHES:[\s\S]*?\n}\n\n\ndef list_owner_action_demo_scenarios/)?.[0] ?? '';
  const scenarios = [...block.matchAll(/^\s{4}"([^"]+)":\s*\{/gm)].map((match) => match[1]);
  return [...new Set(scenarios)].sort();
}

describe('restaurant owner action registry', () => {
  it('keeps frontend scenario ids aligned with Python demo scenarios', () => {
    expect([...RESTAURANT_OWNER_ACTION_SCENARIOS].sort()).toEqual(backendDemoScenarios());
  });

  it('does not keep legacy or unknown scenario ids in frontend terms', () => {
    const valid = new Set(RESTAURANT_OWNER_ACTION_SCENARIOS);
    const scenarios = OWNER_ACTION_SCENARIO_TERMS.map((entry) => entry.scenario);

    expect(new Set(scenarios).size).toBe(scenarios.length);
    expect(scenarios).not.toContain('review_recovery');
    for (const scenario of scenarios) {
      expect(valid.has(scenario)).toBe(true);
    }
  });

  it('keeps ambiguous owner questions on their intended backend scenarios', () => {
    expect(inferOwnerActionScenario('如果服务差评多，店长今天应该怎么培训员工？')).toBe('staff_training');
    expect(inferOwnerActionScenario('库存预警和采购补货今天先看什么？')).toBe('inventory_reorder');
    expect(inferOwnerActionScenario('哪家店最值得学习？它的做法能不能复制到青花椒？')).toBe('store_compare');
    expect(inferOwnerActionScenario('主推单品怎么判断有没有拉动加购？')).toBe('single_item_push');
    expect(inferOwnerActionScenario('根据菜品毛利和成本，帮我算一个适合今天推的小套餐')).toBe('package');
  });
});
