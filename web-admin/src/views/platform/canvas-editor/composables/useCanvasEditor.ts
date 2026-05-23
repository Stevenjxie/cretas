// useCanvasEditor.ts — shared editor state across all canvas components
import { ref, computed } from 'vue'
import { useAuthStore } from '@/store/modules/auth'
import { getConfigVersion } from '@/api/canvasApi'
import type { ConfigStatus, ConfigVersion } from '@/types/canvas'

// Singleton state (shared across components)
const selectedModule = ref('')
const activeTab = ref('workflow') // default: flow design first
const configVersion = ref<ConfigVersion | null>(null)
const dirtyCount = ref(0)
const leftCollapsed = ref(false)
const rightCollapsed = ref(false)
const isOnboarding = ref(false)

// Round 7a fix: in-flight action lock. Parent view sets to the action code when
// starting an await call, resets to null in finally. CanvasHeader emitLocked()
// reads this to drop duplicate clicks and drive :loading/:disabled binding.
const inFlightAction = ref<string | null>(null)

// Round 7a fix: install beforeunload guard ONCE at module load. When dirtyCount > 0,
// browser shows "unsaved changes" confirm on tab close / refresh / navigation.
// Previously there was zero protection → data silently lost on tab close.
if (typeof window !== 'undefined') {
  window.addEventListener('beforeunload', (e) => {
    if (dirtyCount.value > 0) {
      e.preventDefault()
      // Modern browsers ignore custom text; just setting returnValue is enough to trigger prompt.
      e.returnValue = ''
      return ''
    }
  })
}

// Restore collapse state from localStorage
const savedState = localStorage.getItem('canvas-editor-state')
if (savedState) {
  try {
    const s = JSON.parse(savedState)
    leftCollapsed.value = s.leftCollapsed ?? false
    rightCollapsed.value = s.rightCollapsed ?? false
  } catch { /* ignore */ }
}

export function useCanvasEditor() {
  const authStore = useAuthStore()
  const factoryId = computed(() => authStore.factoryId || '')

  const status = computed<ConfigStatus>(() => configVersion.value?.status || 'DRAFT')
  const isReadOnly = computed(() => status.value !== 'DRAFT')
  const canSubmitReview = computed(() => status.value === 'DRAFT' && dirtyCount.value === 0)
  const canApprove = computed(() => status.value === 'PENDING_REVIEW')
  const canPublishNow = computed(() => status.value === 'APPROVED')
  const versionLabel = computed(() => {
    const v = configVersion.value
    if (!v) return ''
    const icons: Record<ConfigStatus, string> = {
 DRAFT: '', PENDING_REVIEW: '', APPROVED: '⏰', PUBLISHED: '', REJECTED: ''
    }
    const labels: Record<ConfigStatus, string> = {
      DRAFT: '草稿', PENDING_REVIEW: '待审核', APPROVED: '已审核', PUBLISHED: '已发布', REJECTED: '已驳回'
    }
    return `${icons[v.status]} ${labels[v.status]} v${v.configVersion}`
  })

  async function loadVersion() {
    if (!factoryId.value) return
    try {
      const res = await getConfigVersion(factoryId.value)
      if (res.data) {
        configVersion.value = res.data
        // Round 4 Fix: Only show OnboardingWizard if NO version in history has been published.
        // Previously we showed it whenever current version was DRAFT, but that also triggered
        // for factories that had published earlier and just created a new draft on top.
        // Check full version history to determine if factory has ever published.
        try {
          const { getConfigVersions } = await import('@/api/canvasApi')
          // Round 5 PERF-3: backend returns Page shape {content, totalElements, ...}.
          // Round 6 P0 fix: previously `historyRes.data.some()` threw TypeError and crashed
          // the onboarding check, forcing every user into fallback mode.
          const historyRes = await getConfigVersions(factoryId.value)
          const history = (historyRes.data?.content || []) as ConfigVersion[]
          const everPublished = history.some(v => v.publishedAt !== null && v.publishedAt !== undefined)
          isOnboarding.value = !everPublished
        } catch {
          // If versions endpoint fails, fall back to current-version check
          isOnboarding.value = res.data.status !== 'PUBLISHED' && !res.data.publishedAt
        }
      } else {
        // No version record at all = fresh factory, definitely onboarding
        isOnboarding.value = true
      }
    } catch {
      // API error or first time, show onboarding as safe default
      isOnboarding.value = true
    }
  }

  function markDirty() { dirtyCount.value++ }
  function clearDirty() { dirtyCount.value = 0 }

  function toggleLeft() {
    leftCollapsed.value = !leftCollapsed.value
    persistState()
  }

  function toggleRight() {
    rightCollapsed.value = !rightCollapsed.value
    persistState()
  }

  function enterFocusMode() {
    leftCollapsed.value = true
    rightCollapsed.value = true
    persistState()
  }

  function exitFocusMode() {
    leftCollapsed.value = false
    rightCollapsed.value = false
    persistState()
  }

  function persistState() {
    localStorage.setItem('canvas-editor-state', JSON.stringify({
      leftCollapsed: leftCollapsed.value,
      rightCollapsed: rightCollapsed.value,
    }))
  }

  // Responsive defaults
  function applyResponsive() {
    const w = window.innerWidth
    if (w < 1024) {
      // Not supported — handled in template
    } else if (w < 1200) {
      leftCollapsed.value = true
      rightCollapsed.value = true
    } else if (w < 1440) {
      rightCollapsed.value = true
    }
  }

  return {
    factoryId, selectedModule, activeTab, configVersion, dirtyCount,
    leftCollapsed, rightCollapsed, isOnboarding,
    inFlightAction,  // Round 7a: action-button single-flight lock state
    status, isReadOnly, canSubmitReview, canApprove, canPublishNow, versionLabel,
    loadVersion, markDirty, clearDirty,
    toggleLeft, toggleRight, enterFocusMode, exitFocusMode, applyResponsive,
  }
}
