import { describe, it, expect, beforeEach, vi } from 'vitest'

const h = vi.hoisted(() => ({
  rows: [] as any[],
  handlers: {} as Record<string, (p: any) => void>,
}))

vi.mock('@/supabase', () => ({
  SUPABASE_URL: 'http://db.test',
  SUPABASE_ANON_KEY: 'anon',
  supabase: {
    from: () => {
      const q: any = {
        select: () => q, eq: () => q, in: () => q,
        then: (resolve: any) => resolve({ data: h.rows, error: null }),
      }
      return q
    },
    rpc: vi.fn(async () => ({ data: null, error: null })),
    channel: () => {
      const ch: any = {
        on: (_t: string, opts: { event: string }, cb: (p: any) => void) => { h.handlers[opts.event] = cb; return ch },
        subscribe: () => ch,
      }
      return ch
    },
    removeChannel: vi.fn(async () => 'ok'),
    auth: {
      getSession: async () => ({ data: { session: null } }),
      onAuthStateChange: () => ({ data: { subscription: { unsubscribe: () => {} } } }),
    },
  },
}))

import { setActivePinia, createPinia } from 'pinia'
import { userDataService } from '@/services/userDataService'
import { useUserData } from '@/composables/useUserData'
import {
  getMemberDisplayName,
  getMemberAvatarUrl,
  getMemberBannerUrl,
  getMemberBio,
  getMemberPronouns,
  getMemberServerProfile,
} from '@/composables/useMemberProfile'

const U = 'user-1'
const S = 'server-S'

function seedUser() {
  ;(userDataService as any).users.set(U, {
    id: U, username: 'ally_acct', displayName: 'Account Name',
    avatarUrl: 'account.png', bannerUrl: 'account-banner.png', bio: 'account bio',
    pronouns: 'they/them',
    status: 0, isOnline: false, isMobile: false, createdAt: '', lastSeen: '',
    lastHeartbeat: '', isLocal: true, lastCacheUpdate: '', source: 'database',
  })
}

