<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Relation, EntityType } from '../../api/types'
const model = defineModel<Relation>({ required: true })
defineProps<{ types: EntityType[]; readonly?: boolean }>()
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
      ><el-input v-model="model.label" data-semantic-field="label" maxlength="128"
    /></el-form-item>
    <el-form-item
      v-for="field in ['sourceTypeKey', 'targetTypeKey'] as const"
      :key="field"
      :label="t('semantic.' + field)"
      required
      ><el-select v-model="model[field]" :data-semantic-field="field" filterable
        ><el-option
          v-for="type in types"
          :key="type.key"
          :value="type.key"
          :label="type.label + ' (' + type.key + ')'" /></el-select
    ></el-form-item>
    <el-form-item :label="t('semantic.multiplicity')"
      ><el-select v-model="model.multiplicity"
        ><el-option
          v-for="value in ['SINGLE', 'MULTI']"
          :key="value"
          :value="value"
          :label="t('semantic.values.' + value)" /></el-select
    ></el-form-item>
    <el-form-item :label="t('semantic.description')"
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
