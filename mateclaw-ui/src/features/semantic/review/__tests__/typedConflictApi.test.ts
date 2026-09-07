import { expect, it } from 'vitest'
import { http } from '@/api'
import { statementApi } from '../../api/statementApi'
import type { Conflict } from '../../api/workbenchTypes'
it('keeps typed expected members and proposal winner IDs in the HTTP resolve payload', async () => {
 const conflict: Conflict = { id: 'c', kind: 'SINGLE_VALUE_DISAGREEMENT', status: 'OPEN', left: { statementId: 's', revision: 1 }, right: { kind: 'CHANGE_PROPOSAL', statementId: 'proposal', revision: 2 }, resolution: '' }
 const previous = http.defaults.adapter
 http.defaults.adapter = async config => {
   expect(config.url).toContain('/conflicts/c/resolve')
   const payload = JSON.parse(config.data)
   expect(payload.expectedMembers).toEqual([conflict.left, conflict.right])
   expect(payload.winnerStatementId).toBe('proposal')
   return { data: { code: 200, data: conflict }, status: 200, statusText: 'OK', headers: {}, config }
 }
 try { await statementApi.resolve('w', 'g', conflict, 'proposal', 'reviewed') } finally { http.defaults.adapter = previous }
})
