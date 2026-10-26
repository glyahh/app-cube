import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useAgentSession } from '../useAgentSession'
import * as api from '../api'

vi.mock('../api', () => ({
  modelConfig: vi.fn(),
  projectPending: vi.fn(),
  projectLatestReview: vi.fn(),
  active: vi.fn(),
  settings: vi.fn(),
  permission: vi.fn(),
}))

describe('review/permission semantics in live session mapping', () => {
  it.each([false, true])('maps import=%s to actual candidate write semantics', async (imported) => {
    vi.mocked(api.modelConfig).mockResolvedValue({
      mode: imported ? null : 'MANUAL',
      models: [],
      preference: {},
      sandboxMode: 'WORKSPACE_WRITE',
    } as unknown as api.ModelConfig)
    const changes = [
      {
        path: 'a.txt',
        change: 'MODIFIED',
        before: 'old',
        after: 'new',
        beforeText: 'old',
        afterText: 'new',
        truncated: true,
      },
    ]
    vi.mocked(api.projectLatestReview).mockResolvedValue({
      reviewId: 'review-1',
      revision: '1',
      reviewHash: 'h',
      risk: 'HIGH',
      status: 'WAITING',
      body: { changes },
    } as unknown as Awaited<ReturnType<typeof api.projectLatestReview>>)
    const permission = {
      id: 'p',
      action: 'execute',
      display: { title: '执行命令', object: 'test', reason: '' },
    } as api.ToolPermission
    vi.mocked(api.projectPending).mockResolvedValue(permission)
    vi.mocked(api.active).mockResolvedValue({
      state: 'IDLE',
      permissionId: 'p',
      review: {
        editId: 'r',
        revision: '1',
        hash: 'h',
        risk: 'HIGH',
        status: 'WAITING',
        body: { changes },
      },
    })
    vi.mocked(api.permission).mockResolvedValue(permission)
    vi.mocked(api.settings).mockResolvedValue({
      mode: 'MANUAL',
      profileId: '',
      effort: null,
      policyRevision: '1',
    })
    const session = useAgentSession({ appId: ref('1'), streaming: ref(false) })
    try {
      await session.load()
      expect(session.error.value).toBe('')
      expect(session.candidate.value?.applicationMode).toBe(imported ? 'STAGED' : 'APPLIED')
      expect(session.candidate.value?.rollbackAvailable).toBe(false)
      expect(session.toolRequest.value?.allowSimilar).toBe(imported)
    } finally {
      session.dispose()
    }
  })
})
