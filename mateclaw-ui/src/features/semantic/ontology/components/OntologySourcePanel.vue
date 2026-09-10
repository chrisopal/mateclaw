<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ontologyApi } from '../../api/ontologyApi'
import { exactQuoteRange, sourceSelectionApi, type SourceKnowledgeBase, type SourceMaterial, type SourceMaterialSummary } from '../../api/sourceSelectionApi'
import type { AxiomDescriptor, OntologySourceBinding, OntologySourceOrigin, OntologySourceReview } from '../../api/types'
import type { DisplayProjection } from '../standardProjection'
import { businessRuleLabel } from '../businessModel'
import { useSemanticScope } from '../../shared/useSemanticScope'

type LoadState = 'idle' | 'loading' | 'ready' | 'error' | 'unknown'
type ReviewDecision = 'ACKNOWLEDGE' | 'REMODEL' | 'KEEP_HISTORICAL'
type BindingWithTitle = OntologySourceBinding & { sourceTitle?: string }

const props = defineProps<{
  ontologyId: string
  draftVersion?: number
  revisionId?: string
  axioms: AxiomDescriptor[]
  projection?: DisplayProjection | null
  canManage: boolean
  canReview: boolean
  focusedAxiomId?: string
}>()
const emit = defineEmits<{ changed: [draftVersion?: number]; 'clear-focus': [] }>()
const { locale } = useI18n()
const { begin, workspace } = useSemanticScope()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en

const bindings = ref<OntologySourceBinding[]>([])
const reviews = ref<OntologySourceReview[]>([])
const selectedAxiom = ref('')
const knowledgeBaseId = ref('')
const sourceRef = ref('')
const sourceMaterial = ref<SourceMaterial | null>(null)
const sourceMaterials = ref<SourceMaterialSummary[]>([])
const sourceKnowledgeBases = ref<SourceKnowledgeBase[]>([])
const exactQuote = ref('')
const origin = ref<OntologySourceOrigin>('EXPERT')
const reason = ref<Record<string, string>>({})
const busy = ref(false)
const pickerBusy = ref(false)
const error = ref('')
const pickerError = ref('')
const bindingsState = ref<LoadState>('idle')
const reviewsState = ref<LoadState>('idle')
const comparison = ref<Awaited<ReturnType<typeof ontologyApi.sourceReviewSnapshots>> | null>(null)
const comparisonVisible = ref(false)
const referenceDialogVisible = ref(false)
const reviewsVisible = ref(false)
const materialTitles = ref(new Map<string, string>())
const selectionEpoch = ref(0)
const pendingOperations = new Map<string, string>()

function operation(prefix: string) { return `${prefix}:${crypto.randomUUID()}` }
function retryOperation(key: string, prefix: string) {
  const existing = pendingOperations.get(key)
  if (existing) return existing
  const id = operation(prefix)
  pendingOperations.set(key, id)
  return id
}
function materialKey(knowledgeBase: string, source: string) { return `${knowledgeBase}:${source}` }
function ruleLabel(axiomId: string) {
  const value = businessRuleLabel(props.projection, axiomId, locale.value)
  return value || tr('业务规则', 'Business rule')
}
function originLabel(value: OntologySourceOrigin) {
  return ({ EXTRACTED: tr('文档提取', 'Document extraction'), EXPERT: tr('专家确认', 'Expert confirmation'), INFERRED: tr('推理生成', 'Reasoning generated') })[value]
}
function sourceStateLabel(value: string) {
  return ({ CURRENT: tr('当前有效', 'Current'), CHANGED: tr('资料已变化', 'Changed'), UNAVAILABLE: tr('资料不可访问', 'Unavailable'), UNCHECKED: tr('尚未检查', 'Not checked') } as Record<string, string>)[value] || value
}
function reviewStateLabel(value: string) {
  return ({ PENDING: tr('待确认', 'Needs confirmation'), REVIEWED: tr('已处理', 'Reviewed'), STALE: tr('已过期', 'Stale'), ACKNOWLEDGE: tr('已确认变化', 'Change acknowledged'), REMODEL: tr('已重新建模', 'Remodeled'), KEEP_HISTORICAL: tr('已保留历史依据', 'Historical evidence kept') } as Record<string, string>)[value] || value
}

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
  if (bindingsState.value === 'loading') return tr('正在加载参考资料', 'Loading references')
  if (bindingsState.value === 'error') return tr('参考资料加载失败', 'Error loading references')
  return focusedAxiomId.value ? tr('所选业务规则暂无参考资料', 'No references for this business rule') : tr('还没有关联参考资料', 'No references yet')
})
const reviewEmptyText = computed(() => {
  if (reviewsState.value === 'loading') return tr('正在检查资料变化', 'Loading source reviews')
  if (reviewsState.value === 'error') return tr('资料检查加载失败', 'Error loading source reviews')
  if (reviewsState.value === 'unknown') return tr('暂无资料复核权限', 'Source reviews unavailable')
  return tr('当前没有待确认事项', 'No items need confirmation')
})
const quoteRange = computed(() => {
  const material = sourceMaterial.value
  const quote = exactQuote.value.trim()
  if (!material || !quote) return null
  return exactQuoteRange(material.sourceText, quote)
})
const quoteMessage = computed(() => {
  if (!exactQuote.value.trim()) return ''
  if (!sourceMaterial.value) return tr('请选择资料后再填写原文摘录', 'Choose a document before entering the excerpt')
  if (!quoteRange.value) return tr('这段文字不在当前资料中，请复制资料里的完整原文', 'This excerpt is not in the selected document; copy the exact text')
  return tr('已在当前资料中定位原文，提交时会再次由服务端校验', 'Excerpt found; the server will verify it again when saving')
})
const canBind = computed(() => Boolean(props.draftVersion && selectedAxiom.value && knowledgeBaseId.value && sourceRef.value && sourceMaterial.value && quoteRange.value && origin.value && !pickerBusy.value))

