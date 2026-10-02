import { afterAll, afterEach, beforeAll, beforeEach, expect, it } from 'vitest'
import { http } from '@/api'
import { workspaceRequest } from '@/api/workspaceRequest'
import { biddingApi } from '@/features/bidding/api/biddingApi'

import { startWorkspaceLoopback } from '../../../../test/support/workspaceLoopback.mjs'
import type { WorkspaceLoopback } from '../../../../test/support/workspaceLoopback.mjs'

let loopback: WorkspaceLoopback
const originalBase = http.defaults.baseURL
const originalAdapter = http.defaults.adapter
beforeAll(async () => {
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
  await loopback.close()
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
  const controller = new AbortController()
  const started = loopback.waitForSlowRequest()
  const promise = workspaceRequest('captured', { url: '/slow', signal: controller.signal })
  const rejected = expect(promise).rejects.toThrow(/cancel/i)
  await started
  controller.abort()
  await rejected
})
