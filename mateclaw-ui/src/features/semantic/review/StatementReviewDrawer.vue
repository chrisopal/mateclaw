<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useStatementReview } from './useStatementReview'
import { graphQueryApi } from '../api/graphQueryApi'
import { useSemanticScope } from '../shared/useSemanticScope'
import type { Statement } from '../api/workbenchTypes'
const props = defineProps<{ graphId: string; statement: Statement | null; canReview: boolean }>()
const emit = defineEmits<{ close: []; saved: []; evidence: [id: string]; change: [statement: Statement] }>()
const { t } = useI18n(), { begin } = useSemanticScope(), { current, busy, error, confirm } = useStatementReview(() => props.graphId)
const reason = ref(''), history = ref<Statement[]>([]), historyError = ref('')
watch(() => props.statement, async value => {
  current.value = value; reason.value = ''; history.value = []; historyError.value = ''; error.value = null
  const run = begin()
  if (value && props.canReview) try { const result = await graphQueryApi.history(run.id, props.graphId, value.id, run.signal); if (run.current()) history.value = result.revisions } catch (e) { if (run.current()) historyError.value = (e as Error).message }
}, { immediate: true })
async function review(action: string) { if (await confirm(action, reason.value)) emit('saved') }
</script>
<template><el-drawer :model-value="!!statement" :title="t('semantic.w.review')" @close="emit('close')"><template v-if="current"><p>{{ current.id }} · r{{ current.revision }} · {{ current.reviewStatus }} · {{ current.supportStatus }}</p><p>{{ t('semantic.w.ontologyRevision') }}: {{ current.ontologyRevisionId }}</p><h3>{{ current.predicateKey }}: {{ current.value ?? current.targetEntityId }} {{ current.unit }}</h3><p>{{ current.validityKind }} {{ current.validFrom }} — {{ current.validTo }}</p><el-button v-for="id in current.evidenceIds" :key="id" @click="emit('evidence', id)">{{ t('semantic.w.evidence') }} {{ id }}</el-button><template v-if="canReview"><el-input v-model="reason" type="textarea" :placeholder="t('semantic.w.reason')" /><el-button v-if="current.reviewStatus === 'PROPOSED'" :disabled="busy || !reason.trim()" @click="review('ACCEPT')">{{ t('semantic.w.accept') }}</el-button><el-button v-if="current.reviewStatus === 'PROPOSED'" :disabled="busy || !reason.trim()" @click="review('REJECT')">{{ t('semantic.w.reject') }}</el-button><el-button v-if="current.reviewStatus === 'ACCEPTED'" :disabled="busy || !reason.trim()" @click="review('RETRACT')">{{ t('semantic.w.retract') }}</el-button><el-button v-if="current.reviewStatus === 'ACCEPTED'" @click="emit('change', current)">{{ t('semantic.w.proposeChange') }}</el-button></template><el-alert v-if="error" :title="error.code" :description="error.message" type="error" :closable="false" /><template v-if="canReview"><h3>{{ t('semantic.w.history') }}</h3><el-alert v-if="historyError" :title="historyError" type="error" /><div v-for="revision in history" :key="revision.revision"><p>r{{ revision.revision }} · {{ revision.reviewStatus }} · {{ revision.supportStatus }} · {{ revision.value ?? revision.targetEntityId }}</p><el-button v-for="id in revision.evidenceIds" :key="id" @click="emit('evidence', id)">{{ t('semantic.w.evidence') }} {{ id }}</el-button></div></template></template></el-drawer></template>