describe('useMemberProfile', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    ;(userDataService as any).users.clear()
    ;(userDataService as any).memberServerFields?.clear()
    seedUser()
  })

  it('resolver_is_per_call', () => {
    userDataService.setMemberServerProfile(S, U, { nickname: 'Ally' })
    expect(getMemberDisplayName(U, S).value).toBe('Ally')
    expect(getMemberDisplayName(U, null).value).toBe('Account Name')
    expect(getMemberDisplayName(U, 'other-server').value).toBe('Account Name')
  })

  it('falls back to the account avatar, banner, bio, pronouns when server fields are null', () => {
    userDataService.setMemberServerProfile(S, U, { nickname: 'Ally' })
    expect(getMemberAvatarUrl(U, S).value).toBe('account.png')
    expect(getMemberBannerUrl(U, S).value).toBe('account-banner.png')
    expect(getMemberBio(U, S).value).toBe('account bio')
    expect(getMemberPronouns(U, S).value).toBe('they/them')
  })

  it('uses server fields when set', () => {
    userDataService.setMemberServerProfile(S, U, {
      avatar_url: 'srv.png', banner_url: 'srv-b.png', bio: 'srv bio', pronouns: 'she/her',
    })
    expect(getMemberAvatarUrl(U, S).value).toBe('srv.png')
    expect(getMemberBannerUrl(U, S).value).toBe('srv-b.png')
    expect(getMemberBio(U, S).value).toBe('srv bio')
    expect(getMemberPronouns(U, S).value).toBe('she/her')
    expect(getMemberAvatarUrl(U, null).value).toBe('account.png')
  })

  it('getMemberServerProfile returns raw values or null', () => {
    expect(getMemberServerProfile(U, S).value).toBeNull()
    userDataService.setMemberServerProfile(S, U, { nickname: 'Ally', bio: 'b' })
    expect(getMemberServerProfile(U, S).value).toEqual({
      nickname: 'Ally', avatar_url: null, banner_url: null, bio: 'b', pronouns: null,
    })
    expect(getMemberServerProfile(U, null).value).toBeNull()
  })

  it('a realtime update event changes the resolved nickname', () => {
    useUserData() // binds the service listeners
    const name = getMemberDisplayName(U, S)
    expect(name.value).toBe('Account Name')
    ;(userDataService as any).handleServerMemberUpdate(S, { new: { user_id: U, nickname: 'Al' } })
    expect(name.value).toBe('Al')
    ;(userDataService as any).handleServerMemberUpdate(S, { new: { user_id: U, nickname: 'Ally' } })
    expect(name.value).toBe('Ally')
  })

  it('a delete event (leaving) falls back', async () => {
    useUserData()
    userDataService.setMemberServerProfile(S, U, { nickname: 'Ally' })
    const name = getMemberDisplayName(U, S)
    expect(name.value).toBe('Ally')
    await (userDataService as any).handleServerMemberLeave(S, { old: { user_id: U } })
    expect(name.value).toBe('Account Name')
    expect(userDataService.getMemberServerFields(S, U)).toBeNull()
  })

  it('getUserPronouns returns profiles.pronouns', () => {
    expect(useUserData().getUserPronouns(U).value).toBe('they/them')
    expect(useUserData().getUserPronouns('nobody').value).toBeNull()
  })

  it('a partial update keeps the other fields', () => {
    userDataService.setMemberServerProfile(S, U, { nickname: 'Ally', bio: 'b', pronouns: 'she/her' })
    userDataService.setMemberServerProfile(S, U, { nickname: 'Al' })
    expect(userDataService.getMemberServerFields(S, U)).toEqual({
      nickname: 'Al', avatar_url: null, banner_url: null, bio: 'b', pronouns: 'she/her',
    })
    userDataService.setMemberServerProfile(S, U, { bio: null })
    expect(userDataService.getMemberServerFields(S, U)?.bio).toBeNull()
    expect(userDataService.getMemberServerFields(S, U)?.nickname).toBe('Al')
  })

  describe('member directory rows', () => {
    const row = (i: number, extra: Record<string, unknown> = {}) => ({
      id: `u${i}`, username: `user${i}`, online: false, presence_status: 0, is_mobile: false, role_ids: [], ...extra,
    })

    it('a loaded page stores nickname and avatar and emits one event', async () => {
      const members = Array.from({ length: 50 }, (_, i) => row(i, { nickname: `n${i}`, server_avatar_url: `a${i}.png` }))
      let events = 0
      const count = () => { events++ }
      for (const t of ['user-updated', 'data-refreshed']) userDataService.addEventListener(t, count)
      await userDataService.primeMembers(members as any, S)
      for (const t of ['user-updated', 'data-refreshed']) userDataService.removeEventListener(t, count)
      expect(events).toBe(1)
      expect(userDataService.getMemberServerFields(S, 'u7')?.nickname).toBe('n7')
      expect(userDataService.getMemberServerFields(S, 'u7')?.avatar_url).toBe('a7.png')
    })

    it('a member the directory sends without a nickname has none, but keeps its bio, banner and pronouns', async () => {
      userDataService.setMemberServerProfile(S, 'u1', { nickname: 'Old', avatar_url: 'old.png', bio: 'kept', pronouns: 'she/her' })
      await userDataService.primeMembers([row(1)] as any, S)
      expect(userDataService.getMemberServerFields(S, 'u1')).toEqual({
        nickname: null, avatar_url: null, banner_url: null, bio: 'kept', pronouns: 'she/her',
      })
    })

    it('without a server, no server profile is stored', async () => {
      await userDataService.primeMembers([row(2, { nickname: 'Nope' })] as any)
      expect(userDataService.getMemberServerFields(S, 'u2')).toBeNull()
    })

    it('a member carries its account pronouns', async () => {
      await userDataService.primeMembers([row(3, { pronouns: 'they/them' })] as any, S)
      expect(useUserData().getUserPronouns('u3').value).toBe('they/them')
    })
  })

  it('the current user leaving purges that server entries', async () => {
    ;(userDataService as any).currentUserId = 'me'
    userDataService.setMemberServerProfile(S, 'x', { nickname: 'X' })
    userDataService.setMemberServerProfile(S, 'y', { nickname: 'Y' })
    userDataService.setMemberServerProfile('other', 'x', { nickname: 'Keep' })
    await (userDataService as any).handleServerMemberLeave(S, { old: { user_id: 'me' } })
    ;(userDataService as any).currentUserId = null
    expect(userDataService.getMemberServerFields(S, 'x')).toBeNull()
    expect(userDataService.getMemberServerFields(S, 'y')).toBeNull()
    expect(userDataService.getMemberServerFields('other', 'x')?.nickname).toBe('Keep')
  })

  it('member:update arrives through the presence_event broadcast handler', async () => {
    useUserData()
    ;(userDataService as any).contexts.set(S, { id: S, type: 'server', userIds: new Set([U]), lastSync: new Date() })
    await (userDataService as any).setupServerPresence(S, [U])
    const name = getMemberDisplayName(U, S)
    expect(name.value).toBe('Account Name')
    // The shape public.broadcast_user_server_change() sends, wrapped as realtime delivers it.
    h.handlers.presence_event({ payload: {
      type: 'member:update', user_id: U, server_id: S,
      nickname: 'Ally', avatar_url: 'srv.png', banner_url: null, bio: null, pronouns: 'she/her',
    } })
    expect(name.value).toBe('Ally')
    expect(getMemberAvatarUrl(U, S).value).toBe('srv.png')
    expect(getMemberPronouns(U, S).value).toBe('she/her')
    h.handlers.presence_event({ payload: { type: 'member:leave', user_id: U, server_id: S } })
    expect(getMemberDisplayName(U, S).value).toBe('Account Name')
    ;(userDataService as any).contexts.delete(S)
  })

  it('account pronouns arrive through profile:updated (broadcast_profile_change)', async () => {
    const { getUserPronouns } = useUserData()
    ;(userDataService as any).contexts.set(S, { id: S, type: 'server', userIds: new Set([U]), lastSync: new Date() })
    await (userDataService as any).setupServerPresence(S, [U])
    const pronouns = getUserPronouns(U)
    // The shape public.broadcast_profile_change() sends (20261112100006), as realtime delivers it.
    h.handlers.presence_event({ payload: { type: 'profile:updated', user_id: U, pronouns: 'they/them' } })
    expect(pronouns.value).toBe('they/them')
    ;(userDataService as any).contexts.delete(S)
  })
})
