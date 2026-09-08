<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { vLoading } from 'element-plus'
import { ontologyApi } from '../../api/ontologyApi'
import { semanticError, type SemanticError } from '../../api/semanticErrors'
import type { ImpactReport, OntologyUsageItem } from '../../api/types'
import { useSemanticScope } from '../../shared/useSemanticScope'

const props = defineProps<{
  ontologyId: string
  targetRevisionId?: string
  expectedDraftVersion?: number
  enabled?: boolean
}>()
const { t, locale } = useI18n()
const router = useRouter()
const { workspace, begin, cancel } = useSemanticScope()
const items = ref<OntologyUsageItem[]>([])
const total = ref(0)
const page = ref(1)
const reports = ref<Record<string, ImpactReport>>({})
const busy = ref(false)
const analyzing = ref<string | null>(null)
const error = ref<SemanticError | null>(null)

const targetReady = computed(() => !!props.targetRevisionId || typeof props.expectedDraftVersion === 'number')
const targetLabel = computed(() => props.targetRevisionId ? t('semantic.impactRevision') : t('semantic.impactDraft'))
function clearResults() {
  items.value = []
  total.value = 0
  reports.value = {}
  busy.value = false
  analyzing.value = null
  error.value = null
}
function changeClassLabel(value: string) {
  const key = {
    ANNOTATION: 'annotation',
    ADDITIVE_OR_WIDENING: 'additiveOrWidening',
    POTENTIALLY_BREAKING: 'potentiallyBreaking',
    BREAKING: 'breaking',
  }[value]
  return key ? t('semantic.' + key) : value
}
function diagnosticKindLabel(value: string) {
  if (!value) return '—'
  const key = { ENTITY: 'entityDiagnostic', STATEMENT: 'statementDiagnostic', CHANGE_PROPOSAL: 'changeProposalDiagnostic' }[value]
  return key ? t('semantic.' + key) : value
}
function diagnosticCodeLabel(value: string) {
  if (!value) return '—'
  const key = `semantic.diagnosticCodes.${value}`
  return t(key, value)
}
function formatScanTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString(locale.value)
}

async function load() {
  if (props.enabled === false) {
    cancel()
    clearResults()
    return
  }
  const c = begin()
  busy.value = true
  error.value = null
  reports.value = {}
  analyzing.value = null
  try {
    const result = await ontologyApi.usage(c.id, props.ontologyId, page.value, 20, c.signal)
    if (c.current()) {
      items.value = result.items
      total.value = result.total
    }
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) busy.value = false
  }
}

async function analyze(item: OntologyUsageItem) {
  if (!targetReady.value || analyzing.value || props.enabled === false) return
  const c = begin()
  const nextReports = { ...reports.value }
  delete nextReports[item.graphId]
  reports.value = nextReports
  analyzing.value = item.graphId
  error.value = null
  try {
    const report = await ontologyApi.impact(c.id, props.ontologyId, {
      graphId: item.graphId,
      targetRevisionId: props.targetRevisionId,
      expectedDraftVersion: props.targetRevisionId ? undefined : props.expectedDraftVersion,
    }, c.signal)
    if (c.current()) reports.value = { ...reports.value, [item.graphId]: report }
  } catch (e) {
    if (c.current()) error.value = semanticError(e)
  } finally {
    if (c.current()) analyzing.value = null
  }
}

function locate(item: OntologyUsageItem, id: string, kind: string) {
  void router.push({ name: 'SemanticWorkbench', params: { graphId: item.graphId }, query: { focus: id, kind } })
}

