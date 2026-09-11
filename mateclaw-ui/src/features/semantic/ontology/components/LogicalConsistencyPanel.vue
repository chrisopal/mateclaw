<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ontologyApi } from '../../api/ontologyApi'
import type { LogicalConsistencyReport } from '../../api/types'
import type { DisplayProjection } from '../standardProjection'
import { useSemanticScope } from '../../shared/useSemanticScope'

type LogicalCheckState = 'idle' | 'running' | 'result' | 'timeout' | 'error' | 'unsupported' | 'stale' | 'cancelled'

const props = defineProps<{
  ontologyId: string
  draftVersion: number
  dirty: boolean
  projection?: DisplayProjection | null
  editable: boolean
  disabled?: boolean
}>()

const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const { workspace, begin, cancel } = useSemanticScope()
const state = ref<LogicalCheckState>('idle')
const report = ref<LogicalConsistencyReport | null>(null)
const message = ref('')
const running = ref(false)
let generation = 0

const canRun = computed(() => props.editable && !props.disabled && !props.dirty && !running.value && !!props.ontologyId && props.draftVersion > 0)
const resultTitle = computed(() => {
  if (report.value?.consistent === false) return tr('模型存在矛盾', 'Model is inconsistent')
  if (report.value?.consistent === true && report.value.unsatisfiableClasses.length) return tr('存在无法成立的对象类型', 'Some object types are unsatisfiable')
  if (report.value?.consistent === true) return tr('未发现逻辑矛盾', 'No logical inconsistency found')
  return tr('未能完成检查', 'Check could not be completed')
})
const resultClass = computed(() => report.value?.consistent === false ? 'is-inconsistent' : report.value?.consistent === true && !report.value.unsatisfiableClasses.length ? 'is-consistent' : 'is-unknown')
const stateMessage = computed(() => {
  if (state.value === 'idle') return props.dirty
    ? tr('请先保存修改，再运行逻辑检查。', 'Save changes before running the logical check.')
    : tr('尚未执行逻辑检查。', 'The logical check has not run yet.')
  if (state.value === 'running') return tr('正在检查当前已保存草稿，可能需要几十秒。', 'Checking the saved draft; this may take several seconds.')
  if (state.value === 'timeout') return tr('逻辑检查超时，请稍后重试。', 'The logical check timed out. Try again later.')
  if (state.value === 'unsupported') return tr('当前环境不支持逻辑检查。', 'Logical checking is not supported in this environment.')
  if (state.value === 'stale') return tr('检查结果对应旧版本，已丢弃，请重新运行。', 'The result belongs to an older draft and was discarded. Run it again.')
  if (state.value === 'cancelled') return tr('已停止等待；服务器可能仍在处理本次检查。', 'Waiting stopped; the server may still be processing this check.')
  if (state.value === 'error') return tr('逻辑检查失败，请重试。', 'The logical check failed. Try again.')
  return report.value?.message || ''
})

