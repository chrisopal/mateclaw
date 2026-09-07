import { ref } from 'vue'
import { statementApi } from '../api/statementApi'
import { graphQueryApi } from '../api/graphQueryApi'
import { semanticError, type SemanticError } from '../api/semanticErrors'
import type { Statement } from '../api/workbenchTypes'
import { useSemanticScope } from '../shared/useSemanticScope'
export function useStatementReview(graphId: () => string) {
  const { begin } = useSemanticScope()
  const current = ref<Statement | null>(null), busy = ref(false), error = ref<SemanticError | null>(null)
  async function confirm(action = 'ACCEPT', reason = '') {
    if (!current.value || busy.value) return false
    const run = begin(), selected = current.value
    busy.value = true; error.value = null
    try {
      await statementApi.review(run.id, graphId(), selected, action, reason, run.signal)
      if (!run.current()) return false
      const result = await graphQueryApi.history(run.id, graphId(), selected.id, run.signal)
      if (!run.current()) return false
      current.value = result.revisions.reduce((a, b) => a.revision > b.revision ? a : b)
      return true
    } catch (e) { if (run.current()) error.value = semanticError(e); return false }
    finally { if (run.current()) busy.value = false }
  }
  return { current, busy, error, confirm }
}
