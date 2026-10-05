<template>
  <main
    class="presales-workbench"
    v-loading="loading"
  >
    <header class="page-heading">
      <div>
        <el-button
          v-if="projectId"
          link
          type="primary"
          class="navigation-link"
          @click="router.push('/presales')"
          >← {{ t('presales.projects') }}</el-button
        >
        <h1>{{ project?.name || t('presales.presales_workbench') }}</h1>
      </div>
      <el-button
        v-if="!projectId"
        type="primary"
        :disabled="!canWrite"
        @click="openEditor('project')"
        >{{ t('presales.new_project') }}</el-button
      >
      <el-button
        v-else
        :disabled="!canWrite || project?.sourceAccessRestricted"
        @click="openEditor('project', project)"
        >{{ t('presales.edit_project') }}</el-button
      >
    </header>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      show-icon
      :closable="false"
      ><template #default
        ><p v-if="conflict">
          {{ t('presales.context_message_1') }}
        </p>
        <el-button
          type="primary"
          size="small"
          @click="load"
          >{{ t('presales.reload') }}</el-button
        ></template
      ></el-alert
    >
    <el-alert
      v-if="capabilities && !capabilities.enabled"
      type="info"
      :closable="false"
      :title="t('presales.context_message_2')"
    />
    <template v-if="capabilities?.enabled">
      <el-alert
        v-if="!capabilities.canWrite"
        type="info"
        :closable="false"
        :title="t('presales.context_message_3')"
      />
      <el-alert
        v-if="!capabilities.semanticEnabled"
        type="warning"
        :closable="false"
        :title="t('presales.context_message_4')"
      />
      <template v-if="!projectId">
        <PresalesDashboard
          :projects="portfolio"
          :loading="portfolioLoading"
          :error="portfolioError"
          :selected-stage="statusFilter"
          @refresh="refreshPortfolio"
          @stage="filterStage"
          @open="(id) => router.push(`/presales/${id}`)"
        />
        <PresalesProjectLedger
          :projects="projects"
          :total="total"
          :error="error"
          :members="members"
          :members-loading="membersLoading"
          :member-name="memberName"
          :owner-name="ownerName"
          :state-label="stateLabel"
          v-model:query="query"
          v-model:owner-filter="ownerFilter"
          v-model:status-filter="statusFilter"
          v-model:page="page"
          @search="search"
          @reload="load"
          @open="(id) => router.push(`/presales/${id}`)"
        />
      </template>
      <template v-else-if="project">
        <div class="project-meta">
          <el-tag>{{ stateLabel(project.stage || project.status) }}</el-tag
          ><span>{{ project.customer }}</span
          ><span>{{ t('presales.owner') }}: {{ ownerName(project.ownerId) }}</span
          ><span>{{ t('presales.record_version') }} {{ project.version }}</span
          ><el-button
            plain
            size="small"
            type="danger"
            :disabled="!canWrite || project.sourceAccessRestricted"
            @click="archive"
            >{{ t('presales.archive') }}</el-button
          >
        </div>
        <el-alert
          v-if="project.sourceAccessRestricted"
          type="warning"
          :closable="false"
          :title="t('presales.sourceAccessRestricted')"
        />
        <el-alert
          v-if="project.status === 'ARCHIVED'"
          type="info"
          :closable="false"
          :title="t('presales.this_project_is_archived_and_read_only')"
        />
        <section class="employee-owner">
          <div>
            <span class="muted">{{ t('presales.assigned_digital_employee') }}</span>
            <h2>
              {{
                project.agentName ||
                employees.find((e) => e.id === project?.agentId)?.name ||
                (project.agentId
                  ? t('presales.employee_pending_verification')
                  : t('presales.no_presales_employee_assigned'))
              }}
            </h2>
          </div>
          <div>
            <el-button
              :disabled="!canWrite"
              @click="openEditor('employee', project)"
              >{{ t('presales.assign_employee') }}</el-button
            ><el-button
              type="primary"
              :disabled="!canGenerate || !project.agentId"
              @click="openGeneration('S1')"
              >{{ t('presales.delegate_to_employee') }}</el-button
            >
          </div>
        </section>
        <section
          v-if="project.sourceAccessRestricted && project.repairBindings?.length"
          class="repair-bindings"
        >
          <h2>{{ t('presales.repair_bindings') }}</h2>
          <p>{{ t('presales.repair_bindings_help') }}</p>
          <ul>
            <li
              v-for="binding in project.repairBindings"
              :key="binding.id"
            >
              <span>{{ binding.id }} · {{ stateLabel(binding.role) }}</span>
              <el-button
                :disabled="!canWrite || saving"
                @click="command({ action: 'UNBIND_MATERIAL', payload: { id: binding.id } })"
                >{{ t('presales.unbind_material') }}</el-button
              >
            </li>
          </ul>
        </section>
        <nav
          class="project-pulse"
          :aria-label="t('presales.project_statistics')"
        >
          <button
            v-for="item in projectMetrics"
            :key="item.tab"
            type="button"
            @click="tab = item.tab"
          >
            <span>{{ item.label }}</span
            ><strong>{{ item.value }}</strong
            ><small v-if="item.secondary">{{ item.secondary }}</small>
          </button>
        </nav>
        <el-tabs v-model="tab">
          <el-tab-pane
            :label="t('presales.overview')"
            name="overview"
          >
            <PresalesOverview
              :project="project"
              :is-narrow="isNarrow"
              :can-write="canWrite"
              :can-generate="canGenerate"
              :skill-names="skillNames"
              :state-label="stateLabel"
              :employee-issue="employeeIssue"
              :printable="printable"
              @generate="openGeneration('S1')"
              @cancel="cancelTask"
              @execution="
                router.push({
                  path: '/chat',
                  query: {
                    conversationId: $event.conversationId,
                    agentId: $event.agentId,
                  },
                })
              "
              @evidence="showEvidence"
              @adopt="adopt"
              @presentation="previewPresentation"
            />
          </el-tab-pane>
          <el-tab-pane
            :label="t('presales.materials_evidence')"
            name="materials"
          >
            <PresalesMaterials
              :project="project"
              :can-write="canWrite"
              :state-label="stateLabel"
              @bind="openEditor('material')"
              @unbind="(id) => command({ action: 'UNBIND_MATERIAL', payload: { id } })"
              @evidence="showEvidence"
            />
          </el-tab-pane>
          <el-tab-pane
            :label="t('presales.requirements_questions')"
            name="requirements"
          >
            <PresalesRequirements
              :project="project"
              :can-write="canWrite"
              :can-generate="canGenerate"
              :can-approve="canApprove"
              :state-label="stateLabel"
              :owner-name="ownerName"
              v-model:clarification-filter="clarificationFilter"
              @generate="openGeneration('S2')"
              @confirm="openEditor('baseline')"
              @continue="continueEmployee"
              @revise-requirement="(item) => openEditor('requirement', item)"
              @revise-clarification="(item) => openEditor('clarification', item)"
              @evidence="showEvidence"
            />
          </el-tab-pane>
          <el-tab-pane
            :label="t('presales.capabilities_cases')"
            name="fitgap"
          >
            <PresalesFitGap
              :project="project"
              :can-generate="canGenerate"
              :state-label="stateLabel"
              @generate="openGeneration('S3')"
              @evidence="showEvidence"
            />
          </el-tab-pane>
          <el-tab-pane
            :label="t('presales.solution_design')"
            name="solution"
          >
            <PresalesSolutions
              :project="project"
              :can-write="canWrite"
              :can-generate="canGenerate"
              :state-label="stateLabel"
              :version-label="versionLabel"
              v-model:compare-id="compareId"
              v-model:compare-target-id="compareTargetId"
              @generate="openGeneration('S5')"
              @revise="(solution) => openEditor('solution', solution)"
              @download="(id, filename) => download(id, filename, 'draft')"
              @evidence="showEvidence"
            />
          </el-tab-pane>
          <el-tab-pane
            :label="t('presales.review_outputs')"
            name="review"
          >
            <PresalesOutputs
              :project="project"
              :can-write="canWrite"
              :can-generate="canGenerate"
              :can-approve="canApprove"
              :state-label="stateLabel"
              :version-label="versionLabel"
              :printable="printable"
              @generate="openGeneration('S7')"
              @review="openEditor('review')"
              @release="openEditor('release')"
              @handoff="downloadHandoff"
              @evidence="showEvidence"
              @approve="approveRelease"
              @publish="
                (releaseId) => command({ action: 'PUBLISH_RELEASE', payload: { releaseId } })
              "
              @download="download"
            />
          </el-tab-pane>
        </el-tabs>
      </template>
    </template>
    <el-drawer
      v-model="evidenceOpen"
      :title="t('presales.evidence_and_references')"
      size="min(560px, 95vw)"
      ><details class="inline-help">
        <summary>{{ t('presales.about_displayed_evidence') }}</summary>
        <p>
          {{ t('presales.context_message_13') }}
        </p>
      </details>
      <el-descriptions
        v-if="evidence"
        :column="1"
        border
        ><el-descriptions-item
          v-for="(value, key) in evidence"
          :key="key"
          :label="String(key)"
        >
          <pre class="safe-content">{{ printable(value) }}</pre>
        </el-descriptions-item></el-descriptions
      ></el-drawer
    ><el-dialog
      v-model="presentationPreview.open"
      :title="presentationPreview.title"
      width="min(1100px, 95vw)"
      @closed="!presentationPreview.open && clearPresentationPreview()"
      ><img
        v-if="presentationPreview.url"
        :src="presentationPreview.url"
        :alt="presentationPreview.title"
        class="presentation-preview"
    /></el-dialog>
    <el-dialog
      v-model="editorOpen"
      :title="editorTitle"
      width="min(720px, 95vw)"
      :close-on-click-modal="false"
      :before-close="closeEditor"
    >
      <el-form
        label-position="top"
        @submit.prevent="save"
      >
        <PresalesAssignmentFields
          v-if="editorKind === 'project' || editorKind === 'employee'"
          :kind="editorKind"
          v-model:form="form"
          :members="members"
          :members-loading="membersLoading"
          :members-error="membersError"
          :employees="employees"
          :employees-loading="employeesLoading"
          :employee-error="employeeError"
          :owner-name="ownerName"
          :member-name="memberName"
          @agents="router.push('/agents')"
        />
        <PresalesOutputFields
          v-else-if="
            editorKind === 'solution' || editorKind === 'review' || editorKind === 'release'
          "
          :kind="editorKind"
          v-model:form="form"
          :project="project"
          :editable-requirements="editableRequirements"
          :state-label="stateLabel"
          :baseline-label="baselineLabel"
          :version-label="versionLabel"
        />
        <PresalesDiscoveryFields
          v-else
          :kind="editorKind"
          v-model:form="form"
          :project="project"
          :options-loading="optionsLoading"
          :source-options="sourceOptions"
          :statement-options="statementOptions"
          :state-label="stateLabel"
          @source="selectSource"
          @statement="selectStatement"
          @wiki="router.push('/wiki')"
        />
        <el-alert
          v-if="editError"
          :title="editError"
          type="error"
          :closable="false"
          show-icon
        /> </el-form
      ><template #footer
        ><span
          v-if="dirty"
          class="muted"
          >{{ t('presales.unsaved_changes') }}</span
        ><el-button @click="closeEditor(() => (editorOpen = false))">{{
          t('presales.cancel')
        }}</el-button
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="conflict"
          @click="save"
          >{{ t('presales.save') }}</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="generationOpen"
      :title="t('presales.delegate_to_presales_employee')"
      width="min(580px, 95vw)"
      :close-on-click-modal="false"
      ><el-alert
        v-if="employeeError"
        :title="employeeError"
        type="error"
        :closable="false"
      /><el-alert
        v-if="!project?.agentId"
        type="warning"
        :closable="false"
        :title="t('presales.context_message_25')"
      />
      <p>
        {{ t('presales.assigned_employee') }}：{{
          project?.agentName || employees.find((e) => e.id === project?.agentId)?.name || '—'
        }}
      </p>
      <el-form label-position="top"
        ><el-form-item :label="t('presales.work_to_perform')"
          ><el-select v-model="generation.skill"
            ><el-option
              v-for="(name, key) in skillNames"
              :key="key"
              :value="key"
              :label="name" /></el-select></el-form-item
        ><el-form-item :label="t('presales.goal_and_additional_context')"
          ><el-input
            v-model="generation.taskGoal"
            type="textarea"
            :rows="4" /></el-form-item
      ></el-form>
      <details class="inline-help">
        <summary>{{ t('presales.execution_scope') }}</summary>
        <p>
          {{ t('presales.context_message_26') }}
        </p>
      </details>
      <template #footer
        ><el-button @click="generationOpen = false">{{ t('presales.close') }}</el-button
        ><el-button
          type="primary"
          :disabled="!project?.agentId || !generation.taskGoal.trim() || dirty"
          :loading="saving"
          @click="generate"
          >{{ t('presales.start_work') }}</el-button
        ></template
      ></el-dialog
    >
  </main>