function statusCode(value: unknown) { return typeof value === 'string' ? value.trim().toUpperCase() : '' }
function stateForReport(value: LogicalConsistencyReport): LogicalCheckState {
  const status = statusCode(value.status)
  if (status === 'CONSISTENT' || status === 'INCONSISTENT' || status === 'UNSATISFIABLE') return 'result'
  if (status === 'TIMEOUT' || status === 'DEADLINE_EXCEEDED') return 'timeout'
  if (status === 'UNSUPPORTED' || status === 'NOT_AVAILABLE' || status === 'REASONING_DISABLED') return 'unsupported'
  if (status === 'STALE' || status === 'DRAFT_CONFLICT' || status === 'DRAFT_VERSION_MISMATCH') return 'stale'
  if (status === 'CANCELLED' || status === 'CANCELED') return 'cancelled'
  return 'error'
}
function errorState(error: unknown): LogicalCheckState {
  const value = error as { status?: number; code?: string; message?: string }
  const code = statusCode(value.code)
  const text = `${value.message ?? ''} ${code}`.toLowerCase()
  if (code.includes('UNSUPPORTED') || code === 'REASONING_DISABLED' || value.status === 415) return 'unsupported'
  if (code === 'STALE' || code === 'REASONING_STALE' || code === 'DRAFT_CONFLICT' || code === 'DRAFT_VERSION_MISMATCH') return 'stale'
  if (code.includes('CANCEL') || code === 'ERR_CANCELED') return 'cancelled'
  if (code.includes('TIMEOUT') || code === 'ECONNABORTED' || code === 'ETIMEDOUT' || text.includes('timeout') || text.includes('timed out')) return 'timeout'
  return 'error'
}
function localName(value: string) { return value.split(/[#/:]/).pop() || tr('未命名对象类型', 'Unnamed object type') }
function classLabel(iri: string) {
  const node = props.projection?.nodes.find(item => item.iri === iri || item.id === iri)
  if (!node) return localName(iri)
  const preferred = locale.value.toLowerCase()
  const labels = [...node.labels].sort((a, b) => {
    const score = (language: string) => language.toLowerCase() === preferred ? 0 : language.toLowerCase().split('-')[0] === preferred.split('-')[0] ? 1 : !language ? 2 : 3
    return score(a.language) - score(b.language)
  })
  return labels[0]?.value || localName(node.iri)
}

function invalidate() {
  generation++
  report.value = null
  message.value = ''
  if (running.value) {
    cancel()
    running.value = false
  }
  state.value = 'idle'
}
watch(() => [props.ontologyId, props.draftVersion, props.dirty, workspace.currentWorkspaceId], invalidate, { flush: 'sync' })

async function run() {
  if (!canRun.value) return
  const scope = begin()
  const runGeneration = ++generation
  running.value = true
  report.value = null
  message.value = ''
  state.value = 'running'
  try {
    const result = await ontologyApi.reason(scope.id, props.ontologyId, props.draftVersion, scope.signal)
    if (!scope.current() || runGeneration !== generation) return
    if (result.draftVersion !== props.draftVersion) {
      state.value = 'stale'
      message.value = tr('检查结果对应旧草稿版本，已丢弃，请重新运行。', 'The check returned an older draft version and was discarded. Run it again.')
      return
    }
    report.value = result
    state.value = stateForReport(result)
    message.value = result.message || ''
  } catch (error) {
    if (!scope.current() || runGeneration !== generation) return
    state.value = errorState(error)
    message.value = (error as { message?: string }).message || ''
  } finally {
    if (scope.current() && runGeneration === generation) running.value = false
  }
}
function cancelRun() {
  if (!running.value) return
  generation++
  cancel()
  running.value = false
  report.value = null
  state.value = 'cancelled'
  message.value = ''
}
</script>

<template>
  <section class="logical-check-panel" aria-labelledby="logical-check-title">
    <div class="logical-check-heading">
      <div>
        <h3 id="logical-check-title">{{ tr('模型逻辑检查', 'Model logical check') }}</h3>
        <p class="semantic-muted">{{ tr('检查已保存模型能否在逻辑上成立；这不替代结构检查。', 'Check whether the saved model is logically satisfiable; this does not replace structural checks.') }}</p>
      </div>
      <div class="logical-check-actions">
        <el-button v-if="running" @click="cancelRun">{{ tr('停止等待', 'Stop waiting') }}</el-button>
        <el-button type="primary" :loading="running" :disabled="!canRun" @click="run">{{ tr('运行逻辑检查', 'Run logical check') }}</el-button>
      </div>
    </div>
    <p v-if="stateMessage && state !== 'result'" class="logical-check-state" :class="`is-${state}`" role="status" aria-live="polite">{{ stateMessage }}</p>
    <details v-if="message && state !== 'result'" class="logical-check-details"><summary>{{ tr('检查详情', 'Check details') }}</summary><p>{{ message }}</p></details>
    <div v-if="state === 'result' && report" class="logical-check-result" :class="resultClass" role="status" aria-live="polite">
      <strong>{{ resultTitle }}</strong>
      <details v-if="report.message" class="logical-check-details"><summary>{{ tr('检查详情', 'Check details') }}</summary><p>{{ report.message }}</p></details>
      <template v-if="report.unsatisfiableClasses.length">
        <span>{{ tr('无法满足的对象类型', 'Unsatisfiable object types') }}</span>
        <ul><li v-for="iri in report.unsatisfiableClasses" :key="iri">{{ classLabel(iri) }}</li></ul>
      </template>
    </div>
  </section>
</template>

<style scoped>
.logical-check-panel{margin:20px 0 0;padding:16px;border:1px solid var(--mc-border);border-radius:4px}.logical-check-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.logical-check-heading h3{margin:0;font-size:15px;font-weight:600}.logical-check-heading p{margin:7px 0 0}.logical-check-actions{display:flex;gap:8px;flex-wrap:wrap;flex-shrink:0}.logical-check-state{margin:14px 0 0;color:var(--mc-text-secondary);font-size:13px}.logical-check-state.is-error,.logical-check-state.is-timeout,.logical-check-state.is-stale{color:var(--el-color-danger)}.logical-check-state.is-unsupported,.logical-check-state.is-cancelled{color:var(--el-color-warning)}.logical-check-result{display:grid;gap:6px;margin-top:14px;padding:12px;border:1px solid var(--mc-border);font-size:13px}.logical-check-result p{margin:0;color:var(--mc-text-secondary)}.logical-check-result span{color:var(--mc-text-secondary);font-size:12px}.logical-check-result ul{margin:0;padding-left:18px}.logical-check-result li{margin:3px 0}.logical-check-result.is-consistent{border-color:var(--el-color-success)}.logical-check-result.is-inconsistent{border-color:var(--el-color-danger)}.logical-check-result.is-unknown{border-color:var(--el-color-warning)}
@media(max-width:650px){.logical-check-heading{flex-direction:column}.logical-check-actions{width:100%}.logical-check-actions .el-button{flex:1}}
</style>
