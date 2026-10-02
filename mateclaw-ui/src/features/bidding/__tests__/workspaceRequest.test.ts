import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http } from '@/api'
import { AxiosError } from 'axios'
import { biddingApi } from '../api/biddingApi'
const originalAdapter = http.defaults.adapter
beforeEach(() => localStorage.clear())
afterEach(() => {
  http.defaults.adapter = originalAdapter
})

describe('bidding request contract', () => {
  it('uploads all multipart fields without a feature-specific serializer', async () => {
    http.defaults.adapter = async (config) => {
      expect(config.headers.get('X-Workspace-Id')).toBe('captured')
      expect(config.data).toBeInstanceOf(FormData)
      const body = config.data as FormData
      expect(body.get('sourceKind')).toBe('TENDER')
      expect(body.get('operationId')).toBe('op-1')
      expect(body.get('supersedesRef')).toBe('source:1')
      expect((body.get('file') as File).name).toBe('tender.txt')
      expect(await (body.get('file') as File).text()).toBe('exact source')
      expect(config.url).toBe('/bidding/projects/project%2F1/sources')
      return {
        data: { code: 200, data: { sourceId: '9223372036854775800' } },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      }
    }
    localStorage.setItem('mc-workspace-id', 'other')
    expect(
      await biddingApi.upload(
        'captured',
        'project/1',
        new File(['exact source'], 'tender.txt'),
        'TENDER',
        'op-1',
        'source:1',
      ),
    ).toEqual({ sourceId: '9223372036854775800' })
  })

  it('keeps binary bytes, encoded paths, mode and signal across domain downloads', async () => {
    const blob = new Blob(['unchanged bytes'])
    const signal = new AbortController().signal
    const paths: string[] = []
    http.defaults.adapter = async (config) => {
      expect(config.headers.get('X-Workspace-Id')).toBe('captured')
      expect(config.responseType).toBe('blob')
      paths.push(config.url!)
      if (config.url?.startsWith('/bidding/')) expect(config.signal).toBe(signal)
      if (config.url?.includes('/artifacts/')) expect(config.params).toEqual({ mode: 'formal' })
      return { data: blob, status: 200, statusText: 'OK', headers: {}, config }
    }
    localStorage.setItem('mc-workspace-id', 'other')
    expect(await biddingApi.content('captured', 'p/1', 's/1', 3, signal)).toBe(blob)
    expect(await biddingApi.download('captured', 'p/1', 'a/1', 'formal', signal)).toBe(blob)
    expect(paths).toEqual([
      '/bidding/projects/p%2F1/sources/s%2F1/versions/3/content',
      '/bidding/projects/p%2F1/artifacts/a%2F1/content',
    ])
    expect(await blob.text()).toBe('unchanged bytes')
  })
})

it('preserves 409 response details through the domain wrapper', async () => {
  http.defaults.adapter = async (config) => {
    throw new AxiosError('conflict', 'ERR_BAD_REQUEST', config, undefined, {
      data: { code: 409, msg: 'revision changed' },
      status: 409,
      statusText: 'Conflict',
      headers: {},
      config,
    })
  }
  await expect(biddingApi.capabilities('ws')).rejects.toMatchObject({
    message: 'revision changed',
    response: { status: 409 },
  })
})
