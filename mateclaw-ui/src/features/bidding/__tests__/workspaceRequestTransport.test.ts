// @vitest-environment node
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest'
import { http } from '@/api'
import { workspaceRequest } from '@/api/workspaceRequest'
import { biddingApi } from '@/features/bidding/api/biddingApi'

import { startWorkspaceLoopback } from '../../../../test/support/workspaceLoopback.mjs'
import type { WorkspaceLoopback } from '../../../../test/support/workspaceLoopback.mjs'

// Axios chooses browser FormData header rules when these globals exist at import time.
// Keep that caller contract while using Node's actual fetch and byte classes.
vi.hoisted(() => {
  vi.stubGlobal('window', { location: { href: 'http://127.0.0.1/' } })
  vi.stubGlobal('document', { cookie: '' })
})

let loopback: WorkspaceLoopback
const originalBase = http.defaults.baseURL
const originalAdapter = http.defaults.adapter
beforeAll(async () => {
  const storage = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    clear: () => storage.clear(),
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
  } satisfies Pick<Storage, 'clear' | 'getItem' | 'setItem'>)
  loopback = await startWorkspaceLoopback()
})
beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('mc-workspace-id', 'switched')
  loopback.reset()
  http.defaults.baseURL = loopback.baseURL
  http.defaults.adapter = 'fetch'
})
afterEach(() => {
  http.defaults.baseURL = originalBase
  http.defaults.adapter = originalAdapter
})
afterAll(async () => {
  try {
    await loopback.close()
  } finally {
    vi.unstubAllGlobals()
  }
})

it('sends a real multipart boundary, exact fields and captured scope to loopback', async () => {
  expect(
    await biddingApi.upload(
      'captured',
      'p1',
      new File(['exact file bytes'], 'tender.txt'),
      'TENDER',
      'op1',
      'previous',
    ),
  ).toEqual({ sourceId: 'source-1' })
  expect(loopback.received?.scope).toBe('captured')
  const boundary = loopback.received?.contentType?.match(
    /^multipart\/form-data; boundary=(.+)$/,
  )?.[1]
  expect(boundary).toBeTruthy()
  expect(loopback.received?.body).toContain(`--${boundary}`)
  expect(loopback.received?.body).toContain('name="file"; filename="tender.txt"')
  expect(loopback.received?.body).toContain('exact file bytes')
  for (const [name, value] of [
    ['sourceKind', 'TENDER'],
    ['operationId', 'op1'],
    ['supersedesRef', 'previous'],
  ]) {
    expect(loopback.received?.body).toContain(`name="${name}"\r\n\r\n${value}`)
  }
})

it('receives exact Blob and ArrayBuffer bytes through real transport', async () => {
  const blob = await workspaceRequest<Blob>('captured', { url: '/bytes', responseType: 'blob' })
  expect(Array.from(new Uint8Array(await blob.arrayBuffer()))).toEqual([0, 1, 127, 128, 255])
  const buffer = await workspaceRequest<ArrayBuffer>('captured', {
    url: '/bytes',
    responseType: 'arraybuffer',
  })
  expect(Array.from(new Uint8Array(buffer))).toEqual([0, 1, 127, 128, 255])
  expect(loopback.received?.scope).toBe('captured')
})

it('cancels an in-flight real request after the loopback server receives it', async () => {
  const errors = vi.spyOn(console, 'error')
  try {
    const disconnected = loopback.waitForSlowDisconnect()
    const controller = new AbortController()
    const started = loopback.waitForSlowRequest()
    const promise = workspaceRequest('captured', { url: '/slow', signal: controller.signal })
    const rejected = expect(promise).rejects.toThrow(/cancel/i)
    await started
    controller.abort()
    await rejected
    await disconnected
    await new Promise<void>((resolve) => setTimeout(resolve, 0))
    expect(errors).not.toHaveBeenCalled()
  } finally {
    errors.mockRestore()
  }
})

it('preserves a real non-cancellation HTTP failure instead of swallowing it', async () => {
  await expect(workspaceRequest('captured', { url: '/conflict' })).rejects.toMatchObject({
    message: 'Retry conflict',
    response: { status: 409 },
  })
  expect(loopback.received?.scope).toBe('captured')
})

it('rejects a real unexpected server disconnect instead of treating it as cancellation success', async () => {
  await expect(workspaceRequest('captured', { url: '/disconnect' })).rejects.toThrow(
    'Network Error',
  )
  expect(loopback.received?.scope).toBe('captured')
})
