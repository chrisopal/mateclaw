<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { extractionApi, type ExtractionCapabilities, type ExtractionTask } from '../api/extractionApi'
import { sourceApi } from '../api/sourceApi'
import type { Definition, SemanticEntity } from '../api/types'
import { useSemanticScope } from '../shared/useSemanticScope'
import { useExtractionTask } from './useExtractionTask'
import ExtractionTaskPanel from './ExtractionTaskPanel.vue'
import SuggestionReviewPanel from './SuggestionReviewPanel.vue'
const props = defineProps<{ graphId: string; knowledgeBaseId: string; definition: Definition; entities: SemanticEntity[] }>()
const emit = defineEmits<{ register: []; submitted: [id: string] }>()
const { t } = useI18n(), route = useRoute(), router = useRouter(), { workspace } = useSemanticScope()
const ws = computed(() => workspace.currentWorkspaceId ?? ''), graph = computed(() => props.graphId)
const taskId = ref(typeof route.query.extractionTask === 'string' ? route.query.extractionTask : '')
const { task, suggestions, loading, error: taskError, refresh } = useExtractionTask(ws, graph, taskId)
const capabilities = ref<ExtractionCapabilities | null>(null), materials = ref<{ id: string; title: string }[]>([]), history = ref<ExtractionTask[]>([])
const source = ref(''), model = ref(''), selectedId = ref(''), busy = ref(false), error = ref(''), historyPage = ref(1), historyTotal = ref(0)
const review = ref<InstanceType<typeof SuggestionReviewPanel>>()
const selected = computed(() => suggestions.value.find(s => s.id === selectedId.value))
let controller = new AbortController(), epoch = 0
const startOperation = ref({ payload: '', id: '' })
const startStorageKey = () => `semantic-extraction-start:${ws.value}:${props.graphId}`
const actions = new Map<string, string>()
function stableAction(key: string) { if (!actions.has(key)) actions.set(key, crypto.randomUUID()); return actions.get(key)! }
async function setup() {
  const current = ++epoch; controller.abort(); controller = new AbortController(); const signal = controller.signal
  capabilities.value = null; materials.value = []; history.value = []; source.value = ''; model.value = ''; error.value = ''; busy.value = false
  try {
    const cap = await extractionApi.capabilities(ws.value, props.graphId, signal)
    if (current !== epoch) return
    capabilities.value = cap
    const [items, previous] = await Promise.all([cap.enabled ? sourceApi.materials(ws.value, props.knowledgeBaseId, signal) : Promise.resolve([]), extractionApi.list(ws.value, props.graphId, historyPage.value, signal)])
    if (current !== epoch) return
    materials.value = items; history.value = previous.items; historyTotal.value = previous.total ?? previous.items.length
  } catch (e) { if (current === epoch) error.value = (e as Error).message }
}
watch(() => [ws.value, props.graphId], () => { selectedId.value = ''; historyPage.value = 1; taskId.value = typeof route.query.extractionTask === 'string' ? route.query.extractionTask : ''; void setup() }, { immediate: true, flush: 'sync' })
watch(suggestions, rows => { if (!rows.some(s => s.id === selectedId.value)) selectedId.value = rows[0]?.id ?? '' })
onBeforeUnmount(() => { epoch++; controller.abort() })
async function confirmLeave() { return review.value ? review.value.confirmLeave() : !busy.value }
defineExpose({ confirmLeave })
async function register() { if (await confirmLeave()) emit('register') }
async function selectTask(id: string) {
  if (review.value && !await review.value.confirmLeave()) return
  taskId.value = id; selectedId.value = ''
  await router.replace({ query: { ...route.query, extractionTask: id || undefined } })
}
async function selectSuggestion(event: Event) {
  const select = event.target as HTMLSelectElement, next = select.value
  if (review.value && !await review.value.confirmLeave()) { select.value = selectedId.value; return }
  selectedId.value = next
}
async function start() {
  if (busy.value || !source.value || !model.value) return
  if (review.value && !await review.value.confirmLeave()) return
  const current = epoch, payload = JSON.stringify([ws.value, props.graphId, source.value, model.value]); busy.value = true; error.value = ''
  if (startOperation.value.payload !== payload) {
    try { const saved = JSON.parse(sessionStorage.getItem(startStorageKey()) ?? 'null'); startOperation.value = saved?.payload === payload ? saved : { payload, id: crypto.randomUUID() } } catch { startOperation.value = { payload, id: crypto.randomUUID() } }
    sessionStorage.setItem(startStorageKey(), JSON.stringify(startOperation.value))
  }
  try {
    const result = await extractionApi.start(ws.value, props.graphId, { sourceRef: source.value, modelConfigId: model.value, operationId: startOperation.value.id }, controller.signal)
    if (current !== epoch) return
    sessionStorage.removeItem(startStorageKey()); startOperation.value = { payload: '', id: '' }
    taskId.value = result.id; history.value = [result, ...history.value.filter(x => x.id !== result.id)]
    await router.replace({ query: { ...route.query, extractionTask: result.id } })
  } catch (e) { if (current === epoch) error.value = (e as Error).message }
  finally { if (current === epoch) busy.value = false }
}
async function action(kind: 'cancel' | 'retry') {
  if (!task.value || busy.value) return
  const current = epoch, id = task.value.id; busy.value = true; error.value = ''
  try { await extractionApi[kind](ws.value, props.graphId, id, stableAction(`${kind}:${id}:${task.value.version}`), controller.signal); if (current === epoch) await refresh() }
  catch (e) { if (current === epoch) error.value = (e as Error).message }
  finally { if (current === epoch) busy.value = false }
}
async function pageHistory(page: number) {
  const current = epoch; historyPage.value = page
  try { const result = await extractionApi.list(ws.value, props.graphId, page, controller.signal); if (current === epoch) { history.value = result.items; historyTotal.value = result.total ?? result.items.length } }
  catch (e) { if (current === epoch) error.value = (e as Error).message }
}
</script>
<template>
  <section class="extraction-wizard">
    <h3>{{ t('semantic.extraction.title') }}</h3>
    <el-alert v-if="error || taskError" :title="error || taskError" type="error" :closable="false" />
    <el-alert v-if="capabilities && !capabilities.enabled" :title="t('semantic.extraction.disabled')" type="info" :closable="false" />
    <details v-if="capabilities?.enabled" :open="!taskId" class="new-task"><summary>{{ t('semantic.extraction.start') }}</summary>
    <el-form label-position="top" :disabled="busy" @submit.prevent="start">
      <el-form-item :label="t('semantic.extraction.source')"><el-select v-model="source" filterable><el-option v-for="item in materials" :key="item.id" :value="item.id" :label="item.title" /></el-select></el-form-item>
      <el-form-item :label="t('semantic.extraction.model')"><el-select v-model="model"><el-option v-for="item in capabilities.models" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
      <el-alert v-if="!capabilities.models.length" :title="t('semantic.extraction.noModel')" type="warning" :closable="false" />
      <p>{{ t('semantic.extraction.sendNotice') }}</p><p class="metadata">{{ t('semantic.extraction.binding') }} {{ capabilities.ontologyRevisionId }}</p>
      <el-button native-type="submit" type="primary" :loading="busy" :disabled="!source || !model">{{ t('semantic.extraction.start') }}</el-button>
    </el-form></details>
    <div v-if="history.length" class="history"><label>{{ t('semantic.extraction.history') }}<select :value="taskId" @change="selectTask(($event.target as HTMLSelectElement).value)"><option value="">{{ t('semantic.extraction.selectTask') }}</option><option v-for="item in history" :key="item.id" :value="item.id">{{ item.sourceTitle }} · {{ t(`semantic.extraction.states.${item.status}`) }} · {{ new Date(item.createdAt).toLocaleString() }}</option></select></label><el-pagination v-if="historyTotal > 20" :current-page="historyPage" :total="historyTotal" :page-size="20" layout="prev, pager, next" @current-change="pageHistory" /></div>
    <ExtractionTaskPanel v-if="task" :task="task" :busy="busy || loading" :enabled="capabilities?.enabled ?? false" @refresh="refresh" @cancel="action('cancel')" @retry="action('retry')" />
    <p v-if="task?.status === 'SUCCEEDED' && !suggestions.length">{{ t('semantic.extraction.empty') }}</p>
    <template v-if="task && suggestions.length">
      <label class="suggestion-selector">{{ t('semantic.extraction.suggestions', { count: suggestions.length }) }}<select :value="selectedId" @change="selectSuggestion"><option v-for="item in suggestions" :key="item.id" :value="item.id">{{ item.subjectName }} · {{ (item.predicateKind === 'RELATION' ? definition.relations : definition.properties).find(p => p.key === item.predicateKey)?.label ?? item.predicateKey }} · {{ t(`semantic.extraction.states.${item.status}`) }}</option></select></label>
      <SuggestionReviewPanel v-if="selected" :key="`${task.id}:${selected.id}`" ref="review" :graph-id="graphId" :suggestion="selected" :source-text="task.sourceText" :definition="definition" :entities="entities" @saved="refresh" @register="register" @submitted="$emit('submitted', $event)" />
      <el-button v-if="selected?.statementId" text @click="$emit('submitted', selected.statementId!)">{{ t('semantic.extraction.openStatement') }}</el-button>
    </template>
  </section>
</template>
<style scoped>
h3 { margin: 0 0 8px; } p { color: var(--mc-text-secondary); line-height: 1.7; }
.new-task { margin: 16px 0; } summary { cursor: pointer; font-weight: 600; padding: 10px 0; }
.metadata { font-size: 12px; overflow-wrap: anywhere; }.history { margin-top: 20px; }
label { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
select { color: var(--mc-text-primary); background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 4px; padding: 9px; max-width: 100%; min-width: 0; }
.suggestion-selector select { width: min(100%, 520px); } select:focus-visible { outline: 2px solid var(--mc-primary); }
</style>
