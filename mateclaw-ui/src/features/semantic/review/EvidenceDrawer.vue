<script setup lang="ts">
import { ref, watch } from 'vue'
import { vLoading } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { graphQueryApi } from '../api/graphQueryApi'
import { useSemanticScope } from '../shared/useSemanticScope'
import type { Evidence } from '../api/workbenchTypes'
const props = defineProps<{ graphId: string; evidenceId: string | null; generation?: number }>()
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n(), { begin, workspace } = useSemanticScope()
const evidence = ref<Evidence | null>(null), error = ref(''), busy = ref(false)
watch(() => [props.graphId, props.evidenceId, props.generation, workspace.currentWorkspaceId], async () => {
  const run = begin(); evidence.value = null; error.value = ''; busy.value = false
  if (!props.evidenceId) return
  busy.value = true
  try { const result = await graphQueryApi.evidence(run.id, props.graphId, props.evidenceId, run.signal); if (run.current()) evidence.value = result }
  catch (e) { if (run.current()) error.value = (e as Error).message || t('semantic.w.evidenceUnavailable') }
  finally { if (run.current()) busy.value = false }
}, { immediate: true, flush: 'sync' })
</script>
<template><el-drawer :model-value="!!evidenceId" :title="t('semantic.w.evidence')" @close="emit('close')"><div v-loading="busy"><el-alert v-if="error" :title="error" type="error" :closable="false" /><template v-if="evidence"><h3>{{ evidence.sourceTitle }}</h3><blockquote>{{ evidence.exactQuote }}</blockquote><p>{{ evidence.sourceKind }} · {{ evidence.sourceRef }}</p><p>{{ t('semantic.w.snapshot') }}: {{ evidence.snapshotId }}</p><p>[{{ evidence.startCodePoint }}, {{ evidence.endCodePoint }})</p><small>{{ evidence.textDigest }}</small></template></div></el-drawer></template>
<style scoped>blockquote { white-space: pre-wrap; margin: 16px 0; } small { overflow-wrap: anywhere; }</style>
