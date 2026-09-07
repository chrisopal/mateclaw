<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { sourceApi } from '../api/sourceApi'
import { useSemanticScope } from '../shared/useSemanticScope'
import type { ImportJob } from '../api/workbenchTypes'
const props = defineProps<{ graphId: string; knowledgeBaseId: string }>()
const emit = defineEmits<{ saved: [] }>()
const { t } = useI18n(), { begin } = useSemanticScope()
const materials = ref<{ id: string; title: string }[]>([]), selected = ref(''), job = ref<ImportJob | null>(null), error = ref(''), busy = ref(false)
async function run(action: 'load' | 'capture' | 'refresh' | 'retry') {
  const request = begin(); busy.value = true; error.value = ''
  try {
    if (action === 'load') { const result = await sourceApi.materials(request.id, props.knowledgeBaseId, request.signal); if (request.current()) materials.value = result }
    else {
      const result = action === 'capture' ? await sourceApi.capture(request.id, props.graphId, selected.value, request.signal) : action === 'retry' ? await sourceApi.retry(request.id, props.graphId, job.value!.id, request.signal) : await sourceApi.job(request.id, props.graphId, job.value!.id, request.signal)
      if (request.current()) { job.value = result; if (result.snapshotId) emit('saved') }
    }
  } catch (e) { if (request.current()) error.value = (e as Error).message }
  finally { if (request.current()) busy.value = false }
}
onMounted(() => run('load'))
</script>
<template><section><p>{{ t('semantic.w.captureHelp') }}</p><el-select v-model="selected" filterable style="width: min(500px, 100%)" :placeholder="t('semantic.w.material')"><el-option v-for="item in materials" :key="item.id" :value="String(item.id)" :label="item.title" /></el-select> <el-button :disabled="!selected || busy" @click="run('capture')">{{ t('semantic.w.capture') }}</el-button><div v-if="job"><p>{{ job.id }} · {{ job.status }} · {{ job.errorMessage }}</p><el-button :disabled="busy" @click="run('refresh')">{{ t('semantic.w.refresh') }}</el-button><el-button v-if="job.status === 'FAILED'" :disabled="busy" @click="run('retry')">{{ t('semantic.w.retry') }}</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false" /></section></template>
