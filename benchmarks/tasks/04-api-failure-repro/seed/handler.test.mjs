import test from 'node:test';
import assert from 'node:assert/strict';
import { handle } from './handler.mjs';

test('health returns 200 with ok body', () => {
  const response = handle('/health');
  assert.equal(response.status, 200);
  assert.deepEqual(response.body, { ok: true });
});

test('unknown path stays 404', () => {
  assert.equal(handle('/missing').status, 404);
});