</template>
<script setup lang="ts">
import { presalesMessages } from '../shared/messages'
import { useI18n } from 'vue-i18n'
import PresalesMaterials from '../components/PresalesMaterials.vue'
import PresalesRequirements from '../components/PresalesRequirements.vue'
import PresalesFitGap from '../components/PresalesFitGap.vue'
import PresalesDashboard from '../components/PresalesDashboard.vue'
import PresalesProjectLedger from '../components/PresalesProjectLedger.vue'
import PresalesAssignmentFields from '../components/PresalesAssignmentFields.vue'
import PresalesDiscoveryFields from '../components/PresalesDiscoveryFields.vue'
import PresalesOutputFields from '../components/PresalesOutputFields.vue'
import PresalesSolutions from '../components/PresalesSolutions.vue'
import PresalesOutputs from '../components/PresalesOutputs.vue'
import PresalesOverview from '../components/PresalesOverview.vue'
import { usePresalesWorkbenchQuery } from '../composables/usePresalesWorkbenchQuery'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { type PresalesMember, type PresalesProject, type PresalesRecord } from '../api/presalesApi'
import { label as l } from '../shared/locale'
import { usePresalesTaskPolling } from '../composables/usePresalesTaskPolling'
import { usePresalesSourcePreview } from '../composables/usePresalesSourcePreview'
import { isCurrentRequest, operationReceipt } from '../shared/state'
import {
  classifyStatus,
  classifyDomainStatus,
  isDomainStatus,
  statusLabel,
  type StatusDomain,
} from '../shared/status'
import { usePresalesExecutionSession } from '../composables/usePresalesExecutionSession'
import { usePresalesEditorSession } from '../composables/usePresalesEditorSession'
import { usePresalesMutationSession } from '../composables/usePresalesMutationSession'
import { usePresalesDownloads } from '../composables/usePresalesDownloads'
const { t } = useI18n({ messages: presalesMessages })
const narrowQuery = window.matchMedia('(max-width: 768px)')
const isNarrow = ref(narrowQuery.matches)
function updateViewport(event: MediaQueryListEvent) {
  isNarrow.value = event.matches
}
narrowQuery.addEventListener('change', updateViewport)
const receipt = operationReceipt()
const route = useRoute(),
  router = useRouter(),
  workspace = useWorkspaceStore()
