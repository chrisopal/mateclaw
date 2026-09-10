import { expect, it } from 'vitest'
import { http } from '@/api'
import { sourceChangeApi } from '../../api/sourceChangeApi'

it('sends the graph CAS version and operation identity when scanning source changes', async () => {
  const previous = http.defaults.adapter
  let requestBody: unknown
  http.defaults.adapter = async (config) => {
    requestBody = config.data
    return {
      data: { code: 200, data: { runId: 'run-1', graphId: 'graph-1', graphMutationVersion: 8, changes: [] } },
      status: 200, statusText: 'OK', headers: {}, config,
    }
  }
  try {
    await sourceChangeApi.scan('workspace-1', 'graph-1', { operationId: 'op-scan', expectedGraphVersion: 7 })
    expect(String(requestBody)).toContain('op-scan')
    expect(String(requestBody)).toContain('expectedGraphVersion')
    expect(String(requestBody)).toContain('7')
  } finally { http.defaults.adapter = previous }
})

it('uses the observed digest and graph CAS version for a human review decision', async () => {
  const previous = http.defaults.adapter
  let requestUrl = '', requestBody: unknown
  http.defaults.adapter = async (config) => {
    requestUrl = String(config.url)
    requestBody = config.data
    return {
      data: { code: 200, data: { id: 'item-1', changeId: 'change-1', newDigest: 'sha256:new', reviewState: 'REVIEWED' } },
      status: 200, statusText: 'OK', headers: {}, config,
    }
  }
  try {
    await sourceChangeApi.decide('workspace-1', 'graph-1', 'item-1', {
      operationId: 'op-decision', expectedObservedDigest: 'sha256:new', expectedGraphVersion: 8,
      decision: 'KEEP_HISTORICAL', reason: 'Preserve the historical record while the source is reviewed.',
    })
    expect(requestUrl).toBe('/semantic/graphs/graph-1/source-changes/items/item-1/decision')
    expect(String(requestBody)).toContain('op-decision')
    expect(String(requestBody)).toContain('sha256:new')
    expect(String(requestBody)).toContain('expectedGraphVersion')
    expect(String(requestBody)).toContain('KEEP_HISTORICAL')
  } finally { http.defaults.adapter = previous }
})

it('loads pending items and a selected change through graph scoped routes', async () => {
  const previous = http.defaults.adapter
  const urls: string[] = []
  http.defaults.adapter = async (config) => {
    urls.push(String(config.url))
    return { data: { code: 200, data: [] }, status: 200, statusText: 'OK', headers: {}, config }
  }
  try {
    await sourceChangeApi.pending('workspace-1', 'graph-1', 50)
    await sourceChangeApi.items('workspace-1', 'graph-1', 'change-1')
    expect(urls).toEqual([
      '/semantic/graphs/graph-1/source-changes',
      '/semantic/graphs/graph-1/source-changes/change-1/items',
    ])
  } finally { http.defaults.adapter = previous }
})
