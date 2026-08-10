import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf-8');

describe('画布接入 BOM 浮层', () => {
  it('注册了两类 cell 的插槽', () => {
    expect(source).toContain('#node-bomAuxiliary');
    expect(source).toContain('#node-bomPackaging');
  });

  it('两个插槽都把 canEdit 传给 cell 的 canWrite —— 只读用户不给编辑入口', () => {
    expect(source).toMatch(/<WorkflowAuxiliaryNode[\s\S]{0,400}?:can-write="canEdit"/);
    expect(source).toMatch(/<WorkflowPackagingNode[\s\S]{0,400}?:can-write="canEdit"/);
  });

  it('注入前先清旧浮层, 避免累积', () => {
    expect(source).toMatch(/stripBomOverlay\(flowNodes\.value\)/);
    expect(source).toMatch(/stripBomOverlayEdges\(flowEdges\.value\)/);
  });

  it('序列化仍然剥离浮层(Task 1 的闸没被绕过)', () => {
    expect(source).toMatch(/nodes:\s*stripBomOverlay\(flowNodes\.value\)\.map\(serializeFlowNode\)/);
    expect(source).toMatch(/edges:\s*stripBomOverlayEdges\(flowEdges\.value\)\.map\(serializeFlowEdge\)/);
  });

  it('每个 hydrate 调用点之后浮层都会被重新派生', () => {
    // 逐个点补是漏的源头 —— 派生必须长在 hydrate 里, 而不是 undo()/handleAutoLayout()/
    // reconcileLoadedUnits() 各自调一次 refreshBomOverlay (那样漏一个又是同样的 bug)。
    const hydrateBody = source.slice(source.indexOf('function hydrate'), source.indexOf('function hydrate') + 1200);
    expect(hydrateBody).toMatch(/deriveBomOverlay|refreshBomOverlay/);
  });

  it('undo() 不在自己身上补一次浮层派生 —— 结构性挂在 hydrate() 里, 不是三个点各补一次', () => {
    // 钉死"结构性"这条: undo 内部只该调 hydrate(previous), 不该看到它自己直接调
    // deriveBomOverlay/refreshBomOverlay —— 否则又退回"三个调用点各补一次"的形状,
    // 第四个 hydrate 调用方还会再踩同样的坑。
    const undoBody = source.slice(source.indexOf('function undo('), source.indexOf('function undo(') + 400);
    expect(undoBody).toContain('hydrate(previous)');
    expect(undoBody).not.toMatch(/deriveBomOverlay|refreshBomOverlay/);
  });

  it('派生边挂靠 handle id 与 bomOverlay.ts 的常量一致(不是两边各写一份字面量)', () => {
    expect(source).not.toMatch(/sourceHandle:\s*'bom-aux-out'/);
    expect(source).not.toMatch(/targetHandle:\s*'bom-pack-in'/);
  });

  it('Vue Flow 的统一连接门先放行精确派生浮层边，避免 setEdges 静默过滤', () => {
    const validationBody = source.slice(
      source.indexOf('function isValidConnection'),
      source.indexOf('function isValidConnection') + 900,
    );
    expect(validationBody).toContain('isDerivedBomOverlayConnection(connection)');
    expect(validationBody).toMatch(/isBomOverlayNode\(source\)[\s\S]{0,80}isBomOverlayNode\(target\)[\s\S]{0,80}return false/);
  });

  it('首次创建草稿钉住当前 Workflow revision，并用返回草稿刷新而不要求手工刷新', () => {
    expect(source).toContain('definition.value?.revisionId');
    expect(source).toContain('loadBomOverlayData({ preferredDraft: draft })');
    expect(source).toContain('versions.unshift(options.preferredDraft)');
  });

  it('浮层允许拖动但不会进入 Workflow dirty，派生刷新保留拖后位置', () => {
    expect(source).toMatch(/existingOverlayPositions[\s\S]{0,1800}draggable:\s*true/);
    const dragStopBody = source.slice(source.indexOf('function onNodeDragStop'), source.indexOf('function onNodeDragStop') + 900);
    expect(dragStopBody).toContain('isBomOverlayNode(node)');
    expect(dragStopBody).toContain('overlay.position');
    expect(dragStopBody.indexOf('return;')).toBeLessThan(dragStopBody.indexOf('dirty.value = true'));
  });
});
