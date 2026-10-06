<template>
  <section class="section-heading">
    <h2>{{ t('presales.context_and_open_items') }}</h2>
    <el-button
      :disabled="!canGenerate"
      @click="emit('generate')"
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
          {{ project.baselines.at(-1)?.id ? t('presales.confirmed') : t('presales.not_confirmed') }}
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
        {{ stateLabel(task.status, 'task') }}</strong
      >
      <div class="task-actions">
        <el-button
          v-if="isDomainStatus('task', task.status, 'RUNNING')"
          :disabled="!canWrite"
          @click="emit('cancel', task)"
          >{{ t('presales.discard_this_run_result') }}</el-button
        ><el-button
          v-if="task.conversationId && task.agentId"
          type="primary"
          size="small"
          @click="emit('execution', task)"
          >{{ t('presales.view_employee_execution') }}</el-button
        ><el-button
          type="primary"
          size="small"
          @click="emit('evidence', task.contextSnapshot || task)"
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
      }}<span v-if="task.conversationId"> · {{ t('presales.execution') }} {{ task.runId }}</span>
    </p>
    <p
      v-if="task.queueState === 'QUEUED' && isDomainStatus('task', task.status, 'RUNNING')"
      class="muted"
    >
      {{ t('presales.accepted_and_waiting_for_the_employee') }}
    </p>
    <p v-if="task.error">{{ employeeIssue(task.error) }}</p>
    <div
      v-for="(item, index) in task.result?.items || []"
      :key="index"
    >
      <h4>{{ item.title }} · {{ stateLabel(item.originKind, 'origin') }}</h4>
      <pre class="safe-content">{{ item.text }}</pre>
      <el-button
        v-if="['S1', 'S5', 'S6'].includes(task.skill!)"
        :disabled="!canWrite"
        @click="emit('adopt', task.skill, item)"
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
          @click="emit('presentation', task.result.solution.presentation, slide.filename)"
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
      ><template #default="{ row }">{{ stateLabel(row.status, 'task') }}</template></el-table-column
    ><el-table-column
      prop="error"
      :label="t('presales.failure_reason')"
  /></el-table>
</template>
<script setup lang="ts">
import { isDomainStatus, type StateLabel } from '../shared/status'
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
import type {
  PresalesProject,
  PresalesTask,
  PresalesRecord,
  PresalesPresentation,
} from '../api/presalesApi'
const { t } = useI18n({ messages: presalesMessages })
defineProps<{
  project: PresalesProject
  isNarrow: boolean
  canWrite: boolean
  canGenerate: boolean
  skillNames: Record<string, string>
  stateLabel: StateLabel
  employeeIssue: (code: string) => string
  printable: (value: unknown) => string
}>()
const emit = defineEmits<{
  generate: []
  cancel: [task: PresalesTask]
  execution: [task: PresalesTask]
  evidence: [value: PresalesRecord]
  adopt: [skill: string | undefined, item: PresalesRecord]
  presentation: [presentation: PresalesPresentation, filename: string]
}>()
</script>
<style scoped src="../shared/workbenchSections.css"></style>
<style scoped src="../shared/solutionSections.css"></style>
<style scoped src="../shared/taskActivity.css"></style>
