<template>
  <section class="overview">
    <div class="facts"><div><span>{{ l('标段', 'Lot') }}</span><strong>{{ project.lotName }}</strong></div><div><span>{{ l('负责人', 'Owner') }}</span><strong>{{ ownerName }}</strong></div><div><span>{{ l('阶段', 'Stage') }}</span><strong>{{ project.stage }}</strong></div></div>
    <section class="panel"><header><h2>{{ l('数字员工岗位', 'Employee roles') }}</h2><el-button v-if="canApprove" type="primary" plain size="small" :disabled="!dirty || saving || !employees.length" :loading="saving" @click="save">{{ l('保存岗位', 'Save roles') }}</el-button></header>
      <el-form label-position="right" label-width="130px">
        <el-form-item v-for="role in roles" :key="role.key" :label="role.label">
          <el-select v-model="form[role.key]" clearable filterable :disabled="!canApprove || saving" :placeholder="l('选择已配置员工', 'Select configured employee')">
            <el-option v-for="employee in employees" :key="String(employee.id)" :value="String(employee.id)" :label="employee.name" :disabled="employee.enabled === false" />
          </el-select>
          <span v-if="bindingIssue(role.key)" class="issue">{{ bindingIssue(role.key) }}</span>
        </el-form-item>
      </el-form>
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-alert v-if="!canApprove" type="info" :title="l('只读访问：岗位配置需要项目负责人或工作区管理员。', 'Read-only: role assignment requires the project owner or workspace admin.')" :closable="false" />
      <el-empty v-if="!employees.length" :description="l('没有可用的数字员工配置', 'No available employee configuration')" />
    </section>
    <section class="panel unavailable"><h2>{{ l('后续阶段', 'Later stages') }}</h2><el-tag effect="plain">{{ l('目录规划：P2 配置待办', 'Outline: P2 configuration required') }}</el-tag><el-tag effect="plain">{{ l('写作、审核与导出：P2/P3', 'Writing, review and export: P2/P3') }}</el-tag></section>
  </section>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Employee, EmployeeBinding, Project } from '../api/types'
const props = defineProps<{ project: Project; members: { userId: string | number; nickname?: string; username?: string }[]; employees: Employee[]; canApprove: boolean; saving: boolean; error: string }>()
const emit = defineEmits<{ save: [value: Record<string, string | null>] }>()
const { locale } = useI18n(), l = (zh: string, en: string) => String(locale.value).startsWith('zh') ? zh : en
const roles = [ { key: 'analyst', label: l('招标分析员', 'Analysis employee') }, { key: 'writer', label: l('标书编写员', 'Writing employee') }, { key: 'reviewer', label: l('标书审核员', 'Review employee') } ] as const
const form = reactive<Record<string, string | null>>({ analyst: null, writer: null, reviewer: null })
function sync() { for (const role of ['analyst', 'writer', 'reviewer']) form[role] = props.project.bindings?.[role]?.agentId ? String(props.project.bindings[role]!.agentId) : null }
watch(() => props.project, sync, { immediate: true, deep: true })
const initial = computed(() => ['analyst','writer','reviewer'].map(role => form[role] || '').join('|'))
const dirty = ref(false)
watch(initial, () => { dirty.value = ['analyst','writer','reviewer'].some(role => (form[role] || '') !== (props.project.bindings?.[role]?.agentId ? String(props.project.bindings[role]!.agentId) : '')) })
function save() { emit('save', { analystAgentId: form.analyst, writerAgentId: form.writer, reviewerAgentId: form.reviewer }) }
function bindingIssue(role: string) { const binding: EmployeeBinding | undefined = props.project.bindings?.[role]; return binding?.issue || (!binding?.agentId && role === 'analyst' ? l('分析岗位未配置', 'Analysis role is not configured') : '') }
const ownerName = computed(() => { const member = props.members.find(item => String(item.userId) === props.project.ownerId); return member?.nickname || member?.username || l('成员不可用', 'Member unavailable') })
</script>
<style scoped>
.overview { display:grid; gap:16px; min-width:0; }.facts { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); border:1px solid var(--mc-border); border-radius:6px; background:var(--mc-bg-elevated); }.facts div { padding:14px 18px; display:flex; flex-direction:column; gap:6px; border-right:1px solid var(--mc-border-light); }.facts div:last-child { border:0; }.facts span { color:var(--mc-text-secondary); font-size:12px; }.facts strong { font-weight:600; }.panel { padding:16px 20px; border:1px solid var(--mc-border); border-radius:6px; background:var(--mc-bg-elevated); }.panel header { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:12px; }h2 { margin:0; font-size:16px; }.panel .el-select { width:min(420px,100%); }.issue { margin-left:12px; color:var(--mc-text-secondary); font-size:12px; }.unavailable { display:flex; align-items:center; flex-wrap:wrap; gap:10px; }.unavailable h2 { flex-basis:100%; }@media(max-width:768px) { .facts { grid-template-columns:1fr; }.facts div { border-right:0; border-bottom:1px solid var(--mc-border-light); }.issue { display:block; margin:4px 0; } }
</style>
