/**
 * Keep the viewport budget explicit so the editor does not rely on content
 * height (which would let a large canvas or the legacy list grow document.body).
 */
export const WORKFLOW_GLOBAL_NAVIGATION_PX = 64;
export const WORKFLOW_PAGE_CHROME_PX = 156;
export const WORKFLOW_MIN_INTERACTIVE_HEIGHT_PX = 360;

export function workflowEditorViewportHeight(
  viewportHeight: number,
  zoom = 1,
): number {
  const cssViewportHeight = viewportHeight / zoom;
  return Math.max(
    WORKFLOW_MIN_INTERACTIVE_HEIGHT_PX,
    Math.floor(cssViewportHeight - WORKFLOW_GLOBAL_NAVIGATION_PX - WORKFLOW_PAGE_CHROME_PX),
  );
}

export function hasWorkflowControlBudget(editorHeight: number): boolean {
  // Sticky toolbar + canvas controls + minimum graph interaction area.
  return editorHeight >= WORKFLOW_MIN_INTERACTIVE_HEIGHT_PX;
}
