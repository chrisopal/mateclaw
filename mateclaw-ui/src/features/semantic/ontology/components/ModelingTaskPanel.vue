<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  modelingTaskApi,
  proposalLabel,
  proposalDetails,
  type ModelingTask,
  type ModelingProposal,
} from '../../api/modelingTaskApi'
import { ontologyApi } from '../../api/ontologyApi'
import { useSemanticScope } from '../../shared/useSemanticScope'
import type { DisplayProjection } from '../standardProjection'
const props = withDefaults(
  defineProps<{
    active?: boolean
    projection?: DisplayProjection
    ontologyId: string
    taskId?: string
    disabled?: boolean
    canManage: boolean
  }>(),
  { active: true },
)
const emit = defineEmits<{
  changed: [decision: 'ACCEPT' | 'REJECT']
  selected: [id: string]
}>()
const { workspace, begin } = useSemanticScope(),
  router = useRouter()
const tasks = ref<ModelingTask[]>([]),
  task = ref<ModelingTask>(),
  busy = ref(false),
  error = ref(''),
  answers = ref<Record<string, Record<string, string>>>({})
const { locale } = useI18n()
const tr = (zh: string, en: string) => locale.value.startsWith('zh') ? zh : en
const comparison = ref<Awaited<ReturnType<typeof ontologyApi.sourceReviewSnapshots>> | null>(null)
const comparisonOpen = ref(false)
const names = computed(() =>
  Object.fromEntries(
    (props.projection?.nodes || []).flatMap((n) => [
      [n.id, n.labels[0]?.value || '未命名业务项'],
      [n.iri, n.labels[0]?.value || '未命名业务项'],
    ]),
  ),
)
const operations = new Map<string, string>()
const status = (value: string) =>
  (
    ({
      READY: '待开始',
      RUNNING: '生成中',
      FAILED: '生成失败',
      AWAITING_CONFIRMATION: '待确认',
      PENDING: '待确认',
      ACCEPTED: '已采用',
      REJECTED: '已拒绝',
      STALE: '草稿或资料已变化，请重新生成',
      CREATED: '待开始',
      GENERATING: '生成中',
      REVIEW: '待确认',
      COMPLETED: '已完成',
      CANCELLED: '已取消',
    }) as Record<string, string>
  )[value] || value
