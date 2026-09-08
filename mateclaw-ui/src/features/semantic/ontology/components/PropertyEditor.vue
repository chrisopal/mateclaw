<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Property, EntityType, ValueType } from '../../api/types'
const model = defineModel<Property>({ required: true })
defineProps<{ types: EntityType[]; readonly?: boolean }>()
const { t } = useI18n()
const aliases = computed({
  get: () => model.value.aliases ?? [],
  set: (value: string[]) => { model.value.aliases = value.filter((alias) => alias.trim()) },
})
const allowedValuesText = ref((model.value.constraints?.allowedValues ?? []).join('\n'))
function normalizeConstraints() {
  const source = model.value.constraints
  if (model.value.valueType !== 'DECIMAL') model.value.fixedUnit = null
  if (!source) return
  const constraints = model.value.valueType === 'TEXT'
    ? { allowedValues: source.allowedValues }
    : model.value.valueType === 'DECIMAL'
      ? { minimum: source.minimum, maximum: source.maximum }
      : {}
  if (Object.values(constraints).some((value) => value !== undefined && value !== '')) model.value.constraints = constraints
  else delete model.value.constraints
}
watch(() => [model.value, model.value.valueType] as const, ([property, value], [previous]) => {
  if (property === previous) normalizeConstraints()
  allowedValuesText.value = value === 'TEXT' ? (property.constraints?.allowedValues ?? []).join('\n') : ''
})
watch(() => model.value.constraints?.allowedValues?.join('\n'), (value) => {
  if (value !== undefined) allowedValuesText.value = value
})
function setAllowedValues(value: string) {
  allowedValuesText.value = value
}
function commitAllowedValues() {
  const allowedValues = allowedValuesText.value.split(/\r?\n/).filter((item) => item.trim().length > 0)
  model.value.constraints = allowedValues.length ? { ...(model.value.constraints ?? {}), allowedValues } : undefined
}
function setValueType(value: ValueType) {
  model.value.valueType = value
  normalizeConstraints()
}
function setDecimalBound(field: 'minimum' | 'maximum', value: string) {
  model.value.constraints = { ...(model.value.constraints ?? {}), [field]: value || undefined }
  normalizeConstraints()
}
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
      ><el-select :model-value="model.valueType" data-semantic-field="valueType" @update:model-value="setValueType"
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
    <el-form-item :label="t('semantic.aliases')" :help="t('semantic.aliasesHelp')"
      ><el-select v-model="aliases" multiple filterable allow-create default-first-option collapse-tags :placeholder="t('semantic.aliasPlaceholder')" data-semantic-field="aliases"
        ><el-option v-for="alias in aliases" :key="alias" :value="alias" :label="alias" /></el-select
    ></el-form-item>
    <el-checkbox v-model="model.deprecated" data-semantic-field="deprecated">{{ t('semantic.deprecated') }}</el-checkbox>
    <template v-if="model.valueType === 'TEXT'">
      <el-form-item :label="t('semantic.allowedValues')" :help="t('semantic.allowedValuesHelp')"
        ><el-input :model-value="allowedValuesText" data-semantic-field="constraints.allowedValues" type="textarea" :rows="4" :placeholder="t('semantic.allowedValuesPlaceholder')" @update:model-value="setAllowedValues" @blur="commitAllowedValues" /></el-form-item>
    </template>
    <template v-else-if="model.valueType === 'DECIMAL'">
      <div class="semantic-constraint-grid"
        ><el-form-item :label="t('semantic.minimum')" :help="t('semantic.decimalHelp')"><el-input :model-value="model.constraints?.minimum ?? ''" data-semantic-field="constraints.minimum" @update:model-value="setDecimalBound('minimum', $event)" /></el-form-item
        ><el-form-item :label="t('semantic.maximum')" :help="t('semantic.decimalHelp')"><el-input :model-value="model.constraints?.maximum ?? ''" data-semantic-field="constraints.maximum" @update:model-value="setDecimalBound('maximum', $event)" /></el-form-item
      ></div>
    </template>
  </el-form>
</template>
