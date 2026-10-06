import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/api'
import { AxiosHeaders, CanceledError } from 'axios'
import type { AxiosRequestTransformer } from 'axios'
import { workspaceRequest } from '@/api/workspaceRequest'
const originalAdapter = http.defaults.adapter
beforeEach(() => localStorage.clear())
afterEach(() => {
  http.defaults.adapter = originalAdapter
})

describe('public transport and domain adapters', () => {
  it('keeps JSON serialization, headers, response transforms and caller configuration', async () => {
    const headers = new AxiosHeaders({ 'X-Workspace-Id': false, 'X-Custom': 'original' })
    const transformResponse = vi.fn((data: unknown) => data)
    const config = {
      url: '/contract',
      method: 'POST',
      data: { id: '9223372036854775800' },
      headers,
      transformResponse,
    }
    http.defaults.adapter = async (request) => {
      expect(JSON.parse(request.data)).toEqual(config.data)
      expect(request.headers.get('X-Workspace-Id')).toBe('captured')
      expect(request.headers.get('X-Custom')).toBe('original')
      return {
        data: { code: 200, data: 'accepted' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config: request,
      }
    }
    expect(await workspaceRequest('captured', config)).toEqual({ code: 200, data: 'accepted' })
    expect(headers.get('X-Workspace-Id')).toBe(false)
    expect(config.data).toEqual({ id: '9223372036854775800' })
    expect(transformResponse).toHaveBeenCalledTimes(1)
  })

  it('preserves every transform in order and does not mutate caller arrays', async () => {
    const transforms: AxiosRequestTransformer[] = [
      (data) => `${data}:one`,
      (data, headers) => {
        headers.set('X-Workspace-Id', false)
        return `${data}:two`
      },
    ]
    http.defaults.adapter = async (config) => {
      expect(config.data).toBe('body:one:two')
      expect(config.headers.get('X-Workspace-Id')).toBe('ws')
      return { data: 'raw', status: 200, statusText: 'OK', headers: {}, config }
    }
    expect(
      await workspaceRequest('ws', {
        url: '/contract',
        method: 'POST',
        data: 'body',
        transformRequest: transforms,
      }),
    ).toBe('raw')
    expect(transforms).toHaveLength(2)
  })

  it('prefers the separate signal while retaining an in-flight cancellation', async () => {
    const controller = new AbortController()
    const stale = new AbortController()
    stale.abort()
    let entered!: () => void
    const started = new Promise<void>((resolve) => {
      entered = resolve
    })
    http.defaults.adapter = (config) =>
      new Promise((_resolve, reject) => {
        expect(config.signal).toBe(controller.signal)
        config.signal?.addEventListener?.('abort', () =>
          reject(new CanceledError('cancelled', undefined, config)),
        )
        entered()
      })
    const promise = workspaceRequest(
      'ws',
      { url: '/contract', signal: stale.signal },
      controller.signal,
    )
    const assertion = expect(promise).rejects.toThrow('cancelled')
    await started
    controller.abort()
    await assertion
  })
})
