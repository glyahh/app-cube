export function handle(path) {
  if (path === '/health') {
    return { status: 200, body: { ok: true } };
  }
  return { status: 404, body: { error: 'not found' } };
}
