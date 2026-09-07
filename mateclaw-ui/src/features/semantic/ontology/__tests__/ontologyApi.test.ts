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
