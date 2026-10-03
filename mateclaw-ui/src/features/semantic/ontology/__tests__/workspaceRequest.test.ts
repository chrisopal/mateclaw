import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/api'
import { AxiosError } from 'axios'
import type { AxiosRequestTransformer, InternalAxiosRequestConfig } from 'axios'
import { semanticRequest } from '../../api/ontologyApi'
const originalAdapter = http.defaults.adapter
beforeEach(() => localStorage.clear())
afterEach(() => {
  http.defaults.adapter = originalAdapter
})

describe('captured Workspace request contract', () => {
  it.each(['function', 'array'] as const)(
    'preserves caller %s transforms and pins scope after them',
    async (kind) => {
      const transform: AxiosRequestTransformer = vi.fn(function (
        this: InternalAxiosRequestConfig,
        data,
        headers,
      ) {
        expect(this.timeout).toBe(4321)
        headers.set('X-Workspace-Id', 'forged')
        headers.set('X-Custom', 'kept')
        return `custom:${data.value}`
      })
      http.defaults.adapter = async (config) => {
        expect(config.data).toBe('custom:payload')
        expect(config.headers.get('X-Workspace-Id')).toBe('9223372036854775800')
        expect(config.headers.get('X-Custom')).toBe('kept')
        expect(config.params).toEqual({ revisionId: 'r/1' })
        return {
          data: { code: 200, data: 'accepted' },
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        }
      }
      const promise = semanticRequest('9223372036854775800', {
        url: '/contract',
        method: 'POST',
        data: { value: 'payload' },
        timeout: 4321,
        params: { revisionId: 'r/1' },
        transformRequest: kind === 'function' ? transform : [transform],
      })
      localStorage.setItem('mc-workspace-id', 'changed-before-dispatch')
      expect(await promise).toBe('accepted')
      expect(transform).toHaveBeenCalledTimes(1)
    },
  )

  it('preserves a pre-cancelled config signal when no separate signal is supplied', async () => {
    const controller = new AbortController()
    controller.abort()
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
      data: { code: 200, data: 'accepted' },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }))
    http.defaults.adapter = adapter
    await expect(
      semanticRequest('ws', { url: '/contract', signal: controller.signal }),
    ).rejects.toMatchObject({ code: 'REQUEST_FAILED' })
    expect(adapter).not.toHaveBeenCalled()
  })

  it('respects an explicit empty transform list', async () => {
    const payload = { value: 'raw' }
    http.defaults.adapter = async (config) => {
      expect(config.data).toEqual(payload)
      expect(config.headers.get('X-Workspace-Id')).toBe('ws')
      return {
        data: { code: 200, data: 'accepted' },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    expect(
      await semanticRequest('ws', {
        url: '/contract',
        method: 'POST',
        data: payload,
        transformRequest: [],
      }),
    ).toBe('accepted')
  })
})

describe('semantic conflict contract', () => {
  it('retains 409 response details and semantic field errors', async () => {
    http.defaults.adapter = async (config) => {
      throw new AxiosError('conflict', 'ERR_BAD_REQUEST', config, undefined, {
        data: {
          code: 409,
          msg: 'revision changed',
          data: {
            code: 'STALE_REVISION',
            fieldErrors: [{ path: 'revision', message: 'stale' }],
            traceId: 'trace-1',
          },
        },
        status: 409,
        statusText: 'Conflict',
        headers: {},
        config,
      })
    }
    await expect(semanticRequest('ws', { url: '/contract' })).rejects.toMatchObject({
      status: 409,
      code: 'STALE_REVISION',
      message: 'revision changed',
      fieldErrors: [{ path: 'revision', message: 'stale' }],
      traceId: 'trace-1',
    })
  })
})
