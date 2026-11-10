import { describe, it, expect, beforeEach, vi } from 'vitest'

vi.mock('@/supabase', () => ({
  SUPABASE_URL: 'http://db.test',
  SUPABASE_ANON_KEY: 'anon',
  supabase: {
    from: () => { throw new Error('no db in this test') },
    rpc: vi.fn(async () => ({ data: null, error: null })),
    channel: () => ({ on: () => ({}), subscribe: () => ({}) }),
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
})