function sourceTitle(item: OntologySourceBinding) {
  const withTitle = item as BindingWithTitle
  return withTitle.sourceTitle || materialTitles.value.get(materialKey(item.knowledgeBaseId, item.sourceRef)) || tr('已关联资料', 'Linked material')
}
async function hydrateTitles(items: OntologySourceBinding[], run: ReturnType<typeof begin>) {
  const groups = [...new Set(items.map(item => item.knowledgeBaseId).filter(Boolean))]
  let results: PromiseSettledResult<SourceMaterialSummary[]>[]
  try {
    results = await Promise.allSettled(groups.map(kb => sourceSelectionApi.materials(run.id, kb, run.signal)))
  } catch {
    return
  }
  if (!run.current()) return
  const next = new Map(materialTitles.value)
  results.forEach((result, index) => {
    if (result.status !== 'fulfilled') return
    ;(result.value || []).forEach(item => next.set(materialKey(groups[index]!, String(item.id)), item.title))
  })
  materialTitles.value = next
}
async function load() {
  const run = begin()
  busy.value = true
  error.value = ''
  bindings.value = []
  reviews.value = []
  materialTitles.value = new Map()
  bindingsState.value = props.revisionId ? 'loading' : 'ready'
  reviewsState.value = props.canReview || props.canManage ? 'loading' : 'unknown'
  const bindingRequest = props.revisionId ? Promise.resolve().then(() => ontologyApi.axiomSources(run.id, props.ontologyId, props.revisionId!, run.signal)) : Promise.resolve([])
  const reviewRequest = props.canReview || props.canManage ? Promise.resolve().then(() => ontologyApi.sourceReviews(run.id, props.ontologyId, run.signal)) : Promise.resolve([])
  const [boundResult, reviewResult] = await Promise.allSettled([bindingRequest, reviewRequest])
  if (!run.current()) return
  const messages: string[] = []
  if (boundResult.status === 'fulfilled') {
    bindings.value = boundResult.value
    bindingsState.value = 'ready'
    if (boundResult.value.length) void hydrateTitles(boundResult.value, run)
  } else {
    bindingsState.value = 'error'
    messages.push(`${tr('参考资料', 'References')}: ${(boundResult.reason as Error).message}`)
  }
  if (reviewResult.status === 'fulfilled') {
    reviews.value = reviewResult.value
    if (props.canReview || props.canManage) reviewsState.value = 'ready'
  } else {
    reviewsState.value = 'error'
    messages.push(`${tr('资料复核', 'Source reviews')}: ${(reviewResult.reason as Error).message}`)
  }
  error.value = messages.join('; ')
  busy.value = false
}
function resetPicker() {
  selectionEpoch.value++
  selectedAxiom.value = focusedAxiomId.value || ''
  knowledgeBaseId.value = ''
  sourceRef.value = ''
  sourceMaterials.value = []
  sourceMaterial.value = null
  exactQuote.value = ''
  origin.value = 'EXPERT'
  pickerError.value = ''
}
async function openPicker() {
  resetPicker()
  referenceDialogVisible.value = true
  const run = begin()
  pickerBusy.value = true
  pickerError.value = ''
  try {
    const result = await sourceSelectionApi.knowledgeBases(run.id, run.signal)
    if (run.current()) sourceKnowledgeBases.value = result
  } catch (e) {
    if (run.current()) pickerError.value = (e as Error).message
  } finally {
    if (run.current()) pickerBusy.value = false
  }
}
function closePicker() {
  referenceDialogVisible.value = false
  resetPicker()
}
async function loadMaterials() {
  const epoch = ++selectionEpoch.value
  sourceRef.value = ''
  sourceMaterials.value = []
  sourceMaterial.value = null
  exactQuote.value = ''
  if (!knowledgeBaseId.value) return
  const run = begin()
  pickerBusy.value = true
  pickerError.value = ''
  try {
    const result = await sourceSelectionApi.materials(run.id, knowledgeBaseId.value, run.signal)
    if (run.current() && epoch === selectionEpoch.value) sourceMaterials.value = result
  } catch (e) {
    if (run.current() && epoch === selectionEpoch.value) pickerError.value = (e as Error).message
  } finally {
    if (run.current() && epoch === selectionEpoch.value) pickerBusy.value = false
  }
}
async function loadMaterial() {
  const epoch = ++selectionEpoch.value
  sourceMaterial.value = null
  exactQuote.value = ''
  if (!knowledgeBaseId.value || !sourceRef.value) return
  const run = begin()
  pickerBusy.value = true
  pickerError.value = ''
  try {
    const result = await sourceSelectionApi.material(run.id, props.ontologyId, knowledgeBaseId.value, sourceRef.value, run.signal)
    if (run.current() && epoch === selectionEpoch.value) {
      sourceMaterial.value = result
      materialTitles.value = new Map(materialTitles.value).set(materialKey(result.knowledgeBaseId, result.sourceRef), result.sourceTitle)
    }
  } catch (e) {
    if (run.current() && epoch === selectionEpoch.value) pickerError.value = (e as Error).message
  } finally {
    if (run.current() && epoch === selectionEpoch.value) pickerBusy.value = false
  }
}
async function bind() {
  const range = quoteRange.value
  if (!props.draftVersion || !selectedAxiom.value || !knowledgeBaseId.value || !sourceRef.value || !sourceMaterial.value || !range || busy.value) return
  const run = begin()
  busy.value = true
  error.value = ''
  try {
    const result = await ontologyApi.bindAxiomSource(run.id, props.ontologyId, {
      expectedDraftVersion: props.draftVersion,
      operationId: retryOperation(JSON.stringify([run.id, props.ontologyId, props.draftVersion, selectedAxiom.value, knowledgeBaseId.value, sourceRef.value, sourceMaterial.value.sourceDigest, range.startCodePoint, range.endCodePoint, range.exactQuote, origin.value]), 'bind-source'),
      axiomId: selectedAxiom.value,
      knowledgeBaseId: knowledgeBaseId.value,
      sourceRef: sourceRef.value,
      expectedSourceDigest: sourceMaterial.value.sourceDigest,
      startCodePoint: range.startCodePoint,
      endCodePoint: range.endCodePoint,
      exactQuote: range.exactQuote,
      origin: origin.value,
    }, run.signal)
    if (run.current()) {
      bindings.value = [...bindings.value, result.binding]
      materialTitles.value = new Map(materialTitles.value).set(materialKey(knowledgeBaseId.value, sourceRef.value), sourceMaterial.value.sourceTitle)
      closePicker()
      emit('changed', result.draftVersion)
    }
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  } finally {
    if (run.current()) busy.value = false
  }
}
async function scan() {
  if (busy.value) return
  const run = begin()
  busy.value = true
  error.value = ''
  try {
    const result = await ontologyApi.scanSourceReviews(run.id, props.ontologyId, operation('scan-source'), run.signal)
    if (run.current()) reviews.value = result
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  } finally {
    if (run.current()) busy.value = false
  }
}
async function compareSources(item: OntologySourceReview) {
  const run = begin()
  busy.value = true
  error.value = ''
  comparison.value = null
  try {
    const result = await ontologyApi.sourceReviewSnapshots(run.id, props.ontologyId, item.id, run.signal)
    if (run.current()) { comparison.value = result; comparisonVisible.value = true }
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  } finally {
    if (run.current()) busy.value = false
  }
}
async function decide(item: OntologySourceReview, decision: ReviewDecision) {
  const reviewReason = reason.value[item.id]?.trim() || ''
  if (!reviewReason || busy.value) return
  const run = begin()
  busy.value = true
  error.value = ''
  try {
    const result = await ontologyApi.decideSourceReview(run.id, props.ontologyId, item.id, {
      operationId: retryOperation(JSON.stringify([run.id, props.ontologyId, item.id, item.observedDigest, decision, reviewReason]), 'source-review'),
      expectedObservedDigest: item.observedDigest,
      decision,
      reason: reviewReason,
    }, run.signal)
    const bound = props.revisionId ? await ontologyApi.axiomSources(run.id, props.ontologyId, props.revisionId, run.signal) : []
    if (run.current()) {
      bindings.value = bound
      reviews.value = reviews.value.map(row => row.id === result.id ? result : row)
      reason.value = { ...reason.value, [item.id]: '' }
      emit('changed')
    }
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  } finally {
    if (run.current()) busy.value = false
  }
}

