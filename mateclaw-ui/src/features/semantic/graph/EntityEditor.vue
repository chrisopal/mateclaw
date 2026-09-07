<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Definition } from '../api/types'
import { graphApi } from '../api/graphApi'
import { useSemanticScope } from '../shared/useSemanticScope'
const props = defineProps<{ graphId: string; definition: Definition }>()
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n(), { begin } = useSemanticScope()
const typeKey = ref(''), displayName = ref(''), busy = ref(false), error = ref('')
async function save() {
  const run = begin(); busy.value = true; error.value = ''
  try { await graphApi.createEntity(run.id, props.graphId, { typeKey: typeKey.value, displayName: displayName.value.trim() }, run.signal); if (run.current()) { displayName.value = ''; emit('saved') } }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
</script>
<template><el-form inline @submit.prevent="save"><el-form-item :label="t('semantic.w.type')"><el-select v-model="typeKey" style="width: 180px"><el-option v-for="type in definition.types" :key="type.key" :value="type.key" :label="type.label" /></el-select></el-form-item><el-form-item :label="t('semantic.w.name')"><el-input v-model="displayName" /></el-form-item><el-button native-type="submit" :loading="busy" :disabled="!typeKey || !displayName.trim()">{{ t('semantic.w.addEntity') }}</el-button><el-alert v-if="error" :title="error" type="error" :closable="false" /></el-form></template>
