<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Property, EntityType } from '../../api/types'
const model = defineModel<Property>({ required: true })
defineProps<{ types: EntityType[]; readonly?: boolean }>()
const { t } = useI18n()
</script>
<template>
  <el-form label-position="top" :disabled="readonly">
    <el-form-item :label="t('semantic.key')" required
      ><el-input v-model="model.key" data-semantic-field="key" maxlength="64" /></el-form-item
    ><el-form-item :label="t('semantic.label')" required
      ><el-input v-model="model.label" data-semantic-field="label" maxlength="128"
    /></el-form-item>
    <el-form-item :label="t('semantic.ownerTypeKey')" required
      ><el-select v-model="model.ownerTypeKey" data-semantic-field="ownerTypeKey" filterable
        ><el-option
          v-for="type in types"
          :key="type.key"
          :value="type.key"
          :label="type.label + ' (' + type.key + ')'" /></el-select
    ></el-form-item>
    <el-form-item :label="t('semantic.valueType')"
      ><el-select v-model="model.valueType" data-semantic-field="valueType"
        ><el-option
          v-for="value in ['TEXT', 'DECIMAL', 'BOOLEAN', 'DATE', 'INSTANT']"
          :key="value"
          :value="value"
          :label="t('semantic.values.' + value)" /></el-select
    ></el-form-item>
    <el-form-item :label="t('semantic.multiplicity')"
      ><el-select v-model="model.multiplicity"
        ><el-option
          v-for="value in ['SINGLE', 'MULTI']"
          :key="value"
          :value="value"
          :label="t('semantic.values.' + value)" /></el-select
    ></el-form-item>
    <el-form-item :label="t('semantic.fixedUnit')"
      ><el-input
        :model-value="model.fixedUnit ?? ''"
        data-semantic-field="fixedUnit"
        maxlength="32"
        @update:model-value="model.fixedUnit = $event || null"
    /></el-form-item>
    <el-form-item :label="t('semantic.description')"
      ><el-input
        v-model="model.description"
        data-semantic-field="description"
        type="textarea"
        maxlength="1000"
        :rows="3"
    /></el-form-item>
  </el-form>
</template>
