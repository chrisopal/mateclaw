<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { sourceApi } from '../api/sourceApi'
import { useSemanticScope } from '../shared/useSemanticScope'
import type { Source, Snapshot } from '../api/workbenchTypes'
const props = defineProps<{ graphId: string; sources: Source[]; snapshots: Snapshot[]; canReview: boolean }>()
const emit = defineEmits<{ saved: [] }>()
const { t, te } = useI18n(), { begin } = useSemanticScope()
function statusName(status: string) { const key = `semantic.w.states.${status}`; return te(key) ? t(key) : status }
const reason = ref(''), error = ref(''), busy = ref(false)
async function govern(source: Pick<Source, 'sourceKind' | 'sourceRef'>, snapshotId?: string) {
  const run = begin(); busy.value = true; error.value = ''
  try { await sourceApi.govern(run.id, props.graphId, source, reason.value, snapshotId, run.signal); if (run.current()) { reason.value = ''; emit('saved') } }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
</script>
<template><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-input v-if="canReview" v-model="reason" :placeholder="t('semantic.w.reason')" /><h3>{{ t('semantic.w.sources') }}</h3><el-table :data="sources"><el-table-column prop="title" :label="t('semantic.w.name')" /><el-table-column :label="t('semantic.w.status')"><template #default="{ row }">{{ statusName(row.state) }}</template></el-table-column><el-table-column prop="snapshotCount" :label="t('semantic.w.snapshot')" /><el-table-column v-if="canReview"><template #default="{ row }"><el-button :disabled="busy || !reason.trim() || row.state === 'WITHDRAWN'" @click="govern(row)">{{ t('semantic.w.withdrawSource') }}</el-button></template></el-table-column></el-table><h3>{{ t('semantic.w.snapshots') }}</h3><el-table :data="snapshots"><el-table-column prop="sourceTitle" :label="t('semantic.w.name')" /><el-table-column prop="captureVersion" :label="t('semantic.w.version')" /><el-table-column prop="textDigest" :label="t('semantic.w.digest')" show-overflow-tooltip /><el-table-column v-if="canReview"><template #default="{ row }"><el-button :disabled="busy || !reason.trim()" @click="govern(row, row.id)">{{ t('semantic.w.excludeSnapshot') }}</el-button></template></el-table-column></el-table></template>