const projectId = computed(() => String(route.params.projectId || ''))
let scopeGeneration = 0
function captureScope() {
  return {
    workspaceId: workspace.currentWorkspaceId,
    projectId: projectId.value,
    generation: scopeGeneration,
  }
}
function isActiveScope(scope: ReturnType<typeof captureScope>) {
  return (
    scope.generation === scopeGeneration &&
    !!scope.workspaceId &&
    isCurrentRequest(
      scope.workspaceId,
      workspace.currentWorkspaceId,
      scope.projectId,
      projectId.value,
    )
  )
}
const {
  members,
  membersLoading,
  membersError,
  capabilities,
  project,
  projects,
  portfolio,
  portfolioLoading,
  portfolioError,
  loading,
  error,
  conflict,
  page,
  total,
  query,
  ownerFilter,
  statusFilter,
  load,
  readProject,
  refreshPortfolio,
  search,
  dispose: disposeQuery,
} = usePresalesWorkbenchQuery({
  workspaceId: () => workspace.currentWorkspaceId,
  projectId: () => projectId.value,
  isDirty: () => dirty.value,
  t,
  invalidateScope: () => {
    scopeGeneration++
  },
  resetPolling: () => taskPolling.reset(),
  resetView: () => {
    sourcePreview.reset()
    saving.value = false
    optionsLoading.value = false
    employeesLoading.value = false
    editError.value = ''
    compareId.value = ''
  },
  acceptProject,
})
function memberName(member: PresalesMember): string {
  return member.nickname || member.username || t('presales.unnamed_member')
}
function ownerName(id?: string): string {
  const member = members.value.find((item) => item.userId === id)
  return member ? memberName(member) : id ? t('presales.member_unavailable') : '—'
}
function filterStage(stage: string) {
  statusFilter.value = stage
  search()
}
const projectMetrics = computed(() => {
  const p = project.value
  if (!p) return []
  return [
    {
      tab: 'requirements',
      label: t('presales.requirements_2'),
      value: p.requirements.length,
      secondary: t('presales.unanswered_count', {
        count: p.clarifications.filter(
          (c) => !isDomainStatus('clarification', c.status, 'ANSWERED'),
        ).length,
      }),
    },
    {
      tab: 'materials',
      label: t('presales.sources'),
      value: p.materials.filter((m) => m.status !== 'WITHDRAWN').length,
    },
    {
      tab: 'solution',
      label: t('presales.solutions'),
      value: p.solutions.length,
    },
    {
      tab: 'review',
      label: t('presales.published_releases'),
      value: p.releases.filter((r) => r.status === 'PUBLISHED').length,
    },
  ]
})
const saving = ref(false)
const compareId = ref(''),
  compareTargetId = ref('')