watch(() => props.focusedAxiomId, value => { selectedAxiom.value = value?.trim() || '' }, { immediate: true })
watch(() => knowledgeBaseId.value, () => { void loadMaterials() })
watch(() => sourceRef.value, () => { if (sourceRef.value) void loadMaterial() })
watch(() => [workspace.currentWorkspaceId, props.ontologyId, props.revisionId, props.canReview, props.canManage], (next, previous) => {
  if (previous && (next[0] !== previous[0] || next[1] !== previous[1] || next[2] !== previous[2])) {
    referenceDialogVisible.value = false
    emit('clear-focus')
  }
  bindings.value = []
  reviews.value = []
  bindingsState.value = 'idle'
  reviewsState.value = 'idle'
  comparison.value = null
  comparisonVisible.value = false
  reviewsVisible.value = false
  pendingOperations.clear()
  void load()
}, { immediate: true, flush: 'sync' })
</script>

<template>
  <section data-ontology-source-panel class="semantic-source-panel">
    <header class="semantic-header source-header">
      <div>
        <h3>{{ tr('参考资料', 'Reference materials') }}</h3>
        <p class="semantic-muted">{{ tr('把业务规则和可核验的原文摘录关联起来，资料变化后再由负责人确认。', 'Connect business rules to verifiable excerpts and confirm changes when a document moves.') }}</p>
      </div>
      <div class="source-actions">
        <el-button v-if="canManage && draftVersion" data-testid="add-reference" :disabled="busy" type="primary" @click="openPicker">{{ tr('添加参考资料', 'Add reference') }}</el-button>
        <el-button v-if="canReview" data-testid="scan-references" :disabled="busy" @click="scan">{{ tr('检查资料变化', 'Check for changes') }}</el-button>
      </div>
    </header>
    <div v-if="focusedAxiomId" class="source-focus" data-testid="source-focus">
      <span>{{ tr('当前业务规则', 'Focused business rule') }}：{{ focusedAxiom ? ruleLabel(focusedAxiom.axiomId) : tr('已选规则', 'Selected rule') }}</span>
      <el-button link type="primary" @click="emit('clear-focus')">{{ tr('清除规则定位', 'Clear rule focus') }}</el-button>
    </div>
    <div class="source-counts">
      <span data-testid="selected-axiom-source-count">{{ tr('参考资料', 'References') }}：<span data-testid="source-bindings-status">{{ selectedSourceCount }}</span></span>
      <span data-testid="selected-axiom-pending-count">{{ tr('待确认', 'Needs confirmation') }}：<span data-testid="source-reviews-status">{{ selectedPendingCount }}</span></span>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="visibleBindings" :empty-text="bindingEmptyText" class="reference-table">
      <el-table-column :label="tr('业务规则', 'Business rule')" min-width="220"><template #default="{ row }"><span>{{ ruleLabel(row.axiomId) }}</span></template></el-table-column>
      <el-table-column :label="tr('参考资料', 'Reference material')" min-width="190"><template #default="{ row }"><span :data-source-ref="row.sourceRef">{{ sourceTitle(row) }}</span></template></el-table-column>
      <el-table-column :label="tr('原文摘录', 'Exact excerpt')" min-width="260" show-overflow-tooltip><template #default="{ row }"><span>{{ row.exactQuote }}</span></template></el-table-column>
      <el-table-column :label="tr('形成方式', 'How formed')" width="150"><template #default="{ row }">{{ originLabel(row.origin) }}</template></el-table-column>
      <el-table-column :label="tr('资料状态', 'Material status')" width="140"><template #default="{ row }">{{ sourceStateLabel(row.currentSourceState) }}</template></el-table-column>
      <el-table-column :label="tr('确认状态', 'Review status')" width="150"><template #default="{ row }">{{ reviewStateLabel(row.reviewState) }}</template></el-table-column>
    </el-table>
    <template v-if="canReview">
      <div class="review-toggle-row"><el-button data-testid="toggle-pending-reviews" link type="primary" @click="reviewsVisible = !reviewsVisible">{{ reviewsVisible ? tr('收起待确认事项', 'Hide items needing confirmation') : tr('查看待确认事项', 'View items needing confirmation') }}<el-tag size="small" type="warning">{{ selectedPendingCount }}</el-tag></el-button></div>
      <div v-if="reviewsVisible" class="source-reviews" data-testid="pending-reviews">
        <el-table :data="visibleReviewable" :empty-text="reviewEmptyText">
          <el-table-column :label="tr('业务规则', 'Business rule')" min-width="190"><template #default="{ row }"><span>{{ ruleLabel(visibleBindings.find(item => item.id === row.bindingId)?.axiomId || '') }}</span></template></el-table-column>
          <el-table-column :label="tr('资料状态', 'Material status')" width="140"><template #default="{ row }">{{ sourceStateLabel(row.sourceState) }}</template></el-table-column>
          <el-table-column :label="tr('操作', 'Actions')" min-width="450"><template #default="{ row }"><div class="review-actions"><el-input v-model="reason[row.id]" :placeholder="tr('填写处理理由', 'Reason for this decision')" :disabled="busy" /><el-button :disabled="busy" @click="compareSources(row)">{{ tr('查看变化', 'Compare') }}</el-button><el-button :disabled="busy || !reason[row.id]?.trim()" @click="decide(row, 'ACKNOWLEDGE')">{{ tr('确认变化', 'Acknowledge') }}</el-button><el-button :disabled="busy || !reason[row.id]?.trim()" @click="decide(row, 'REMODEL')">{{ tr('重新建模', 'Remodel') }}</el-button><el-button :disabled="busy || !reason[row.id]?.trim()" @click="decide(row, 'KEEP_HISTORICAL')">{{ tr('保留历史', 'Keep history') }}</el-button></div></template></el-table-column>
        </el-table>
      </div>
    </template>
    <el-dialog v-model="referenceDialogVisible" :title="tr('添加参考资料', 'Add reference material')" width="min(720px, 94vw)" destroy-on-close @closed="resetPicker">
      <el-alert v-if="pickerError" :title="pickerError" type="error" :closable="false" />
      <el-form label-position="top" :disabled="busy" data-testid="reference-form" @submit.prevent="bind">
        <el-form-item :label="tr('选择业务规则', 'Choose a business rule')" required><el-select v-model="selectedAxiom" filterable data-testid="reference-rule-select" :placeholder="tr('选择需要依据的规则', 'Choose the rule this supports')"><el-option v-for="axiom in axioms" :key="axiom.axiomId" :value="axiom.axiomId" :label="ruleLabel(axiom.axiomId)" /></el-select></el-form-item>
        <el-form-item :label="tr('选择知识库', 'Choose a knowledge base')" required><el-select v-model="knowledgeBaseId" filterable data-testid="reference-kb-select" :loading="pickerBusy" :placeholder="tr('按名称选择知识库', 'Choose by knowledge base name')"><el-option v-for="item in sourceKnowledgeBases" :key="String(item.id)" :value="String(item.id)" :label="item.name" /></el-select></el-form-item>
        <el-form-item v-if="knowledgeBaseId" :label="tr('选择资料', 'Choose a document')" required><el-select v-model="sourceRef" filterable data-testid="reference-document-select" :loading="pickerBusy" :placeholder="tr('按标题选择资料', 'Choose by document title')"><el-option v-for="item in sourceMaterials" :key="String(item.id)" :value="String(item.id)" :label="item.title" /></el-select><p v-if="!pickerBusy && !sourceMaterials.length" class="field-help">{{ tr('该知识库暂无可访问资料', 'No accessible documents in this knowledge base') }}</p></el-form-item>
        <el-form-item v-if="sourceMaterial" :label="tr('粘贴精确原文摘录', 'Paste the exact excerpt')" required>
          <details class="source-preview" open>
            <summary>{{ tr('查看资料原文并复制', 'View and copy source text') }}</summary>
            <pre data-testid="reference-source-preview">{{ sourceMaterial.sourceText }}</pre>
          </details>
          <el-input v-model="exactQuote" data-testid="reference-excerpt" type="textarea" :rows="4" :placeholder="tr('从上方资料正文复制需要作为依据的原文', 'Copy the supporting text from the source above')" />
          <p v-if="quoteMessage" class="field-help" :class="{ 'field-error': exactQuote.trim() && !quoteRange }">{{ quoteMessage }}</p>
        </el-form-item>
        <el-form-item v-if="sourceMaterial" :label="tr('依据形成方式', 'How was this evidence formed?')" required><el-radio-group v-model="origin" data-testid="reference-origin"><el-radio value="EXTRACTED">{{ tr('文档提取', 'Document extraction') }}</el-radio><el-radio value="EXPERT">{{ tr('专家确认', 'Expert confirmation') }}</el-radio><el-radio value="INFERRED">{{ tr('推理生成', 'Reasoning generated') }}</el-radio></el-radio-group></el-form-item>
        <p v-if="sourceMaterial" class="selected-material-title">{{ sourceMaterial.sourceTitle }}</p>
        <div class="dialog-actions"><el-button @click="closePicker">{{ tr('取消', 'Cancel') }}</el-button><el-button data-testid="save-reference" type="primary" native-type="submit" :loading="busy" :disabled="!canBind">{{ tr('关联参考资料', 'Link reference') }}</el-button></div>
      </el-form>
    </el-dialog>
    <el-dialog v-model="comparisonVisible" :title="tr('查看资料变化', 'Compare source versions')" width="min(980px, 94vw)"><div v-if="comparison" class="source-comparison"><section><h4>{{ tr('原始依据', 'Original evidence') }}</h4><p>{{ comparison.original.sourceTitle }}</p><pre>{{ comparison.original.sourceText }}</pre></section><section><h4>{{ tr('当前资料', 'Current material') }}</h4><template v-if="comparison.observed"><p>{{ comparison.observed.sourceTitle }}</p><pre>{{ comparison.observed.sourceText }}</pre></template><p v-else>{{ tr('当前资料不可访问，已保留原始快照。', 'Current material is unavailable; the original snapshot is retained.') }}</p></section></div></el-dialog>
  </section>
