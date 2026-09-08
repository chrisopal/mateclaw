<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { formatValidity } from '../shared/formatValidity'
import { vLoading } from 'element-plus'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { graphApi } from '../api/graphApi'
import { graphQueryApi } from '../api/graphQueryApi'
import { sourceApi } from '../api/sourceApi'
import { statementApi } from '../api/statementApi'
import { useSemanticScope } from '../shared/useSemanticScope'
import type { SemanticEntity } from '../api/types'
import type { Change, Conflict, GraphDetail, GraphResult, Snapshot, Source, Statement } from '../api/workbenchTypes'
import EntityEditor from './EntityEditor.vue'
import SourceCapturePanel from './SourceCapturePanel.vue'
import SemanticGraphView from './SemanticGraphView.vue'
import CandidateEditor from '../review/CandidateEditor.vue'
import ExtractionWizard from '../extraction/ExtractionWizard.vue'
import StatementReviewDrawer from '../review/StatementReviewDrawer.vue'
import EvidenceDrawer from '../review/EvidenceDrawer.vue'
import SourceGovernancePanel from '../review/SourceGovernancePanel.vue'
import ConflictReviewPanel from '../review/ConflictReviewPanel.vue'
const route = useRoute(), { t, te } = useI18n(), { workspace, begin } = useSemanticScope()
const graphId = computed(() => String(route.params.graphId)), scopeKey = computed(() => `${workspace.currentWorkspaceId}:${graphId.value}`)
const canWrite = computed(() => workspace.accessLoaded && (workspace.isGlobalAdmin || workspace.isAtLeast('member')))
const canReview = computed(() => workspace.accessLoaded && (workspace.isGlobalAdmin || workspace.isAtLeast('admin')))
const detail = ref<GraphDetail | null>(null), entities = ref<SemanticEntity[]>([]), facts = ref<Statement[]>([]), candidates = ref<Statement[]>([]), changes = ref<Change[]>([]), conflicts = ref<Conflict[]>([]), sources = ref<Source[]>([]), snapshots = ref<Snapshot[]>([])
const graph = ref<GraphResult>({ nodes: [], edges: [], traceId: '', truncated: false }), selectedEntity = ref(''), selected = ref<Statement | null>(null), evidenceId = ref<string | null>(null), changeTarget = ref<Statement | null>(null)
const extraction = ref<InstanceType<typeof ExtractionWizard>>()
function beforeTabLeave(_next: string | number, previous: string | number) { return previous === 'extraction' && extraction.value ? extraction.value.confirmLeave() : true }
const tab = ref('facts'), error = ref(''), busy = ref(false), generation = ref(0), reason = ref(''), page = ref(1), totals = ref<Record<string, number>>({}), focusedChangeId = ref(''), focusApplied = ref('')
const total = computed(() => totals.value[tab.value] ?? 0)
const paginated = computed(() => ['facts', 'queue', 'changes', 'conflicts'].includes(tab.value))
function predicateName(row: Pick<Statement, 'predicateKind' | 'predicateKey'>) { const terms = row.predicateKind === 'RELATION' ? detail.value?.definition.relations : detail.value?.definition.properties; return terms?.find(p => p.key === row.predicateKey)?.label ?? row.predicateKey }
function statusName(status: string) { const key = `semantic.w.states.${status}`; return te(key) ? t(key) : status }
function entityName(id: string) { return entities.value.find(e => e.id === id)?.displayName ?? id }
async function load() {
  const run = begin(); busy.value = true; error.value = ''; evidenceId.value = null; generation.value++
  try {
    const [d, e, f, s, sn, c, ch, co] = await Promise.all([
      graphQueryApi.detail(run.id, graphId.value, run.signal), graphApi.entities(run.id, graphId.value, run.signal), statementApi.list(run.id, graphId.value, 'trusted', page.value, run.signal), sourceApi.sources(run.id, graphId.value, run.signal), sourceApi.snapshots(run.id, graphId.value, run.signal),
      canWrite.value ? statementApi.list(run.id, graphId.value, canReview.value ? 'review' : 'mine', page.value, run.signal) : Promise.resolve({ items: [], total: 0 }),
      canReview.value ? statementApi.changes(run.id, graphId.value, page.value, run.signal) : Promise.resolve({ items: [], total: 0 }), canReview.value ? statementApi.conflicts(run.id, graphId.value, page.value, run.signal) : Promise.resolve({ items: [], total: 0 }),
    ])
    if (!run.current()) return
    detail.value = d; entities.value = e.items; facts.value = f.items; totals.value = { facts: f.total ?? f.items.length, queue: c.total ?? c.items.length, changes: ch.total ?? ch.items.length, conflicts: co.total ?? co.items.length }; sources.value = s.items; snapshots.value = sn.items; candidates.value = c.items; changes.value = ch.items; conflicts.value = co.items
    if (selected.value) selected.value = [...c.items, ...f.items].find(x => x.id === selected.value?.id) ?? null
    selectedEntity.value = e.items.some(x => x.id === selectedEntity.value) ? selectedEntity.value : e.items[0]?.id ?? ''
    if (selectedEntity.value) { const result = await graphQueryApi.neighbors(run.id, graphId.value, selectedEntity.value, run.signal); if (run.current()) graph.value = result }
    else graph.value = { nodes: [], edges: [], traceId: '', truncated: false }
  } catch (e) { if (run.current()) { error.value = (e as Error).message; detail.value = null; graph.value = { nodes: [], edges: [], traceId: '', truncated: false } } }
  finally { if (run.current()) busy.value = false }
}
async function select(id: string) {
  selected.value = [...candidates.value, ...facts.value].find(f => f.id === id) ?? null
  if (selected.value) return
  const run = begin(); error.value = ''
  try {
    for (let next = 1; run.current(); next++) {
      const result = await statementApi.list(run.id, graphId.value, canReview.value ? 'review' : 'trusted', next, run.signal)
      if (!run.current()) return
      const found = result.items.find(f => f.id === id)
      if (found) { selected.value = found; return }
      if (next * 100 >= (result.total ?? result.items.length)) return
    }
  } catch (e) { if (run.current()) error.value = (e as Error).message }
}
async function reviewChange(change: Change, action: string) {
  const run = begin(); busy.value = true; error.value = ''
  try { await statementApi.reviewChange(run.id, graphId.value, change, action, reason.value, run.signal); if (run.current()) { reason.value = ''; await load() } }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
function proposeChange(statement: Statement) { changeTarget.value = statement; selected.value = null; tab.value = 'candidate' }
function queryString(value: unknown) { return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '') }
function scrollToFocus(id: string) {
  void nextTick(() => document.getElementById(id)?.scrollIntoView?.({ block: 'center', behavior: 'smooth' }))
}
function focusChange(id: string) {
  focusedChangeId.value = id
  tab.value = 'changes'
  scrollToFocus(`semantic-change-${id}`)
}
async function loadFocusedChange(id: string) {
  const run = begin()
  try {
    const result = await statementApi.getChange(run.id, graphId.value, id, run.signal)
    if (run.current()) {
      changes.value = [result, ...changes.value.filter((item) => item.id !== id)]
      focusChange(id)
    }
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  }
}
function applyRouteFocus() {
  const id = queryString(route.query?.focus), kind = queryString(route.query?.kind), marker = `${kind}:${id}`
  if (busy.value || !detail.value || !id || !kind || focusApplied.value === marker) return
  if (page.value !== 1) { page.value = 1; void load(); return }
  if (kind === 'ENTITY') {
    if (!entities.value.some((entity) => entity.id === id)) return
    focusApplied.value = marker
    tab.value = 'entities'
    selectedEntity.value = id
    scrollToFocus(`semantic-entity-${id}`)
    return
  }
  if (kind === 'STATEMENT') {
    focusApplied.value = marker
    tab.value = canWrite.value && candidates.value.some((statement) => statement.id === id) ? 'queue' : 'facts'
    void select(id)
    return
  }
  if (kind === 'CHANGE_PROPOSAL') {
    focusApplied.value = marker
    const found = changes.value.some((change) => change.id === id)
    if (found) focusChange(id)
    else void loadFocusedChange(id)
  }
}
watch(() => route.query?.extractionTask, value => { if (value) tab.value = 'extraction' }, { immediate: true })
watch(tab, () => { if (page.value !== 1) { page.value = 1; void load() } })
watch(() => [route.query?.focus, route.query?.kind, busy.value, entities.value.length, facts.value.length, candidates.value.length, changes.value.length], applyRouteFocus, { flush: 'post' })
watch(scopeKey, () => { detail.value = null; selected.value = null; evidenceId.value = null; changeTarget.value = null; focusedChangeId.value = ''; focusApplied.value = ''; facts.value = []; candidates.value = []; changes.value = []; page.value = 1; void load() }, { immediate: true, flush: 'sync' })
</script>
<template><main class="semantic-workbench"><header><div><h2>{{ t('semantic.w.title') }}</h2><p class="workbench-intro">{{ t('semantic.w.workbenchHelp') }}</p><p v-if="detail" class="workbench-meta">{{ t('semantic.w.ontologyVersion') }} {{ detail.binding.ontologyVersion }} · {{ t('semantic.w.graphVersion') }} {{ detail.binding.graphVersion }} · {{ graphId }}</p></div><el-button :loading="busy" @click="load">{{ t('semantic.w.refresh') }}</el-button></header><el-alert v-if="error" :title="error" type="error" :closable="false" />
<section v-if="detail" :key="scopeKey" v-loading="busy"><el-alert v-if="!detail.binding.enabled" :title="t('semantic.bindingDisabled')" :closable="false" /><el-tabs v-model="tab" :before-leave="beforeTabLeave">
<el-tab-pane :label="t('semantic.w.facts')" name="facts"><p>{{ t('semantic.w.businessTimeHelp') }}</p><el-select v-model="selectedEntity" :placeholder="t('semantic.w.subject')" @change="load"><el-option v-for="entity in entities" :key="entity.id" :value="entity.id" :label="entity.displayName" /></el-select><SemanticGraphView :result="graph" :definition="detail.definition" @select-statement="select($event.statementId)" @select-entity="selectedEntity = $event; load()" /><p v-if="graph.truncated">{{ t('semantic.w.truncated') }}</p><el-table :empty-text="t('semantic.w.noFacts')" :data="facts" @row-click="selected = $event"><el-table-column :label="t('semantic.w.subject')"><template #default="{ row }">{{ entityName(row.subjectId) }}</template></el-table-column><el-table-column :label="t('semantic.w.predicate')"><template #default="{ row }">{{ predicateName(row) }}</template></el-table-column><el-table-column :label="t('semantic.w.value')"><template #default="{ row }">{{ row.value ?? entityName(row.targetEntityId) }} {{ row.unit }}</template></el-table-column><el-table-column prop="revision" :label="t('semantic.w.version')" /><el-table-column :label="t('semantic.w.support')"><template #default="{ row }">{{ statusName(row.supportStatus) }}</template></el-table-column><el-table-column :label="t('semantic.w.evidence')" min-width="110"><template #default="{ row }"><el-button text @click.stop="selected = row">{{ t('semantic.w.viewEvidence') }} ({{ row.evidenceIds.length }})</el-button></template></el-table-column><el-table-column v-if="canWrite"><template #default="{ row }"><el-button text @click.stop="proposeChange(row)">{{ t('semantic.w.proposeChange') }}</el-button></template></el-table-column></el-table></el-tab-pane>
<el-tab-pane v-if="canWrite && detail.binding.enabled" :label="t('semantic.w.entities')" name="entities"><EntityEditor :graph-id="graphId" :definition="detail.definition" @saved="load" /><el-table :data="entities" :row-class-name="({ row }) => row.id === selectedEntity ? 'semantic-focus-row' : ''"><el-table-column :label="t('semantic.w.name')"><template #default="{ row }"><span :id="`semantic-entity-${row.id}`">{{ row.displayName }}</span></template></el-table-column><el-table-column :label="t('semantic.w.type')"><template #default="{ row }">{{ detail.definition.types.find(type => type.key === row.typeKey)?.label ?? row.typeKey }}</template></el-table-column></el-table></el-tab-pane>
<el-tab-pane :label="t('semantic.w.sources')" name="sources"><SourceCapturePanel v-if="canWrite && detail.binding.enabled" :graph-id="graphId" :knowledge-base-id="detail.binding.knowledgeBaseId" @saved="load" /><SourceGovernancePanel :graph-id="graphId" :sources="sources" :snapshots="snapshots" :can-review="canReview" @saved="load" /></el-tab-pane>
<el-tab-pane v-if="canWrite && detail.binding.enabled" :label="t('semantic.w.propose')" name="candidate"><el-button v-if="changeTarget" @click="changeTarget = null">{{ t('semantic.w.newCandidate') }}</el-button><CandidateEditor :graph-id="graphId" :definition="detail.definition" :entities="entities" :snapshots="snapshots" :target="changeTarget" @saved="changeTarget = null; tab = 'queue'; load()" /></el-tab-pane>
<el-tab-pane v-if="canWrite && detail.binding.enabled" :label="t('semantic.extraction.title')" name="extraction"><ExtractionWizard ref="extraction" v-if="tab === 'extraction'" :graph-id="graphId" :knowledge-base-id="detail.binding.knowledgeBaseId" :definition="detail.definition" :entities="entities" @register="tab = 'entities'" @submitted="tab = 'queue'; load().then(() => select($event))" /></el-tab-pane>
<el-tab-pane v-if="canWrite" :label="t('semantic.w.queue')" name="queue"><el-table :empty-text="t('semantic.w.noCandidates')" :data="candidates" @row-click="selected = $event"><el-table-column :label="t('semantic.w.predicate')"><template #default="{ row }">{{ predicateName(row) }}</template></el-table-column><el-table-column :label="t('semantic.w.subject')"><template #default="{ row }">{{ entityName(row.subjectId) }}</template></el-table-column><el-table-column :label="t('semantic.w.value')"><template #default="{ row }">{{ row.value ?? entityName(row.targetEntityId) }} {{ row.unit }}</template></el-table-column><el-table-column :label="t('semantic.w.status')"><template #default="{ row }">{{ statusName(row.reviewStatus) }}</template></el-table-column><el-table-column :label="t('semantic.w.support')"><template #default="{ row }">{{ statusName(row.supportStatus) }}</template></el-table-column><el-table-column :label="t('semantic.w.evidence')" min-width="110"><template #default="{ row }"><el-button text @click.stop="selected = row">{{ t('semantic.w.viewEvidence') }} ({{ row.evidenceIds.length }})</el-button></template></el-table-column><el-table-column prop="revision" :label="t('semantic.w.version')" /></el-table></el-tab-pane>
<el-tab-pane v-if="canReview" :label="t('semantic.w.changes')" name="changes"><el-input v-model="reason" :placeholder="t('semantic.w.reason')" /><el-card v-for="change in changes" :id="`semantic-change-${change.id}`" :key="change.id" :class="{ 'semantic-focus-target': focusedChangeId === change.id }"><p>{{ statusName(change.status) }} · r{{ change.expectedRevision }}</p><small class="metadata">{{ change.targetStatementId }}</small><template v-if="change.content"><p>{{ entityName(change.content.subjectId) }} · {{ predicateName(change.content) }} · {{ change.content.value ?? entityName(change.content.targetEntityId ?? '') }} {{ change.content.unit }}</p><p>{{ formatValidity(change.content, t) }}</p><el-button v-for="id in change.content.evidenceIds" :key="id" text @click="evidenceId = id">{{ t('semantic.w.evidence') }} {{ id }}</el-button></template><template v-if="change.status === 'PENDING'"><el-button :disabled="busy || !reason.trim() || !change.content" @click="reviewChange(change, 'ACCEPT')">{{ t('semantic.w.accept') }}</el-button><el-button :disabled="busy || !reason.trim()" @click="reviewChange(change, 'REJECT')">{{ t('semantic.w.reject') }}</el-button></template></el-card></el-tab-pane>
<el-tab-pane v-if="canReview" :label="t('semantic.w.conflicts')" name="conflicts"><ConflictReviewPanel :graph-id="graphId" :conflicts="conflicts" :statements="[...facts, ...candidates]" :entities="entities" :definition="detail.definition" @saved="load" @select="select" @evidence="evidenceId = $event" /></el-tab-pane>
</el-tabs><el-pagination v-if="paginated && total > 100" v-model:current-page="page" :page-size="100" :total="total" layout="prev, pager, next" @current-change="load" /><StatementReviewDrawer :definition="detail.definition" :entities="entities" :graph-id="graphId" :statement="selected" :can-review="canReview" @close="selected = null" @saved="load" @evidence="evidenceId = $event" @change="proposeChange" /><EvidenceDrawer :graph-id="graphId" :evidence-id="evidenceId" :generation="generation" @close="evidenceId = null" /></section></main></template>
<style scoped>
.workbench-meta { font-size: 12px; }
.metadata { color: var(--mc-text-secondary); overflow-wrap: anywhere; }
.semantic-focus-target, :deep(.semantic-focus-row) { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.semantic-workbench { box-sizing: border-box; padding: 24px; width: 100%; min-width: 0; max-width: 1500px; margin: 0 auto; overflow: hidden; }
header { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 12px; }
header > div { min-width: 0; }
header h2 { margin: 0; font-size: 22px; font-weight: 600; line-height: 1.4; }
header p { color: var(--mc-text-secondary); overflow-wrap: anywhere; margin-top: 6px; }
section { min-width: 0; }
:deep(.el-card) { margin: 12px 0; }
:deep(.el-form) { max-width: 760px; }
:deep(.el-select) { min-width: 0; width: 100%; max-width: 360px; }
@media(max-width: 600px) {
  .semantic-workbench { padding: 12px; }
  header { align-items: flex-start; gap: 8px; }
  header h2 { font-size: 20px; }
}
</style>
