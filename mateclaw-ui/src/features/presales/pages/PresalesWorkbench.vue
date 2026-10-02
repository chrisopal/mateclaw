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
            <section class="section-heading">
              <h2>{{ t('presales.context_and_open_items') }}</h2>
              <el-button
                :disabled="!canGenerate"
                @click="openGeneration('S1')"
                >{{ t('presales.ask_employee_to_analyze') }}</el-button
              >
            </section>
            <el-descriptions
              :column="isNarrow ? 1 : 2"
              border
              ><el-descriptions-item :label="t('presales.customer')">{{
                project.customer
              }}</el-descriptions-item
              ><el-descriptions-item :label="t('presales.goal')">{{
                project.goal || '—'
              }}</el-descriptions-item
              ><el-descriptions-item :label="t('presales.industry')">{{
                project.industry || '—'
              }}</el-descriptions-item
              ><el-descriptions-item :label="t('presales.requirement_confirmation')"
                ><details class="baseline-details">
                  <summary>
                    {{
                      project.baselines.at(-1)?.id
                        ? t('presales.confirmed')
                        : t('presales.not_confirmed')
                    }}
                  </summary>
                  <p
                    v-if="project.baselines.at(-1)?.id"
                    class="muted"
                  >
                    {{ project.baselines.at(-1)?.id }}
                  </p>
                </details></el-descriptions-item
              ></el-descriptions
            >
            <template v-if="project.contextCards?.length || project.context"
              ><h3>{{ t('presales.context_card') }}</h3>
              <pre class="safe-content">{{
                printable(project.contextCards?.at(-1) || project.context)
              }}</pre></template
            ><el-empty
              v-else
              :description="t('presales.no_project_context_yet')"
            />
            <h3>{{ t('presales.employee_activity') }}</h3>
            <article
              v-for="task in project.tasks || []"
              :key="task.id"
              class="solution-revision"
            >
              <div class="task-heading">
                <strong
                  >{{ skillNames[task.skill as keyof typeof skillNames] || task.skill }} ·
                  {{ stateLabel(task.status) }}</strong
                >
                <div class="task-actions">
                  <el-button
                    v-if="task.status === 'RUNNING'"
                    :disabled="!canWrite"
                    @click="cancelTask(task)"
                    >{{ t('presales.discard_this_run_result') }}</el-button
                  ><el-button
                    v-if="task.conversationId && task.agentId"
                    type="primary"
                    size="small"
                    @click="
                      router.push({
                        path: '/chat',
                        query: {
                          conversationId: task.conversationId,
                          agentId: task.agentId,
                        },
                      })
                    "
                    >{{ t('presales.view_employee_execution') }}</el-button
                  ><el-button
                    type="primary"
                    size="small"
                    @click="showEvidence(task.contextSnapshot || task)"
                    >{{ t('presales.input_snapshot') }}</el-button
                  >
                </div>
              </div>
              <el-alert
                v-if="task.contextSnapshot?.truncated"
                type="warning"
                :closable="false"
                :title="t('presales.context_message_5')"
              />
              <p class="muted">
                {{ task.agentName || t('presales.legacy_generation')
                }}<span v-if="task.conversationId">
                  · {{ t('presales.execution') }} {{ task.runId }}</span
                >
              </p>
              <p
                v-if="task.queueState === 'QUEUED' && task.status === 'RUNNING'"
                class="muted"
              >
                {{ t('presales.accepted_and_waiting_for_the_employee') }}
              </p>
              <p v-if="task.error">{{ employeeIssue(task.error) }}</p>
              <div
                v-for="(item, index) in task.result?.items || []"
                :key="index"
              >
                <h4>{{ item.title }} · {{ stateLabel(item.originKind) }}</h4>
                <pre class="safe-content">{{ item.text }}</pre>
                <el-button
                  v-if="['S1', 'S5', 'S6'].includes(task.skill!)"
                  :disabled="!canWrite"
                  @click="adopt(task.skill, item)"
                  >{{ t('presales.review_proposal') }}</el-button
                >
              </div>
              <div
                v-if="task.result?.solution?.presentation"
                class="presentation-result"
              >
                <strong>{{ t('presales.output_draft') }} · ppt-master-plus</strong
                ><span class="muted"
                  >{{ task.result.solution.presentation.skill }} ·
                  {{ task.result.solution.presentation.skillVersion }} ·
                  {{ task.result.solution.presentation.pageCount }}
                  {{ t('presales.pages') }}</span
                >
                <div class="task-actions">
                  <el-button
                    v-for="slide in task.result.solution.presentation.slides || []"
                    :key="slide.filename"
                    type="primary"
                    size="small"
                    @click="previewPresentation(task.result.solution.presentation, slide.filename)"
                    >{{ t('presales.preview') }} {{ slide.title || slide.filename }}</el-button
                  >
                </div>
              </div>
              <pre
                v-if="task.result"
                class="safe-content"
                >{{
                  printable({
                    unknowns: task.result.unknowns,
                    assumptions: task.result.assumptions,
                  })
                }}</pre
              >
            </article>
            <el-table :data="project.tasks || []"
              ><el-table-column :label="t('presales.work')"
                ><template #default="{ row }">{{
                  skillNames[row.skill as keyof typeof skillNames] || t('presales.previous_task')
                }}</template></el-table-column
              ><el-table-column
                prop="agentName"
                :label="t('presales.employee')" /><el-table-column :label="t('presales.status')"
                ><template #default="{ row }">{{
                  stateLabel(row.status)
                }}</template></el-table-column
              ><el-table-column
                prop="error"
                :label="t('presales.failure_reason')"
            /></el-table>
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
            <section class="section-heading">
              <h2>{{ t('presales.versioned_solutions') }}</h2>
              <div>
                <el-button
                  :disabled="!canGenerate"
                  @click="openGeneration('S5')"
                  >{{ t('presales.ask_employee_to_compose') }}</el-button
                >
              </div>
            </section>
            <section
              v-if="project.solutions.length"
              class="coverage"
            >
              <div class="coverage-heading">
                <h3>{{ t('presales.requirement_coverage') }}</h3>
                <details class="inline-help">
                  <summary>{{ t('presales.definition') }}</summary>
                  <p>
                    {{ t('presales.context_message_8') }}
                  </p>
                </details>
              </div>
              <div class="coverage-stats">
                <div>
                  <span>{{ t('presales.requirement_response') }}</span
                  ><strong>{{
                    project.solutions.at(-1)?.coverage?.applicable
                      ? `${project.solutions.at(-1)?.coverage!.handledIn}/${project.solutions.at(-1)?.coverage!.totalIn}`
                      : t('presales.not_applicable')
                  }}</strong>
                </div>
                <div>
                  <span>{{ t('presales.section_references') }}</span
                  ><strong>{{ coverage }}</strong>
                </div>
              </div>
              <el-table :data="responseRows"
                ><el-table-column
                  prop="title"
                  :label="t('presales.requirement')"
                  min-width="200" /><el-table-column :label="t('presales.scope')"
                  ><template #default="{ row }">{{
                    stateLabel(row.scope)
                  }}</template></el-table-column
                ><el-table-column :label="t('presales.fulfillment_approach')"
                  ><template #default="{ row }">{{
                    stateLabel(row.fit)
                  }}</template></el-table-column
                ><el-table-column :label="t('presales.response')"
                  ><template #default="{ row }">{{
                    stateLabel(row.response)
                  }}</template></el-table-column
                ><el-table-column
                  prop="sections"
                  :label="t('presales.latest_solution_sections')"
                  min-width="230"
              /></el-table>
            </section>
            <div
              v-if="project.solutions.length > 1"
              class="toolbar"
            >
              <el-select
                v-model="compareId"
                :placeholder="t('presales.compare_with_previous_version')"
                ><el-option
                  v-for="solution in project.solutions.slice(0, -1)"
                  :key="solution.id"
                  :value="solution.id"
                  :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`" /></el-select
              ><el-select
                v-model="compareTargetId"
                :placeholder="t('presales.compare_target_latest_by_default')"
                ><el-option
                  v-for="solution in project.solutions"
                  :key="solution.id"
                  :value="solution.id"
                  :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`"
              /></el-select>
            </div>
            <el-table
              v-if="compareId"
              :data="comparison"
              ><el-table-column
                prop="title"
                :label="t('presales.section')"
              /><el-table-column
                :label="t('presales.selected_previous_version')"
                min-width="250"
                ><template #default="{ row }">
                  <pre class="safe-content">{{ row.before }}</pre>
                </template></el-table-column
              ><el-table-column
                :label="t('presales.target_version')"
                min-width="250"
                ><template #default="{ row }">
                  <pre class="safe-content">{{ row.after }}</pre>
                </template></el-table-column
              ></el-table
            >
            <el-empty
              v-if="!project.solutions.length"
              :description="t('presales.context_message_9')"
            />
            <article
              v-for="solution in [...project.solutions].reverse()"
              :key="solution.id"
              class="solution-revision"
            >
              <div class="solution-version-header">
                <div>
                  <h3>
                    {{ solution.title }} ·
                    {{ versionLabel('solutions', solution.id) }}
                  </h3>
                  <el-tag>{{ stateLabel(solution.status || 'DRAFT') }}</el-tag>
                </div>
                <el-button
                  type="primary"
                  size="small"
                  :disabled="!canWrite"
                  @click="openEditor('solution', solution)"
                  >{{ t('presales.revise_this_version') }}</el-button
                >
              </div>
              <div class="solution-downloads">
                <span>{{ t('presales.download_draft') }}</span
                ><el-button
                  v-for="filename in ['solution.md', 'solution.docx', 'solution.pptx']"
                  :key="filename"
                  type="primary"
                  size="small"
                  @click="download(solution.id, filename, 'draft')"
                  >{{ filename }}</el-button
                >
              </div>
              <details class="baseline-details">
                <summary>
                  {{ t('presales.confirmed_requirements') }} ·
                  {{ solution.baselineId ? t('presales.view_details') : t('presales.unconfirmed') }}
                </summary>
                <p class="muted">
                  {{ solution.baselineId || t('presales.context_message_10') }}
                </p>
              </details>
              <el-alert
                v-if="solution.baselineId && solution.baselineId !== project.baselines.at(-1)?.id"
                type="warning"
                :closable="false"
                :title="t('presales.context_message_11')"
              />
              <section
                v-for="(section, index) in solution.sections"
                :key="index"
                class="solution-section"
              >
                <h4>{{ section.title }}</h4>
                <pre class="safe-content">{{ section.text }}</pre>
                <el-button
                  v-if="section.evidenceRefs?.length"
                  type="primary"
                  size="small"
                  @click="showEvidence(section)"
                  >{{ t('presales.section_evidence') }}</el-button
                >
              </section>
            </article>
          </el-tab-pane>
          <el-tab-pane
            :label="t('presales.review_outputs')"
            name="review"
          >
            <section class="section-heading">
              <h2>{{ t('presales.independent_review') }}</h2>
              <div>
                <el-button
                  :disabled="!canGenerate || !project.solutions.length"
                  @click="openGeneration('S7')"
                  >{{ t('presales.ask_employee_to_review') }}</el-button
                ><el-button
                  :disabled="!canWrite"
                  @click="openEditor('review')"
                  >{{ t('presales.record_independent_review') }}</el-button
                ><el-button
                  :disabled="!canWrite"
                  @click="openEditor('release')"
                  >{{ t('presales.create_release_candidate') }}</el-button
                >
              </div>
            </section>
            <el-table :data="project.reviews"
              ><el-table-column :label="t('presales.solution')"
                ><template #default="{ row }">{{
                  versionLabel('solutions', row.solutionId)
                }}</template></el-table-column
              ><el-table-column
                prop="summary"
                :label="t('presales.summary')"
              /><el-table-column
                :label="t('presales.findings')"
                min-width="300"
                ><template #default="{ row }">
                  <pre class="safe-content">{{
                    (row.findings || row.issues || []).length
                      ? printable(row.findings || row.issues)
                      : t('presales.no_findings')
                  }}</pre>
                </template></el-table-column
              ></el-table
            >
            <section class="section-heading">
              <h3>{{ t('presales.release_versions') }}</h3>
              <el-button @click="downloadHandoff">{{
                t('presales.export_internal_handoff')
              }}</el-button>
            </section>
            <el-table :data="project.releases"
              ><el-table-column :label="t('presales.version')"
                ><template #default="{ row }">{{
                  versionLabel('releases', row.id)
                }}</template></el-table-column
              ><el-table-column :label="t('presales.status')"
                ><template #default="{ row }">{{
                  stateLabel(row.status)
                }}</template></el-table-column
              ><el-table-column
                :label="t('presales.files')"
                width="90"
                ><template #default="{ row }">{{
                  row.files?.length || 0
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
                      >{{ t('presales.manifest') }}</el-button
                    ><el-button
                      v-for="file in row.files || []"
                      :key="file.filename"
                      type="primary"
                      size="small"
                      :disabled="row.status !== 'PUBLISHED' && !canApprove"
                      @click="
                        download(
                          row.id,
                          file.filename,
                          row.status === 'PUBLISHED' ? 'files' : 'preview',
                        )
                      "
                      >{{
                        row.status === 'PUBLISHED'
                          ? t('presales.download')
                          : t('presales.unapproved_preview')
                      }}
                      {{ file.filename }}</el-button
                    ><el-button
                      type="primary"
                      size="small"
                      :disabled="!canApprove || row.status !== 'PENDING'"
                      @click="approveRelease(row)"
                      >{{ t('presales.approve_release') }}</el-button
                    ><el-button
                      type="primary"
                      size="small"
                      :disabled="!canApprove || row.status !== 'APPROVED'"
                      @click="command('PUBLISH_RELEASE', { releaseId: row.id })"
                      >{{ t('presales.publish') }}</el-button
                    >
                  </div></template
                ></el-table-column
              ></el-table
            >
            <details class="inline-help">
              <summary>{{ t('presales.release_rules') }}</summary>
              <p>
                {{ t('presales.context_message_12') }}
              </p>
            </details>
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
        <template v-if="editorKind === 'project'"
          ><el-form-item
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
          ><el-form-item :label="t('presales.presales_solution_employee')"
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
              @click="router.push('/agents')"
              >{{ t('presales.manage_employees') }}</el-button
            ></el-form-item
          ><el-alert
            v-if="employeeError"
            :title="employeeError"
            type="warning"
            :closable="false" />
          <p
            v-if="!employeesLoading && !employees.length"
            class="muted"
          >
            {{ t('presales.context_message_14') }}
          </p>
          <el-form-item :label="t('presales.industry')"
            ><el-input v-model="form.industry" /></el-form-item
          ><el-form-item :label="t('presales.goal')"
            ><el-input
              v-model="form.goal"
              type="textarea"
              :rows="3" /></el-form-item
        ></template>
        <template v-else-if="editorKind === 'employee'">
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
              @click="router.push('/agents')"
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
        </template>
        <template v-else-if="editorKind === 'material'"
          ><el-form-item
            :label="t('presales.knowledge_base_2')"
            required
            ><el-select
              v-model="form.kbId"
              filterable
              :loading="optionsLoading"
              @change="selectSource"
              ><el-option
                v-for="source in sourceOptions"
                :key="source.kbId"
                :value="source.kbId"
                :label="source.name" /></el-select
          ></el-form-item>
          <p
            v-if="form.kbId"
            class="muted"
          >
            {{ form.graphId ? t('presales.context_message_15') : t('presales.context_message_16') }}
          </p>
          <el-button
            type="primary"
            size="small"
            @click="router.push('/wiki')"
            >{{ t('presales.open_wiki_to_upload_and_parse_material') }}</el-button
          >
          <details>
            <summary>
              {{ t('presales.advanced_source_identifiers') }}
            </summary>
            <el-form-item :label="t('presales.knowledge_base_id')"
              ><el-input v-model="form.kbId" /></el-form-item
            ><el-form-item :label="t('presales.graph_id_optional')"
              ><el-input v-model="form.graphId"
            /></el-form-item>
          </details>
          <el-form-item :label="t('presales.source_role')"
            ><el-select v-model="form.role"
              ><el-option
                v-for="role in ['PROJECT', 'PRODUCT', 'CASE']"
                :key="role"
                :value="role"
                :label="stateLabel(role)" /></el-select></el-form-item
        ></template>
        <template v-else-if="editorKind === 'requirement'"
          ><el-form-item
            :label="t('presales.requirement_title')"
            required
            ><el-input v-model="form.title" /></el-form-item
          ><el-form-item :label="t('presales.description')"
            ><el-input
              v-model="form.description"
              type="textarea"
              :rows="3" /></el-form-item
          ><el-form-item :label="t('presales.origin_draft_not_accepted_fact')"
            ><el-select v-model="form.originKind"
              ><el-option
                v-for="v in [
                  'CUSTOMER_SOURCE',
                  'PRODUCT_SOURCE',
                  'INTERNAL_JUDGMENT',
                  'ASSUMPTION',
                  'AI_SUGGESTION',
                ]"
                :key="v"
                :value="v"
                :label="stateLabel(v)" /></el-select
          ></el-form-item>
          <div class="form-grid">
            <el-form-item :label="t('presales.priority')"
              ><el-select v-model="form.priority"
                ><el-option
                  v-for="v in ['HIGH', 'MEDIUM', 'LOW']"
                  :key="v"
                  :value="v"
                  :label="stateLabel(v)" /></el-select></el-form-item
            ><el-form-item :label="t('presales.scope')"
              ><el-select v-model="form.scope"
                ><el-option
                  v-for="v in ['IN', 'OUT', 'UNKNOWN']"
                  :key="v"
                  :value="v"
                  :label="stateLabel(v)" /></el-select
            ></el-form-item>
          </div>
          <el-form-item :label="t('presales.context_message_17')"
            ><el-select
              :model-value="form.statementId ? `${form.graphId}:${form.statementId}` : ''"
              filterable
              clearable
              :loading="optionsLoading"
              @change="selectStatement"
              ><el-option
                v-for="statement in statementOptions"
                :key="`${statement.graphId}:${statement.id}`"
                :value="`${statement.graphId}:${statement.id}`"
                :label="statement.label" /></el-select
          ></el-form-item>
          <p
            v-if="!statementOptions.length"
            class="muted"
          >
            {{ t('presales.context_message_18') }}
          </p>
          <details>
            <summary>
              {{ t('presales.advanced_exact_revision_references') }}
            </summary>
            <el-form-item :label="t('presales.graph_id')"
              ><el-input v-model="form.graphId" /></el-form-item
            ><el-form-item label="Statement ID"
              ><el-input v-model="form.statementId" /></el-form-item
            ><el-form-item :label="t('presales.statement_revision')"
              ><el-input v-model="form.statementRevision"
            /></el-form-item></details
        ></template>
        <template v-else-if="editorKind === 'clarification'"
          ><el-alert
            type="info"
            :closable="false"
            :title="
              form.proposedByTaskId
                ? t('presales.context_message_19')
                : t('presales.context_message_20')
            " />
          <h3>{{ form.question }}</h3>
          <p class="safe-content">{{ form.impact }}</p>
          <p class="muted">
            {{
              project?.requirements.find((r) => r.id === form.requirementId)?.title ||
              t('presales.project_wide_question')
            }}
          </p>
          <el-form-item
            :label="t('presales.answer')"
            :required="form.status === 'ANSWERED'"
            ><el-input
              v-model="form.answer"
              type="textarea" /></el-form-item
          ><el-form-item
            :label="t('presales.answer_source')"
            :required="form.status === 'ANSWERED'"
            ><el-input
              v-model="form.answerSourceId"
              maxlength="2000"
              :placeholder="t('presales.context_message_21')"
          /></el-form-item>
          <p class="muted">
            {{ t('presales.context_message_22') }}
          </p>
          <el-form-item :label="t('presales.status')"
            ><el-select v-model="form.status"
              ><el-option
                v-for="v in ['OPEN', 'ANSWERED']"
                :key="v"
                :value="v"
                :label="stateLabel(v)" /></el-select></el-form-item
        ></template>
        <template v-else-if="editorKind === 'baseline'"
          ><el-alert
            type="warning"
            :closable="false"
            :title="t('presales.context_message_23')" /><el-form-item
            :label="t('presales.decision_reason_conditions_and_owner')"
            required
            ><el-input
              v-model="form.reason"
              type="textarea"
              :rows="5" /></el-form-item
        ></template>
        <template v-else-if="editorKind === 'fitgap'"
          ><el-form-item
            :label="t('presales.requirement')"
            required
            ><el-select v-model="form.requirementId"
              ><el-option
                v-for="r in project?.requirements"
                :key="r.id"
                :value="r.id"
                :label="r.title" /></el-select></el-form-item
          ><el-form-item :label="t('presales.fulfillment_approach')"
            ><el-select v-model="form.status"
              ><el-option
                v-for="v in ['FIT', 'CONFIG', 'EXTEND', 'PARTNER', 'GAP', 'UNKNOWN']"
                :key="v"
                :value="v"
                :label="stateLabel(v)" /></el-select></el-form-item
          ><el-form-item :label="t('presales.product_version')"
            ><el-input v-model="form.productVersion" /></el-form-item
          ><el-form-item :label="t('presales.basis_gap_and_response')"
            ><el-input
              v-model="form.reason"
              type="textarea" /></el-form-item
          ><el-form-item :label="t('presales.graph_id')"
            ><el-input v-model="form.graphId" /></el-form-item
          ><el-form-item :label="t('presales.evidence_ids_comma_separated')"
            ><el-input v-model="form.evidenceText" /></el-form-item
        ></template>
        <template v-else-if="editorKind === 'solution'"
          ><el-form-item
            :label="t('presales.solution_title')"
            required
            ><el-input v-model="form.title" /></el-form-item
          ><el-form-item :label="t('presales.confirmed_requirements_2')"
            ><el-select
              v-model="form.baselineId"
              clearable
              ><el-option
                v-for="baseline in project?.baselines"
                :key="baseline.id"
                :value="baseline.id"
                :label="baselineLabel(baseline.id)" /></el-select
          ></el-form-item>
          <div
            v-for="(section, index) in form.sections"
            :key="index"
            class="section-editor"
          >
            <el-form-item :label="`${t('presales.section')} ${Number(index) + 1}`"
              ><el-input v-model="section.title" /></el-form-item
            ><el-form-item :label="t('presales.requirement_references')"
              ><el-select
                v-model="section.requirementRefs"
                multiple
                ><el-option
                  v-for="requirement in editableRequirements"
                  :key="requirement.id"
                  :value="requirement.id"
                  :label="requirement.title" /></el-select></el-form-item
            ><el-form-item :label="t('presales.body')"
              ><el-input
                v-model="section.text"
                type="textarea"
                :rows="6"
            /></el-form-item>
          </div>
          <el-button @click="form.sections!.push({ title: '', text: '', requirementRefs: [] })">{{
            t('presales.add_section')
          }}</el-button>
          <h3>{{ t('presales.requirement_responses') }}</h3>
          <section
            v-for="response in form.requirementResponses"
            :key="response.requirementId"
            class="section-editor"
          >
            <strong>{{
              project?.requirements.find((item) => item.id === response.requirementId)?.title ||
              response.requirementId
            }}</strong
            ><el-form-item :label="t('presales.response_classification')"
              ><el-select v-model="response.status"
                ><el-option
                  v-for="v in ['FULL', 'PARTIAL', 'CONDITIONAL', 'EXCLUDED', 'UNHANDLED']"
                  :key="v"
                  :value="v"
                  :label="stateLabel(v)" /></el-select></el-form-item
            ><el-form-item :label="t('presales.reason_remaining_gaps_or_conditions')"
              ><el-input
                v-model="response.reason"
                type="textarea"
            /></el-form-item></section
        ></template>
        <template v-else-if="editorKind === 'context'"
          ><el-form-item
            :label="t('presales.title')"
            required
            ><el-input v-model="form.title" /></el-form-item
          ><el-form-item
            :label="t('presales.context_not_accepted_facts')"
            required
            ><el-input
              v-model="form.text"
              type="textarea"
              :rows="8"
          /></el-form-item>
          <p>{{ stateLabel(form.originKind) }}</p></template
        >
        <template v-else-if="editorKind === 'review'"
          ><details class="inline-help">
            <summary>{{ t('presales.review_rules') }}</summary>
            <p>
              {{ t('presales.context_message_24') }}
            </p>
          </details>
          <el-form-item
            :label="t('presales.exact_solution_version')"
            required
            ><el-select v-model="form.solutionId"
              ><el-option
                v-for="solution in project?.solutions"
                :key="solution.id"
                :value="solution.id"
                :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`" /></el-select></el-form-item
          ><el-form-item
            :label="t('presales.review_summary')"
            required
            ><el-input
              v-model="form.summary"
              type="textarea"
              :rows="3"
          /></el-form-item>
          <section
            v-for="(issue, index) in form.issues"
            :key="index"
            class="section-editor"
          >
            <el-form-item :label="t('presales.issue_and_remediation')"
              ><el-input
                v-model="issue.description"
                type="textarea"
            /></el-form-item>
            <div class="form-grid">
              <el-form-item :label="t('presales.severity')"
                ><el-select v-model="issue.severity"
                  ><el-option
                    v-for="v in ['BLOCKER', 'WARNING', 'INFO']"
                    :key="v"
                    :value="v"
                    :label="stateLabel(v)" /></el-select></el-form-item
              ><el-form-item :label="t('presales.disposition')"
                ><el-select v-model="issue.status"
                  ><el-option
                    v-for="v in ['OPEN', 'RESOLVED', 'ACCEPTED']"
                    :key="v"
                    :value="v"
                    :label="stateLabel(v)" /></el-select
              ></el-form-item>
            </div>
          </section>
          <el-button
            @click="
              form.issues!.push({
                description: '',
                severity: 'WARNING',
                status: 'OPEN',
              })
            "
            >{{ t('presales.add_finding') }}</el-button
          ></template
        >
        <template v-else-if="editorKind === 'release'"
          ><el-form-item
            :label="t('presales.solution_version')"
            required
            ><el-select v-model="form.solutionId"
              ><el-option
                v-for="solution in project?.solutions"
                :key="solution.id"
                :value="solution.id"
                :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`" /></el-select></el-form-item
          ><el-form-item :label="t('presales.intended_use')"
            ><el-input v-model="form.purpose" /></el-form-item
        ></template>
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
import { loadPortfolio } from '../shared/dashboard'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import {
  presalesApi,
  type PresalesCapabilities,
  type PresalesEditorForm,
  type PresalesMember,
  type PresalesSource,
  type PresalesEmployee,
  type PresalesTask,
  type PresalesProject,
  type PresalesRecord,
} from '../api/presalesApi'
import { label as l } from '../shared/locale'
import { usePresalesTaskPolling } from '../composables/usePresalesTaskPolling'
import { usePresalesSourcePreview } from '../composables/usePresalesSourcePreview'
import { isCurrentRequest, presalesError, coverageLabel, operationReceipt } from '../shared/state'
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
const responseRows = computed(() =>
  (project.value?.requirements || []).map((requirement) => {
    const sections = (project.value?.solutions.at(-1)?.sections || []).filter(
      (section: PresalesRecord) => section.requirementRefs?.includes(requirement.id),
    )
    const solution = project.value?.solutions.at(-1)
    const baseline = project.value?.baselines.find((item) => item.id === solution?.baselineId)
    const ref = baseline?.references?.find(
      (item: PresalesRecord) => item.requirementId === requirement.id,
    )
    return {
      title: requirement.title,
      scope: ref?.scope || requirement.scope,
      response:
        solution?.coverage?.responses?.find(
          (item: PresalesRecord) => item.requirementId === requirement.id,
        )?.status || 'UNHANDLED',
      fit:
        project.value?.fitGaps.filter((fit) => fit.requirementId === requirement.id).at(-1)
          ?.status || 'UNKNOWN',
      sections: sections.map((section: PresalesRecord) => section.title).join(' / ') || '—',
      covered: sections.length > 0,
    }
  }),
)
const coverage = computed(() => {
  const rows = responseRows.value.filter((row) => row.scope === 'IN')
  return rows.length
    ? coverageLabel(rows.length, rows.filter((row) => row.covered).length)
    : t('presales.not_applicable_zero_denominator')
})
const comparison = computed(() => {
  const before =
    project.value?.solutions.find((solution) => solution.id === compareId.value)?.sections || []
  const after =
    (
      project.value?.solutions.find((solution) => solution.id === compareTargetId.value) ||
      project.value?.solutions.at(-1)
    )?.sections || []
  return Array.from(
    new Set([...before, ...after].map((section: PresalesRecord) => String(section.title))),
  ).map((title) => ({
    title,
    before: before.find((section: PresalesRecord) => section.title === title)?.text || '—',
    after: after.find((section: PresalesRecord) => section.title === title)?.text || '—',
  }))
})
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
type Editor =
  | 'project'
  | 'employee'
  | 'material'
  | 'requirement'
  | 'clarification'
  | 'baseline'
  | 'fitgap'
  | 'solution'
  | 'release'
  | 'review'
  | 'context'
