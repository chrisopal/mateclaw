<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { vLoading } from 'element-plus'
import { ontologyApi } from '../../api/ontologyApi'
import { graphApi } from '../../api/graphApi'
import type { OntologyUsageItem, Revision } from '../../api/types'
import { useSemanticScope } from '../../shared/useSemanticScope'
const props = defineProps<{ ontologyId: string; target?: Revision; archived?: boolean }>()
const { locale } = useI18n(), router = useRouter(), { workspace, begin } = useSemanticScope()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const rows = ref<OntologyUsageItem[]>([]), page = ref(1), total = ref(0), busy = ref(false), error = ref('')
async function load() {
  const run = begin(); busy.value = true; error.value = ''
  try { const result = await ontologyApi.usage(run.id, props.ontologyId, page.value, 20, run.signal); if (run.current()) { rows.value = result.items; total.value = result.total } }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
async function toggle(row: OntologyUsageItem) {
  if (busy.value || !workspace.can('publish:ontology')) return
  const run = begin(); busy.value = true; error.value = ''
  try {
    const current = await graphApi.binding(run.id, row.kbId, run.signal)
    if (!run.current()) return
    if (current.graphId !== row.graphId || current.enabled !== row.enabled || current.ontologyRevisionId !== row.ontologyRevisionId) throw Error(tr('知识库状态已变化，请刷新后重试。', 'This application changed. Refresh and retry.'))
    const result = await graphApi.bind(run.id, row.kbId, { action: row.enabled ? 'DISABLE' : 'ENABLE', revisionId: row.ontologyRevisionId, expectedGraphVersion: current.graphVersion }, run.signal)
    if (run.current()) rows.value = rows.value.map(item => item.graphId === row.graphId ? { ...item, enabled: result.enabled, graphVersion: result.graphVersion } : item)
  } catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
function migrate(row: OntologyUsageItem) { if (props.target) void router.push({ path: `/semantic/graphs/${row.graphId}`, query: { tab: 'migration', targetRevision: props.target.id } }) }
watch(() => [workspace.currentWorkspaceId, props.ontologyId], () => { rows.value = []; page.value = 1; total.value = 0; void load() }, { immediate: true })
</script>
<template>
  <section class="semantic-panel ontology-applications" v-loading="busy">
    <header><div><h3>{{ tr('知识库应用', 'Knowledge base applications') }}</h3><p>{{ tr('各知识库独立使用固定版本。暂停后仍可查看历史。', 'Each knowledge base uses its own fixed version. History remains readable when paused.') }}</p></div><el-button :disabled="busy" @click="load">{{ tr('刷新', 'Refresh') }}</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <table class="applications-table">
      <thead><tr><th>{{ tr('知识库', 'Knowledge base') }}</th><th>{{ tr('使用版本', 'Current version') }}</th><th>{{ tr('状态', 'Status') }}</th><th>{{ tr('操作', 'Actions') }}</th></tr></thead>
      <tbody><tr v-for="row in rows" :key="row.graphId">
        <td class="application-name">{{ row.kbName }}</td>
        <td>{{ tr('使用版本', 'Current version') }} v{{ row.ontologyVersion }}</td>
        <td>{{ row.enabled ? tr('使用中', 'Active') : tr('已暂停', 'Paused') }}</td>
        <td>
        <div class="application-actions">
          <el-button text @click="router.push(`/semantic/graphs/${row.graphId}`)">{{ tr('查看知识', 'View knowledge') }}</el-button>
          <el-button v-if="target && target.id !== row.ontologyRevisionId" text @click="router.push({ path: `/semantic/ontologies/${ontologyId}/versions`, query: { from: row.ontologyRevisionId, revision: target.id } })">{{ tr('比较版本', 'Compare versions') }}</el-button>
          <el-button v-if="workspace.can('publish:ontology') && target && target.id !== row.ontologyRevisionId && target.availableForNewBindings && !archived" text :disabled="busy || !row.enabled" @click="migrate(row)">{{ tr('准备升级', 'Prepare upgrade') }}</el-button>
          <el-button v-if="workspace.can('publish:ontology')" text :disabled="busy" @click="toggle(row)">{{ row.enabled ? tr('暂停使用', 'Pause') : tr('恢复使用', 'Resume') }}</el-button>
        </div>
        </td>
      </tr><tr v-if="!rows.length"><td colspan="4">{{ tr('尚未应用到知识库', 'No knowledge base applications') }}</td></tr></tbody>
    </table>
    <el-pagination v-if="total > 20" v-model:current-page="page" :page-size="20" :total="total" layout="prev, pager, next" @current-change="load" />
  </section>
</template>
<style scoped>
.ontology-applications{min-width:0}.ontology-applications header{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:16px}.ontology-applications h3{margin:0 0 8px}.ontology-applications p{margin:0;color:var(--mc-text-secondary);font-size:13px}.application-actions{display:flex;flex-wrap:wrap;gap:4px}.application-actions :deep(.el-button + .el-button){margin-left:0}
.applications-table{width:100%;border-collapse:collapse;text-align:left;font-size:14px}.applications-table th,.applications-table td{padding:12px;border-bottom:1px solid var(--mc-border-color,#e4e7ed);vertical-align:middle;overflow-wrap:anywhere}.applications-table th{background:var(--mc-bg-secondary,#f5f7fa);color:var(--mc-text-secondary);font-size:12px;font-weight:500}.application-name{font-weight:500}.applications-table td:nth-child(2){white-space:nowrap}
@media(max-width:700px){.applications-table thead{display:none}.applications-table,.applications-table tbody,.applications-table tr,.applications-table td{display:block;width:100%}.applications-table tr{padding:12px 0;border-bottom:1px solid var(--mc-border-color,#e4e7ed)}.applications-table td{padding:4px 0;border:0}.application-actions{margin-top:8px}.application-actions :deep(.el-button){padding:8px}.ontology-applications header{gap:8px}.ontology-applications header>.el-button{flex-shrink:0}}
</style>
