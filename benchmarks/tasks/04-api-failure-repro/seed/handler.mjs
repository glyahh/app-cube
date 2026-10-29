export function handle(path) {
  if (path === '/health') {
    return { status: 500, body: { ok: false } };
  }
  return { status: 404, body: { error: 'not found' } };
}
