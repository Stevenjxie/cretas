import { ref, computed } from 'vue'
import type {
  PrintElement,
  PrintTemplateSchema,
  ElementType,
  PrintEntityType,
  PrintElementDraft,
} from '../utils/printSchemaTypes'
import { emptySchema } from '../utils/printSchemaTypes'
import { generateElementId, clampToCanvas, snapToGrid } from '../utils/a4Coords'

/**
 * Editor state composable — single instance per editor mount.
 *
 * Holds the working schema, selection, dirty flag, and zoom. Components
 * (FormCanvas, ElementPalette, PropertyPanel) read/write through these refs;
 * no global store needed because the editor lives in a single route.
 */
export function usePrintEditor() {
  const schema = ref<PrintTemplateSchema>(emptySchema())
  const selectedElementId = ref<string | null>(null)
  const dirty = ref(false)
  const entityType = ref<PrintEntityType | ''>('')
  const templateId = ref<string | null>(null)
  const templateName = ref<string>('')
  const zoom = ref(1.25)

  const selectedElement = computed<PrintElement | null>(() => {
    if (!selectedElementId.value) return null
    return schema.value.elements.find(e => e.id === selectedElementId.value) ?? null
  })

  function loadSchema(s: PrintTemplateSchema, id: string | null, name: string) {
    schema.value = JSON.parse(JSON.stringify(s)) as PrintTemplateSchema
    templateId.value = id
    templateName.value = name
    selectedElementId.value = null
    dirty.value = false
  }

  function resetForEntity(et: PrintEntityType) {
    entityType.value = et
    schema.value = emptySchema()
    templateId.value = null
    templateName.value = `${et} 模板`
    selectedElementId.value = null
    dirty.value = false
  }

  function addElement(partial: PrintElementDraft): PrintElement {
    const id = generateElementId()
    const el = { ...partial, id } as PrintElement
    schema.value.elements.push(el)
    selectedElementId.value = id
    dirty.value = true
    return el
  }

  function updateElement(id: string, patch: Partial<PrintElement>) {
    const idx = schema.value.elements.findIndex(e => e.id === id)
    if (idx === -1) return
    const current = schema.value.elements[idx]
    schema.value.elements[idx] = { ...current, ...patch } as PrintElement
    dirty.value = true
  }

  function moveElement(id: string, x: number, y: number, snap: boolean = true) {
    const el = schema.value.elements.find(e => e.id === id)
    if (!el) return
    const sx = snap ? snapToGrid(x) : x
    const sy = snap ? snapToGrid(y) : y
    const elWidth = el.width ?? 100
    const elHeight = el.height ?? 20
    const clamped = clampToCanvas(
      sx, sy, elWidth, elHeight,
      schema.value.canvas.width, schema.value.canvas.height,
    )
    el.x = clamped.x
    el.y = clamped.y
    dirty.value = true
  }

  function removeElement(id: string) {
    schema.value.elements = schema.value.elements.filter(e => e.id !== id)
    if (selectedElementId.value === id) selectedElementId.value = null
    dirty.value = true
  }

  function duplicateElement(id: string) {
    const el = schema.value.elements.find(e => e.id === id)
    if (!el) return
    const copy = { ...el, id: generateElementId(), x: el.x + 10, y: el.y + 10 } as PrintElement
    schema.value.elements.push(copy)
    selectedElementId.value = copy.id
    dirty.value = true
  }

  function selectElement(id: string | null) {
    selectedElementId.value = id
  }

  function markClean() {
    dirty.value = false
  }

  return {
    // state
    schema,
    selectedElementId,
    selectedElement,
    dirty,
    entityType,
    templateId,
    templateName,
    zoom,
    // ops
    loadSchema,
    resetForEntity,
    addElement,
    updateElement,
    moveElement,
    removeElement,
    duplicateElement,
    selectElement,
    markClean,
  }
}

export type PrintEditorState = ReturnType<typeof usePrintEditor>

/**
 * Default-shape factory for each element type — used when dragging from
 * ElementPalette onto the canvas. Coordinates are relative to drop point;
 * sensible default sizes per element type.
 */
export function createDefaultElement(type: ElementType, x: number, y: number): PrintElementDraft {
  switch (type) {
    case 'text':
      return { type, x, y, text: '示例文本', fontSize: 12, color: '#1f2937' }
    case 'field':
      return { type, x, y, binding: '{{entity.field}}', fontSize: 12 }
    case 'table':
      return {
        type, x, y, width: 495, binding: '{{entity.items}}', rowHeight: 24,
        headerFontSize: 11, bodyFontSize: 10, headerBg: '#f3f4f6',
        columns: [
          { header: '名称', binding: '{{item.name}}', width: 200, align: 'left' },
          { header: '数量', binding: '{{item.quantity}}', width: 100, align: 'right' },
          { header: '单位', binding: '{{item.unit}}', width: 80, align: 'center' },
          { header: '备注', binding: '{{item.remark}}', width: 115, align: 'left' },
        ],
      }
    case 'qr':
      return { type, x, y, size: 80, content: '{{entity.id}}' }
    case 'barcode':
      return { type, x, y, width: 160, height: 40, content: '{{entity.code}}', format: 'CODE128' }
    case 'image':
      return { type, x, y, width: 120, height: 60, src: '' }
    case 'stamp':
      return { type, x, y, size: 100, stampId: 'default', opacity: 0.8 }
  }
}
