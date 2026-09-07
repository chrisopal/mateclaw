<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { statementApi } from '../api/statementApi'
import { useSemanticScope } from '../shared/useSemanticScope'
import type { Conflict } from '../api/workbenchTypes'
const props = defineProps<{ graphId: string; conflicts: Conflict[] }>()
const emit = defineEmits<{ saved: []; select: [id: string] }>()
const { t } = useI18n(), { begin } = useSemanticScope()
const reasons = ref<Record<string, string>>({}), error = ref(''), busy = ref(false)
async function resolve(conflict: Conflict, winner: string) {
  const run = begin(); busy.value = true; error.value = ''
  try { await statementApi.resolve(run.id, props.graphId, conflict, winner, reasons.value[conflict.id] ?? '', run.signal); if (run.current()) emit('saved') }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
</script>
<template><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-empty v-if="!conflicts.length" :description="t('semantic.w.empty')" /><el-card v-for="conflict in conflicts" :key="conflict.id"><p>{{ conflict.kind }} · {{ conflict.status }}</p><div v-for="member in [conflict.left, conflict.right]" :key="member.statementId"><el-button text @click="emit('select', member.statementId)">{{ member.statementId }} · r{{ member.revision }}</el-button><el-button v-if="conflict.status === 'OPEN'" :disabled="busy || !reasons[conflict.id]?.trim()" @click="resolve(conflict, member.statementId)">{{ t('semantic.w.keepWinner') }}</el-button></div><el-input v-if="conflict.status === 'OPEN'" v-model="reasons[conflict.id]" :placeholder="t('semantic.w.reason')" /><p v-else>{{ conflict.resolution }}</p></el-card></template>