</template>

<style scoped>
.semantic-source-panel{margin-top:24px}.source-header{align-items:flex-start}.source-actions{display:flex;gap:8px;flex-wrap:wrap}.semantic-muted{color:var(--mc-text-secondary)}.source-focus,.source-counts{display:flex;align-items:center;gap:16px;margin:12px 0}.source-focus{flex-wrap:wrap}.source-focus span{overflow-wrap:anywhere}.source-counts{color:var(--mc-text-secondary);font-size:13px}.review-toggle-row{margin:16px 0 4px}.review-toggle-row :deep(.el-tag){margin-left:8px}.review-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.review-actions :deep(.el-input){width:190px}.field-help{margin:6px 0 0;color:var(--mc-text-secondary);font-size:12px}.field-error{color:var(--el-color-danger)}.selected-material-title{margin:0 0 14px;color:var(--mc-text-secondary);font-size:13px}.source-preview{margin-bottom:12px;border:1px solid var(--mc-border-color, #dcdfe6);border-radius:6px;padding:8px 12px;background:var(--mc-surface-subtle, #f8fafc)}.source-preview summary{cursor:pointer;color:var(--mc-text-secondary);font-size:13px}.source-preview pre{max-height:180px;overflow:auto;margin:8px 0 0;padding:10px;background:var(--mc-surface, #fff);white-space:pre-wrap;overflow-wrap:anywhere;user-select:text}.dialog-actions{display:flex;justify-content:flex-end;gap:8px;margin-top:8px}.source-comparison{display:grid;grid-template-columns:1fr 1fr;gap:20px}.source-comparison pre{white-space:pre-wrap;overflow-wrap:anywhere;margin:0}.source-comparison section{min-width:0}.reference-table :deep(.cell){overflow-wrap:anywhere}@media(max-width:700px){.source-header{display:block}.source-actions{margin-top:12px}.source-comparison{grid-template-columns:1fr}.review-actions :deep(.el-input){width:100%}}
</style>
