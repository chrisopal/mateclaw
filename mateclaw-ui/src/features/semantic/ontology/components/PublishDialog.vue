<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Diff } from '../../api/types'
import OntologyImpactPanel from './OntologyImpactPanel.vue'
const open = defineModel<boolean>({ required: true })
const props = defineProps<{ busy: boolean; diff: Diff | null; error?: string; ontologyId?: string; expectedDraftVersion?: number }>()
defineEmits<{ publish: [note: string] }>()
const { t } = useI18n(),
  note = ref('')
function changeClassLabel(value?: string) {
  const key = {
    ANNOTATION: 'annotation',
    ADDITIVE_OR_WIDENING: 'additiveOrWidening',
    POTENTIALLY_BREAKING: 'potentiallyBreaking',
    BREAKING: 'breaking',
  }[value ?? '']
  return key ? t('semantic.' + key) : value ?? ''
}
</script>
<template>
  <el-dialog
    v-model="open"
    :title="t('semantic.publish')"
    width="min(720px,95vw)"
    :close-on-click-modal="false"
    ><p>{{ t('semantic.publishHelp') }}</p>
    <el-alert v-if="diff?.definitionChangeClass" type="info" :title="t('semantic.definitionChangeClass') + ': ' + changeClassLabel(diff.definitionChangeClass)" :closable="false" />
    <OntologyImpactPanel
      v-if="props.ontologyId && typeof props.expectedDraftVersion === 'number'"
      :ontology-id="props.ontologyId"
      :expected-draft-version="props.expectedDraftVersion"
      :enabled="open"
    />
    <el-alert v-if="error" type="error" :title="error" :closable="false" /><el-table
      v-if="diff"
      :data="diff.changes"
      :empty-text="t('semantic.noChanges')"
      ><el-table-column :label="t('semantic.change')" width="120"
        ><template #default="{ row }">{{ row.kind ? t('semantic.changes.' + row.kind) : '—' }}</template></el-table-column
      ><el-table-column :label="t('semantic.category')" width="120"
        ><template #default="{ row }">{{ row.category ? t('semantic.' + row.category) : '—' }}</template></el-table-column
      ><el-table-column prop="key" :label="t('semantic.key')" /></el-table
    ><el-table v-if="diff?.termChanges?.length" :data="diff.termChanges" class="semantic-term-change-table">
      <el-table-column :label="t('semantic.category')" width="130"><template #default="{ row }">{{ row.kind ? t('semantic.termKinds.' + row.kind, row.kind) : '—' }}</template></el-table-column>
      <el-table-column prop="key" :label="t('semantic.key')" min-width="140" />
      <el-table-column :label="t('semantic.definitionChangeClass')" min-width="180"><template #default="{ row }">{{ changeClassLabel(row.definitionChangeClass) }}</template></el-table-column>
      <el-table-column :label="t('semantic.reasons')" min-width="220"><template #default="{ row }">{{ row.reasons.map((reason: string) => t('semantic.changeReasons.' + reason, reason)).join('；') }}</template></el-table-column>
    </el-table
    ><el-form label-position="top"
      ><el-form-item :label="t('semantic.publicationNote')" required
        ><el-input
          v-model="note"
          type="textarea"
          :rows="3"
          :disabled="busy"
          maxlength="1000" /></el-form-item></el-form
    ><template #footer
      ><el-button :disabled="busy" @click="open = false">{{ t('semantic.cancel') }}</el-button
      ><el-button type="primary" :disabled="!note.trim()" :loading="busy" @click="$emit('publish', note)">{{
        t('semantic.confirmPublish')
      }}</el-button></template
    ></el-dialog
  >
</template>
