<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { sourceChangeApi, type SourceChangeDecisionRequest } from '../api/sourceChangeApi'
import { useSemanticScope } from '../shared/useSemanticScope'
import type { SourceChange, SourceChangeDecision, SourceChangeReviewItem } from '../api/workbenchTypes'

const props = defineProps<{ graphId: string; graphVersion: number; canScan: boolean; canReview: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const { t, te } = useI18n()
const { workspace, begin, cancel } = useSemanticScope()

const changes = ref<SourceChange[]>([])
const pending = ref<SourceChangeReviewItem[]>([])
const items = ref<SourceChangeReviewItem[]>([])
const selectedChangeId = ref('')
const selectedItemId = ref('')
const decision = ref<SourceChangeDecision>('ACKNOWLEDGE')
const reason = ref('')
const error = ref('')
const busy = ref(false)
const graphVersion = ref(props.graphVersion)

const selectedItem = computed(() => items.value.find(item => item.id === selectedItemId.value) ?? pending.value.find(item => item.id === selectedItemId.value) ?? null)
const selectedChange = computed(() => changes.value.find(change => change.id === selectedChangeId.value) ?? null)
const reviewable = computed(() => props.canReview && !!selectedItem.value && !!reason.value.trim() && !busy.value)

function operationId() { return crypto.randomUUID() }
function statusName(status: string) {
  const key = `semantic.w.states.${status}`
  return te(key) ? t(key) : status
}
function digest(value: string | null | undefined) {
  if (!value) return '—'
  return value.length > 18 ? `${value.slice(0, 10)}…${value.slice(-6)}` : value
}
function clear() {
  cancel()
  changes.value = []
  pending.value = []
  items.value = []
  selectedChangeId.value = ''
  selectedItemId.value = ''
  reason.value = ''
  error.value = ''
  busy.value = false
  graphVersion.value = props.graphVersion
}
async function loadPending() {
  const run = begin()
  busy.value = true
  error.value = ''
  try {
    await fetchPending(run)
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  } finally {
    if (run.current()) busy.value = false
  }
}
async function fetchPending(run: ReturnType<typeof begin>) {
  const result = await sourceChangeApi.pending(run.id, props.graphId, 100, run.signal)
  if (!run.current()) return
  pending.value = result
  if (!selectedItemId.value && result[0]) selectedItemId.value = result[0].id
}
async function inspect(change: SourceChange) {
  selectedChangeId.value = change.id
  selectedItemId.value = ''
  items.value = []
  const run = begin()
  busy.value = true
  error.value = ''
  try {
    const result = await sourceChangeApi.items(run.id, props.graphId, change.id, run.signal)
    if (!run.current()) return
    items.value = result
    selectedItemId.value = result[0]?.id ?? ''
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  } finally {
    if (run.current()) busy.value = false
  }
}
async function scan() {
  if (!props.canScan) return
  const run = begin()
  busy.value = true
  error.value = ''
  try {
    const result = await sourceChangeApi.scan(run.id, props.graphId, {
      operationId: operationId(),
      expectedGraphVersion: graphVersion.value,
    }, run.signal)
    if (!run.current()) return
    graphVersion.value = result.graphMutationVersion
    changes.value = result.changes
    selectedChangeId.value = ''
    selectedItemId.value = ''
    items.value = []
    await fetchPending(run)
    if (run.current()) emit('changed')
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  } finally {
    if (run.current()) busy.value = false
  }
}
async function decide() {
  const current = selectedItem.value
  if (!current || !reason.value.trim() || !props.canReview) return
  const body: SourceChangeDecisionRequest = {
    operationId: operationId(),
    expectedObservedDigest: current.newDigest,
    expectedGraphVersion: graphVersion.value,
    decision: decision.value,
    reason: reason.value.trim(),
  }
  const run = begin()
  busy.value = true
  error.value = ''
  try {
    await sourceChangeApi.decide(run.id, props.graphId, current.id, body, run.signal)
    if (run.current()) {
      reason.value = ''
      selectedItemId.value = ''
      selectedChangeId.value = ''
      items.value = []
      await fetchPending(run)
      if (run.current()) emit('changed')
    }
  } catch (e) {
    if (run.current()) error.value = (e as Error).message
  } finally {
    if (run.current()) busy.value = false
  }
}
watch(() => props.graphVersion, value => { graphVersion.value = value })
watch(() => [workspace.currentWorkspaceId, props.graphId], () => { clear(); void loadPending() }, { immediate: true, flush: 'sync' })
</script>

<template>
  <section class="source-change-review" aria-labelledby="source-change-review-title">
    <div class="source-change-heading">
      <div>
        <h3 id="source-change-review-title">{{ t('semantic.w.sourceChanges') }}</h3>
        <p class="metadata">{{ t('semantic.w.sourceChangesHelp') }}</p>
      </div>
      <el-button type="primary" :disabled="!canScan" :loading="busy" @click="scan">{{ t('semantic.w.scanSourceChanges') }}</el-button>
    </div>
    <el-alert :title="t('semantic.w.sourceChangesNoRewrite')" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />

    <h4>{{ t('semantic.w.sourceChangesFound') }}</h4>
    <el-table v-if="changes.length" :data="changes" row-key="id" @row-click="inspect">
      <el-table-column :label="t('semantic.w.source')"><template #default="{ row }">{{ row.sourceRef }}<small class="metadata">{{ row.sourceKind }}</small></template></el-table-column>
      <el-table-column :label="t('semantic.w.sourceState')"><template #default="{ row }">{{ statusName(row.sourceState) }}</template></el-table-column>
      <el-table-column :label="t('semantic.w.sourceDigest')"><template #default="{ row }">{{ digest(row.oldDigest) }} → {{ digest(row.newDigest) }}</template></el-table-column>
      <el-table-column prop="affectedCount" :label="t('semantic.w.affected')" />
      <el-table-column prop="observedGraphVersion" :label="t('semantic.w.graphVersion')" />
    </el-table>
    <el-empty v-else :description="t('semantic.w.noSourceChanges')" />

    <h4>{{ t('semantic.w.sourceChangeItems') }}</h4>
    <el-table :data="selectedChange ? items : pending" row-key="id" :empty-text="t('semantic.w.noSourceChangeItems')" @row-click="row => selectedItemId = row.id">
      <el-table-column prop="itemKind" :label="t('semantic.w.item')" />
      <el-table-column :label="t('semantic.w.record')"><template #default="{ row }">{{ row.itemId }}<span v-if="row.itemRevision != null"> · r{{ row.itemRevision }}</span></template></el-table-column>
      <el-table-column :label="t('semantic.w.reviewState')"><template #default="{ row }">{{ statusName(row.reviewState) }}</template></el-table-column>
      <el-table-column :label="t('semantic.w.sourceDigest')"><template #default="{ row }">{{ digest(row.oldDigest) }} → {{ digest(row.newDigest) }}</template></el-table-column>
    </el-table>

    <el-card v-if="selectedItem" class="source-change-decision">
      <p class="metadata">{{ t('semantic.w.sourceChangeDecisionHint') }}</p>
      <el-select v-model="decision" :disabled="!canReview || busy" :placeholder="t('semantic.w.sourceChangeDecision')">
        <el-option value="ACKNOWLEDGE" :label="t('semantic.w.acknowledge')" />
        <el-option value="REMODEL" :label="t('semantic.w.remodel')" />
        <el-option value="KEEP_HISTORICAL" :label="t('semantic.w.keepHistorical')" />
      </el-select>
      <el-input v-model="reason" type="textarea" :rows="3" :disabled="!canReview || busy" :placeholder="t('semantic.w.sourceDecisionReason')" />
      <el-button type="primary" :disabled="!reviewable" :loading="busy" @click="decide">{{ t('semantic.w.sourceChangeDecide') }}</el-button>
    </el-card>
  </section>
</template>

<style scoped>
.source-change-review { margin-top: 20px; }
.source-change-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.source-change-heading h3, .source-change-review h4 { margin: 0 0 6px; }
.source-change-review h4 { margin-top: 20px; }
.metadata { display: block; color: var(--mc-text-secondary); overflow-wrap: anywhere; }
.source-change-decision { margin-top: 16px; }
.source-change-decision .el-select, .source-change-decision .el-textarea { display: block; width: 100%; max-width: 640px; margin-bottom: 12px; }
small.metadata { margin-top: 2px; }
</style>
