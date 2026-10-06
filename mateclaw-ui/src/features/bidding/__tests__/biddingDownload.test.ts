import { expect, it, vi } from 'vitest'
import { http } from '@/api'
import { biddingApi } from '../api/biddingApi'
import type { InternalAxiosRequestConfig } from 'axios'

it('returns the binary body from the shared HTTP interceptor for artifact downloads', async () => {
  const bytes = new Blob(['docx-bytes'], {
    type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  })
  const previous = http.defaults.adapter
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
    data: bytes,
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  }))
  http.defaults.adapter = adapter
  try {
    const downloaded = await biddingApi.download('ws-1', 'project-1', 'artifact-1', 'preview')
    expect(downloaded).toBe(bytes)
    expect(adapter).toHaveBeenCalledWith(
      expect.objectContaining({
        url: '/bidding/projects/project-1/artifacts/artifact-1/content',
        responseType: 'blob',
        params: { mode: 'preview' },
      }),
    )
    expect(adapter.mock.calls[0]![0].headers.get('X-Workspace-Id')).toBe('ws-1')
  } finally {
    http.defaults.adapter = previous
  }
})
