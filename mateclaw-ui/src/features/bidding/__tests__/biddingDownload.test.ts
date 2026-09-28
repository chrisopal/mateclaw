import { expect, it, vi } from 'vitest'
import { http } from '@/api'
import { biddingApi } from '../api/biddingApi'

vi.mock('@/api', () => ({ http: { get: vi.fn(), defaults: { transformRequest: [] } } }))

it('returns the binary body from the shared HTTP interceptor for artifact downloads', async () => {
  const bytes = new Blob(['docx-bytes'], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })
  vi.mocked(http.get).mockResolvedValue(bytes)

  const downloaded = await biddingApi.download('ws-1', 'project-1', 'artifact-1', 'preview')

  expect(downloaded).toBe(bytes)
  expect(http.get).toHaveBeenCalledWith(
    '/bidding/projects/project-1/artifacts/artifact-1/content',
    expect.objectContaining({ responseType: 'blob', params: { mode: 'preview' } }),
  )
})
