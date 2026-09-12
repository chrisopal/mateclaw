<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { migrationApi, type MappingRole, type MigrationPlan, type MigrationTarget } from '../api/migrationApi'
import { useSemanticScope } from '../shared/useSemanticScope'
const props = defineProps<{ graphId: string; revisionId: string; graphVersion: number; canManage: boolean; initialTarget?: string; currentVersion?: number }>()
const emit = defineEmits<{ changed: [] }>()
const { t, locale } = useI18n(); const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en; const { workspace, begin } = useSemanticScope()
const targets = ref<MigrationTarget[]>([]); const target = ref(''); const plan = ref<MigrationPlan | null>(null)
const planId = ref(''); const busy = ref(false); const error = ref('')
const mappings = ref<{ role: MappingRole; from: string; to: string }[]>([])
const roles: MappingRole[] = ['classes', 'objectProperties', 'dataProperties', 'individuals']
const retries = new Map<string, string>()
const blocked = computed(() => !!plan.value?.impact.blockers.length || !!plan.value?.impact.truncated)
const rollbackSafe = computed(() => plan.value?.status === 'EXECUTED' && plan.value.executedGraphVersion === props.graphVersion && props.revisionId === plan.value.targetRevisionId)
const rollbackReason = computed(() => {
  if (plan.value?.status !== 'EXECUTED' || rollbackSafe.value) return ''
  return props.revisionId !== plan.value.targetRevisionId
    ? tr('当前应用版本已变化，不能回滚此计划。', 'The applied version has changed; this plan cannot be rolled back.')
    : tr('迁移后知识库已发生变化，不能安全回滚。', 'The knowledge base changed after migration; rollback is no longer safe.')
})
const currentVersionLabel = computed(() => props.currentVersion ?? (plan.value?.sourceRevisionId === props.revisionId ? plan.value.sourceVersion : plan.value?.targetRevisionId === props.revisionId ? plan.value.targetVersion : undefined))
const statusLabel = (status: string) => ({ PREPARED: tr('已检查，待批准', 'Checked, awaiting approval'), APPROVED: tr('已批准，待执行', 'Approved, ready to execute'), EXECUTED: tr('已执行', 'Executed'), ROLLED_BACK: tr('已回滚', 'Rolled back') } as Record<string, string>)[status] || status
function op(payload: unknown) { const key = JSON.stringify([workspace.currentWorkspaceId, props.graphId, payload]); if (!retries.has(key)) retries.set(key, crypto.randomUUID()); return retries.get(key)! }
function storageKey() { return `semantic-migration:${workspace.currentWorkspaceId}:${props.graphId}` }
function accept(value: MigrationPlan) { plan.value = value; planId.value = value.id; sessionStorage.setItem(storageKey(), value.id) }
async function refresh() {
  const run = begin(); busy.value = true; error.value = ''
  try {
    const available = await migrationApi.targets(run.id, props.graphId, run.signal)
    if (!run.current()) return
    targets.value = available
    if (!available.some(item => item.id === target.value)) target.value = available.some(item => item.id === props.initialTarget) ? props.initialTarget! : ''
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
  if (busy.value || !props.canManage || !plan.value || (action === 'rollback' && !rollbackSafe.value)) return
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
    <ol class="migration-stages" :aria-label="tr('迁移步骤', 'Migration stages')"><li>{{ tr('准备并检查影响', 'Prepare and check impact') }}</li><li>{{ tr('负责人批准', 'Approve the plan') }}</li><li>{{ tr('执行版本迁移', 'Execute migration') }}</li></ol>
    <p data-testid="migration-current-version">{{ tr('当前应用版本', 'Current applied version') }}: {{ currentVersionLabel !== undefined ? `v${currentVersionLabel}` : tr('已绑定版本', 'Bound revision') }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="canManage" label-position="top" :disabled="busy" @submit.prevent="prepare">
      <el-form-item :label="t('semantic.migration.target')"><el-select v-model="target" :aria-label="t('semantic.migration.target')"><el-option v-for="item in targets" :key="item.id" :value="item.id" :label="`${item.name} · v${item.version}`" /></el-select></el-form-item>
      <p>{{ tr('只修改名称且标识未变，无需填写映射；对象、关系或属性的标识改变时，才需要指定旧标识与新标识。', 'A label rename with the same identifier needs no mapping. Map old to new identifiers only when an object, relation or property identifier changes.') }}</p>
      <div v-for="(row, index) in mappings" :key="index" class="mapping-row">
        <el-select v-model="row.role" :aria-label="`${tr('映射类型', 'Mapping type')} ${index + 1}`"><el-option v-for="role in roles" :key="role" :value="role" :label="t(`semantic.migration.${role}`)" /></el-select>
        <el-input v-model="row.from" :aria-label="`${tr('旧标识', 'Old identifier')} ${index + 1}`" :placeholder="t('semantic.migration.from')" /><span>→</span><el-input v-model="row.to" :aria-label="`${tr('新标识', 'New identifier')} ${index + 1}`" :placeholder="t('semantic.migration.to')" />
        <el-button :aria-label="`${tr('删除映射', 'Remove mapping')} ${index + 1}`" @click="mappings.splice(index, 1)">{{ t('semantic.delete') }}</el-button>
      </div>
      <el-button @click="mappings.push({ role: 'classes', from: '', to: '' })">{{ t('semantic.migration.addMapping') }}</el-button>
      <el-button native-type="submit" type="primary" :disabled="!target || mappings.some(row => !row.from.trim() || !row.to.trim())">{{ t('semantic.migration.prepare') }}</el-button>
    </el-form>
    <details class="migration-advanced"><summary>{{ tr('按计划编号恢复', 'Recover by plan ID') }}</summary><div class="migration-lookup"><el-input v-model="planId" :aria-label="t('semantic.migration.planId')" :placeholder="t('semantic.migration.planId')" /><el-button :disabled="busy" @click="refresh">{{ t('semantic.migration.reload') }}</el-button></div></details>
    <template v-if="plan">
      <h4>v{{ plan.sourceVersion }} → v{{ plan.targetVersion }} · {{ statusLabel(plan.status) }}</h4>
      <p>{{ t('semantic.migration.counts', { entities: plan.impact.changedEntities, facts: plan.impact.changedFacts }) }}</p>
      <el-alert v-for="item in plan.impact.blockers" :key="`${item.code}:${item.path}`" :title="item.message" type="warning" :closable="false" />
      <el-alert v-if="plan.impact.truncated" :title="t('semantic.migration.truncated')" type="warning" :closable="false" />
      <details class="migration-advanced"><summary>{{ tr('高级：标识与事实变化明细', 'Advanced: identifier and assertion changes') }}</summary>
      <el-table :data="plan.entities"><el-table-column prop="oldIri" :label="t('semantic.migration.from')" /><el-table-column prop="targetIri" :label="t('semantic.migration.to')" /></el-table>
      <el-table :data="plan.facts"><el-table-column prop="sourceAssertionText" :label="t('semantic.migration.oldAssertion')" /><el-table-column prop="targetAssertionText" :label="t('semantic.migration.newAssertion')" /></el-table>
      <p class="metadata">{{ plan.id }} · {{ plan.planDigest }}</p></details>
      <div v-if="canManage" class="migration-actions">
        <el-button v-if="plan.status === 'PREPARED'" :disabled="busy || blocked" @click="act('approve')">{{ t('semantic.migration.approve') }}</el-button>
        <el-button v-if="plan.status === 'APPROVED'" type="primary" :disabled="busy || blocked" @click="act('execute')">{{ t('semantic.migration.execute') }}</el-button>
        <el-button v-if="plan.status === 'EXECUTED'" :disabled="busy || !rollbackSafe" @click="act('rollback')">{{ t('semantic.migration.rollback') }}</el-button>
      </div>
      <p v-if="rollbackReason" role="status" class="metadata">{{ rollbackReason }}</p>
    </template>
  </section>
</template>
<style scoped>.migration-stages { display:flex;flex-wrap:wrap;gap:12px 32px;padding-left:20px;color:var(--mc-text-secondary); }.migration-advanced { margin:14px 0; }.migration-advanced summary { cursor:pointer; }.mapping-row { display:grid;grid-template-columns:minmax(120px, 1fr) minmax(160px, 2fr) auto minmax(160px, 2fr) auto;align-items:center; }.migration-actions { flex-wrap:wrap; }@media(max-width:700px) { .mapping-row { grid-template-columns:minmax(0, 1fr); }.mapping-row > span { display:none; }.migration-lookup { flex-wrap:wrap; } }.mapping-row { gap:8px;margin:12px 0; }.migration-lookup,.migration-actions { display:flex;gap:8px;margin:12px 0; }.mapping-row > * { min-width:0; }.migration-lookup { max-width:600px; }.metadata { overflow-wrap:anywhere;font-size:12px; }.migration-panel { min-width:0; }</style>
