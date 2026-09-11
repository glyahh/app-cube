import assert from 'node:assert/strict'
import test from 'node:test'

import { evaluateInitialLoad } from '../initial-load-budget.mjs'

test('reports every exceeded budget', () => {
  const result = evaluateInitialLoad({
    entryGzipBytes: 600 * 1024,
    loginRequestCount: 2,
    testRequestCount: 1,
    loadedUrls: ['/assets/MainIndex.js', '/assets/three.js', '/assets/login-image.png'],
  })

  assert.equal(result.passed, false)
  assert.equal(result.warnings.length, 6)
  assert.match(result.warnings.join('\n'), /entry gzip/)
  assert.match(result.warnings.join('\n'), /login endpoint/)
  assert.match(result.warnings.join('\n'), /test endpoint/)
  assert.match(result.warnings.join('\n'), /Three\.js/)
  assert.match(result.warnings.join('\n'), /non-chat route/)
  assert.match(result.warnings.join('\n'), /login image/)
})

test('accepts a split chat entry with one login probe and no unrelated assets', () => {
  const result = evaluateInitialLoad({
    entryGzipBytes: 320 * 1024,
    loginRequestCount: 1,
    testRequestCount: 0,
    loadedUrls: ['/assets/index.js', '/assets/AppChatView.js'],
  })

  assert.deepEqual(result, { passed: true, warnings: [] })
})
