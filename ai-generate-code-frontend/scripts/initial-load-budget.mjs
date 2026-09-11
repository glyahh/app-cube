const ENTRY_GZIP_BUDGET = 500 * 1024

const unrelatedAssetRules = [
  ['Three.js', /(?:^|[/_.-])three(?:[/_.-]|$)/i],
  ['non-chat route', /(?:Admin|LoopMarket|MyLoop|LoopCreate|LoopEdit|UserLogin|MainIndex)/i],
  ['login image', /(?:login|image-DIeDV9Vw).*\.(?:png|jpe?g|webp)(?:$|\?)/i],
]

export function evaluateInitialLoad(metrics) {
  const warnings = []

  if (metrics.entryGzipBytes >= ENTRY_GZIP_BUDGET) {
    warnings.push(`entry gzip is ${metrics.entryGzipBytes} bytes (budget: < ${ENTRY_GZIP_BUDGET})`)
  }
  if (metrics.loginRequestCount > 1) {
    warnings.push(`login endpoint was requested ${metrics.loginRequestCount} times (budget: <= 1)`)
  }
  if (metrics.testRequestCount > 0) {
    warnings.push(`test endpoint was requested ${metrics.testRequestCount} times (budget: 0)`)
  }

  for (const [label, pattern] of unrelatedAssetRules) {
    const match = metrics.loadedUrls.find((url) => pattern.test(url))
    if (match) warnings.push(`${label} asset loaded on chat entry: ${match}`)
  }

  return { passed: warnings.length === 0, warnings }
}

