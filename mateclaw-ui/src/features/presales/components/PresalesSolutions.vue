<template>
  <section class="section-heading">
    <h2>{{ t('presales.versioned_solutions') }}</h2>
    <div>
      <el-button
        :disabled="!canGenerate"
        @click="emit('generate')"
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
          stateLabel(row.scope, 'requirementScope')
        }}</template></el-table-column
      ><el-table-column :label="t('presales.fulfillment_approach')"
        ><template #default="{ row }">{{
          stateLabel(row.fit, 'fitGap')
        }}</template></el-table-column
      ><el-table-column :label="t('presales.response')"
        ><template #default="{ row }">{{
          stateLabel(row.response, 'response')
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
        <el-tag>{{ stateLabel(solution.status || 'DRAFT', 'solution') }}</el-tag>
      </div>
      <el-button
        type="primary"
        size="small"
        :disabled="!canWrite"
        @click="emit('revise', solution)"
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
        @click="emit('download', solution.id, filename)"
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
        @click="emit('evidence', section)"
        >{{ t('presales.section_evidence') }}</el-button
      >
    </section>
  </article>
</template>
<script setup lang="ts">
import type { StateLabel } from '../shared/status'
import { computed } from 'vue'
import { coverageLabel } from '../shared/state'
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
import type { PresalesProject, PresalesRecord } from '../api/presalesApi'
const { t } = useI18n({ messages: presalesMessages })
const props = defineProps<{
  project: PresalesProject
  canWrite: boolean
  canGenerate: boolean
  stateLabel: StateLabel
  versionLabel: (kind: 'solutions' | 'releases', id: string) => string
}>()
const emit = defineEmits<{
  generate: []
  revise: [solution: PresalesRecord]
  download: [id: string, filename: string]
  evidence: [record: PresalesRecord]
}>()
const compareId = defineModel<string>('compareId', { required: true })
const compareTargetId = defineModel<string>('compareTargetId', { required: true })
const responseRows = computed(() =>
  (props.project?.requirements || []).map((requirement) => {
    const sections = (props.project?.solutions.at(-1)?.sections || []).filter(
      (section: PresalesRecord) => section.requirementRefs?.includes(requirement.id),
    )
    const solution = props.project?.solutions.at(-1)
    const baseline = props.project?.baselines.find((item) => item.id === solution?.baselineId)
    const ref = baseline?.references?.find((item) => item.requirementId === requirement.id)
    return {
      title: requirement.title,
      scope: ref?.scope || requirement.scope,
      response:
        solution?.coverage?.responses?.find(
          (item: PresalesRecord) => item.requirementId === requirement.id,
        )?.status || 'UNHANDLED',
      fit:
        props.project?.fitGaps.filter((fit) => fit.requirementId === requirement.id).at(-1)
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
    props.project?.solutions.find((solution) => solution.id === compareId.value)?.sections || []
  const after =
    (
      props.project?.solutions.find((solution) => solution.id === compareTargetId.value) ||
      props.project?.solutions.at(-1)
    )?.sections || []
  return Array.from(
    new Set([...before, ...after].map((section: PresalesRecord) => String(section.title))),
  ).map((title) => ({
    title,
    before: before.find((section: PresalesRecord) => section.title === title)?.text || '—',
    after: after.find((section: PresalesRecord) => section.title === title)?.text || '—',
  }))
})
</script>
<style scoped src="../shared/workbenchSections.css"></style>
<style scoped src="../shared/solutionSections.css"></style>
