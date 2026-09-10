<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatValidity } from '../shared/formatValidity'
import { statementApi } from '../api/statementApi'
import { useSemanticScope } from '../shared/useSemanticScope'
import type { SemanticEntity } from '../api/types'
import type { Change, Conflict, ConflictMember, Proposal, Statement } from '../api/workbenchTypes'
const props = defineProps<{ graphId: string; conflicts: Conflict[]; statements?: Statement[]; entities?: SemanticEntity[] }>()
const emit = defineEmits<{ saved: []; select: [id: string]; evidence: [id: string] }>()
const { t, te } = useI18n(), { begin, workspace, cancel } = useSemanticScope()
function statusName(status: string) { const key = `semantic.w.states.${status}`; return te(key) ? t(key) : status }
const proposals = ref<Record<string, Change>>({})
function proposalMember(member: ConflictMember) { return member.kind === 'CHANGE_PROPOSAL' }
function contentName(row: Proposal | Statement) {
  const entity = (id: string | null) => props.entities?.find(item => item.id === id)?.displayName ?? id
  if ('assertion' in row && row.assertion) return `${entity(row.subjectId)} · ${row.assertion.kind}: ${row.assertion.functionalSyntax}`
  return `${entity(row.subjectId)} · ${'assertionText' in row ? row.assertionText ?? 'Assertion' : ('assertion' in row ? row.assertion?.functionalSyntax ?? 'Assertion' : 'Assertion')} `
}
function memberName(member: ConflictMember, index: number) {
  const row = proposalMember(member) ? proposals.value[member.statementId]?.content : props.statements?.find(statement => statement.id === member.statementId)
  return row ? contentName(row) : t('semantic.w.conflictMember', { number: index + 1 })
}
function ready(conflict: Conflict) {
  return [conflict.left, conflict.right].every(member => !proposalMember(member) || !!proposals.value[member.statementId]?.content)
}
watch(() => [workspace.currentWorkspaceId, props.graphId, props.conflicts], () => { cancel(); proposals.value = {}; error.value = ''; busy.value = false }, { flush: 'sync' })
async function inspect(member: ConflictMember) {
  if (!proposalMember(member)) { emit('select', member.statementId); return }
  const run = begin(); busy.value = true; error.value = ''
  delete proposals.value[member.statementId]
  try {
    const proposal = await statementApi.getChange(run.id, props.graphId, member.statementId, run.signal)
    if (run.current()) {
      if (proposal.expectedRevision !== member.revision) throw new Error(t('semantic.w.conflictStale'))
      proposals.value[member.statementId] = proposal
    }
  } catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
const reasons = ref<Record<string, string>>({}), error = ref(''), busy = ref(false)
async function resolve(conflict: Conflict, winner: string) {
  if (!ready(conflict)) return
  const run = begin(); busy.value = true; error.value = ''
  try { await statementApi.resolve(run.id, props.graphId, conflict, winner, reasons.value[conflict.id] ?? '', run.signal); if (run.current()) emit('saved') }
  catch (e) { if (run.current()) error.value = (e as Error).message }
  finally { if (run.current()) busy.value = false }
}
</script>
<template><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-empty v-if="!conflicts.length" :description="t('semantic.w.empty')" /><el-card v-for="conflict in conflicts" :key="conflict.id"><p>{{ statusName(conflict.kind) }} · {{ statusName(conflict.status) }}</p><div v-for="(member, index) in [conflict.left, conflict.right]" :key="member.statementId"><el-button text @click="inspect(member)">{{ t(proposalMember(member) ? 'semantic.w.proposalMember' : 'semantic.w.statementMember') }} · {{ memberName(member, index) }} · r{{ member.revision }}</el-button><div v-if="proposalMember(member) && proposals[member.statementId]?.content"><p>{{ formatValidity(proposals[member.statementId]!.content, t) }}</p><el-button v-for="id in proposals[member.statementId]!.content.evidenceIds" :key="id" text @click="emit('evidence', id)">{{ t('semantic.w.viewEvidence') }} · {{ id }}</el-button></div><small class="metadata">{{ member.statementId }}</small><el-button v-if="conflict.status === 'OPEN'" :disabled="busy || !ready(conflict) || !reasons[conflict.id]?.trim()" @click="resolve(conflict, member.statementId)">{{ t(proposalMember(member) ? 'semantic.w.keepProposal' : 'semantic.w.keepWinner') }}</el-button></div><p v-if="!ready(conflict)" class="metadata">{{ t('semantic.w.inspectProposals') }}</p><el-input v-if="conflict.status === 'OPEN'" v-model="reasons[conflict.id]" :placeholder="t('semantic.w.reason')" /><p v-else>{{ conflict.resolution }}</p></el-card></template>

<style scoped>.metadata { display: block; color: var(--mc-text-secondary); overflow-wrap: anywhere; }</style>
