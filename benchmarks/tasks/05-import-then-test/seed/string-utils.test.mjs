import test from 'node:test';
import assert from 'node:assert/strict';
import { slug } from './string-utils.mjs';

test('slug joins words with a dash', () => {
  assert.equal(slug('Hello World'), 'hello-world');
  assert.equal(slug('Already-slugged'), 'already-slugged');
});
