<template>
  <div class="ledger-heading">
    <h2>
      {{ t('presales.project_register') }} <span>{{ total }}</span>
    </h2>
  </div>
  <form
    class="toolbar"
    @submit.prevent="emit('search')"
  >
    <el-input
      v-model="query"
      clearable
      :aria-label="t('presales.search_projects_or_customers')"
      :placeholder="t('presales.search_projects_or_customers')"
    /><el-select
      v-model="ownerFilter"
      filterable
      clearable
      :loading="membersLoading"
      :aria-label="t('presales.owner')"
      :placeholder="t('presales.all_owners')"
      ><el-option
        v-for="member in members"
        :key="member.userId"
        :value="member.userId"
        :label="memberName(member)" /></el-select
    ><el-select
      v-model="statusFilter"
      clearable
      :aria-label="t('presales.stage')"
      :placeholder="t('presales.all_stages')"
      ><el-option
        v-for="status in stages"
        :key="status"
        :value="status"
        :label="stateLabel(status, 'projectStage')" /></el-select
    ><el-button native-type="submit">{{ t('presales.search') }}</el-button>
  </form>
  <el-table
    class="project-ledger"
    :data="projects"
    @row-dblclick="(row) => emit('open', row.id)"
  >
    <el-table-column
      :label="t('presales.project')"
      min-width="210"
      ><template #default="{ row }"
        ><el-button
          link
          type="primary"
          class="navigation-link"
          @click="emit('open', row.id)"
          >{{ row.name }}</el-button
        ></template
      ></el-table-column
    >
    <el-table-column
      prop="customer"
      :label="t('presales.customer')"
      min-width="160"
    /><el-table-column
      :label="t('presales.owner')"
      min-width="150"
      ><template #default="{ row }"
        ><span>{{ ownerName(row.ownerId) }}</span></template
      ></el-table-column
    ><el-table-column
      :label="t('presales.stage')"
      min-width="130"
      ><template #default="{ row }"
        ><span
          class="stage-label"
          :data-stage="row.stage"
          >{{
            stateLabel(row.stage || row.status, row.stage ? 'projectStage' : 'projectStatus')
          }}</span
        ></template
      ></el-table-column
    ><el-table-column
      :label="t('presales.solution_version')"
      width="110"
      ><template #default="{ row }">{{
        row.latestSolutionVersion ? `V${row.latestSolutionVersion}` : t('presales.not_started')
      }}</template></el-table-column
    ><el-table-column
      :label="t('presales.open_questions')"
      width="110"
      ><template #header
        ><span :title="t('presales.unanswered_explanation')">{{
          t('presales.open_questions')
        }}</span></template
      ><template #default="{ row }">{{
        row.openClarificationCount ?? '—'
      }}</template></el-table-column
    ><el-table-column
      :label="t('presales.updated')"
      min-width="170"
      ><template #default="{ row }">{{ displayDate(row.updatedAt) }}</template></el-table-column
    >
    <template #empty>{{
      error
        ? t('presales.loading_failed_retry')
        : t('presales.no_projects_create_a_project_to_start')
    }}</template>
  </el-table>
  <el-pagination
    v-model:current-page="page"
    :page-size="20"
    :total="total"
    layout="total, prev, pager, next"
    @current-change="emit('reload')"
  />
</template>
<script setup lang="ts">
import type { StateLabel } from '../shared/status'
import { useI18n } from 'vue-i18n'
import type { PresalesMember, PresalesProjectSummary } from '../api/presalesApi'
import { presalesMessages } from '../shared/messages'

const { t } = useI18n({ messages: presalesMessages })
defineProps<{
  projects: PresalesProjectSummary[]
  total: number
  error: string
  members: PresalesMember[]
  membersLoading: boolean
  memberName: (member: PresalesMember) => string
  ownerName: (id?: string) => string
  stateLabel: StateLabel
}>()
const emit = defineEmits<{
  search: []
  reload: []
  open: [id: string]
}>()
const query = defineModel<string>('query', { required: true })
const ownerFilter = defineModel<string>('ownerFilter', { required: true })
const statusFilter = defineModel<string>('statusFilter', { required: true })
const page = defineModel<number>('page', { required: true })
const stages = ['DISCOVERY', 'REQUIREMENTS', 'BASELINED', 'SOLUTION', 'RELEASE', 'ARCHIVED']
function displayDate(value: string | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleString(undefined, {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      })
}
</script>
<style scoped>
.el-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
.ledger-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.ledger-heading h2 {
  margin: 0;
  font-size: 17px;
}
.ledger-heading h2 span {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.project-ledger {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}
.project-ledger :deep(.el-table__cell) {
  padding: 16px 0;
}
.project-ledger :deep(.el-table__header th) {
  background: var(--el-fill-color-light);
  font-weight: 500;
}
.stage-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.stage-label::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
}
.stage-label[data-stage='RELEASE']::before {
  background: var(--el-color-success);
}
.stage-label[data-stage='ARCHIVED']::before {
  background: var(--el-text-color-placeholder);
}
@media (max-width: 768px) {
  .ledger-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
<style scoped src="../shared/workbenchSections.css"></style>
