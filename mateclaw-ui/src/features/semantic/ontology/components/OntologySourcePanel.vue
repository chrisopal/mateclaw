<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ontologyApi } from '../../api/ontologyApi'
import type { AxiomDescriptor, OntologySourceBinding, OntologySourceOrigin, OntologySourceReview } from '../../api/types'
import { useSemanticScope } from '../../shared/useSemanticScope'

type LoadState = 'idle' | 'loading' | 'ready' | 'error' | 'unknown'
const props = defineProps<{ ontologyId: string; draftVersion?: number; revisionId?: string; axioms: AxiomDescriptor[]; canManage: boolean; canReview: boolean; focusedAxiomId?: string }>()
const emit = defineEmits<{ changed: [draftVersion?: number]; 'clear-focus': [] }>()
const { t, locale } = useI18n(); const { begin, workspace } = useSemanticScope()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const bindings = ref<OntologySourceBinding[]>([]); const reviews = ref<OntologySourceReview[]>([]); const selectedAxiom = ref(''); const knowledgeBaseId = ref(''); const sourceRef = ref(''); const expectedSourceDigest = ref(''); const exactQuote = ref(''); const startCodePoint = ref(0); const endCodePoint = ref(0); const origin = ref<OntologySourceOrigin>('EXPERT'); const reason = ref(''); const busy = ref(false); const error = ref('')
const bindingsState = ref<LoadState>('idle'); const reviewsState = ref<LoadState>('idle')
const comparison = ref<Awaited<ReturnType<typeof ontologyApi.sourceReviewSnapshots>> | null>(null)
const comparisonVisible = ref(false)
const pendingOperations = new Map<string, string>()
function retryOperation(key: string, prefix: string) { const existing = pendingOperations.get(key); if (existing) return existing; const id = operation(prefix); pendingOperations.set(key, id); return id }
async function compareSources(item: OntologySourceReview) { const run = begin(); busy.value = true; error.value = ''; comparison.value = null; try { const result = await ontologyApi.sourceReviewSnapshots(run.id, props.ontologyId, item.id, run.signal); if (run.current()) { comparison.value = result; comparisonVisible.value = true } } catch (e) { if (run.current()) error.value = (e as Error).message } finally { if (run.current()) busy.value = false } }
function operation(prefix: string) { return `${prefix}:${crypto.randomUUID()}` }
const focusedAxiomId = computed(() => props.focusedAxiomId?.trim() || '')
const focusedAxiom = computed(() => props.axioms.find(item => item.axiomId === focusedAxiomId.value))
const visibleBindings = computed(() => focusedAxiomId.value ? bindings.value.filter(item => item.axiomId === focusedAxiomId.value) : bindings.value)
const visibleBindingIds = computed(() => new Set(visibleBindings.value.map(item => item.id)))
const visibleReviews = computed(() => focusedAxiomId.value ? reviews.value.filter(item => visibleBindingIds.value.has(item.bindingId)) : reviews.value)
const visibleReviewable = computed(() => visibleReviews.value.filter(item => item.reviewState === 'PENDING'))
const selectedSourceCount = computed(() => {
  if (bindingsState.value === 'loading') return tr('加载中', 'Loading')
  if (bindingsState.value === 'error') return tr('加载失败', 'Error')
  return String(visibleBindings.value.length)
})
const selectedPendingCount = computed(() => {
  if (bindingsState.value === 'loading' || reviewsState.value === 'loading') return tr('加载中', 'Loading')
  if (bindingsState.value === 'error' || reviewsState.value === 'error') return tr('加载失败', 'Error')
  if (reviewsState.value === 'unknown') return tr('未知', 'Unknown')
  return String(visibleReviewable.value.length)
})
const bindingEmptyText = computed(() => {
  if (bindingsState.value === 'loading') return tr('正在加载来源绑定', 'Loading source bindings')
  if (bindingsState.value === 'error') return tr('来源绑定加载失败', 'Error loading source bindings')
  return focusedAxiomId.value ? tr('所选公理暂无来源绑定', 'No source bindings for selected axiom') : t('semantic.noSourceBindings', 'No axiom sources')
})
const reviewEmptyText = computed(() => {
  if (reviewsState.value === 'loading') return tr('正在加载来源复核', 'Loading source reviews')
  if (reviewsState.value === 'error') return tr('来源复核加载失败', 'Error loading source reviews')
  if (reviewsState.value === 'unknown') return tr('暂无来源复核权限', 'Source reviews unavailable')
  return t('semantic.noSourceReviews', 'No pending source reviews')
})
async function load() {
  const run = begin(); busy.value = true; error.value = ''; bindings.value = []; reviews.value = []
  bindingsState.value = props.revisionId ? 'loading' : 'ready'
  reviewsState.value = props.canReview || props.canManage ? 'loading' : 'unknown'
  const bindingRequest = props.revisionId ? Promise.resolve().then(() => ontologyApi.axiomSources(run.id, props.ontologyId, props.revisionId!, run.signal)) : Promise.resolve([])
  const reviewRequest = props.canReview || props.canManage ? Promise.resolve().then(() => ontologyApi.sourceReviews(run.id, props.ontologyId, run.signal)) : Promise.resolve([])
  const [boundResult, reviewResult] = await Promise.allSettled([bindingRequest, reviewRequest])
  if (!run.current()) return
  const messages: string[] = []
  if (boundResult.status === 'fulfilled') { bindings.value = boundResult.value; bindingsState.value = 'ready' } else { bindingsState.value = 'error'; messages.push(`Source bindings: ${(boundResult.reason as Error).message}`) }
  if (reviewResult.status === 'fulfilled') { reviews.value = reviewResult.value; if (props.canReview || props.canManage) reviewsState.value = 'ready' } else { reviewsState.value = 'error'; messages.push(`Source reviews: ${(reviewResult.reason as Error).message}`) }
  error.value = messages.join('; '); busy.value = false
}
async function bind() { if (!props.draftVersion || !selectedAxiom.value || !knowledgeBaseId.value || !sourceRef.value || !expectedSourceDigest.value || !exactQuote.value || busy.value) return; const run = begin(); busy.value = true; error.value = ''; try { const result = await ontologyApi.bindAxiomSource(run.id, props.ontologyId, { expectedDraftVersion: props.draftVersion, operationId: retryOperation(JSON.stringify([run.id, props.ontologyId, props.draftVersion, selectedAxiom.value, knowledgeBaseId.value, sourceRef.value, expectedSourceDigest.value, startCodePoint.value, endCodePoint.value, exactQuote.value, origin.value]), 'bind-source'), axiomId: selectedAxiom.value, knowledgeBaseId: knowledgeBaseId.value, sourceRef: sourceRef.value, expectedSourceDigest: expectedSourceDigest.value, startCodePoint: startCodePoint.value, endCodePoint: endCodePoint.value, exactQuote: exactQuote.value, origin: origin.value }, run.signal); if (run.current()) { bindings.value = [...bindings.value, result.binding]; expectedSourceDigest.value = ''; exactQuote.value = ''; emit('changed', result.draftVersion) } } catch (e) { if (run.current()) error.value = (e as Error).message } finally { if (run.current()) busy.value = false } }
async function scan() { if (busy.value) return; const run = begin(); busy.value = true; error.value = ''; try { const result = await ontologyApi.scanSourceReviews(run.id, props.ontologyId, operation('scan-source'), run.signal); if (run.current()) reviews.value = result } catch (e) { if (run.current()) error.value = (e as Error).message } finally { if (run.current()) busy.value = false } }
async function decide(item: OntologySourceReview, decision: 'ACKNOWLEDGE' | 'REMODEL' | 'KEEP_HISTORICAL') { if (!reason.value.trim() || busy.value) return; const run = begin(); busy.value = true; error.value = ''; try { const result = await ontologyApi.decideSourceReview(run.id, props.ontologyId, item.id, { operationId: retryOperation(JSON.stringify([run.id, props.ontologyId, item.id, item.observedDigest, decision, reason.value.trim()]), 'source-review'), expectedObservedDigest: item.observedDigest, decision, reason: reason.value.trim() }, run.signal); if (run.current()) {
    const bound = props.revisionId ? await ontologyApi.axiomSources(run.id, props.ontologyId, props.revisionId, run.signal) : []
    if (run.current()) { bindings.value = bound; reviews.value = reviews.value.map(row => row.id === result.id ? result : row); reason.value = ''; emit('changed') }
  } } catch (e) { if (run.current()) error.value = (e as Error).message } finally { if (run.current()) busy.value = false } }
