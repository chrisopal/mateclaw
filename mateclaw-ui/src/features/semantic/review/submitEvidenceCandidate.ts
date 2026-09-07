import { sourceApi } from '../api/sourceApi'
import { statementApi } from '../api/statementApi'
import type { Proposal, Statement } from '../api/workbenchTypes'
import type { evidenceSelection } from './evidenceSelection'
/** Evidence creation must succeed in the initiating workspace before any proposal. */
export async function submitEvidenceCandidate(
  run: { id: string; signal: AbortSignal; current: () => boolean },
  graphId: string,
  snapshotId: string,
  range: ReturnType<typeof evidenceSelection>,
  content: Proposal,
  target?: Statement | null,
) {
  const evidence = await sourceApi.evidence(run.id, graphId, snapshotId, range, run.signal)
  if (!run.current()) return false
  content = { ...content, evidenceIds: [evidence.id] }
  if (target) await statementApi.change(run.id, graphId, target, content, run.signal)
  else await statementApi.propose(run.id, graphId, content, run.signal)
  return run.current()
}
