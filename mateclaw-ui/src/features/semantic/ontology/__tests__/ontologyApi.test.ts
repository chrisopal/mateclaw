import { beforeEach, it, expect, vi } from 'vitest'
import { http } from '@/api'
import { ontologyApi } from '../../api/ontologyApi'
import { useSemanticAvailability } from '../../shared/useSemanticAvailability'
beforeEach(() => {
  localStorage.clear()
})
it('pins captured workspace after global interceptors and unwraps R.data', async () => {
  localStorage.setItem('mc-workspace-id', 'new-workspace')
  const previous = http.defaults.adapter
  http.defaults.adapter = async (config) => {
    expect(config.headers.get('X-Workspace-Id')).toBe('9223372036854775800')
    expect(config.url).toContain('9223372036854775799')
    return {
      data: { code: 200, data: { id: '9223372036854775799' } },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
  try {
    expect(await ontologyApi.get('9223372036854775800', '9223372036854775799')).toEqual({
      id: '9223372036854775799',
    })
  } finally {
    http.defaults.adapter = previous
  }
})
it('does not read status before authentication and defaults disabled on flag false or request failure', async () => {
  const status = vi.spyOn(ontologyApi, 'status'),
    availability = useSemanticAvailability()
  expect(await availability.refresh()).toBe(false)
  expect(status).not.toHaveBeenCalled()
  localStorage.setItem('token', 'test')
  status.mockResolvedValueOnce({ enabled: false })
  expect(await availability.refresh()).toBe(false)
  status.mockRejectedValueOnce(new Error('offline'))
  expect(await availability.refresh()).toBe(false)
  status.mockResolvedValueOnce({ enabled: true })
  expect(await availability.refresh()).toBe(true)
  status.mockRestore()
})

it('uses the M4 package and impact endpoints with string IDs and scoped requests', async () => {
  const previous = http.defaults.adapter
  const packageBody = {
    packageFormatVersion: 1 as const,
    name: 'Quality',
    description: '',
    definition: {
      definitionFormatVersion: 2,
      types: [{ key: 'Issue', label: 'Issue', description: '', aliases: ['偏差'], deprecated: false }],
      properties: [],
      relations: [],
    },
  }
  let lastConfig: Record<string, unknown> | undefined
  http.defaults.adapter = async (config) => {
    lastConfig = config as unknown as Record<string, unknown>
    const url = String(config.url)
    const data = url.includes('/preview')
      ? { digest: 'sha256:test', name: 'Quality', typeCount: 1, predicateCount: 0, violations: [] }
      : url.includes('/impact')
        ? { graphId: '9223372036854775801', sourceRevisionId: 'r1', definitionDigest: 'd', graphVersion: 4, scannedAt: '2026-09-08T00:00:00Z', definitionChangeClass: 'ANNOTATION', dataConformance: 'CONFORMS', scannedEntities: 0, scannedStatements: 0, scannedProposals: 0, affectedEntities: 0, affectedStatements: 0, affectedProposals: 0, detailsTruncated: false, diagnostics: [] }
        : packageBody
    return { data: { code: 200, data }, status: 200, statusText: 'OK', headers: {}, config }
  }
  try {
    expect(await ontologyApi.previewPackage('ws-1', packageBody)).toMatchObject({ digest: 'sha256:test' })
    expect(lastConfig?.url).toBe('/semantic/ontology-packages/preview')
    expect(await ontologyApi.impact('ws-1', '9223372036854775800', { graphId: '9223372036854775801', expectedDraftVersion: 7 })).toMatchObject({ graphId: '9223372036854775801' })
    expect(lastConfig?.url).toBe('/semantic/ontologies/9223372036854775800/impact')
    expect(await ontologyApi.packageForRevision('ws-1', '9223372036854775800', 'r-1')).toEqual(packageBody)
  } finally {
    http.defaults.adapter = previous
  }
})

it('shares concurrent availability checks without falsely denying navigation', async () => {
  localStorage.setItem('token', 'test')
  let resolve!: (value: { enabled: boolean }) => void
  const status = vi.spyOn(ontologyApi, 'status').mockImplementation(() => new Promise((done) => { resolve = done }))
  const one = useSemanticAvailability().refresh()
  const two = useSemanticAvailability().refresh()
  resolve({ enabled: true })
  expect(await Promise.all([one, two])).toEqual([true, true])
  expect(status).toHaveBeenCalledTimes(1)
  status.mockRestore()
})
