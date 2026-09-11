<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { extractionApi, type ExtractionCapabilities, type ExtractionTask } from '../api/extractionApi'
import { sourceApi } from '../api/sourceApi'
import type { DocumentView, SemanticEntity } from '../api/types'
import { useSemanticScope } from '../shared/useSemanticScope'
import { useExtractionTask } from './useExtractionTask'
import ExtractionTaskPanel from './ExtractionTaskPanel.vue'
import SuggestionReviewPanel from './SuggestionReviewPanel.vue'
const props = defineProps<{ graphId: string; knowledgeBaseId: string; document?: DocumentView; entities: SemanticEntity[] }>(); const emit = defineEmits<{ submitted: [id: string] }>(); const { t, locale } = useI18n(); const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en; const route = useRoute(); const router = useRouter(); const { workspace } = useSemanticScope(); const ws = computed(() => workspace.currentWorkspaceId ?? ''); const taskId = ref(typeof route.query.extractionTask === 'string' ? route.query.extractionTask : ''); const { task, suggestions, loading, error: taskError, refresh } = useExtractionTask(ws, computed(() => props.graphId), taskId); const capabilities = ref<ExtractionCapabilities | null>(null); const materials = ref<{ id: string; title: string }[]>([]); const history = ref<ExtractionTask[]>([]); const source = ref(''); const model = ref(''); const selectedId = ref(''); const busy = ref(false); const error = ref(''); let controller = new AbortController(); let epoch = 0; const selected = computed(() => suggestions.value.find(item => item.id === selectedId.value)); const actions = new Map<string, string>(); function stableAction(key: string) { if (!actions.has(key)) actions.set(key, crypto.randomUUID()); return actions.get(key)! }
const ready = computed(() => capabilities.value?.canStart === true)
const unavailable = computed(() => {
  const labels: Record<string, [string, string]> = {
    EXTRACTION_DISABLED: ['当前环境未开启试抽取。', 'Extraction is disabled in this environment.'],
    SCHEDULER_DISABLED: ['任务执行器未开启，暂时无法试抽取。', 'The task executor is disabled.'],
    BINDING_DISABLED: ['知识库尚未启用此模型，请先恢复应用。', 'Enable this knowledge base binding first.'],
    MODEL_UNAVAILABLE: ['没有可用的抽取模型，请先配置模型。', 'Configure an available extraction model first.'],
    NO_AVAILABLE_MODELS: ['没有可用的抽取模型，请先配置模型。', 'Configure an available extraction model first.'],
    PERMISSION_DENIED: ['当前角色不能发起试抽取。', 'Your role cannot start extraction.'],
  }
  const reasons = capabilities.value?.unavailableReasons ?? (!capabilities.value?.enabled ? ['EXTRACTION_DISABLED'] : [])
  return reasons.length ? reasons.map(code => labels[code] ? tr(...labels[code]) : tr('试抽取暂不可用，请刷新检查。', 'Extraction is unavailable. Refresh to check again.')) : ready.value ? [] : [tr('试抽取条件未确认，请刷新检查。', 'Extraction readiness is unconfirmed. Refresh to check again.')]
})
async function setup() {
  const current = ++epoch; controller.abort(); controller = new AbortController()
  capabilities.value = null; materials.value = []; history.value = []; source.value = ''; model.value = ''; busy.value = false; error.value = ''
  try {
    const cap = await extractionApi.capabilities(ws.value, props.graphId, controller.signal)
    if (current !== epoch) return
    capabilities.value = cap
    const [items, previous] = await Promise.all([cap.canStart ? sourceApi.materials(ws.value, props.knowledgeBaseId, controller.signal) : Promise.resolve([]), extractionApi.list(ws.value, props.graphId, 1, controller.signal)])
    if (current === epoch) { materials.value = items.map(item => ({ ...item, id: String(item.id) })); history.value = previous.items }
  } catch (e) { if (current === epoch) error.value = (e as Error).message }
}
watch(() => [ws.value, props.graphId, props.knowledgeBaseId], () => { selectedId.value = ''; taskId.value = typeof route.query.extractionTask === 'string' ? route.query.extractionTask : ''; actions.clear(); void setup() }, { immediate: true, flush: 'sync' })
watch(task, current => { if (current) history.value = history.value.map(item => item.id === current.id ? current : item) })
watch(suggestions, rows => { if (!rows.some(item => item.id === selectedId.value)) selectedId.value = rows[0]?.id ?? '' })
onBeforeUnmount(() => { epoch++; controller.abort() })
async function selectTask(id: string) { taskId.value = id; selectedId.value = ''; await router.replace({ query: { ...route.query, extractionTask: id || undefined } }) }
async function start() {
  if (busy.value || !ready.value || !materials.value.some(item => item.id === source.value) || !capabilities.value?.models.some(item => item.id === model.value)) return
  const current = epoch; busy.value = true; error.value = ''
  const key = `start:${ws.value}:${props.graphId}:${capabilities.value.ontologyRevisionId}:${source.value}:${model.value}`
  try {
    const result = await extractionApi.start(ws.value, props.graphId, { sourceRef: source.value, modelConfigId: model.value, expectedOntologyRevisionId: capabilities.value.ontologyRevisionId, operationId: stableAction(key) }, controller.signal)
    if (current === epoch) { history.value = [result, ...history.value.filter(item => item.id !== result.id)]; actions.delete(key); await selectTask(result.id) }
  } catch (e) { if (current === epoch) error.value = (e as Error).message }
  finally { if (current === epoch) busy.value = false }
}
async function action(kind: 'cancel' | 'retry') {
  if (!task.value || busy.value || (kind === 'retry' && !ready.value)) return
  const current = epoch; busy.value = true; error.value = ''
  try { await extractionApi[kind](ws.value, props.graphId, task.value.id, stableAction(`${kind}:${task.value.id}:${task.value.version}`), controller.signal); if (current === epoch) await refresh() }
  catch (e) { if (current === epoch) error.value = (e as Error).message }
  finally { if (current === epoch) busy.value = false }
}

