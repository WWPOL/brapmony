/**
 * /migrate/<token>: landing, error, claim result and new-account states, driven by the query string.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'

enableAutoUnmount(afterEach)

const h = vi.hoisted(() => {
  class DiscordMigrationError extends Error {
    code: string
    constructor(code: string) { super(code); this.code = code }
  }
  return {
    DiscordMigrationError,
    svc: {
      preview: vi.fn(),
      startDiscordSignIn: vi.fn(),
      createAccount: vi.fn(),
      claim: vi.fn(),
      saveSyncChoice: vi.fn(),
      myLink: vi.fn(),
      setMySync: vi.fn(async () => {}),
    },
    route: { params: { token: 'tok' } as Record<string, string>, query: {} as Record<string, string>, fullPath: '/migrate/tok' },
    router: { push: vi.fn(), replace: vi.fn() },
    auth: { isLoggedIn: false, session: null as any, login: vi.fn(), logout: vi.fn() },
    profile: { profile: null as any },
  }
})

vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (key: string, params?: Record<string, unknown>) => (params ? `${key} ${JSON.stringify(params)}` : key) }),
}))
vi.mock('vue-toastification', () => ({ useToast: () => ({ error: vi.fn(), success: vi.fn() }) }))
vi.mock('vue-router', () => ({
  useRoute: () => h.route,
  useRouter: () => h.router,
  RouterLink: { props: ['to'], template: '<a :href="typeof to === \'string\' ? to : \'\'"><slot /></a>' },
}))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => h.auth }))
vi.mock('@/stores/useServerChannel', () => ({ useServerChannelStore: () => ({ setCurrentServer: vi.fn() }) }))
vi.mock('@/stores/useProfile', () => ({ useProfileStore: () => h.profile }))
vi.mock('@/services/discordMigrationService', () => ({
  ...h.svc,
  DiscordMigrationError: h.DiscordMigrationError,
}))

import MigrateFromDiscordView from '../MigrateFromDiscordView.vue'

async function open(query: Record<string, string> = {}) {
  h.route.query = query
  const wrapper = mount(MigrateFromDiscordView)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  Object.values(h.svc).forEach(fn => (fn as any).mockReset?.())
  h.svc.preview.mockResolvedValue({ serverId: 'srv', serverName: 'Lobby', iconUrl: null })
  h.svc.setMySync.mockResolvedValue(undefined)
  h.svc.myLink.mockResolvedValue(null)
  h.auth.isLoggedIn = false
  h.auth.session = null
  h.profile.profile = null
  h.router.replace.mockReset()
  h.auth.login.mockReset()
})

describe('MigrateFromDiscordView', () => {
  it('signed out with no ticket offers both ways in', async () => {
    const wrapper = await open()
    expect(wrapper.text()).toContain('Lobby')
    expect(wrapper.find('[data-testid="migrate-have-account"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="migrate-new-account"]').exists()).toBe(true)
    await wrapper.get('[data-testid="migrate-new-account"]').trigger('click')
    expect(h.svc.startDiscordSignIn).toHaveBeenCalledWith('tok')
  })

  it('signed in names the account the link goes to', async () => {
    h.auth.isLoggedIn = true
    h.profile.profile = { username: 'alice', is_local: true }
    const wrapper = await open()
    expect(wrapper.text()).toContain('"username":"alice"')
    expect(wrapper.find('[data-testid="migrate-signin-discord"]').exists()).toBe(true)
  })

  it('error=harmony_banned shows its message and no sign-in button', async () => {
    const wrapper = await open({ error: 'harmony_banned' })
    expect(wrapper.get('[data-testid="migrate-error"]').text()).toContain('discordMigration.migrate.errors.harmony_banned')
    expect(wrapper.find('[data-testid="migrate-new-account"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="migrate-signin-discord"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="migrate-restart"]').exists()).toBe(false)
  })

  it('error=ticket_expired offers to start again', async () => {
    const wrapper = await open({ error: 'ticket_expired' })
    await wrapper.get('[data-testid="migrate-restart"]').trigger('click')
    expect(h.svc.startDiscordSignIn).toHaveBeenCalledWith('tok')
  })

  it('an unknown error code reads as a generic failure', async () => {
    const wrapper = await open({ error: 'something_new' })
    expect(wrapper.get('[data-testid="migrate-error"]').text()).toContain('discordMigration.migrate.errors.unknown')
  })

  it('signed in with a ticket claims once and lists the roles received', async () => {
    h.auth.isLoggedIn = true
    h.profile.profile = { username: 'alice', is_local: true }
    h.svc.claim.mockResolvedValue({ serverId: 'srv', rolesGranted: ['Mod', 'Artist'] })
    const wrapper = await open({ ticket: 'tkt' })
    expect(h.svc.claim).toHaveBeenCalledTimes(1)
    expect(h.svc.claim).toHaveBeenCalledWith('tkt')
    expect(wrapper.get('[data-testid="migrate-roles"]').text()).toContain('Mod')
    expect(wrapper.get('[data-testid="migrate-roles"]').text()).toContain('Artist')
    expect(wrapper.find('[data-testid="sync-avatar"]').exists()).toBe(true)
  })

  it('a claim refusal shows its message', async () => {
    h.auth.isLoggedIn = true
    h.profile.profile = { username: 'alice', is_local: true }
    h.svc.claim.mockRejectedValue(new h.DiscordMigrationError('kicked_on_harmony'))
    const wrapper = await open({ ticket: 'tkt' })
    expect(wrapper.get('[data-testid="migrate-error"]').text()).toContain('discordMigration.migrate.errors.kicked_on_harmony')
  })

  it('a ticket claimed meanwhile (TICKET_EXPIRED) with an active link shows the result, not an error', async () => {
    h.auth.isLoggedIn = true
    h.profile.profile = { username: 'alice', is_local: true }
    h.svc.claim.mockRejectedValue(new h.DiscordMigrationError('ticket_expired'))
    h.svc.myLink.mockResolvedValue({ status: 'active', syncFields: ['avatar'], discordJoinedAt: null })
    const wrapper = await open({ ticket: 'tkt' })
    expect(h.svc.myLink).toHaveBeenCalledWith('srv')
    expect(wrapper.find('[data-testid="migrate-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="migrate-done"]').exists()).toBe(true)
  })

  it('TICKET_EXPIRED without a link is still an error', async () => {
    h.auth.isLoggedIn = true
    h.profile.profile = { username: 'alice', is_local: true }
    h.svc.claim.mockRejectedValue(new h.DiscordMigrationError('ticket_expired'))
    const wrapper = await open({ ticket: 'tkt' })
    expect(wrapper.get('[data-testid="migrate-error"]').text()).toContain('ticket_expired')
  })

  it('prefills the username from the Discord username in the address', async () => {
    const wrapper = await open({ ticket: 'tkt', username: 'dave_d' })
    expect((wrapper.get('[data-testid="migrate-username"]').element as HTMLInputElement).value).toBe('dave_d')
  })

  it('a federated session is asked to create an account here', async () => {
    h.auth.isLoggedIn = true
    h.profile.profile = { username: 'bob', is_local: false }
    const wrapper = await open({ ticket: 'tkt' })
    expect(h.svc.claim).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="migrate-federated"]').exists()).toBe(true)
  })

  async function fillForm(wrapper: ReturnType<typeof mount>) {
    await wrapper.get('[data-testid="migrate-username"]').setValue('newbie')
    await wrapper.get('[data-testid="migrate-email"]').setValue('n@example.com')
    await wrapper.get('[data-testid="migrate-password"]').setValue('correct horse battery')
    await wrapper.get('[data-testid="migrate-form"]').trigger('submit')
    await flushPromises()
  }

  it('signed out with a ticket shows the new-account form; username_taken is inline', async () => {
    h.svc.createAccount.mockRejectedValue(new h.DiscordMigrationError('username_taken'))
    const wrapper = await open({ ticket: 'tkt' })
    expect(wrapper.find('[data-testid="migrate-form"]').exists()).toBe(true)
    await fillForm(wrapper)
    expect(wrapper.get('[data-testid="migrate-field-error"]').text()).toContain('discordMigration.migrate.errors.username_taken')
    expect(h.auth.login).not.toHaveBeenCalled()
  })

  it('needsConfirmation asks for the email confirmation', async () => {
    h.svc.createAccount.mockResolvedValue({ needsConfirmation: true })
    const wrapper = await open({ ticket: 'tkt' })
    await fillForm(wrapper)
    expect(wrapper.find('[data-testid="migrate-confirm-email"]').exists()).toBe(true)
    expect(h.auth.login).not.toHaveBeenCalled()
  })

  it('without confirmation it signs in and continues to the profile step with the username', async () => {
    h.svc.createAccount.mockResolvedValue({ needsConfirmation: false })
    h.auth.login.mockResolvedValue({})
    const wrapper = await open({ ticket: 'tkt' })
    await fillForm(wrapper)
    expect(h.auth.login).toHaveBeenCalledWith('n@example.com', 'correct horse battery')
    expect(h.router.replace).toHaveBeenCalledWith({ path: '/new-profile', query: { username: 'newbie' } })
  })

  it('account_creation_disabled from the gateway shows the closed-sign-up message', async () => {
    h.svc.createAccount.mockRejectedValue(new h.DiscordMigrationError('account_creation_disabled'))
    const wrapper = await open({ ticket: 'tkt' })
    await fillForm(wrapper)
    expect(wrapper.get('[data-testid="migrate-field-error"]').text()).toContain('discordMigration.migrate.errors.account_creation_disabled')
  })
})
