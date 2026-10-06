<template>
  <template v-if="kind === 'solution'">
    <el-form-item
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
            :label="stateLabel(v, 'response')" /></el-select></el-form-item
      ><el-form-item :label="t('presales.reason_remaining_gaps_or_conditions')"
        ><el-input
          v-model="response.reason"
          type="textarea"
      /></el-form-item>
    </section>
  </template>
  <template v-else-if="kind === 'review'">
    <details class="inline-help">
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
              :label="stateLabel(v, 'reviewSeverity')" /></el-select></el-form-item
        ><el-form-item :label="t('presales.disposition')"
          ><el-select v-model="issue.status"
            ><el-option
              v-for="v in ['OPEN', 'RESOLVED', 'ACCEPTED']"
              :key="v"
              :value="v"
              :label="stateLabel(v, 'reviewIssue')" /></el-select
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
    >
  </template>
  <template v-else-if="kind === 'release'">
    <el-form-item
      :label="t('presales.solution_version')"
      required
      ><el-select v-model="form.solutionId"
        ><el-option
          v-for="solution in project?.solutions"
          :key="solution.id"
          :value="solution.id"
          :label="`${solution.title} · ${versionLabel('solutions', solution.id)}`" /></el-select></el-form-item
    ><el-form-item :label="t('presales.intended_use')"
      ><el-input v-model="form.purpose"
    /></el-form-item>
  </template>
</template>
<script setup lang="ts">
import type { StateLabel } from '../shared/status'
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
const { t } = useI18n({ messages: presalesMessages })
const form = defineModel<PresalesEditorForm>('form', { required: true })
import type { PresalesEditorForm, PresalesProject } from '../api/presalesApi'
defineProps<{
  kind: 'solution' | 'review' | 'release'
  project: PresalesProject | undefined
  editableRequirements: PresalesProject['requirements']
  stateLabel: StateLabel
  baselineLabel: (id: string) => string
  versionLabel: (kind: 'solutions' | 'releases', id: string) => string
}>()
</script>
<style scoped src="../shared/workbenchSections.css"></style>
<style scoped src="../shared/editorFields.css"></style>
