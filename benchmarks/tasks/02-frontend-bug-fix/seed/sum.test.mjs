import test from 'node:test';
import assert from 'node:assert/strict';
import { sum } from './sum.mjs';

test('adds two numbers', () => {
  assert.equal(sum(2, 3), 5);
  assert.equal(sum(-1, 1), 0);
});
