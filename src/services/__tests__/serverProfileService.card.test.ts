import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const h = vi.hoisted(() => ({ reads: [] as Array<Record<string, string>>, row: null as any, fail: false }))

vi.mock('@/supabase', () => ({
  supabase: {
    from: () => {
      const filters: Record<string, string> = {}
      const q: any = {
        select: () => q,
        eq: (k: string, v: string) => { filters[k] = v; return q },
        maybeSingle: async () => {
          h.reads.push({ ...filters })
          return h.fail ? { data: null, error: new Error('down') } : { data: h.row, error: null }
        },
      }
      return q
    },
    rpc: vi.fn(),
  },
}))

import { userDataService } from '@/services/userDataService'
import { loadServerProfileForCard } from '@/services/serverProfileService'

describe('loadServerProfileForCard', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    h.reads = []
    h.fail = false
    h.row = { user_id: 'u1', server_id: 'S', nickname: 'Ally', avatar_url: null, banner_url: 'b.png', bio: 'hi', pronouns: 'she/her' }
  })
  afterEach(() => { vi.useRealTimers() })

  it('reads the one member row and stores all five fields', async () => {
    loadServerProfileForCard('S', 'u1')
    await vi.advanceTimersByTimeAsync(0)
    expect(h.reads).toEqual([{ server_id: 'S', user_id: 'u1' }])
    expect(userDataService.getMemberServerFields('S', 'u1')).toEqual({
      nickname: 'Ally', avatar_url: null, banner_url: 'b.png', bio: 'hi', pronouns: 'she/her',
    })
  })

  it('does not read the same member again within five minutes, but does after', async () => {
    loadServerProfileForCard('S', 'u2')
    loadServerProfileForCard('S', 'u2')
    await vi.advanceTimersByTimeAsync(0)
    expect(h.reads).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(5 * 60 * 1000 + 1)
    loadServerProfileForCard('S', 'u2')
    await vi.advanceTimersByTimeAsync(0)
    expect(h.reads).toHaveLength(2)
  })

  it('a failed read is tried again on the next open', async () => {
    h.fail = true
    loadServerProfileForCard('S', 'u3')
    await vi.advanceTimersByTimeAsync(0)
    h.fail = false
    loadServerProfileForCard('S', 'u3')
    await vi.advanceTimersByTimeAsync(0)
    expect(h.reads).toHaveLength(2)
  })
})
