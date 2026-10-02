<template>
  <template v-if="kind === 'project'">
    <el-form-item
      :label="t('presales.project_name')"
      required
      ><el-input
        v-model="form.name"
        maxlength="200" /></el-form-item
    ><el-form-item
      :label="t('presales.customer')"
      required
      ><el-input
        v-model="form.customer"
        maxlength="200" /></el-form-item
    ><el-form-item :label="t('presales.owner')"
      ><el-select
        v-model="form.ownerId"
        filterable
        :loading="membersLoading"
        :disabled="membersLoading || !!membersError"
        :aria-label="t('presales.owner')"
        :placeholder="t('presales.select_a_workspace_member')"
        ><el-option
          v-if="form.ownerId && !members.some((member) => member.userId === form.ownerId)"
          :value="form.ownerId"
          :label="ownerName(form.ownerId)"
          disabled /><el-option
          v-for="member in members"
          :key="member.userId"
          :value="member.userId"
          :label="memberName(member)" /></el-select
      ><span
        v-if="membersError"
        role="alert"
        >{{ membersError }}</span
      ></el-form-item
    >
  </template>
  <el-form-item :label="t('presales.presales_solution_employee')"
    ><el-select
      v-model="form.agentId"
      clearable
      :loading="employeesLoading"
      :placeholder="t('presales.select_a_configured_workspace_employee')"
      ><el-option
        v-for="employee in employees"
        :key="employee.id"
        :value="employee.id"
        :label="employee.name"
        :disabled="employee.enabled === false" /></el-select
    ><el-button
      type="primary"
      size="small"
      @click="emit('agents')"
      >{{ t('presales.manage_employees') }}</el-button
    ></el-form-item
  ><el-alert
    v-if="employeeError"
    :title="employeeError"
    type="warning"
    :closable="false"
  />
  <p
    v-if="!employeesLoading && !employees.length"
    class="muted"
  >
    {{ t('presales.context_message_14') }}
  </p>
  <template v-if="kind === 'project'">
    <el-form-item :label="t('presales.industry')"><el-input v-model="form.industry" /></el-form-item
    ><el-form-item :label="t('presales.goal')"
      ><el-input
        v-model="form.goal"
        type="textarea"
        :rows="3"
    /></el-form-item>
  </template>
</template>
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
const { t } = useI18n({ messages: presalesMessages })
const form = defineModel<PresalesEditorForm>('form', { required: true })
import type { PresalesEditorForm, PresalesMember, PresalesEmployee } from '../api/presalesApi'
defineProps<{
  kind: 'project' | 'employee'
  members: PresalesMember[]
  membersLoading: boolean
  membersError: string
  employees: PresalesEmployee[]
  employeesLoading: boolean
  employeeError: string
  ownerName: (id?: string) => string
  memberName: (member: PresalesMember) => string
}>()
const emit = defineEmits<{ agents: [] }>()
</script>
<style scoped src="../shared/workbenchSections.css"></style>
<style scoped src="../shared/editorFields.css"></style>
