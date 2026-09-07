import { beforeEach, expect, it, vi } from 'vitest'
import { candidateContent } from '../candidateContent'
import { submitEvidenceCandidate } from '../submitEvidenceCandidate'
import { sourceApi } from '../../api/sourceApi'
import { statementApi } from '../../api/statementApi'
import type { Evidence, Proposal } from '../../api/workbenchTypes'
vi.mock('../../api/sourceApi', () => ({ sourceApi: { evidence: vi.fn() } }))
vi.mock('../../api/statementApi', () => ({ statementApi: { propose: vi.fn(), change: vi.fn() } }))
const run = { id: 'w', signal: new AbortController().signal, current: () => true }
const range = { startCodePoint: 1, endCodePoint: 5, exactQuote: '380V' }
const content: Proposal = { operationId: 'op', subjectId: 'e', predicateKind: 'PROPERTY', predicateKey: 'voltage', valueType: 'DECIMAL', value: '380', unit: 'V', targetEntityId: null, validityKind: 'UNKNOWN', validFrom: null, validTo: null, evidenceIds: [] }
beforeEach(() => vi.clearAllMocks())
it('never proposes a candidate when protected evidence creation fails', async () => {
 vi.mocked(sourceApi.evidence).mockRejectedValue({ status: 403 })
 await expect(submitEvidenceCandidate(run, 'g', 's', range, content)).rejects.toEqual({ status: 403 })
 expect(statementApi.propose).not.toHaveBeenCalled()
 expect(statementApi.change).not.toHaveBeenCalled()
})
it('posts the server evidence id and exact selected range before proposing', async () => {
 vi.mocked(sourceApi.evidence).mockResolvedValue({ id: 'protected-evidence' } as Evidence)
 await submitEvidenceCandidate(run, 'g', 's', range, content)
 expect(sourceApi.evidence).toHaveBeenCalledWith('w', 'g', 's', range, run.signal)
 expect(statementApi.propose).toHaveBeenCalledWith('w', 'g', { ...content, evidenceIds: ['protected-evidence'] }, run.signal)
 expect(sourceApi.evidence).toHaveBeenCalledBefore(vi.mocked(statementApi.propose))
})
it('does not dispatch after workspace invalidation during evidence creation', async () => {
 vi.mocked(sourceApi.evidence).mockResolvedValue({ id: 'ev' } as Evidence)
 expect(await submitEvidenceCandidate({ ...run, current: () => false }, 'g', 's', range, content)).toBe(false)
 expect(statementApi.propose).not.toHaveBeenCalled()
})

it('submits ontology relation candidates as ENTITY values with unknown validity', async () => {
 vi.mocked(sourceApi.evidence).mockResolvedValue({ id: 'ev' } as Evidence)
 const relation = candidateContent({ subjectId: 'a', predicate: { kind: 'RELATION', key: 'feeds', valueType: '', fixedUnit: null }, value: '', targetEntityId: 'b', validityKind: 'UNKNOWN', validFrom: '', validTo: '' })
 await submitEvidenceCandidate(run, 'g', 's', range, relation)
 expect(statementApi.propose).toHaveBeenCalledWith('w', 'g', expect.objectContaining({ predicateKind: 'RELATION', valueType: 'ENTITY', targetEntityId: 'b', value: null, validityKind: 'UNKNOWN', validFrom: null, validTo: null, evidenceIds: ['ev'] }), run.signal)
})