const clarificationFilter = ref('ALL')
const tab = ref('overview')
const canWrite = computed(
  () =>
    !!capabilities.value?.enabled &&
    !!capabilities.value?.canWrite &&
    (!projectId.value || !!project.value) &&
    project.value?.status !== 'ARCHIVED',
)
const canGenerate = computed(() => canWrite.value && !project.value?.sourceAccessRestricted)
const canApprove = computed(
  () =>
    canGenerate.value && !!capabilities.value?.canApprove && !!capabilities.value?.semanticEnabled,
)
const sourcePreview = usePresalesSourcePreview({
  workspaceId: () => workspace.currentWorkspaceId,
  projectId: () => projectId.value,
  project,
  error,
})
const {
  evidenceOpen,
  evidence,
  presentationPreview,
  showEvidence,
  previewPresentation,
  clearPresentationPreview,
} = sourcePreview
function versionLabel(kind: 'solutions' | 'releases', id: string): string {
  const index = project.value?.[kind].findIndex((item) => item.id === id) ?? -1
  return index < 0 ? '—' : `V${index + 1}`
}
function baselineLabel(id: string): string {
  const index = project.value?.baselines.findIndex((item) => item.id === id) ?? -1
  return index < 0
    ? t('presales.requirements_confirmation')
    : `${t('presales.requirements_confirmation')} V${index + 1}`
}
function employeeIssue(code: string): string {
  const messages: Record<string, [string, string]> = {
    EMPLOYEE_UNAVAILABLE: [
      '负责员工不可用，请检查绑定、工作区与启用状态。',
      'Assigned employee unavailable. Check assignment, workspace and enabled state.',
    ],
    EMPLOYEE_RUNTIME_FAILED: [
      '员工执行失败，请查看执行过程并检查员工的模型配置后重试。',
      'Employee execution failed. Check the execution and model configuration before retrying.',
    ],
    EMPLOYEE_RUNTIME_UNAVAILABLE: ['员工运行服务暂不可用。', 'Employee runtime is unavailable.'],
    PRESENTATION_UNAVAILABLE: [
      '成果编译服务暂不可用，本次执行未完成。',
      'Presentation compiler is unavailable; this run did not complete.',
    ],
    PRESENTATION_FAILED: [
      '成果草稿编译失败，请检查页面内容后重试。',
      'Presentation draft compilation failed; review the content and retry.',
    ],
    PPT_GENERATION_FAILED: [
      '成果草稿生成失败，请检查 PPT 技能配置后重试。',
      'Output draft generation failed; check the PPT skill configuration and retry.',
    ],
    PPT_GENERATION_TIMEOUT: [
      '成果草稿生成超时，请稍后重试。',
      'Output draft generation timed out; retry later.',
    ],
    PROJECT_CHANGED_DURING_GENERATION: [
      '执行期间项目已变化，本次结果未采纳。请重新执行。',
      'Project changed during execution. Results were not applied; run again.',
    ],
  }
  const message = messages[code]
  return message ? l(...message) : code
}
function stateLabel(state: string | undefined, domain?: StatusDomain): string {
  return statusLabel(domain ? classifyDomainStatus(state, domain) : classifyStatus(state), t)
}
function printable(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2) || '—'
}
const taskPolling = usePresalesTaskPolling({
  workspaceId: () => workspace.currentWorkspaceId,
  projectId: () => projectId.value,
  project,
  error,
  read: readProject,
})
function acceptProject(detail: PresalesProject) {
  taskPolling.reset()
  project.value = detail
  if (
    detail.tasks?.some((task) => isDomainStatus('task', task.status, 'RUNNING') && task.operationId)
  )
    taskPolling.start()
}
function acceptMutation(detail: PresalesProject, scope: ReturnType<typeof captureScope>) {
  if (!isActiveScope(scope)) return false
  if (detail.version < (project.value?.version || 0)) {
    conflict.value = true
    error.value = editError.value = t('presales.context_message_1')
    return false
  }
  acceptProject(detail)
  return true
}
const editor = usePresalesEditorSession({
  project,
  canWrite: () => canWrite.value,
  saving,
  workspaceId: () => workspace.currentWorkspaceId,
  projectId: () => projectId.value,
  captureScope,
  isActiveScope,
  loadEmployees: (): Promise<void> => loadEmployees(),
  confirmDiscard: () =>
    ElMessageBox.confirm(t('presales.discard_unsaved_changes'), t('presales.unsaved_changes_2'), {
      type: 'warning',
    }),
})
const {
  sourceOptions,
  statementOptions,
  optionsLoading,
  editorOpen,
  editorKind,
  form,
  editError,
  editableRequirements,
  dirty,
  openEditor,
  selectSource,
  selectStatement,
  discard,
  closeEditor,
} = editor
const editorTitle = computed(
  () =>
    ({
      project: t('presales.project_details'),
      employee: t('presales.assign_employee'),
      material: t('presales.bind_material'),
      requirement: t('presales.requirement_revision'),
      clarification: t('presales.clarification'),
      baseline: t('presales.confirm_requirements'),
      fitgap: t('presales.requirement_capability_matching'),
      solution: t('presales.solution_editor'),
      release: t('presales.release_candidate'),
      review: t('presales.independent_human_review'),
      context: t('presales.context_draft'),
    })[editorKind.value],
)
onBeforeRouteLeave(discard)
onBeforeRouteUpdate(discard)
const unregister = workspace.registerBeforeSwitch(discard)
function adopt(skill: string | undefined, item: PresalesRecord) {
  if (skill === 'S1') openEditor('context', { ...item })
  else if (skill === 'S5' || skill === 'S6')
    openEditor('solution', {
      id: '',
      title: item.title,
      baselineId: project.value?.baselines.at(-1)?.id || '',
      sections: [
        {
          title: item.title,
          text: item.text,
          evidenceRefs: item.sourceRefs || [],
        },
      ],
    })
  else
    openEditor('requirement', {
      id: '',
      title: item.title,
      description: item.text,
      priority: 'MEDIUM',
      scope: 'UNKNOWN',
      originKind: item.originKind,
      sourceRefs: item.sourceRefs || [],
    })
}
const {
  employees,
  employeeError,
  employeesLoading,
  generation,
  generationOpen,
  loadEmployees,
  openGeneration,
  continueEmployee,
  generate,
  cancelTask,
} = usePresalesExecutionSession({
  project,
  saving,
  error,
  conflict,
  workspaceId: () => workspace.currentWorkspaceId,
  projectId: () => projectId.value,
  canWrite: () => canWrite.value,
  canGenerate: () => canGenerate.value,
  isDirty: (): boolean => dirty.value,
  captureScope,
  isActiveScope,
  receipt,
  acceptMutation,
  startPolling: () => taskPolling.start(),
  employeeIssue,
  t,
})
const { command, save, approveRelease, archive } = usePresalesMutationSession({
  project,
  saving,
  error,
  conflict,
  editError,
  workspaceId: () => workspace.currentWorkspaceId,
  projectId: () => projectId.value,
  canWrite: () => canWrite.value,
  canApprove: () => canApprove.value,
  captureScope,
  isActiveScope,
  receipt,
  acceptMutation,
  editor: {
    editorKind,
    form,
    editorOpen,
    captureSession: editor.captureSession,
  },
  continueEmployee,
  load,
  navigateToProject: (id) => router.push(`/presales/${id}`),
  t,
})
const { download, downloadHandoff } = usePresalesDownloads({
  project,
  error,
  workspaceId: () => workspace.currentWorkspaceId,
  projectId: () => projectId.value,
  captureScope,
  isActiveScope,
})
const skillNames = computed(() => ({
  S1: t('presales.analyze_project_context'),
  S2: t('presales.assess_requirements_and_missing_information'),
  S3: t('presales.match_product_capabilities'),
  S4: t('presales.find_relevant_cases'),
  S5: t('presales.compose_solution'),
  S6: t('presales.prepare_output_drafts'),
  S7: t('presales.review_solution_and_risks'),
  S8: t('presales.prepare_handoff'),
}))
watch(
  () => project.value,
  (current, previous) => {
    if (current && !current.sourceAccessRestricted) return
    if (!current && !previous) return
    evidenceOpen.value = false
    evidence.value = undefined
    generationOpen.value = false
    clearPresentationPreview()
  },
  { flush: 'sync' },
)
watch(
  [() => workspace.currentWorkspaceId, projectId],
  () => {
    scopeGeneration++
    editorOpen.value = false
    generationOpen.value = false
    page.value = 1
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  scopeGeneration++
  disposeQuery()
  taskPolling.reset()
  clearPresentationPreview()
  unregister()
  narrowQuery.removeEventListener('change', updateViewport)
})
</script>
<style scoped>
.presales-workbench {
  padding: 24px 32px;
  color: var(--el-text-color-primary);
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  height: 100%;
  overflow: auto;
  background: var(--el-fill-color-lighter);
}
.page-heading,
.project-meta {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.page-heading {
  justify-content: space-between;
}
.page-heading > div {
  min-width: 0;
  max-width: 100%;
}
.page-heading h1,
.project-meta span {
  overflow-wrap: anywhere;
  max-width: 100%;
}
.presales-workbench :deep(.el-descriptions__content) {
  overflow-wrap: anywhere;
}
h1 {
  font-size: 24px;
  margin: 8px 0;
  font-weight: 600;
}
.project-meta {
  padding: 16px 0;
  font-size: 13px;
}
@media (max-width: 768px) {
  .presales-workbench {
    padding: 16px;
  }
  .page-heading {
    align-items: flex-start;
  }
}

.page-heading {
  margin-bottom: 24px;
}
.page-heading h1 {
  font-size: 26px;
  letter-spacing: -0.4px;
}
.project-pulse {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 8px 0 24px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-bg-color);
}
.project-pulse button {
  font: inherit;
  color: inherit;
  text-align: left;
  padding: 18px 22px;
  background: none;
  border: 0;
  border-right: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.project-pulse button:last-child {
  border-right: 0;
}
.project-pulse button > span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.project-pulse strong {
  display: block;
  font-size: 25px;
  font-weight: 600;
  margin-top: 8px;
}
.project-pulse small {
  display: block;
  margin-top: 5px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}
.project-pulse button:hover {
  background: var(--el-color-primary-light-9);
}
.project-pulse button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}
.presales-workbench :deep(.el-tabs__content) {
  background: var(--el-bg-color);
  padding: 4px 20px 24px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}
@media (max-width: 768px) {
  .project-pulse {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .project-pulse button {
    padding: 14px;
  }
  .page-heading h1 {
    font-size: 23px;
  }
  .presales-workbench :deep(.el-tabs__content) {
    padding: 4px 12px 16px;
  }
}
.employee-owner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding: 18px 22px;
  border: 1px solid var(--el-border-color-light);
  border-left: 3px solid var(--el-color-primary);
  background: var(--el-bg-color);
  border-radius: 6px;
  margin: 4px 0 18px;
}
.employee-owner h2 {
  margin: 6px 0;
}
.employee-owner > div:last-child {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
@media (max-width: 1000px) {
  .employee-owner {
    flex-direction: column;
    align-items: flex-start;
  }
}

.presales-workbench :deep(.el-button:focus-visible) {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}
.presentation-preview {
  display: block;
  width: 100%;
  max-height: 75vh;
  object-fit: contain;
  background: #fff;
}
.project-pulse button > span::after {
  content: ' ↗';
  color: var(--el-color-primary);
}
</style>
<style scoped src="../shared/workbenchSections.css"></style>
<style scoped src="../shared/editorFields.css"></style>
