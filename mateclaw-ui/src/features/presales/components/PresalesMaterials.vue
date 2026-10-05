<template>
  <section class="section-heading">
    <h2>{{ t('presales.authorized_sources') }}</h2>
    <el-button
      :disabled="!canWrite"
      @click="emit('bind')"
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
      ><template #default="{ row }">{{ stateLabel(row.status) }}</template></el-table-column
    ><el-table-column
      :label="t('presales.actions')"
      min-width="224"
      ><template #default="{ row }"
        ><div class="table-actions">
          <el-button
            type="primary"
            size="small"
            @click="emit('evidence', row)"
            >{{ t('presales.view_source') }}</el-button
          ><el-button
            plain
            size="small"
            type="danger"
            :disabled="!canWrite || row.status === 'WITHDRAWN'"
            @click="emit('unbind', row.id)"
            >{{ t('presales.withdraw') }}</el-button
          >
        </div></template
      ></el-table-column
    ></el-table
  >
</template>
<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { presalesMessages } from '../shared/messages'
import type { PresalesProject, PresalesMaterial } from '../api/presalesApi'
const { t } = useI18n({ messages: presalesMessages })
defineProps<{
  project: PresalesProject
  canWrite: boolean
  stateLabel: (state: string | undefined) => string
}>()
const emit = defineEmits<{
  bind: []
  unbind: [id: string]
  evidence: [item: PresalesMaterial]
}>()
</script>
<style scoped src="../shared/workbenchSections.css"></style>
