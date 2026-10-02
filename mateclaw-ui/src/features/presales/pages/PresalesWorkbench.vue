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
        <div class="ledger-heading">
          <h2>
            {{ t('presales.project_register') }} <span>{{ total }}</span>
          </h2>
        </div>
        <form
          class="toolbar"
          @submit.prevent="search"
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
              :label="stateLabel(status)" /></el-select
          ><el-button native-type="submit">{{ t('presales.search') }}</el-button>
        </form>
        <el-table
          class="project-ledger"
          :data="projects"
          @row-dblclick="(row) => router.push(`/presales/${row.id}`)"
        >
          <el-table-column
            :label="t('presales.project')"
            min-width="210"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                class="navigation-link"
                @click="router.push(`/presales/${row.id}`)"
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
                >{{ stateLabel(row.stage || row.status) }}</span
              ></template
            ></el-table-column
          ><el-table-column
            :label="t('presales.solution_version')"
            width="110"
            ><template #default="{ row }">{{
              row.latestSolutionVersion
                ? `V${row.latestSolutionVersion}`
                : t('presales.not_started')
            }}</template></el-table-column
          ><el-table-column
            :label="t('presales.open_questions')"
            width="110"
            ><template #default="{ row }">{{
              row.openClarificationCount ?? '—'
            }}</template></el-table-column
          ><el-table-column
            :label="t('presales.updated')"
            min-width="170"
            ><template #default="{ row }">{{
              displayDate(row.updatedAt)
            }}</template></el-table-column
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
          @current-change="load"
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
                @click="command('UNBIND_MATERIAL', { id: binding.id })"
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
                  query: { conversationId: $event.conversationId, agentId: $event.agentId },
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
            <section class="section-heading">
              <h2>{{ t('presales.authorized_sources') }}</h2>
              <el-button
                :disabled="!canWrite"
                @click="openEditor('material')"
                >{{ t('presales.bind_material') }}</el-button
              >
            </section>
            <el-table :data="project.materials"
              ><el-table-column :label="t('presales.role')"
                ><template #default="{ row }">{{ stateLabel(row.role) }}</template></el-table-column
              ><el-table-column
                prop="kbId"
                :label="t('presales.knowledge_base')"
                min-width="180"
              /><el-table-column
                prop="graphId"
                :label="t('presales.graph')"
                min-width="180"
              /><el-table-column :label="t('presales.status')"
                ><template #default="{ row }">{{
                  stateLabel(row.status)
                }}</template></el-table-column
              ><el-table-column
                :label="t('presales.actions')"
                min-width="224"
                ><template #default="{ row }"
                  ><div class="table-actions">
                    <el-button
                      type="primary"
                      size="small"
                      @click="showEvidence(row)"
                      >{{ t('presales.view_source') }}</el-button
                    ><el-button
                      plain
                      size="small"
                      type="danger"
                      :disabled="!canWrite || row.status === 'WITHDRAWN'"
                      @click="command('UNBIND_MATERIAL', { id: row.id })"
                      >{{ t('presales.withdraw') }}</el-button
                    >
                  </div></template
                ></el-table-column
              ></el-table
            >
          </el-tab-pane>
          <el-tab-pane
            :label="t('presales.requirements_questions')"
            name="requirements"
          >
            <section class="section-heading">
              <h2>{{ t('presales.requirements') }}</h2>
              <div>
                <el-button
                  :disabled="!canGenerate"
                  @click="openGeneration('S2')"
                  >{{ t('presales.ask_employee_to_assess_requirements') }}</el-button
                ><el-button
                  type="primary"
                  :disabled="!canApprove"
                  @click="openEditor('baseline')"
                  >{{ t('presales.confirm_requirements') }}</el-button
                >
              </div>
            </section>
            <details class="inline-help">
              <summary>
                {{ t('presales.about_confirmation_steps') }}
              </summary>
              <p>
                {{ t('presales.context_message_6') }}
              </p>
            </details>
            <el-table :data="project.requirements"
              ><el-table-column
                prop="title"
                :label="t('presales.requirement')"
                min-width="240"
              /><el-table-column
                :label="t('presales.origin')"
                min-width="130"
                ><template #default="{ row }">{{
                  stateLabel(row.originKind)
                }}</template></el-table-column
              ><el-table-column :label="t('presales.priority')"
                ><template #default="{ row }">{{
                  stateLabel(row.priority)
                }}</template></el-table-column
              ><el-table-column :label="t('presales.scope')"
                ><template #default="{ row }">{{
                  stateLabel(row.scope)
                }}</template></el-table-column
              ><el-table-column
                :label="t('presales.customer_confirmation')"
                min-width="160"
                ><template #default="{ row }">{{
                  stateLabel(row.customerConfirmationStatus)
                }}</template></el-table-column
              ><el-table-column
                :label="t('presales.actions')"
                min-width="224"
                ><template #default="{ row }"
                  ><div class="table-actions">
                    <el-button
                      type="primary"
                      size="small"
                      @click="showEvidence(row)"
                      >{{ t('presales.evidence') }}</el-button
                    ><el-button
                      type="primary"
                      size="small"
                      :disabled="!canWrite"
                      @click="openEditor('requirement', row)"
                      >{{ t('presales.revise') }}</el-button
                    >
                  </div></template
                ></el-table-column
              ></el-table
            >
            <section class="section-heading">
              <h2>
                {{ t('presales.information_requested_by_employee') }}
              </h2>
              <el-button
                :disabled="!canGenerate || !project.agentId"
                @click="continueEmployee"
                >{{ t('presales.continue_employee_work') }}</el-button
              >
            </section>
            <div class="toolbar">
              <el-radio-group
                v-model="clarificationFilter"
                :aria-label="t('presales.filter_clarification_status')"
                ><el-radio-button value="ALL"
                  >{{ t('presales.all') }} {{ project.clarifications.length }}</el-radio-button
                ><el-radio-button value="OPEN"
                  >{{ t('presales.open') }}
                  {{
                    project.clarifications.filter((c) => c.status !== 'ANSWERED').length
                  }}</el-radio-button
                ><el-radio-button value="ANSWERED"
                  >{{ t('presales.answered') }}
                  {{
                    project.clarifications.filter((c) => c.status === 'ANSWERED').length
                  }}</el-radio-button
                ></el-radio-group
              >
            </div>
            <el-table :data="filteredClarifications"
              ><el-table-column
                prop="question"
                :label="t('presales.question')"
                min-width="240"
              /><el-table-column
                :label="t('presales.requirement_impact')"
                min-width="180"
                ><template #default="{ row }"
                  ><span>{{
                    project.requirements.find((r) => r.id === row.requirementId)?.title ||
                    t('presales.project_wide')
                  }}</span>
                  <div class="muted">{{ row.impact || '—' }}</div></template
                ></el-table-column
              ><el-table-column :label="t('presales.owner')"
                ><template #default="{ row }">{{
                  ownerName(row.ownerId)
                }}</template></el-table-column
              ><el-table-column
                :label="t('presales.answer_and_source')"
                min-width="230"
                ><template #default="{ row }"
                  ><p class="safe-content">{{ row.answer || '—' }}</p>
                  <div class="muted">
                    {{ row.answerSourceId || t('presales.no_source_recorded') }}
                  </div></template
                ></el-table-column
              ><el-table-column :label="t('presales.status')"
                ><template #default="{ row }">{{
                  stateLabel(row.status)
                }}</template></el-table-column
              ><el-table-column
                :label="t('presales.actions')"
                min-width="224"
                ><template #default="{ row }"
                  ><div class="table-actions">
                    <el-button
                      type="primary"
                      size="small"
                      @click="showEvidence(row)"
                      >{{ t('presales.view_record') }}</el-button
                    ><el-button
                      type="primary"
                      size="small"
                      :disabled="!canWrite"
                      @click="openEditor('clarification', row)"
                      >{{
                        row.status === 'ANSWERED'
                          ? t('presales.revise_answer')
                          : t('presales.provide_information')
                      }}</el-button
                    >
                  </div></template
                ></el-table-column
              ></el-table
            >
            <h3>{{ t('presales.requirement_confirmation_history') }}</h3>
            <el-table :data="project.baselines"
              ><el-table-column
                prop="id"
                label="ID" /><el-table-column
                prop="reason"
                :label="t('presales.decision_reason')" /><el-table-column
                prop="createdAt"
                :label="t('presales.created')"
            /></el-table>
          </el-tab-pane>
          <el-tab-pane
            :label="t('presales.capabilities_cases')"
            name="fitgap"
          >
            <section class="section-heading">
              <h2>
                {{ t('presales.requirement_capability_matching') }}
              </h2>
              <div>
                <el-button
                  :disabled="!canGenerate"
                  @click="openGeneration('S3')"
                  >{{ t('presales.match_capabilities') }}</el-button
                >
              </div>
            </section>
            <details class="inline-help">
              <summary>
                {{ t('presales.about_capability_assessment') }}
              </summary>
              <p>
                {{ t('presales.context_message_7') }}
              </p>
            </details>
            <el-table :data="project.fitGaps"
              ><el-table-column
                prop="requirementId"
                :label="t('presales.requirement_2')"
                min-width="190"
              /><el-table-column :label="t('presales.fulfillment_approach')"
                ><template #default="{ row }">{{
                  stateLabel(row.status)
                }}</template></el-table-column
              ><el-table-column
                prop="productVersion"
                :label="t('presales.product_version')"
              /><el-table-column
                prop="reason"
                :label="t('presales.basis_gap')"
                min-width="250"
              /><el-table-column
                :label="t('presales.actions')"
                min-width="224"
                ><template #default="{ row }"
                  ><div class="table-actions">
                    <el-button
                      type="primary"
                      size="small"
                      @click="showEvidence(row)"
                      >{{ t('presales.evidence') }}</el-button
                    >
                  </div></template
                ></el-table-column
              ></el-table
            >
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
              @publish="(releaseId) => command('PUBLISH_RELEASE', { releaseId })"
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
import PresalesDashboard from '../components/PresalesDashboard.vue'
import PresalesAssignmentFields from '../components/PresalesAssignmentFields.vue'
import PresalesDiscoveryFields from '../components/PresalesDiscoveryFields.vue'
import PresalesOutputFields from '../components/PresalesOutputFields.vue'
import PresalesSolutions from '../components/PresalesSolutions.vue'
import PresalesOutputs from '../components/PresalesOutputs.vue'
import PresalesOverview from '../components/PresalesOverview.vue'
import { loadPortfolio } from '../shared/dashboard'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import {
  presalesApi,
  type PresalesCapabilities,
  type PresalesMember,
  type PresalesEmployee,
  type PresalesTask,
  type PresalesProject,
  type PresalesRecord,
} from '../api/presalesApi'
import { label as l } from '../shared/locale'
import { usePresalesTaskPolling } from '../composables/usePresalesTaskPolling'
import { usePresalesSourcePreview } from '../composables/usePresalesSourcePreview'
import { isCurrentRequest, presalesError, operationReceipt } from '../shared/state'
import { usePresalesEditorSession } from '../composables/usePresalesEditorSession'
import { prepareEditorSubmission } from '../shared/editorSubmission'
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
const members = ref<PresalesMember[]>([]),
  membersLoading = ref(false),
  membersError = ref('')