function taskStatus(value: ModelingTask) {
  const latest = value.proposals[value.proposals.length - 1]
  return value.stage === 'READY' && latest?.status === 'ACCEPTED' ? '本批已确认，可继续建模' : status(value.stage)
}
async function load() {
  const c = begin()
  busy.value = true
  error.value = ''
  try {
    const rows = await modelingTaskApi.list(c.id, props.ontologyId, c.signal)
    if (!c.current()) return
    tasks.value = rows
    const chosen =
      rows.find((x) => x.id === props.taskId) ||
      (!props.taskId ? rows[0] : undefined)
    if (chosen) {
      const detail = await modelingTaskApi.get(c.id, chosen.id, c.signal)
      if (!c.current()) return
      task.value = detail
    }
    if (props.taskId && !task.value)
      error.value = '此建模任务不属于当前模型或不可访问'
  } catch (e) {
    if (c.current()) error.value = (e as Error).message
  } finally {
    if (c.current()) busy.value = false
  }
}
function select(id: string) {
  emit('selected', id)
}
function answerMap(p: ModelingProposal) {
  return answers.value[p.id] ?? (answers.value[p.id] = { ...p.answers })
}
function complete(p: ModelingProposal) {
  return p.input.questions.every((q) => answerMap(p)[q]?.trim())
}
async function decide(p: ModelingProposal, decision: 'ACCEPT' | 'REJECT') {
  if (!task.value || busy.value || props.disabled || !props.canManage) return
  const c = begin()
  busy.value = true
  error.value = ''
  const payload = { decision, answers: { ...answerMap(p) } }
  const key = JSON.stringify([task.value.id, p.id, payload])
  const operationId = operations.get(key) || crypto.randomUUID()
  operations.set(key, operationId)
  try {
    const result = await modelingTaskApi.decide(
      c.id,
      task.value.id,
      p.id,
      { ...payload, operationId },
      c.signal,
    )
    if (c.current()) {
      task.value = result
      tasks.value = tasks.value.map((x) => (x.id === result.id ? result : x))
      operations.delete(key)
      emit('changed', decision)
    }
  } catch (e) {
    if (c.current()) error.value = (e as Error).message
  } finally {
    if (c.current()) busy.value = false
  }
}
async function compareIncremental() {
  if (busy.value || !task.value?.incremental) return
  const c = begin()
  busy.value = true
  error.value = ''
  comparison.value = null
  try {
    const result = await ontologyApi.sourceReviewSnapshots(c.id, props.ontologyId, task.value.incremental.reviewId, c.signal)
    if (c.current()) { comparison.value = result; comparisonOpen.value = true }
  } catch (e) {
    if (c.current()) error.value = (e as Error).message
  } finally {
    if (c.current()) busy.value = false
  }
}
async function chat() {
  if (!task.value || busy.value || props.disabled) return
  const c = begin()
  busy.value = true
  try {
    const builder = await ontologyApi.ensureBuilder(c.id, c.signal)
    if (c.current())
      await router.push({
        path: '/chat',
        query: {
          agentId: String(builder.agentId),
          modelingTaskId: task.value.id,
          ontologyId: props.ontologyId,
        },
      })
  } catch (e) {
    if (c.current()) error.value = (e as Error).message
  } finally {
    if (c.current()) busy.value = false
  }
}
watch(
  () => [workspace.currentWorkspaceId, props.ontologyId, props.taskId],
  () => {
    task.value = undefined
    comparison.value = null
    comparisonOpen.value = false
    tasks.value = []
    answers.value = {}
    if (props.active !== false) void load()
  },
  { immediate: true },
)
watch(
  () => props.active,
  (value) => {
    if (value) void load()
  },
)
</script>
<template>
  <section class="modeling-task-panel" aria-label="建模任务与建议">
    <div class="task-toolbar">
      <h2>建模任务</h2>
      <el-select
        v-if="tasks.length"
        :model-value="task?.id"
        aria-label="建模任务"
        @change="select"
        ><el-option
          v-for="(row, index) in tasks"
          :key="row.id"
          :value="row.id"
          :label="`任务 ${index + 1} · ${taskStatus(row)}`" /></el-select
      ><el-button :loading="busy" @click="load">刷新建议</el-button
      ><el-button
        v-if="canManage && task"
        :disabled="disabled || busy"
        @click="chat"
        >继续对话</el-button
      >
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="!task && !busy && !error" class="semantic-muted">
      尚无建模任务。描述业务需求或选择资料开始。
    </p>
    <template v-if="task"
      ><section v-if="task.incremental" class="incremental-scope" data-testid="incremental-model-scope">
        <h3>{{ tr('资料变化 · 增量建模', 'Source change · Incremental modeling') }}</h3>
        <p>{{ tr('本次核对', 'Reviewing') }} {{ task.incremental.affectedAxiomIds.length }} {{ tr('条受影响业务规则。建议仍需人工确认，确认前不会改变已发布模型。', 'affected business rules. Proposals require confirmation and do not change the published model automatically.') }}</p>
        <el-button :disabled="busy" @click="compareIncremental">{{ tr('对比原始依据与新资料', 'Compare original and updated sources') }}</el-button>
      </section><details class="task-goal">
        <summary>查看建模需求</summary>
        <p>{{ task.goal }}</p>
      </details>
      <p class="semantic-muted">
        {{ taskStatus(task) }} ·
        {{
          task.sources.length
            ? `已选 ${task.sources.length} 份资料`
            : '依据：用户陈述'
        }}
      </p>
      <p v-if="!task.proposals.length" class="semantic-muted">
        点击“继续对话”生成建议。
      </p>
      <article
        v-for="(proposal, index) in task.proposals"
        :key="proposal.id"
        class="proposal"
      >
        <header>
          <strong>建议 {{ index + 1 }}</strong
          ><span>{{ status(proposal.status) }}</span>
        </header>
        <ul>
          <li v-for="(change, i) in proposal.input.changes" :key="i">
            {{ proposalLabel(change) }}
            <div
              v-if="proposalDetails(change, proposal.input.changes, names)"
              class="semantic-muted"
            >
              {{ proposalDetails(change, proposal.input.changes, names) }}
            </div>
          </li>
        </ul>
        <details v-if="proposal.input.evidence.length">
          <summary>查看依据（{{ proposal.input.evidence.length }}）</summary>
          <blockquote v-for="(e, i) in proposal.input.evidence" :key="i">
            {{ e.exactQuote
            }}<small>{{
              e.origin === 'USER_STATEMENT' ? '用户陈述' : '所选资料原文'
            }}</small>
          </blockquote>
        </details>
        <el-form label-position="top"
          ><el-form-item
            v-for="question in proposal.input.questions"
            :key="question"
            :label="question"
            required
            ><el-input
              v-model="answerMap(proposal)[question]"
              :aria-label="question"
              :disabled="
                !canManage || proposal.status !== 'PENDING' || busy || disabled
              "
              type="textarea"
              :rows="2" /></el-form-item
        ></el-form>
        <p v-if="proposal.input.samples.length" class="semantic-muted">
          包含 {{ proposal.input.samples.length }} 个参考案例，不会写入事实库。
        </p>
        <div
          v-if="proposal.status === 'PENDING' && canManage"
          class="task-actions"
        >
          <el-button
            :disabled="disabled || busy"
            @click="decide(proposal, 'REJECT')"
            >拒绝本批建议</el-button
          ><el-button
            type="primary"
            :disabled="disabled || busy || !complete(proposal)"
            @click="decide(proposal, 'ACCEPT')"
            >确认本批建议</el-button
          >
        </div>
      </article></template
    >
    <el-dialog v-model="comparisonOpen" :title="tr('资料变化范围', 'Changed source material')" width="min(980px, 94vw)">
      <div v-if="comparison" class="incremental-comparison">
        <section><h4>{{ tr('原始依据', 'Original evidence') }}</h4><p>{{ comparison.original.sourceTitle }}</p><pre>{{ comparison.original.sourceText }}</pre></section>
        <section><h4>{{ tr('新资料', 'Updated material') }}</h4><template v-if="comparison.observed"><p>{{ comparison.observed.sourceTitle }}</p><pre>{{ comparison.observed.sourceText }}</pre></template><p v-else>{{ tr('新资料不可访问，原始快照仍保留。', 'Updated material is unavailable; the original snapshot remains.') }}</p></section>
      </div>
    </el-dialog>
  </section>
