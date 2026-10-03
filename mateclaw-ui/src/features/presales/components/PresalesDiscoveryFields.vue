<template>
  <template v-if="kind === 'material'">
    <el-form-item
      :label="t('presales.knowledge_base_2')"
      required
      ><el-select
        v-model="form.kbId"
        filterable
        :loading="optionsLoading"
        @change="(value) => emit('source', value)"
        ><el-option
          v-for="source in sourceOptions"
          :key="source.kbId"
          :value="source.kbId"
          :label="source.name ?? undefined" /></el-select
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
      @click="emit('wiki')"
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
          :label="stateLabel(role)" /></el-select
    ></el-form-item>
  </template>
  <template v-else-if="kind === 'requirement'">
    <el-form-item
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
        @change="(value) => emit('statement', value)"
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
      ><el-form-item label="Statement ID"><el-input v-model="form.statementId" /></el-form-item
      ><el-form-item :label="t('presales.statement_revision')"
        ><el-input v-model="form.statementRevision"
      /></el-form-item>
    </details>
  </template>
  <template v-else-if="kind === 'clarification'">
    <el-alert
      type="info"
      :closable="false"
      :title="
        form.proposedByTaskId ? t('presales.context_message_19') : t('presales.context_message_20')
      "
    />
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
          :label="stateLabel(v)" /></el-select
    ></el-form-item>
  </template>
  <template v-else-if="kind === 'baseline'">
    <el-alert
      type="warning"
      :closable="false"
      :title="t('presales.context_message_23')"
    /><el-form-item
      :label="t('presales.decision_reason_conditions_and_owner')"
      required
      ><el-input
        v-model="form.reason"
        type="textarea"
        :rows="5"
    /></el-form-item>
  </template>
  <template v-else-if="kind === 'fitgap'">
    <el-form-item
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
    ><el-form-item :label="t('presales.graph_id')"><el-input v-model="form.graphId" /></el-form-item
    ><el-form-item :label="t('presales.evidence_ids_comma_separated')"
      ><el-input v-model="form.evidenceText"
    /></el-form-item>
  </template>
  <template v-else-if="kind === 'context'">
    <el-form-item
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
    <p>{{ stateLabel(form.originKind) }}</p>
  </template>
</template>
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
const { t } = useI18n({ messages: presalesMessages })
const form = defineModel<PresalesEditorForm>('form', { required: true })
import type {
  PresalesEditorForm,
  PresalesProject,
  PresalesSource,
  PresalesTrustedStatement,
} from '../api/presalesApi'
defineProps<{
  kind: 'material' | 'requirement' | 'clarification' | 'baseline' | 'fitgap' | 'context'
  project: PresalesProject | undefined
  optionsLoading: boolean
  sourceOptions: PresalesSource[]
  statementOptions: PresalesTrustedStatement[]
  stateLabel: (state: string | undefined) => string
}>()
const emit = defineEmits<{ source: [kbId: string]; statement: [key: string]; wiki: [] }>()
</script>
<style scoped src="../shared/workbenchSections.css"></style>
<style scoped src="../shared/editorFields.css"></style>
