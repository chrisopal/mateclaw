<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Diff } from '../../api/types'
const open = defineModel<boolean>({ required: true })
defineProps<{ busy: boolean; diff: Diff | null; error?: string }>()
defineEmits<{ publish: [note: string] }>()
const { t } = useI18n(),
  note = ref('')
</script>
<template>
  <el-dialog
    v-model="open"
    :title="t('semantic.publish')"
    width="min(720px,95vw)"
    :close-on-click-modal="false"
    ><p>{{ t('semantic.publishHelp') }}</p>
    <el-alert v-if="error" type="error" :title="error" :closable="false" /><el-table
      v-if="diff"
      :data="diff.changes"
      :empty-text="t('semantic.noChanges')"
      ><el-table-column :label="t('semantic.change')" width="120"
        ><template #default="{ row }">{{ t('semantic.changes.' + row.kind) }}</template></el-table-column
      ><el-table-column :label="t('semantic.category')" width="120"
        ><template #default="{ row }">{{ t('semantic.' + row.category) }}</template></el-table-column
      ><el-table-column prop="key" :label="t('semantic.key')" /></el-table
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
