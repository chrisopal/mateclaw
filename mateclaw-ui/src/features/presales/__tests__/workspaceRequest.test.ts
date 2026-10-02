import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http } from '@/api'
import { AxiosError } from 'axios'
import { presalesApi } from '../api/presalesApi'
const originalAdapter = http.defaults.adapter
beforeEach(() => localStorage.clear())
afterEach(() => {
  http.defaults.adapter = originalAdapter
})

describe('presales request contract', () => {
  it('retains raw Blob bytes, captured scope and encoded file paths', async () => {
    const blob = new Blob(['unchanged bytes'])
    http.defaults.adapter = async (config) => {
      expect(config.headers.get('X-Workspace-Id')).toBe('captured')
      expect(config.responseType).toBe('blob')
      expect(config.url).toBe(
        '/presales/projects/p%2F1/releases/v%2F1/files/%E6%96%B9%E6%A1%88.docx',
      )
      return { data: blob, status: 200, statusText: 'OK', headers: {}, config }
    }
    localStorage.setItem('mc-workspace-id', 'other')
    expect(await presalesApi.file('captured', 'p/1', 'v/1', '方案.docx', 'files')).toBe(blob)
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
  await expect(presalesApi.capabilities('ws')).rejects.toMatchObject({
    message: 'revision changed',
    response: { status: 409 },
  })
})
