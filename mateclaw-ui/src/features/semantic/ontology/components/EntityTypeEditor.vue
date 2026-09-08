<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { EntityType } from '../../api/types'
const model = defineModel<EntityType>({ required: true })
defineProps<{ readonly?: boolean }>()
const { t } = useI18n()
const aliases = computed({
  get: () => model.value.aliases ?? [],
  set: (value: string[]) => { model.value.aliases = value.filter((alias) => alias.trim()) },
})
</script>
<template>
  <el-form label-position="top" :disabled="readonly"
    ><el-form-item :label="t('semantic.key')" required
      ><el-input v-model="model.key" data-semantic-field="key" maxlength="64" /></el-form-item
    ><el-form-item :label="t('semantic.label')" required
      ><el-input v-model="model.label" data-semantic-field="label" maxlength="128" /></el-form-item
    ><el-form-item :label="t('semantic.description')"
      ><el-input
        v-model="model.description"
        data-semantic-field="description"
        type="textarea"
        maxlength="1000"
        :rows="3" /></el-form-item
    ><el-form-item :label="t('semantic.aliases')" :help="t('semantic.aliasesHelp')"
      ><el-select v-model="aliases" multiple filterable allow-create default-first-option collapse-tags :placeholder="t('semantic.aliasPlaceholder')" data-semantic-field="aliases"
        ><el-option v-for="alias in aliases" :key="alias" :value="alias" :label="alias" /></el-select
    ></el-form-item
    ><el-checkbox v-model="model.deprecated" data-semantic-field="deprecated">{{ t('semantic.deprecated') }}</el-checkbox
  ></el-form>
</template>
