<template>
  <section class="section-heading">
    <h2>
      {{ t('presales.requirement_capability_matching') }}
    </h2>
    <div>
      <el-button
        :disabled="!canGenerate"
        @click="emit('generate')"
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
      ><template #default="{ row }">{{ stateLabel(row.status) }}</template></el-table-column
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
            @click="emit('evidence', row)"
            >{{ t('presales.evidence') }}</el-button
          >
        </div></template
      ></el-table-column
    ></el-table
  >
</template>
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
import type { PresalesProject, PresalesFitGap } from '../api/presalesApi'
const { t } = useI18n({ messages: presalesMessages })
defineProps<{
  project: PresalesProject
  canGenerate: boolean
  stateLabel: (state: string | undefined) => string
}>()
const emit = defineEmits<{
  generate: []
  evidence: [item: PresalesFitGap]
}>()
</script>
<style scoped src="../shared/workbenchSections.css"></style>
