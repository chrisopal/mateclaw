<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { EntityType } from '../../api/types'
const model = defineModel<EntityType>({ required: true })
defineProps<{ readonly?: boolean }>()
const { t } = useI18n()
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
  ></el-form>
</template>
