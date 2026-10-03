import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

test('real workspace transport completes without late socket diagnostics', () => {
  const result = spawnSync(
    process.execPath,
    [
      'node_modules/vitest/vitest.mjs',
      'run',
      'src/features/bidding/__tests__/workspaceRequestTransport.test.ts',
      '--reporter=json',
    ],
    { cwd: fileURLToPath(new URL('..', import.meta.url)), encoding: 'utf8', timeout: 20_000 },
  )
  assert.ifError(result.error)
  assert.equal(result.signal, null, result.stderr)
  assert.equal(result.status, 0, result.stderr)
  const report = JSON.parse(result.stdout)
  assert.equal(report.success, true)
  assert.equal(report.numFailedTests, 0)
  assert.equal(report.numPendingTests, 0)
  const assertions = report.testResults.flatMap((file) => file.assertionResults)
  for (const title of [
    'sends a real multipart boundary, exact fields and captured scope to loopback',
    'receives exact Blob and ArrayBuffer bytes through real transport',
    'cancels an in-flight real request after the loopback server receives it',
    'preserves a real non-cancellation HTTP failure instead of swallowing it',
    'rejects a real unexpected server disconnect instead of treating it as cancellation success',
  ]) {
    assert.equal(assertions.find((item) => item.title === title)?.status, 'passed', title)
  }
  assert.ok(report.numPassedTests >= 5)
  assert.doesNotMatch(result.stderr, /socket hang up|ECONNRESET/)
})