const sourceOptions = ref<PresalesSource[]>([]),
  statementOptions = ref<PresalesRecord[]>([]),
  optionsLoading = ref(false)
const editorOpen = ref(false),
  editorKind = ref<Editor>('project'),
  form = ref<PresalesEditorForm>({}),
  editError = ref(''),
  initialForm = ref('')
let editorGeneration = 0
watch(
  editorOpen,
  (open) => {
    if (!open) {
      editorGeneration++
      sourceOptions.value = []
      statementOptions.value = []
      optionsLoading.value = false
    }
  },
  { flush: 'sync' },
)
const editableRequirements = computed(() => {
  const baseline = project.value?.baselines.find((item) => item.id === form.value.baselineId)
  return (project.value?.requirements || []).filter(
    (item) =>
      !baseline ||
      baseline.references?.some((reference: PresalesRecord) => reference.requirementId === item.id),
  )
})
watch(
  () => form.value.baselineId,
  () => {
    if (editorKind.value !== 'solution' || !editorOpen.value) return
    form.value.requirementResponses = editableRequirements.value.map(
      (item) =>
        form.value.requirementResponses?.find(
          (response: PresalesRecord) => response.requirementId === item.id,
        ) || { requirementId: item.id, status: 'UNHANDLED', reason: '' },
    )
  },
)
const dirty = computed(() => editorOpen.value && JSON.stringify(form.value) !== initialForm.value)
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
function openEditor(kind: Editor, record?: PresalesRecord) {
  if (
    !canWrite.value ||
    saving.value ||
    (project.value?.sourceAccessRestricted && !['employee', 'material'].includes(kind))
  )
    return
  editorGeneration++
  editorKind.value = kind
  editError.value = ''
  if (kind === 'project' || kind === 'employee') void loadEmployees()
  if (kind === 'employee') record = { agentId: record?.agentId || '' }
  form.value = JSON.parse(
    JSON.stringify(
      record ||
        {
          project: {
            name: '',
            customer: '',
            ownerId: '',
            agentId: '',
            industry: '',
            goal: '',
          },
          employee: { agentId: '' },
          material: { kbId: '', graphId: '', role: 'PROJECT' },
          requirement: {
            title: '',
            description: '',
            originKind: 'INTERNAL_JUDGMENT',
            priority: 'MEDIUM',
            scope: 'IN',
            statementId: '',
            statementRevision: '',
            graphId: '',
          },
          clarification: {
            question: '',
            requirementId: '',
            impact: '',
            ownerId: '',
            answer: '',
            answerSourceId: '',
            status: 'OPEN',
          },
          solution: {
            title: '',
            baselineId: project.value?.baselines.at(-1)?.id || '',
            sections: [{ title: '', text: '' }],
          },
          fitgap: {
            requirementId: '',
            status: 'UNKNOWN',
            reason: '',
            evidenceText: '',
            productVersion: '',
            graphId: '',
          },
          baseline: { reason: '' },
          release: { solutionId: '', purpose: '' },
          review: { solutionId: '', summary: '', issues: [] },
          context: {
            title: '',
            text: '',
            originKind: 'AI_SUGGESTION',
            sourceRefs: [],
          },
        }[kind],
    ),
  )
  if (kind === 'solution') {
    delete form.value.id
    if (!form.value.requirementResponses)
      form.value.requirementResponses = editableRequirements.value.map((item) => ({
        requirementId: item.id,
        status: 'UNHANDLED',
        reason: '',
      }))
  }
  initialForm.value = JSON.stringify(form.value)
  editorOpen.value = true
  if (kind === 'material' || kind === 'requirement') void loadOptions(kind)
}
async function loadOptions(kind: Editor) {
  const scope = captureScope(),
    session = editorGeneration
  const ws = workspace.currentWorkspaceId,
    id = projectId.value
  if (!ws) return
  const active = () =>
    isActiveScope(scope) &&
    session === editorGeneration &&
    editorOpen.value &&
    editorKind.value === kind
  optionsLoading.value = true
  sourceOptions.value = []
  statementOptions.value = []
  try {
    const [sources, statements] =
      kind === 'material'
        ? [await presalesApi.sources(ws), undefined]
        : [undefined, await presalesApi.statements(ws, id)]
    if (!active() || (kind !== 'material' && project.value?.sourceAccessRestricted)) return
    if (sources) sourceOptions.value = sources
    if (statements) statementOptions.value = statements
  } catch (e) {
    if (active()) editError.value = presalesError(e).message
  } finally {
    if (active()) optionsLoading.value = false
  }
}
function selectSource(kbId: string) {
  const source = sourceOptions.value.find((item) => item.kbId === kbId)
  form.value.graphId = source?.graphId || ''
  form.value.name = source?.name || ''
}
function selectStatement(key: string) {
  const statement = statementOptions.value.find((item) => `${item.graphId}:${item.id}` === key)
  form.value.statementId = statement?.id || ''
  form.value.statementRevision = statement?.revision || ''
  form.value.graphId = statement?.graphId || ''
  form.value.evidenceIds = statement?.evidenceIds || []
}
async function discard(): Promise<boolean> {
  if (saving.value) return false
  if (!dirty.value) {
    editorOpen.value = false
    return true
  }
  const scope = captureScope(),
    session = editorGeneration,
    draft = JSON.stringify(form.value)
  try {
    await ElMessageBox.confirm(
      t('presales.discard_unsaved_changes'),
      t('presales.unsaved_changes_2'),
      { type: 'warning' },
    )
    if (
      !isActiveScope(scope) ||
      session !== editorGeneration ||
      saving.value ||
      draft !== JSON.stringify(form.value)
    )
      return false
    editorOpen.value = false
    return true
  } catch {
    return false
  }
}
async function closeEditor(done: () => void) {
  if ((await discard()) && !editorOpen.value) done()
}
onBeforeRouteLeave(discard)
onBeforeRouteUpdate(discard)
const unregister = workspace.registerBeforeSwitch(discard)
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value || saving.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
window.addEventListener('beforeunload', beforeUnload)
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
    session = editorGeneration,
    kind = editorKind.value
  const active = () => isActiveScope(scope) && session === editorGeneration && editorOpen.value
  const data = JSON.parse(JSON.stringify(form.value))
  const required: Partial<Record<Editor, string[]>> = {
    project: ['name', 'customer'],
    material: ['kbId'],
    requirement: ['title'],
    clarification: ['question'],
    baseline: ['reason'],
    fitgap: ['requirementId'],
    solution: ['title'],
    release: ['solutionId'],
    review: ['solutionId', 'summary'],
    context: ['title', 'text'],
  }
  if (required[kind]?.some((key) => !String(data[key] || '').trim())) {
    editError.value = t('presales.complete_the_required_fields')
    return
  }
  if (
    kind === 'clarification' &&
    data.status === 'ANSWERED' &&
    (!data.answer?.trim() || !data.answerSourceId?.trim())
  ) {
    editError.value = t('presales.context_message_28')
    return
  }
  if (kind === 'employee') {
    if ((await command('UPDATE_PROJECT', { agentId: data.agentId || '' })) && active())
      editorOpen.value = false
    return
  }
  if (kind === 'project') {
    const ws = workspace.currentWorkspaceId
    if (!ws) return
    saving.value = true
    try {
      const body = {
        name: data.name,
        customer: data.customer,
        ownerId: data.ownerId,
        agentId: data.agentId ?? '',
        industry: data.industry,
        goal: data.goal,
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
  if (kind === 'fitgap') {
    data.evidenceIds = String(data.evidenceText || '')
      .split(',')
      .map((id: string) => id.trim())
      .filter(Boolean)
    delete data.evidenceText
  }
  const actions = {
    material: 'BIND_MATERIAL',
    requirement: 'SAVE_REQUIREMENT',
    clarification: 'SAVE_CLARIFICATION',
    baseline: 'APPROVE_BASELINE',
    fitgap: 'SAVE_FIT_GAP',
    solution: 'SAVE_SOLUTION',
    release: 'CREATE_RELEASE',
    review: 'SAVE_REVIEW',
    context: 'SAVE_CONTEXT',
  }
  const payload =
    kind === 'material' && project.value?.sourceAccessRestricted
      ? {
          kbId: data.kbId,
          graphId: data.graphId || '',
          role: data.role || 'PROJECT',
        }
      : data
  if ((await command(actions[kind], payload)) && active()) {
    editorOpen.value = false
    if (kind === 'clarification' && data.status === 'ANSWERED' && project.value?.agentId)
      await continueEmployee()
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
    statementOptions.value = []
    if (!current || !['employee', 'material'].includes(editorKind.value)) {
      editorOpen.value = false
      form.value = {}
      initialForm.value = ''
    }
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
  window.removeEventListener('beforeunload', beforeUnload)
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
.section-heading,
.project-meta,
.toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.page-heading,
.section-heading {
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
h2 {
  font-size: 16px;
  font-weight: 600;
}
h3 {
  font-size: 15px;
  font-weight: 600;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.toolbar {
  margin: 20px 0 12px;
}
.toolbar .el-input {
  width: 240px;
}
.toolbar .el-select {
  width: 180px;
}
.project-meta {
  padding: 16px 0;
  font-size: 13px;
}
.section-heading {
  margin: 16px 0;
}
.el-alert {
  margin: 12px 0;
}
.el-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
.safe-content {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  line-height: 1.7;
  margin: 8px 0;
}
.solution-revision {
  border-bottom: 1px solid var(--el-border-color);
  padding: 12px 0 24px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.section-editor {
  border-top: 1px solid var(--el-border-color-light);
  padding-top: 16px;
}
.el-form .el-select {
  width: 100%;
}
@media (max-width: 768px) {
  .presales-workbench {
    padding: 16px;
  }
  .toolbar .el-input,
  .toolbar .el-select {
    width: 100%;
  }
  .form-grid {
    grid-template-columns: 1fr;
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
.inline-help {
  margin: 12px 0 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}
.inline-help summary {
  color: var(--el-color-primary);
  cursor: pointer;
}
.inline-help p {
  margin: 8px 0 0;
  max-width: 80ch;
}
.coverage {
  margin: 20px 0 28px;
  padding: 16px 0 20px;
  border-top: 1px solid var(--el-border-color-light);
  border-bottom: 1px solid var(--el-border-color-light);
}
.coverage-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.coverage-heading h3 {
  margin: 0;
}
.coverage-heading .inline-help {
  margin: 0;
}
.coverage-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.coverage-stats > div {
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
}
.coverage-stats span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.coverage-stats strong {
  display: block;
  margin-top: 5px;
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}
.solution-version-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.solution-version-header > div {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.solution-version-header h3 {
  margin: 0;
  overflow-wrap: anywhere;
}
.solution-downloads {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 14px 0 12px;
  padding: 8px 0;
  border-top: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.solution-downloads .el-button {
  padding: 4px;
}
.baseline-details {
  margin: 0 0 18px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}
.baseline-details summary {
  color: var(--el-color-primary);
  cursor: pointer;
}
.baseline-details p {
  margin: 6px 0 0;
  overflow-wrap: anywhere;
}
.solution-section {
  padding: 16px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}
.solution-section h4 {
  margin: 0 0 8px;
  font-size: 15px;
}
.solution-section .safe-content {
  margin: 0 0 8px;
  line-height: 1.7;
}
@media (max-width: 600px) {
  .coverage-stats {
    grid-template-columns: 1fr;
  }
}

.task-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.task-heading > strong {
  min-width: 0;
  overflow-wrap: anywhere;
}
.task-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.task-actions .el-button + .el-button {
  margin-left: 0;
}
.presales-workbench :deep(.el-button:focus-visible) {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 3px;
}
.presentation-result {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 14px 0;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
}
.presentation-result .task-actions {
  width: 100%;
  justify-content: flex-start;
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
@media (max-width: 768px) {
  .task-actions {
    justify-content: flex-start;
    width: 100%;
  }
}

.table-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.table-actions .el-button {
  margin: 0;
  flex-shrink: 0;
}
</style>
