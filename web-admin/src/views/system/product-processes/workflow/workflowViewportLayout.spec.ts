import { describe, expect, it } from 'vitest';
import {
  hasWorkflowControlBudget,
  workflowEditorViewportHeight,
} from './workflowViewportLayout';

describe('workflow viewport layout budget', () => {
  it.each([
    [1366, 768, 0.8],
    [1366, 768, 1],
    [1366, 768, 1.25],
    [1440, 900, 0.8],
    [1440, 900, 1],
    [1440, 900, 1.25],
    [1920, 1080, 0.8],
    [1920, 1080, 1],
    [1920, 1080, 1.25],
  ])('keeps controls usable at %ix%i and %i%% zoom', (_width, height, zoom) => {
    const editorHeight = workflowEditorViewportHeight(height, zoom);

    expect(editorHeight).toBeGreaterThanOrEqual(360);
    expect(hasWorkflowControlBudget(editorHeight)).toBe(true);
  });
});
