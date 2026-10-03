<template>
  <section class="section-heading">
    <h2>{{ t('presales.independent_review') }}</h2>
    <div>
      <el-button
        :disabled="!canGenerate || !project.solutions.length"
        @click="emit('generate')"
        >{{ t('presales.ask_employee_to_review') }}</el-button
      ><el-button
        :disabled="!canWrite"
        @click="emit('review')"
        >{{ t('presales.record_independent_review') }}</el-button
      ><el-button
        :disabled="!canWrite"
        @click="emit('release')"
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
    <el-button @click="emit('handoff')">{{ t('presales.export_internal_handoff') }}</el-button>
  </section>
  <el-table :data="project.releases"
    ><el-table-column :label="t('presales.version')"
      ><template #default="{ row }">{{
        versionLabel('releases', row.id)
      }}</template></el-table-column
    ><el-table-column :label="t('presales.status')"
      ><template #default="{ row }">{{ stateLabel(row.status) }}</template></el-table-column
    ><el-table-column
      :label="t('presales.files')"
      width="90"
      ><template #default="{ row }">{{ row.files?.length || 0 }}</template></el-table-column
    ><el-table-column
      :label="t('presales.actions')"
      min-width="224"
      ><template #default="{ row }"
        ><div class="table-actions">
          <el-button
            type="primary"
            size="small"
            @click="emit('evidence', row)"
            >{{ t('presales.manifest') }}</el-button
          ><el-button
            v-for="file in row.files || []"
            :key="file.filename"
            type="primary"
            size="small"
            :disabled="row.status !== 'PUBLISHED' && !canApprove"
            @click="
              emit(
                'download',
                row.id,
                file.filename,
                row.status === 'PUBLISHED' ? 'files' : 'preview',
              )
            "
            >{{
              row.status === 'PUBLISHED' ? t('presales.download') : t('presales.unapproved_preview')
            }}
            {{ file.filename }}</el-button
          ><el-button
            type="primary"
            size="small"
            :disabled="!canApprove || row.status !== 'PENDING'"
            @click="emit('approve', row)"
            >{{ t('presales.approve_release') }}</el-button
          ><el-button
            type="primary"
            size="small"
            :disabled="!canApprove || row.status !== 'APPROVED'"
            @click="emit('publish', row.id)"
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
</template>
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
import type { PresalesProject, PresalesRecord } from '../api/presalesApi'
const { t } = useI18n({ messages: presalesMessages })
defineProps<{
  project: PresalesProject
  canWrite: boolean
  canGenerate: boolean
  canApprove: boolean
  stateLabel: (state: string | undefined) => string
  versionLabel: (kind: 'solutions' | 'releases', id: string) => string
  printable: (value: unknown) => string
}>()
const emit = defineEmits<{
  generate: []
  review: []
  release: []
  handoff: []
  evidence: [record: PresalesRecord]
  approve: [release: PresalesRecord]
  publish: [releaseId: string]
  download: [id: string, filename: string, kind: 'files' | 'preview']
}>()
</script>
<style scoped src="../shared/workbenchSections.css"></style>