watch(() => props.focusedAxiomId, value => { selectedAxiom.value = value?.trim() || '' }, { immediate: true })
watch(() => [workspace.currentWorkspaceId, props.ontologyId, props.revisionId, props.canReview, props.canManage], (next, previous) => {
  if (previous && (next[0] !== previous[0] || next[1] !== previous[1] || next[2] !== previous[2])) emit('clear-focus')
  bindings.value = []; reviews.value = []; bindingsState.value = 'idle'; reviewsState.value = 'idle'; comparison.value = null; comparisonVisible.value = false; pendingOperations.clear(); void load()
}, { immediate: true, flush: 'sync' })
</script>
<template>
    <section data-ontology-source-panel class="semantic-source-panel">
    <header class="semantic-header"><div><h3>{{ t('semantic.sourceBindings', 'Axiom sources') }}</h3><p class="semantic-muted">{{ t('semantic.sourceBindingsHelp', 'Pin exact source evidence to an OWL axiom; published snapshots remain immutable.') }}</p></div><el-button v-if="canReview" :disabled="busy" @click="scan">{{ t('semantic.scanSources', 'Scan source changes') }}</el-button></header>
    <div v-if="focusedAxiomId" class="source-focus" data-testid="source-focus"><span>{{ tr('聚焦公理', 'Focused axiom') }}: {{ focusedAxiom?.rendering || focusedAxiomId }}</span><el-button link type="primary" @click="emit('clear-focus')">{{ tr('清除公理聚焦', 'Clear axiom focus') }}</el-button></div>
    <div class="source-counts"><span data-testid="selected-axiom-source-count">{{ tr('来源', 'Sources') }}: <span data-testid="source-bindings-status">{{ selectedSourceCount }}</span></span><span data-testid="selected-axiom-pending-count">{{ tr('待复核', 'Pending reviews') }}: <span data-testid="source-reviews-status">{{ selectedPendingCount }}</span></span></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="canManage && draftVersion" label-position="top" inline :disabled="busy" @submit.prevent="bind">
      <el-form-item :label="t('semantic.axiom', 'Axiom')"><el-select v-model="selectedAxiom" filterable><el-option v-for="axiom in axioms" :key="axiom.axiomId" :value="axiom.axiomId" :label="axiom.rendering" /></el-select></el-form-item>
      <el-form-item :label="t('semantic.knowledgeBaseId', 'Knowledge base ID')"><el-input v-model="knowledgeBaseId" /></el-form-item><el-form-item :label="t('semantic.sourceRef', 'Source ID')"><el-input v-model="sourceRef" /></el-form-item><el-form-item :label="t('semantic.sourceDigest', 'Current source digest')"><el-input v-model="expectedSourceDigest" /></el-form-item><el-form-item :label="t('semantic.exactQuote', 'Exact quote')"><el-input v-model="exactQuote" /></el-form-item><el-form-item label="Start"><el-input-number v-model="startCodePoint" :min="0" /></el-form-item><el-form-item label="End"><el-input-number v-model="endCodePoint" :min="0" /></el-form-item><el-form-item :label="t('semantic.sourceOrigin', 'Origin')"><el-select v-model="origin"><el-option value="EXTRACTED" label="EXTRACTED" /><el-option value="EXPERT" label="EXPERT" /><el-option value="INFERRED" label="INFERRED" /></el-select></el-form-item><el-button native-type="submit" type="primary" :disabled="!selectedAxiom || !knowledgeBaseId || !sourceRef || !expectedSourceDigest || !exactQuote">{{ t('semantic.bindSource', 'Bind source') }}</el-button>
    </el-form>
    <el-table :data="visibleBindings" :empty-text="bindingEmptyText"><el-table-column prop="axiomId" :label="t('semantic.axiom', 'Axiom')" /><el-table-column prop="sourceRef" :label="t('semantic.sourceRef', 'Source ID')" /><el-table-column prop="exactQuote" :label="t('semantic.exactQuote', 'Exact quote')" /><el-table-column prop="currentSourceState" :label="t('semantic.sourceState', 'Source state')" /><el-table-column prop="reviewState" :label="t('semantic.reviewState', 'Review state')" /></el-table>
    <template v-if="canReview"><h4>{{ t('semantic.sourceReviews', 'Source reviews') }}</h4><el-input v-model="reason" :placeholder="t('semantic.sourceDecisionReason', 'Decision reason')" /><el-table :data="visibleReviewable" :empty-text="reviewEmptyText"><el-table-column prop="bindingId" label="Binding" /><el-table-column prop="sourceState" :label="t('semantic.sourceState', 'Source state')" /><el-table-column prop="observedDigest" :label="t('semantic.sourceDigest', 'Observed digest')" show-overflow-tooltip /><el-table-column width="340"><template #default="{ row }"><el-button :disabled="busy" @click="compareSources(row)">{{ t('semantic.compareSources', 'Compare source versions') }}</el-button><el-button :disabled="busy || !reason.trim()" @click="decide(row, 'ACKNOWLEDGE')">{{ t('semantic.acknowledge', 'Acknowledge') }}</el-button><el-button :disabled="busy || !reason.trim()" @click="decide(row, 'REMODEL')">{{ t('semantic.remodel', 'Remodel') }}</el-button><el-button :disabled="busy || !reason.trim()" @click="decide(row, 'KEEP_HISTORICAL')">{{ t('semantic.keepHistorical', 'Keep historical') }}</el-button></template></el-table-column></el-table></template>
    <el-dialog v-model="comparisonVisible" :title="t('semantic.compareSources', 'Compare source versions')" width="80%">
      <div v-if="comparison" class="source-comparison">
        <section><h4>{{ t('semantic.originalSource', 'Original source') }}</h4><p>{{ comparison.original.sourceTitle }}</p><pre>{{ comparison.original.sourceText }}</pre></section>
        <section><h4>{{ t('semantic.observedSource', 'Observed source') }}</h4><template v-if="comparison.observed"><p>{{ comparison.observed.sourceTitle }}</p><pre>{{ comparison.observed.sourceText }}</pre></template><p v-else>{{ t('semantic.sourceUnavailable', 'Source unavailable; original snapshot retained.') }}</p></section>
      </div>
    </el-dialog>
  </section>
</template>
<style scoped>.semantic-source-panel { margin-top: 24px; }.semantic-source-panel .semantic-header { align-items: flex-start; }.semantic-source-panel form { margin: 12px 0; }.semantic-source-panel :deep(.el-input), .semantic-source-panel :deep(.el-select) { max-width: 250px; }.semantic-source-panel :deep(.el-select) { width: 250px; max-width: 100%; }.semantic-muted { color: var(--mc-text-secondary); }.source-focus, .source-counts { display: flex; align-items: center; gap: 16px; margin: 12px 0; }.source-focus { flex-wrap: wrap; }.source-focus span { overflow-wrap: anywhere; }.source-counts { color: var(--mc-text-secondary); font-size: 13px; }.source-comparison { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }.source-comparison pre { white-space: pre-wrap; overflow-wrap: anywhere; }</style>
