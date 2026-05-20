<script setup lang="ts">
import { computed, ref, onMounted } from 'vue';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { fetchActiveReleaseNotes, type ReleaseNote } from '@/api/releaseNotes';

/**
 * U-FEED-1 — in-app release-note feed. Mounted at app root so a fresh login
 * sees announcements. Dismissed releases persist in localStorage by id; each
 * note is shown until the user dismisses it.
 *
 * Markdown body rendered via `marked` (already in deps).
 */
const DISMISSED_KEY = 'release-notes:dismissed';

const allNotes = ref<ReleaseNote[]>([]);
const dismissed = ref<Set<string>>(new Set());

function loadDismissed(): Set<string> {
  if (typeof window === 'undefined') return new Set();
  try {
    const raw = window.localStorage.getItem(DISMISSED_KEY);
    if (!raw) return new Set();
    const arr = JSON.parse(raw);
    return new Set(Array.isArray(arr) ? arr : []);
  } catch {
    return new Set();
  }
}

function persistDismissed(): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(DISMISSED_KEY, JSON.stringify([...dismissed.value]));
  } catch {
    // ignore
  }
}

onMounted(async () => {
  dismissed.value = loadDismissed();
  try {
    allNotes.value = await fetchActiveReleaseNotes(10);
  } catch {
    // Quietly degrade — feed is non-essential, no toast on failure.
    allNotes.value = [];
  }
});

const visibleNotes = computed(() =>
  allNotes.value.filter((n) => !dismissed.value.has(n.id))
);

function dismiss(id: string): void {
  dismissed.value.add(id);
  // Re-assign to trigger reactivity since Set#add mutates in place.
  dismissed.value = new Set(dismissed.value);
  persistDismissed();
}

function severityType(s: ReleaseNote['severity']): 'info' | 'success' | 'warning' {
  if (s === 'breaking') return 'warning';
  if (s === 'improvement') return 'success';
  return 'info';
}

function renderMarkdown(md: string): string {
  // marked default config; sufficient for our 升级日志 content.
  // DOMPurify wrap — admin-published note.body may contain <script>/<img onerror>
  // payloads if write surface (SQL/admin UI) is compromised. Sister sites use
  // same pattern (AIQuery.vue / FallbackLogAdmin.vue / AlertDashboard.vue).
  // AUD-6 audit 2026-05-20: P1 stored XSS path identified, mitigation via sanitize.
  return DOMPurify.sanitize(marked.parse(md, { async: false }) as string);
}
</script>

<template>
  <div v-if="visibleNotes.length" class="release-note-stack" aria-live="polite">
    <el-card
      v-for="note in visibleNotes"
      :key="note.id"
      class="release-note-card"
      shadow="hover"
    >
      <template #header>
        <div class="release-note-header">
          <div class="release-note-meta">
            <el-tag size="small" :type="severityType(note.severity)">
              {{ note.version }}
            </el-tag>
            <span class="release-note-title">{{ note.title }}</span>
          </div>
          <el-button text :icon="undefined" size="small" @click="dismiss(note.id)">
            ✕
          </el-button>
        </div>
      </template>
      <div class="release-note-body" v-html="renderMarkdown(note.body)" />
      <div class="release-note-footer">
        发布于 {{ note.publishedAt }}
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.release-note-stack {
  position: fixed;
  right: 24px;
  bottom: 24px;
  width: 360px;
  max-height: calc(100vh - 96px);
  overflow-y: auto;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  gap: 12px;
  pointer-events: none;
}
.release-note-card {
  pointer-events: auto;
}
.release-note-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.release-note-meta {
  display: flex;
  gap: 8px;
  align-items: center;
}
.release-note-title {
  font-weight: 600;
  font-size: 14px;
}
.release-note-body {
  font-size: 13px;
  line-height: 1.55;
  color: var(--el-text-color-regular);
}
.release-note-body :deep(ul) {
  padding-left: 18px;
  margin: 4px 0;
}
.release-note-body :deep(strong) {
  font-weight: 600;
}
.release-note-footer {
  margin-top: 8px;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
</style>