watch(() => [workspace.currentWorkspaceId, props.ontologyId, props.targetRevisionId, props.expectedDraftVersion, props.enabled], () => {
  page.value = 1
  if (props.enabled === false) {
    cancel()
    clearResults()
    return
  }
  clearResults()
  void load()
}, { flush: 'sync' })
onMounted(load)
</script>
<template>
  <section class="semantic-panel semantic-impact-panel">
    <div class="semantic-header">
      <div>
        <h3>{{ t('semantic.usage') }}</h3>
        <p class="semantic-muted">{{ t('semantic.usageHelp') }}</p>
      </div>
      <el-tag v-if="targetReady" type="info">{{ targetLabel }}</el-tag>
    </div>
    <el-alert v-if="error" type="error" :title="t('semantic.errors.' + error.code, t('semantic.requestFailed'))" :description="error.message" :closable="false" />
    <el-empty v-if="!busy && !items.length" :description="t('semantic.noUsage')" />
    <div v-loading="busy" class="semantic-impact-table">
      <el-table v-if="items.length" :data="items" :empty-text="t('semantic.noUsage')">
        <el-table-column prop="kbName" :label="t('semantic.knowledgeBase')" min-width="180" />
        <el-table-column :label="t('semantic.version')" width="120"><template #default="{ row }">v{{ row.ontologyVersion }}</template></el-table-column>
        <el-table-column :label="t('semantic.state')" width="110"><template #default="{ row }">{{ t(row.enabled ? 'semantic.enabled' : 'semantic.disabled') }}</template></el-table-column>
        <el-table-column :label="t('semantic.actions')" width="150"><template #default="{ row }"><el-button link type="primary" :disabled="!workspace.can('publish:ontology') || !targetReady || analyzing === row.graphId" :loading="analyzing === row.graphId" @click="analyze(row)">{{ t('semantic.analyzeImpact') }}</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next, total" @current-change="load" />
    </div>
    <div v-for="item in items" :key="item.graphId + ':' + (reports[item.graphId]?.scannedAt ?? '')">
      <article v-if="reports[item.graphId]" class="semantic-impact-report">
        <div class="semantic-header">
          <h4>{{ item.kbName }} · {{ t('semantic.impact') }}</h4>
          <el-tag :type="reports[item.graphId].dataConformance === 'CONFORMS' ? 'success' : reports[item.graphId].dataConformance === 'INCOMPLETE' ? 'warning' : 'danger'">
            {{ t(reports[item.graphId].dataConformance === 'CONFORMS' ? 'semantic.conforms' : reports[item.graphId].dataConformance === 'VIOLATIONS' ? 'semantic.dataViolations' : 'semantic.incomplete', reports[item.graphId].dataConformance) }}
          </el-tag>
        </div>
        <p class="semantic-muted">{{ t('semantic.definitionChangeClass') }}: {{ changeClassLabel(reports[item.graphId].definitionChangeClass) }} · {{ t('semantic.scannedAt') }}: {{ formatScanTime(reports[item.graphId].scannedAt) }} · {{ t('semantic.graphVersion') }}: {{ reports[item.graphId].graphVersion }}</p>
        <div class="semantic-impact-counts">
          <span>{{ t('semantic.scanSummary', { entities: reports[item.graphId].scannedEntities, statements: reports[item.graphId].scannedStatements, proposals: reports[item.graphId].scannedProposals }) }}</span>
          <span>{{ t('semantic.affectedSummary', { entities: reports[item.graphId].affectedEntities, statements: reports[item.graphId].affectedStatements, proposals: reports[item.graphId].affectedProposals }) }}</span>
        </div>
        <el-alert v-if="reports[item.graphId].dataConformance === 'INCOMPLETE'" type="warning" :title="t('semantic.analysisIncomplete')" :closable="false" />
        <el-alert v-if="reports[item.graphId].detailsTruncated" type="info" :title="t('semantic.detailsTruncated', { count: reports[item.graphId].diagnostics.length })" :closable="false" />
        <el-table :data="reports[item.graphId].diagnostics" :empty-text="t('semantic.noDiagnostics')">
          <el-table-column :label="t('semantic.category')" width="150"><template #default="{ row }">{{ diagnosticKindLabel(row.kind) }}</template></el-table-column>
          <el-table-column :label="t('semantic.code')" width="190"><template #default="{ row }">{{ diagnosticCodeLabel(row.code) }}</template></el-table-column>
          <el-table-column :label="t('semantic.issue')" min-width="260"><template #default="{ row }"><span :title="row.message">{{ row.termKey || '—' }} · {{ diagnosticCodeLabel(row.code) }}</span></template></el-table-column>
          <el-table-column width="110"><template #default="{ row }"><el-button link type="primary" @click="locate(item, row.id, row.kind)">{{ t('semantic.locate') }}</el-button></template></el-table-column>
        </el-table>
      </article>
    </div>
  </section>
</template>