function memberName(member: PresalesRecord): string {
  return member.nickname || member.username || t('presales.unnamed_member')
}
function ownerName(id?: string): string {
  const member = members.value.find((item) => item.userId === id)
  return member ? memberName(member) : id ? t('presales.member_unavailable') : '—'
}
async function loadMembers(ws: string, signal: AbortSignal) {
  members.value = []
  membersError.value = ''
  membersLoading.value = true
  try {
    const rows = await presalesApi.members(ws, signal)
    if (!signal.aborted && ws === workspace.currentWorkspaceId) members.value = rows
  } catch {
    if (!signal.aborted && ws === workspace.currentWorkspaceId)
      membersError.value = t('presales.context_message_27')
  } finally {
    if (!signal.aborted && ws === workspace.currentWorkspaceId) membersLoading.value = false
  }
}

const capabilities = ref<PresalesCapabilities>(),
  project = ref<PresalesProject>(),
  projects = ref<PresalesProject[]>([])
const portfolio = ref<PresalesProject[]>([]),
  portfolioLoading = ref(false),
  portfolioError = ref(false)
let portfolioController: AbortController | undefined
async function refreshPortfolio() {
  portfolioController?.abort()
  portfolioController = new AbortController()
  const ws = workspace.currentWorkspaceId,
    signal = portfolioController.signal
  portfolio.value = []
  portfolioError.value = false
  if (!ws || projectId.value || !capabilities.value?.enabled) {
    portfolioLoading.value = false
    return
  }
  portfolioLoading.value = true
  try {
    const rows = await loadPortfolio(
      (page) => presalesApi.list(ws, { page, pageSize: 100 }, signal),
      signal,
    )
    if (!signal.aborted && ws === workspace.currentWorkspaceId) portfolio.value = rows
  } catch {
    if (!signal.aborted) portfolioError.value = true
  } finally {
    if (!signal.aborted) portfolioLoading.value = false
  }
}
function filterStage(stage: string) {
  statusFilter.value = stage
  search()
}
function displayDate(value: string) {
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
const projectMetrics = computed(() => {
  const p = project.value
  if (!p) return []
  return [
    {
      tab: 'requirements',
      label: t('presales.requirements_2'),
      value: p.requirements.length,
      secondary: l(
        `${p.clarifications.filter((c) => c.status !== 'ANSWERED').length} 项待澄清`,
        `${p.clarifications.filter((c) => c.status !== 'ANSWERED').length} open`,
      ),
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
const loading = ref(false),
  saving = ref(false),
  error = ref(''),
  conflict = ref(false),
  page = ref(1),
  total = ref(0)
const compareId = ref(''),
  compareTargetId = ref('')
const clarificationFilter = ref('ALL')
const filteredClarifications = computed(() =>
  (project.value?.clarifications || []).filter(
    (c) =>
      clarificationFilter.value === 'ALL' ||
      (clarificationFilter.value === 'OPEN' ? c.status !== 'ANSWERED' : c.status === 'ANSWERED'),
  ),
)
const query = ref(''),
  ownerFilter = ref(''),
  statusFilter = ref(''),
  tab = ref('overview')
const stages = ['DISCOVERY', 'REQUIREMENTS', 'BASELINED', 'SOLUTION', 'RELEASE', 'ARCHIVED']
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
function stateLabel(state: string | undefined): string {
  const labels: Record<string, [string, string]> = {
    DISCOVERY: ['项目理解', 'Discovery'],
    REQUIREMENTS: ['需求梳理', 'Requirements'],
    BASELINED: ['需求已基线', 'Baselined'],
    SOLUTION: ['方案设计', 'Solution'],
    RELEASE: ['成果发布', 'Release'],
    ARCHIVED: ['已归档', 'Archived'],
    ACTIVE: ['进行中', 'Active'],
    FULL: ['完整响应', 'Full'],
    PARTIAL: ['部分响应', 'Partial'],
    CONDITIONAL: ['条件响应', 'Conditional'],
    EXCLUDED: ['排除范围', 'Excluded'],
    UNHANDLED: ['未处理', 'Unhandled'],
  }
  Object.assign(labels, {
    HIGH: ['高', 'High'],
    MEDIUM: ['中', 'Medium'],
    LOW: ['低', 'Low'],
    IN: ['范围内', 'In scope'],
    OUT: ['范围外', 'Out of scope'],
    UNKNOWN: ['待核实', 'Unknown'],
    UNCONFIRMED: ['未确认', 'Unconfirmed'],
    OPEN: ['待处理', 'Open'],
    ANSWERED: ['已答复', 'Answered'],
    RESOLVED: ['已解决', 'Resolved'],
    ACCEPTED: ['已接受', 'Accepted'],
    PENDING: ['待批准', 'Awaiting approval'],
    APPROVED: ['已批准', 'Approved'],
    PUBLISHED: ['已发布', 'Published'],
    DRAFT: ['草稿', 'Draft'],
    SUCCEEDED: ['已完成', 'Succeeded'],
    RUNNING: ['运行中', 'Running'],
    FAILED: ['失败', 'Failed'],
    CANCELLED: ['已停止接收', 'Result discarded'],
    PROJECT: ['项目资料', 'Project material'],
    PRODUCT: ['产品资料', 'Product material'],
    CASE: ['案例资料', 'Case material'],
    CUSTOMER_SOURCE: ['客户来源', 'Customer source'],
    PRODUCT_SOURCE: ['产品来源', 'Product source'],
    INTERNAL_JUDGMENT: ['内部判断', 'Internal judgment'],
    ASSUMPTION: ['假设', 'Assumption'],
    AI_SUGGESTION: ['AI 建议', 'AI suggestion'],
    FIT: ['直接满足', 'Fit'],
    CONFIG: ['配置后满足', 'Configuration'],
    EXTEND: ['需要开发', 'Extension'],
    PARTNER: ['依赖合作方', 'Partner'],
    GAP: ['暂不支持', 'Gap'],
    BLOCKER: ['阻断', 'Blocker'],
    WARNING: ['需关注', 'Warning'],
    INFO: ['提示', 'Information'],
  })
  const pair = state ? labels[state] : undefined
  return pair ? l(pair[0], pair[1]) : state || '—'
}
function printable(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2) || '—'
}
let controller: AbortController | undefined
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
  if (detail.tasks?.some((task) => task.status === 'RUNNING' && task.operationId))
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
async function readProject(ws: string, id: string, signal: AbortSignal): Promise<PresalesProject> {
  try {
    return await presalesApi.get(ws, id, signal)
  } catch (e) {
    if (
      !presalesError(e).accessDenied ||
      signal.aborted ||
      !isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value)
    )
      throw e
    project.value = undefined
    if (!capabilities.value?.canWrite) throw e
    try {
      const repair = await presalesApi.repairContext(ws, id, signal)
      const sourceCollections = [
        'materials',
        'requirements',
        'clarifications',
        'baselines',
        'fitGaps',
        'cases',
        'solutions',
        'reviews',
        'reviewDrafts',
        'releases',
        'tasks',
        'contextCards',
      ] as const
      const metadata = [
        'id',
        'workspaceId',
        'version',
        'name',
        'customer',
        'ownerId',
        'industry',
        'goal',
        'status',
        'stage',
        'agentId',
        'agentName',
        'createdBy',
        'createdAt',
        'updatedBy',
        'updatedAt',
        'sourceAccessRestricted',
        'repairBindings',
      ]
      const allowedKeys = new Set<string>([...metadata, ...sourceCollections])
      if (
        repair.sourceAccessRestricted !== true ||
        repair.id !== id ||
        repair.workspaceId !== ws ||
        !Number.isInteger(repair.version) ||
        Object.keys(repair).some((key) => !allowedKeys.has(key)) ||
        sourceCollections.some((key) => !Array.isArray(repair[key]) || repair[key].length !== 0) ||
        !Array.isArray(repair.repairBindings) ||
        repair.repairBindings.some(
          (binding) =>
            typeof binding.id !== 'string' ||
            !binding.id ||
            !['PROJECT', 'PRODUCT', 'CASE', 'UNKNOWN'].includes(binding.role) ||
            Object.keys(binding).some((key) => !['id', 'role'].includes(key)),
        )
      )
        throw e
      return { ...repair }
    } catch {
      throw e
    }
  }
}
async function load() {
  if (dirty.value) return
  scopeGeneration++
  controller?.abort()
  controller = new AbortController()
  taskPolling.reset()
  const ws = workspace.currentWorkspaceId,
    id = projectId.value,
    signal = controller.signal
  members.value = []
  membersError.value = ''
  membersLoading.value = false
  portfolioController?.abort()
  portfolio.value = []
  portfolioError.value = false
  portfolioLoading.value = false
  project.value = undefined
  projects.value = []
  capabilities.value = undefined
  sourcePreview.reset()
  saving.value = false
  optionsLoading.value = false
  employeesLoading.value = false
  editError.value = ''
  compareId.value = ''
  if (!ws) {
    error.value = t('presales.select_a_workspace')
    return
  }
  loading.value = true
  error.value = ''
  conflict.value = false
  try {
    const caps = await presalesApi.capabilities(ws, signal)
    if (!isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value) || signal.aborted)
      return
    capabilities.value = caps
    if (!caps.enabled) return
    void loadMembers(ws, signal)
    if (!id) void refreshPortfolio()
    if (id) {
      const detail = await readProject(ws, id, signal)
      if (
        isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value) &&
        !signal.aborted
      ) {
        acceptProject(detail)
      }
    } else {
      const result = await presalesApi.list(
        ws,
        {
          q: query.value,
          ownerId: ownerFilter.value,
          stage: statusFilter.value,
          page: page.value,
          pageSize: 20,
        },
        signal,
      )
      if (
        isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value) &&
        !signal.aborted
      ) {
        projects.value = result.items
        total.value = result.total
      }
    }
  } catch (e) {
    if (!signal.aborted) {
      if ((e as { response?: { status?: number } }).response?.status === 404 && !capabilities.value)
        capabilities.value = {
          enabled: false,
          semanticEnabled: false,
          canWrite: false,
          canApprove: false,
        }
      else error.value = presalesError(e).message
    }
  } finally {
    if (!signal.aborted) loading.value = false
  }
}
function search() {
  page.value = 1
  void load()
}
const editor = usePresalesEditorSession({
  project,
  canWrite: () => canWrite.value,
  saving,
  workspaceId: () => workspace.currentWorkspaceId,
  projectId: () => projectId.value,
  captureScope,
  isActiveScope,
  loadEmployees,
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
async function command(action: string, payload: object): Promise<boolean> {
  const scope = captureScope()
  const ws = workspace.currentWorkspaceId,
    current = project.value
  if (!ws || !current || !canWrite.value || saving.value) return false
  if (
    current.sourceAccessRestricted &&
    !(
      action === 'BIND_MATERIAL' ||
      action === 'UNBIND_MATERIAL' ||
      (action === 'UPDATE_PROJECT' &&
        Object.keys(payload).length === 1 &&
        'agentId' in payload &&
        typeof payload.agentId === 'string')
    )
  )
    return false
  saving.value = true
  editError.value = ''
  error.value = ''
  try {
    const result = await presalesApi.command(ws, current.id, {
      action,
      payload,
      expectedVersion: current.version,
      operationId: receipt({
        ws,
        id: current.id,
        version: current.version,
        action,
        payload,
      }),
    })
    return acceptMutation(result, scope)
  } catch (e) {
    if (!isActiveScope(scope)) return false
    const issue = presalesError(e)
    conflict.value = issue.conflict
    error.value = editError.value = issue.message
    return false
  } finally {
    if (isActiveScope(scope)) saving.value = false
  }
}
async function save() {
  if (!canWrite.value || saving.value || conflict.value) return
  const scope = captureScope(),
    active = editor.captureSession(),
    kind = editorKind.value
  const submission = prepareEditorSubmission(
    kind,
    form.value,
    !!project.value?.sourceAccessRestricted,
  )
  if (submission.kind === 'invalid') {
    editError.value = t(
      submission.issue === 'REQUIRED_FIELDS'
        ? 'presales.complete_the_required_fields'
        : 'presales.context_message_28',
    )
    return
  }
  if (submission.kind === 'project') {
    const data = submission.data
    const ws = workspace.currentWorkspaceId
    if (!ws) return
    saving.value = true
    try {
      const body = {
        ...submission.metadata,
        expectedVersion: project.value?.version || 0,
        operationId: receipt({
          ws,
          id: projectId.value,
          version: project.value?.version || 0,
          data,
        }),
      }
      const result = project.value
        ? await presalesApi.update(ws, project.value.id, body)
        : await presalesApi.create(ws, body)
      if (!active() || !acceptMutation(result, scope)) return
      editorOpen.value = false
      saving.value = false
      if (projectId.value) await load()
      else await router.push(`/presales/${result.id}`)
    } catch (e) {
      if (!active()) return
      const issue = presalesError(e)
      editError.value = error.value = issue.message
      conflict.value = issue.conflict
    } finally {
      if (isActiveScope(scope)) saving.value = false
    }
    return
  }
  if ((await command(submission.action, submission.payload)) && active()) {
    editorOpen.value = false
    if (submission.continueEmployee && project.value?.agentId) await continueEmployee()
  }
}

async function downloadHandoff() {
  const scope = captureScope()
  const ws = workspace.currentWorkspaceId,
    id = projectId.value
  const current = project.value
  if (!ws || current?.sourceAccessRestricted) return
  try {
    const result = await presalesApi.handoff(ws, id)
    if (
      !isActiveScope(scope) ||
      project.value !== current ||
      project.value?.sourceAccessRestricted ||
      !isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value)
    )
      return
    const blob = new Blob([JSON.stringify(result, null, 2)], {
      type: 'application/json',
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `internal-handoff-${id}-v${project.value?.version}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    if (isActiveScope(scope) && project.value === current) error.value = presalesError(e).message
  }
}
async function download(versionId: string, filename: string, kind: 'files' | 'preview' | 'draft') {
  const scope = captureScope()
  const ws = workspace.currentWorkspaceId,
    id = projectId.value
  const current = project.value
  if (!ws || current?.sourceAccessRestricted) return
  try {
    const blob = await presalesApi.file(ws, id, versionId, filename, kind)
    if (
      !isActiveScope(scope) ||
      project.value !== current ||
      project.value?.sourceAccessRestricted ||
      !isCurrentRequest(ws, workspace.currentWorkspaceId, id, projectId.value)
    )
      return
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = (kind === 'files' ? '' : 'UNAPPROVED-') + filename
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    if (isActiveScope(scope) && project.value === current) error.value = presalesError(e).message
  }
}
async function approveRelease(release: PresalesRecord) {
  const scope = captureScope(),
    current = project.value
  if (!current || !canApprove.value || saving.value) return
  try {
    const result = await ElMessageBox.prompt(
      t('presales.context_message_29'),
      t('presales.approve_release'),
      { inputValidator: (value) => !!value?.trim() },
    )
    if (
      !isActiveScope(scope) ||
      current.id !== project.value?.id ||
      current.version !== project.value.version ||
      !canApprove.value
    )
      return
    await command('APPROVE_RELEASE', {
      releaseId: release.id,
      reason: result.value,
    })
  } catch {
    /* Cancel. */
  }
}
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
async function archive() {
  const scope = captureScope(),
    current = project.value
  if (!current || !canWrite.value || current.sourceAccessRestricted || saving.value) return
  try {
    await ElMessageBox.confirm(
      t('presales.archive_this_project_and_make_it_read_only'),
      t('presales.archive_project'),
      { type: 'warning' },
    )
    if (
      !isActiveScope(scope) ||
      current.id !== project.value?.id ||
      current.version !== project.value.version ||
      !canWrite.value ||
      project.value.sourceAccessRestricted
    )
      return
    await command('ARCHIVE', {})
  } catch {
    /* Cancel leaves data unchanged. */
  }
}
const generationOpen = ref(false),
  employees = ref<PresalesEmployee[]>([]),
  employeeError = ref(''),
  employeesLoading = ref(false)
let employeeRequest = 0
const generation = ref({ skill: 'S1', taskGoal: '' })
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
async function loadEmployees() {
  const scope = captureScope(),
    request = ++employeeRequest
  const ws = workspace.currentWorkspaceId
  if (!ws) return
  const active = () => isActiveScope(scope) && request === employeeRequest
  employees.value = []
  employeeError.value = ''
  employeesLoading.value = true
  try {
    const result = await presalesApi.employees(ws)
    if (active()) employees.value = result
  } catch (e) {
    if (active()) employeeError.value = employeeIssue(presalesError(e).message)
  } finally {
    if (active()) employeesLoading.value = false
  }
}
async function openGeneration(skill: string, taskGoal = t('presales.context_message_30')) {
  if (dirty.value || saving.value || !canGenerate.value) return
  generation.value.skill = skill
  generation.value.taskGoal = taskGoal
  generationOpen.value = true
  await loadEmployees()
}
async function continueEmployee() {
  await openGeneration('S2', t('presales.context_message_31'))
}
async function generate() {
  const scope = captureScope()
  const ws = workspace.currentWorkspaceId,
    current = project.value
  if (!ws || !current || dirty.value || saving.value || !canGenerate.value) return
  saving.value = true
  const operationId = receipt({
    ws,
    id: current.id,
    version: current.version,
    generation: generation.value,
  })
  try {
    const result = await presalesApi.generate(ws, current.id, {
      ...generation.value,
      expectedVersion: current.version,
      operationId,
    })
    if (acceptMutation(result, scope)) {
      taskPolling.start()
      generationOpen.value = false
    }
  } catch (e) {
    if (!isActiveScope(scope)) return
    const issue = presalesError(e)
    error.value = employeeError.value = employeeIssue(issue.message)
    conflict.value = issue.conflict
  } finally {
    if (isActiveScope(scope)) saving.value = false
  }
}
async function cancelTask(task: PresalesTask) {
  const scope = captureScope()
  const ws = workspace.currentWorkspaceId,
    id = projectId.value
  if (!ws || !id || !canWrite.value || saving.value || task.status !== 'RUNNING') return
  saving.value = true
  try {
    const result = await presalesApi.cancelTask(ws, id, task.id, {
      operationId: receipt({ ws, id, taskId: task.id, action: 'cancel' }),
    })
    acceptMutation(result, scope)
  } catch (e) {
    if (!isActiveScope(scope)) return
    const issue = presalesError(e)
    error.value = employeeError.value = employeeIssue(issue.message)
    conflict.value = issue.conflict
  } finally {
    if (isActiveScope(scope)) saving.value = false
  }
}
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
  portfolioController?.abort()
  controller?.abort()
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
.el-pagination {
  margin-top: 20px;
  justify-content: flex-end;
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
  .ledger-heading {
    align-items: flex-start;
    flex-direction: column;
  }
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
.navigation-link {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.project-pulse button > span::after {
  content: ' ↗';
  color: var(--el-color-primary);
}
</style>
<style scoped src="../shared/workbenchSections.css"></style>
<style scoped src="../shared/editorFields.css"></style>
