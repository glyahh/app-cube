import test from 'node:test';
import assert from 'node:assert/strict';
import { message } from './use.mjs';

test('uses the new greeting contract', () => {
  assert.equal(message('bob'), 'hello, bob');
});