</template>
<style scoped>
.incremental-scope { margin: 12px 0; padding: 12px 0; border-bottom: 1px solid var(--mc-border); }.incremental-scope h3 { font-size: 14px; }.incremental-scope p { color: var(--mc-text-secondary); line-height: 1.7; }.incremental-comparison { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 20px; }.incremental-comparison pre { white-space: pre-wrap; overflow-wrap: anywhere; }.incremental-comparison section { min-width: 0; }
@media (max-width: 700px) { .incremental-comparison { grid-template-columns: 1fr; } }
.modeling-task-panel {
  min-width: 0;
  padding: 0;
}
.task-goal {
  margin: 12px 0;
  font-size: 13px;
}
.task-goal summary {
  cursor: pointer;
  color: var(--mc-text-secondary);
}
.task-goal p {
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.task-toolbar,
.task-actions,
.proposal header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.task-toolbar h2 {
  font-size: 16px;
  margin: 0;
}
.task-toolbar .el-select {
  width: 240px;
  max-width: 100%;
}
.proposal {
  padding: 16px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  overflow-wrap: anywhere;
}
.proposal header {
  justify-content: space-between;
}
.proposal ul {
  padding-left: 20px;
  line-height: 1.8;
}
.proposal blockquote {
  margin: 12px 0;
  padding-left: 12px;
  border-left: 2px solid var(--el-border-color);
  white-space: pre-wrap;
}
.proposal small {
  display: block;
  color: var(--el-text-color-secondary);
}
.proposal .el-form {
  margin-top: 12px;
}
.task-actions {
  justify-content: flex-end;
}
@media (max-width: 600px) {
  .task-toolbar .el-select {
    width: 100%;
  }
  .task-actions {
    justify-content: flex-start;
  }
}
</style>
