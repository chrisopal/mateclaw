<template>
  <main class="bidding-page">
    <header class="page-heading">
      <h1>{{ l('投标项目', 'Bidding projects') }}</h1>
      <el-button v-if="capabilities?.canWrite" type="primary" @click="openCreate">{{ l('新建项目', 'New project') }}</el-button>
    </header>
    <el-alert v-if="unavailable" :title="l('投标模块尚未启用', 'Bidding is not enabled')" type="info" :closable="false" />
    <el-alert v-else-if="error" :title="error" type="error" show-icon :closable="false">
      <el-button type="primary" size="small" @click="load">{{ l('重新加载', 'Reload') }}</el-button>
    </el-alert>
    <template v-else>
      <section class="metrics" aria-label="Project metrics">
        <div v-for="metric in metricRows" :key="metric.key" class="metric"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong><small v-if="metric.secondary">{{ metric.secondary }}</small></div>
      </section>
      <div class="filters">
        <el-input v-model="query" clearable :placeholder="l('项目名称', 'Project name')" @keyup.enter="search" />
        <el-select v-model="ownerId" clearable :placeholder="l('全部负责人', 'All owners')" @change="search">
          <el-option v-for="member in members" :key="String(member.userId)" :label="memberLabel(member)" :value="String(member.userId)" />
        </el-select>
        <el-select v-model="stage" clearable :placeholder="l('全部阶段', 'All stages')" @change="search">
          <el-option v-for="value in stages" :key="value" :label="stageLabel(value)" :value="value" />
        </el-select>
        <el-button @click="search">{{ l('筛选', 'Filter') }}</el-button>
      </div>
      <div class="table-scroll">
        <el-table v-loading="loading" :data="projects" row-key="id" empty-text="">
          <el-table-column :label="l('项目 / 标段', 'Project / lot')" min-width="240">
            <template #default="{ row }"><button class="project-link" @click="router.push(`/bidding/${row.id}`)">{{ row.name }}<small>{{ row.lotName }}</small></button></template>
          </el-table-column>
          <el-table-column :label="l('负责人', 'Owner')" min-width="150"><template #default="{ row }">{{ ownerName(row.ownerId) }}</template></el-table-column>
          <el-table-column :label="l('阶段', 'Stage')" width="150"><template #default="{ row }"><el-tag effect="plain">{{ stageLabel(row.stage) }}</el-tag></template></el-table-column>
          <el-table-column :label="l('操作', 'Actions')" width="132" fixed="right"><template #default="{ row }"><div class="actions"><el-button type="primary" plain size="small" @click="router.push(`/bidding/${row.id}`)">{{ l('打开', 'Open') }}</el-button></div></template></el-table-column>
          <template #empty><el-empty :description="l('暂无投标项目', 'No bidding projects')" /></template>
        </el-table>
      </div>
      <el-pagination v-if="total > pageSize" v-model:current-page="page" :page-size="pageSize" :total="total" layout="prev, pager, next" @current-change="load" />
    </template>
    <el-dialog v-model="createOpen" :title="l('新建投标项目', 'New bidding project')" width="min(560px, 94vw)" :close-on-click-modal="false">
      <el-form label-position="right" label-width="110px">
        <el-form-item :label="l('项目名称', 'Project name')" required><el-input v-model="form.name" maxlength="200" /></el-form-item>
        <el-form-item :label="l('标段', 'Lot')" required><el-input v-model="form.lotName" maxlength="200" /></el-form-item>
        <el-form-item :label="l('负责人', 'Owner')" required>
          <el-select v-model="form.ownerId" filterable :loading="membersLoading" :disabled="membersLoading || !members.length" :placeholder="l('选择工作区成员', 'Select a workspace member')">
            <el-option v-for="member in members" :key="String(member.userId)" :label="memberLabel(member)" :value="String(member.userId)" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      <template #footer><el-button @click="createOpen = false">{{ l('取消', 'Cancel') }}</el-button><el-button type="primary" :disabled="!form.name.trim() || !form.lotName.trim() || !form.ownerId || !capabilities?.canWrite" :loading="saving" @click="create">{{ l('创建', 'Create') }}</el-button></template>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { biddingApi } from '../api/biddingApi'