</script>
<template><section class="extraction-wizard"><h3>{{ t('semantic.extraction.title') }}</h3><el-alert v-if="error || taskError" :title="error || taskError" type="error" :closable="false" /><template v-if="capabilities"><p class="metadata">{{ tr('应用模型', 'Applied model') }}：{{ capabilities.ontologyName || tr('已发布模型', 'Published model') }} · v{{ capabilities.ontologyVersion ?? '—' }}</p><el-alert v-for="reason in unavailable" :key="reason" :title="reason" type="info" :closable="false" /></template><el-button :disabled="busy" @click="setup">{{ tr('刷新可用状态', 'Refresh availability') }}</el-button><details v-if="ready" :open="!taskId" class="new-task"><summary>{{ t('semantic.extraction.start') }}</summary><el-form label-position="top" :disabled="busy" @submit.prevent="start"><el-form-item :label="t('semantic.extraction.source')"><el-select v-model="source" filterable><el-option v-for="item in materials" :key="item.id" :value="item.id" :label="item.title" /></el-select></el-form-item><el-form-item :label="t('semantic.extraction.model')"><el-select v-model="model"><el-option v-for="item in capabilities?.models" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item><p v-if="!materials.length">{{ tr('应用知识库暂无资料，请先上传资料。', 'Upload source material to this knowledge base first.') }}</p><el-button native-type="submit" type="primary" :loading="busy" :disabled="!ready || !source || !model">{{ t('semantic.extraction.start') }}</el-button></el-form></details><div v-if="history.length" class="history"><label>{{ t('semantic.extraction.history') }}<select :value="taskId" @change="selectTask(($event.target as HTMLSelectElement).value)"><option value="">{{ t('semantic.extraction.selectTask') }}</option><option v-for="item in history" :key="item.id" :value="item.id">{{ item.sourceTitle }} · {{ item.status }}</option></select></label></div><ExtractionTaskPanel v-if="task" :task="task" :busy="busy || loading" :enabled="ready" @refresh="refresh" @cancel="action('cancel')" @retry="action('retry')" /><p v-if="task?.status === 'SUCCEEDED' && !suggestions.length">{{ t('semantic.extraction.empty') }}</p><template v-if="task && suggestions.length"><label class="suggestion-selector">{{ t('semantic.extraction.suggestions', { count: suggestions.length }) }}<select :value="selectedId" @change="selectedId = ($event.target as HTMLSelectElement).value"><option v-for="item in suggestions" :key="item.id" :value="item.id">{{ item.subjectName }} · {{ item.assertionText ?? '—' }} · {{ item.status }}</option></select></label><SuggestionReviewPanel v-if="selected" :key="`${task.id}:${selected.id}`" :graph-id="graphId" :suggestion="selected" :source-text="task.sourceText ?? ''" :entities="entities" :document="document" @saved="refresh" @submitted="emit('submitted', $event)" /></template></section></template>
<style scoped>h3 { margin: 0 0 8px; } p { color: var(--mc-text-secondary); line-height: 1.7; }.new-task { margin: 16px 0; } summary { cursor: pointer; font-weight: 600; padding: 10px 0; }.metadata { font-size: 12px; overflow-wrap: anywhere; }.history { margin-top: 20px; }label { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }select { color: var(--mc-text-primary); background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 4px; padding: 9px; max-width: 100%; min-width: 0; }.suggestion-selector select { width: min(100%, 720px); }</style>
