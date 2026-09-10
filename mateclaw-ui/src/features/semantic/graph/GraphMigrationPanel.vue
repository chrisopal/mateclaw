<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { migrationApi, type MappingRole, type MigrationPlan, type MigrationTarget } from '../api/migrationApi'
import { useSemanticScope } from '../shared/useSemanticScope'
const props = defineProps<{ graphId: string; revisionId: string; graphVersion: number; canManage: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const { t } = useI18n(); const { workspace, begin } = useSemanticScope()
const targets = ref<MigrationTarget[]>([]); const target = ref(''); const plan = ref<MigrationPlan | null>(null)
const planId = ref(''); const busy = ref(false); const error = ref('')
const mappings = ref<{ role: MappingRole; from: string; to: string }[]>([])
const roles: MappingRole[] = ['classes', 'objectProperties', 'dataProperties', 'individuals']
const retries = new Map<string, string>()
const blocked = computed(() => !!plan.value?.impact.blockers.length || !!plan.value?.impact.truncated)
function op(payload: unknown) { const key = JSON.stringify([workspace.currentWorkspaceId, props.graphId, payload]); if (!retries.has(key)) retries.set(key, crypto.randomUUID()); return retries.get(key)! }
function storageKey() { return `semantic-migration:${workspace.currentWorkspaceId}:${props.graphId}` }
function accept(value: MigrationPlan) { plan.value = value; planId.value = value.id; sessionStorage.setItem(storageKey(), value.id) }
async function refresh() {
  const run = begin(); busy.value = true; error.value = ''
  try {
    const available = await migrationApi.targets(run.id, props.graphId, run.signal)
    if (!run.current()) return
    targets.value = available
    if (!available.some(item => item.id === target.value)) target.value = ''
    if (planId.value) { const value = await migrationApi.get(run.id, props.graphId, planId.value, run.signal); if (run.current()) accept(value) }
  } catch (e) { if (run.current()) error.value = (e as Error).message } finally { if (run.current()) busy.value = false }
}
async function prepare() {
  if (busy.value || !props.canManage || !target.value) return
  const run = begin(); busy.value = true; error.value = ''
  const body = { sourceRevisionId: props.revisionId, targetRevisionId: target.value, expectedGraphVersion: props.graphVersion,
    classes: mappings.value.filter(v => v.role === 'classes').map(({ from, to }) => ({ from, to })),
    objectProperties: mappings.value.filter(v => v.role === 'objectProperties').map(({ from, to }) => ({ from, to })),
    dataProperties: mappings.value.filter(v => v.role === 'dataProperties').map(({ from, to }) => ({ from, to })),
    individuals: mappings.value.filter(v => v.role === 'individuals').map(({ from, to }) => ({ from, to })) }
  try { const value = await migrationApi.prepare(run.id, props.graphId, { ...body, operationId: op(body) }, run.signal); if (run.current()) accept(value) }
  catch (e) { if (run.current()) error.value = (e as Error).message } finally { if (run.current()) busy.value = false }
}
async function act(action: 'approve' | 'execute' | 'rollback') {
  if (busy.value || !props.canManage || !plan.value) return
  const run = begin(); busy.value = true; error.value = ''
  const body = { expectedPlanDigest: plan.value.planDigest, ...(action === 'approve' ? {} : { expectedGraphVersion: props.graphVersion }) }
  try { const value = await migrationApi.act(run.id, props.graphId, plan.value.id, action, { ...body, operationId: op([action, plan.value.id, body]) }, run.signal); if (run.current()) { accept(value); emit('changed') } }
  catch (e) { if (run.current()) error.value = (e as Error).message } finally { if (run.current()) busy.value = false }
}
watch(() => [workspace.currentWorkspaceId, props.graphId], () => {
  plan.value = null; targets.value = []; target.value = ''; mappings.value = []; retries.clear(); error.value = ''
  planId.value = sessionStorage.getItem(storageKey()) ?? ''; void refresh()
}, { immediate: true, flush: 'sync' })
watch(() => props.revisionId, () => { target.value = ''; void refresh() })
</script>
<template>
  <section class="migration-panel">
    <h3>{{ t('semantic.migration.title') }}</h3><p>{{ t('semantic.migration.help') }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="canManage" label-position="top" :disabled="busy" @submit.prevent="prepare">
      <el-form-item :label="t('semantic.migration.target')"><el-select v-model="target"><el-option v-for="item in targets" :key="item.id" :value="item.id" :label="`${item.name} · v${item.version}`" /></el-select></el-form-item>
      <p>{{ t('semantic.migration.mappingHelp') }}</p>
      <div v-for="(row, index) in mappings" :key="index" class="mapping-row">
        <el-select v-model="row.role"><el-option v-for="role in roles" :key="role" :value="role" :label="t(`semantic.migration.${role}`)" /></el-select>
        <el-input v-model="row.from" :placeholder="t('semantic.migration.from')" /><span>→</span><el-input v-model="row.to" :placeholder="t('semantic.migration.to')" />
        <el-button @click="mappings.splice(index, 1)">{{ t('semantic.delete') }}</el-button>
      </div>
      <el-button @click="mappings.push({ role: 'classes', from: '', to: '' })">{{ t('semantic.migration.addMapping') }}</el-button>
      <el-button native-type="submit" type="primary" :disabled="!target || mappings.some(row => !row.from.trim() || !row.to.trim())">{{ t('semantic.migration.prepare') }}</el-button>
    </el-form>
    <div class="migration-lookup"><el-input v-model="planId" :placeholder="t('semantic.migration.planId')" /><el-button :disabled="busy" @click="refresh">{{ t('semantic.migration.reload') }}</el-button></div>
    <template v-if="plan">
      <h4>v{{ plan.sourceVersion }} → v{{ plan.targetVersion }} · {{ plan.status }}</h4>
      <p>{{ t('semantic.migration.counts', { entities: plan.impact.changedEntities, facts: plan.impact.changedFacts }) }}</p>
      <el-alert v-for="item in plan.impact.blockers" :key="`${item.code}:${item.path}`" :title="item.message" type="warning" :closable="false" />
      <el-alert v-if="plan.impact.truncated" :title="t('semantic.migration.truncated')" type="warning" :closable="false" />
      <el-table :data="plan.entities"><el-table-column prop="oldIri" :label="t('semantic.migration.from')" /><el-table-column prop="targetIri" :label="t('semantic.migration.to')" /></el-table>
      <el-table :data="plan.facts"><el-table-column prop="sourceAssertionText" :label="t('semantic.migration.oldAssertion')" /><el-table-column prop="targetAssertionText" :label="t('semantic.migration.newAssertion')" /></el-table>
      <div v-if="canManage" class="migration-actions">
        <el-button v-if="plan.status === 'PREPARED'" :disabled="busy || blocked" @click="act('approve')">{{ t('semantic.migration.approve') }}</el-button>
        <el-button v-if="plan.status === 'APPROVED'" type="primary" :disabled="busy || blocked" @click="act('execute')">{{ t('semantic.migration.execute') }}</el-button>
        <el-button v-if="plan.status === 'EXECUTED'" :disabled="busy" @click="act('rollback')">{{ t('semantic.migration.rollback') }}</el-button>
      </div>
      <p class="metadata">{{ plan.id }} · {{ plan.planDigest }}</p>
    </template>
  </section>
</template>
<style scoped>.mapping-row,.migration-lookup,.migration-actions { display:flex;gap:8px;margin:12px 0; }.mapping-row > * { min-width:0; }.migration-lookup { max-width:600px; }.metadata { overflow-wrap:anywhere;font-size:12px; }.migration-panel { min-width:0; }</style>