import { apiUnavailable, isCurrentRequest, operationId } from '../shared/state'
import type { Capabilities, Dashboard, Member, Project } from '../api/types'

const router = useRouter(), { locale } = useI18n(), workspace = useWorkspaceStore()
const l = (zh: string, en: string) => String(locale.value).startsWith('zh') ? zh : en
const projects = ref<Project[]>([]), members = ref<Member[]>([]), dashboard = ref<Dashboard>({ inProgress: 0, dueWithin7Days: 0, overdueDeadlines: 0, unknownDeadlines: 0, pendingConfirmation: 0, failedTasks: 0 })
const capabilities = ref<Capabilities>(), loading = ref(false), membersLoading = ref(false), saving = ref(false), error = ref(''), formError = ref(''), unavailable = ref(false)
const query = ref(''), ownerId = ref(''), stage = ref(''), page = ref(1), total = ref(0), pageSize = 20, createOpen = ref(false)
const form = reactive({ name: '', lotName: '', ownerId: '' })
let controller: AbortController | undefined, unregister: (() => void) | undefined, requestGeneration = 0, workspaceGeneration = 0, createMembersGeneration = 0
const stages = ['SETUP', 'ANALYSIS', 'OUTLINE', 'WRITING', 'REVIEW', 'ARCHIVED']
const metricRows = computed(() => [
  { key: 'inProgress', label: l('在办项目', 'In progress'), value: dashboard.value.inProgress },
  { key: 'dueWithin7Days', label: l('7 天内截止', 'Due within 7 days'), value: dashboard.value.dueWithin7Days, secondary: l(`逾期 ${dashboard.value.overdueDeadlines} · 日期未知 ${dashboard.value.unknownDeadlines}`, `Overdue ${dashboard.value.overdueDeadlines} · Unknown ${dashboard.value.unknownDeadlines}`) },
  { key: 'pendingConfirmation', label: l('待确认', 'Awaiting confirmation'), value: dashboard.value.pendingConfirmation },
  { key: 'failedTasks', label: l('失败任务', 'Failed tasks'), value: dashboard.value.failedTasks },
])
function memberLabel(member: Member) { return member.nickname || member.username || l('成员不可用', 'Member unavailable') }
function ownerName(id: string) { return memberLabel(members.value.find(member => String(member.userId) === String(id)) || { userId: id }) }
function stageLabel(value: string) { const labels: Record<string, [string, string]> = { SETUP: ['准备中', 'Setup'], ANALYSIS: ['招标解析', 'Analysis'], OUTLINE: ['目录规划', 'Outline'], WRITING: ['技术标写作', 'Writing'], REVIEW: ['审核', 'Review'], ARCHIVED: ['已归档', 'Archived'] }; return labels[value]?.[String(locale.value).startsWith('zh') ? 0 : 1] || value || '—' }
async function load() {
  controller?.abort(); controller = new AbortController(); const signal = controller.signal, generation = ++requestGeneration, ws = workspace.currentWorkspaceId || '', projectId = ''
  const captured = { name: query.value, ownerId: ownerId.value, stage: stage.value, page: page.value }
  const current = () => !signal.aborted && generation === requestGeneration && isCurrentRequest(ws, projectId, workspace.currentWorkspaceId || '', '')
    && captured.name === query.value && captured.ownerId === ownerId.value && captured.stage === stage.value && captured.page === page.value
  projects.value = []; error.value = ''; unavailable.value = false
  if (!ws) { error.value = l('请选择工作区。', 'Select a workspace.'); return }
  loading.value = true
  try {
    const caps = await biddingApi.capabilities(ws, signal)
    if (!current()) return
    capabilities.value = caps
    if (!caps.enabled) { unavailable.value = true; return }
    const filters = { name: captured.name, ownerId: captured.ownerId, stage: captured.stage }
    const [memberRows, stats, result] = await Promise.all([biddingApi.members(ws, signal), biddingApi.dashboard(ws, filters, signal), biddingApi.list(ws, { ...filters, page: captured.page, pageSize }, signal)])
    if (!current()) return
    members.value = memberRows.map(member => ({ ...member, userId: String(member.userId) })); dashboard.value = stats; projects.value = result.items; total.value = result.total
  } catch (cause) { if (!signal.aborted) { unavailable.value = apiUnavailable(cause); if (!unavailable.value) error.value = l('投标项目加载失败。', 'Could not load bidding projects.') } }
  finally { if (!signal.aborted) loading.value = false }
}
function search() { page.value = 1; void load() }
async function openCreate() {
  form.name = ''; form.lotName = ''; form.ownerId = ''; formError.value = ''; createOpen.value = true
  const ws = workspace.currentWorkspaceId, generation = ++createMembersGeneration; if (!ws) return
  membersLoading.value = true
  try { const rows = await biddingApi.members(ws); if (generation !== createMembersGeneration || ws !== workspace.currentWorkspaceId || !createOpen.value) return; members.value = rows.map(member => ({ ...member, userId: String(member.userId) })); if (!form.ownerId) form.ownerId = members.value[0] ? String(members.value[0].userId) : '' }
  catch { if (generation === createMembersGeneration && ws === workspace.currentWorkspaceId) formError.value = l('工作区成员加载失败。', 'Could not load workspace members.') }
  finally { if (generation === createMembersGeneration) membersLoading.value = false }
}
async function create() {
  const ws = workspace.currentWorkspaceId, generation = workspaceGeneration; if (!ws || !capabilities.value?.canWrite) return
  saving.value = true; formError.value = ''
  try { const project = await biddingApi.create(ws, { operationId: operationId(), name: form.name.trim(), lotName: form.lotName.trim(), ownerId: form.ownerId }); if(generation !== workspaceGeneration || ws !== workspace.currentWorkspaceId) return; createOpen.value = false; await router.push(`/bidding/${project.id}`) }
  catch { if(generation === workspaceGeneration && ws === workspace.currentWorkspaceId) formError.value = l('创建失败。请检查负责人和项目字段后重试。', 'Creation failed. Check the owner and project fields, then retry.') }
  finally { saving.value = false }
}
watch(() => workspace.currentWorkspaceId, () => { workspaceGeneration++; createMembersGeneration++; createOpen.value = false; membersLoading.value = false; members.value=[]; void load() })
onMounted(() => { unregister = workspace.registerBeforeSwitch(() => { controller?.abort(); return true }); void load() })
onBeforeUnmount(() => { controller?.abort(); unregister?.() })
</script>

