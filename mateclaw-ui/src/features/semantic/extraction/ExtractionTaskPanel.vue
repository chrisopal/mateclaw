<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ExtractionTask } from '../api/extractionApi'
const props = defineProps<{ task: ExtractionTask; busy: boolean; enabled: boolean }>()
defineEmits<{ cancel: []; retry: []; refresh: [] }>()
const { t, te } = useI18n()
const running = computed(() => ['QUEUED', 'RUNNING'].includes(props.task.status))
</script>
<template>
  <section class="task-panel" aria-live="polite">
    <div class="task-heading"><strong>{{ task.sourceTitle }}</strong><el-tag>{{ t(`semantic.extraction.states.${task.status}`) }}</el-tag></div>
    <p>{{ t('semantic.extraction.taskContext', { model: task.modelName, attempt: task.attempts }) }}</p>
    <p v-if="running">{{ t('semantic.extraction.progress', { done: task.completedChunks, total: task.totalChunks }) }}</p>
    <el-alert v-if="task.errorMessage" :title="te(`semantic.extraction.errors.${task.errorCode}`) ? t(`semantic.extraction.errors.${task.errorCode}`) : task.errorMessage" type="error" :closable="false" />
    <p v-if="task.explanation">{{ task.explanation }}</p>
    <div class="task-actions">
      <el-button :loading="busy" @click="$emit('refresh')">{{ t('semantic.w.refresh') }}</el-button>
      <el-button v-if="running" :disabled="busy" @click="$emit('cancel')">{{ t('semantic.extraction.cancel') }}</el-button>
      <el-button v-if="enabled && task.status === 'FAILED' && task.attempts < 3" :disabled="busy" @click="$emit('retry')">{{ t('semantic.extraction.retry') }}</el-button>
    </div>
  </section>
</template>
<style scoped>
.task-panel { margin: 18px 0; padding: 16px; border: 1px solid var(--mc-border); border-radius: 6px; }
.task-heading, .task-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
p { color: var(--mc-text-secondary); margin: 8px 0; }
</style>
