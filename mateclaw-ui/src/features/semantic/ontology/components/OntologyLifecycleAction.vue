<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { semanticRequest } from '../../api/ontologyApi'
import type { Ontology } from '../../api/types'
import { useSemanticScope } from '../../shared/useSemanticScope'
const props = defineProps<{ ontology: Ontology }>()
const emit = defineEmits<{ updated: [Ontology] }>()
const { locale } = useI18n(), { workspace, begin, cancel } = useSemanticScope()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const open = ref(false), busy = ref(false), error = ref('')
const label = computed(() => props.ontology.archived ? tr('恢复模型', 'Restore model') : tr('归档模型', 'Archive model'))
watch(() => [workspace.currentWorkspaceId, props.ontology.id], () => { cancel(); open.value = false; error.value = ''; busy.value = false })
async function submit() {
 if (busy.value || !workspace.can('publish:ontology')) return
 const run = begin(); busy.value = true; error.value = ''
 try {
  const result = await semanticRequest<Ontology>(run.id, { url: `/semantic/ontologies/${encodeURIComponent(props.ontology.id)}/${props.ontology.archived ? 'restore' : 'archive'}`, method: 'POST', data: { expectedUpdatedAt: props.ontology.updatedAt } }, run.signal)
  if (run.current()) { open.value = false; emit('updated', result) }
 } catch(e) { if(run.current()) error.value = (e as Error).message }
 finally { if(run.current()) busy.value = false }
}
</script>
<template>
 <el-button v-if="workspace.can('publish:ontology')" :disabled="busy" @click="open=true">{{label}}</el-button>
 <el-dialog v-model="open" :title="label" width="min(520px,94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
  <p><strong>{{ontology.name}}</strong></p>
  <p v-if="ontology.archived">{{tr('恢复后可继续维护模型。各版本仍保持停止新应用，需要时再逐个开启。','Restoring allows model maintenance. Enable new applications for each version separately.')}}</p>
  <p v-else>{{tr('归档后模型只读，所有版本停止新应用。已有知识库继续使用固定版本；历史与证据保留。','Archiving makes the model read-only and stops new applications. Existing knowledge bases retain their fixed versions, history and evidence.')}}</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <template #footer><el-button :disabled="busy" @click="open=false">{{tr('取消','Cancel')}}</el-button><el-button type="primary" :loading="busy" @click="submit">{{label}}</el-button></template>
 </el-dialog>
</template>
<style scoped>p{line-height:1.7;overflow-wrap:anywhere}</style>
