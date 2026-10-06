<template>
  <section class="section-heading">
    <h2>{{ t('presales.requirements') }}</h2>
    <div>
      <el-button
        :disabled="!canGenerate"
        @click="emit('generate')"
        >{{ t('presales.ask_employee_to_assess_requirements') }}</el-button
      ><el-button
        type="primary"
        :disabled="!canApprove"
        @click="emit('confirm')"
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
    <p>{{ t('presales.unanswered_explanation') }}</p>
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
        stateLabel(row.originKind, 'origin')
      }}</template></el-table-column
    ><el-table-column :label="t('presales.priority')"
      ><template #default="{ row }">{{
        stateLabel(row.priority, 'requirementPriority')
      }}</template></el-table-column
    ><el-table-column :label="t('presales.scope')"
      ><template #default="{ row }">{{
        stateLabel(row.scope, 'requirementScope')
      }}</template></el-table-column
    ><el-table-column
      :label="t('presales.customer_confirmation')"
      min-width="160"
      ><template #default="{ row }">{{
        stateLabel(row.customerConfirmationStatus, 'customerConfirmation')
      }}</template></el-table-column
    ><el-table-column
      :label="t('presales.actions')"
      min-width="224"
      ><template #default="{ row }"
        ><div class="table-actions">
          <el-button
            type="primary"
            size="small"
            @click="emit('evidence', row)"
            >{{ t('presales.evidence') }}</el-button
          ><el-button
            type="primary"
            size="small"
            :disabled="!canWrite"
            @click="emit('reviseRequirement', row)"
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
      @click="emit('continue')"
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
          project.clarifications.filter((c) => isDomainStatus('clarification', c.status, 'OPEN'))
            .length
        }}</el-radio-button
      ><el-radio-button value="ANSWERED"
        >{{ t('presales.answered') }}
        {{
          project.clarifications.filter((c) =>
            isDomainStatus('clarification', c.status, 'ANSWERED'),
          ).length
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
      ><template #default="{ row }">{{ ownerName(row.ownerId) }}</template></el-table-column
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
        stateLabel(row.status, 'clarification')
      }}</template></el-table-column
    ><el-table-column
      :label="t('presales.actions')"
      min-width="224"
      ><template #default="{ row }"
        ><div class="table-actions">
          <el-button
            type="primary"
            size="small"
            @click="emit('evidence', row)"
            >{{ t('presales.view_record') }}</el-button
          ><el-button
            type="primary"
            size="small"
            :disabled="!canWrite"
            @click="emit('reviseClarification', row)"
            >{{
              isDomainStatus('clarification', row.status, 'ANSWERED')
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
</template>
<script setup lang="ts">
import { isDomainStatus, type StateLabel } from '../shared/status'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
import type {
  PresalesProject,
  PresalesRequirement,
  PresalesClarification,
} from '../api/presalesApi'
const { t } = useI18n({ messages: presalesMessages })
const props = defineProps<{
  project: PresalesProject
  canWrite: boolean
  canGenerate: boolean
  canApprove: boolean
  stateLabel: StateLabel
  ownerName: (id?: string) => string
}>()
const clarificationFilter = defineModel<string>('clarificationFilter', { required: true })
const filteredClarifications = computed(() =>
  props.project.clarifications.filter(
    (c) =>
      clarificationFilter.value === 'ALL' ||
      (clarificationFilter.value === 'OPEN'
        ? isDomainStatus('clarification', c.status, 'OPEN')
        : isDomainStatus('clarification', c.status, 'ANSWERED')),
  ),
)
const emit = defineEmits<{
  generate: []
  confirm: []
  continue: []
  reviseRequirement: [item: PresalesRequirement]
  reviseClarification: [item: PresalesClarification]
  evidence: [item: PresalesRequirement | PresalesClarification]
}>()
</script>
<style scoped src="../shared/workbenchSections.css"></style>