<style scoped>
.bidding-page { padding: 24px 32px 32px; min-width: 0; color: var(--mc-text-primary); }
.page-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:20px; }
h1 { margin:0; font-size:24px; font-weight:600; }
.metrics { display:grid; grid-template-columns:repeat(4,minmax(120px,1fr)); border:1px solid var(--mc-border); border-radius:6px; background:var(--mc-bg-elevated); margin-bottom:16px; }
.metric { display:flex; flex-direction:column; gap:8px; padding:14px 18px; border-right:1px solid var(--mc-border-light); color:var(--mc-text-secondary); }
.metric:last-child { border-right:0; }.metric strong { color:var(--mc-text-primary); font-size:22px; font-variant-numeric:tabular-nums; }
.filters { display:flex; align-items:center; gap:8px; margin:12px 0; flex-wrap:wrap; }.filters .el-input { width:220px; }.filters .el-select { width:180px; }
.table-scroll { overflow:auto; border:1px solid var(--mc-border); border-radius:6px; background:var(--mc-bg-elevated); }
.project-link { border:0; background:none; padding:0; text-align:left; color:var(--mc-primary); cursor:pointer; font:inherit; font-weight:600; }.project-link:hover { text-decoration:underline; }.project-link small { display:block; margin-top:4px; color:var(--mc-text-secondary); font-weight:400; }
.actions { display:flex; align-items:center; gap:8px; flex-wrap:nowrap; }.el-pagination { justify-content:flex-end; margin-top:12px; }
@media(max-width:768px) { .bidding-page { padding:16px; }.metrics { grid-template-columns:repeat(2,minmax(0,1fr)); }.metric:nth-child(2) { border-right:0; }.metric:nth-child(-n+2) { border-bottom:1px solid var(--mc-border-light); }.page-heading { align-items:flex-start; }.filters>* { flex:1 1 140px; }.filters .el-input,.filters .el-select { width:auto; } }
</style>
