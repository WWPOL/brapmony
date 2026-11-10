/** Moderator nickname dialog and server profile reset (moderate_server_profile). */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'

const h = vi.hoisted(() => ({
  moderate: vi.fn(async (..._args: unknown[]) => ({})),
  setMember: vi.fn(),
  confirm: vi.fn(async (..._args: unknown[]) => true),
  toast: { success: vi.fn(), error: vi.fn() },
  nickname: 'Old' as string | null,
}))

vi.mock('vue-i18n', async (importOriginal) => ({
  ...(await importOriginal<typeof import('vue-i18n')>()),
  useI18n: () => ({ t: (k: string) => k, locale: { value: 'en' } }),
}))
vi.mock('vue-toastification', () => ({ useToast: () => h.toast }))
vi.mock('@/services/serverProfileService', () => ({ moderateServerProfile: h.moderate }))
vi.mock('@/services/userDataService', () => ({ userDataService: { setMemberServerProfile: h.setMember } }))
vi.mock('@/composables/useConfirmDialog', () => ({ useConfirmDialog: () => ({ confirm: h.confirm }) }))
vi.mock('@/composables/useMemberProfile', () => ({
  getMemberServerProfile: () => ({ value: h.nickname ? { nickname: h.nickname } : null }),
}))
vi.mock('@/components/common/Avatar.vue', async () => {
  const { defineComponent, h: render } = await import('vue')
  return { default: defineComponent({ name: 'Avatar', render: () => render('span') }) }
})

import ChangeNicknameDialog from '../ChangeNicknameDialog.vue'
import { useModerateServerProfile } from '@/composables/useModerateServerProfile'

enableAutoUnmount(afterEach)

const USER = { id: 'u-bob', username: 'bob', display_name: 'Bob', avatar_url: null }

async function open() {
  const wrapper = mount(ChangeNicknameDialog, {
    props: { show: true, serverId: 's1', user: USER },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}
const input = () => document.querySelector<HTMLInputElement>('[data-testid="moderate-nickname-input"]')!
const submit = () => document.querySelector<HTMLElement>('[data-testid="moderate-nickname-submit"]')!

beforeEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
  h.nickname = 'Old'
  h.confirm.mockResolvedValue(true)
})

describe('ChangeNicknameDialog', () => {
  it('submits the typed nickname and updates the local cache', async () => {
    const wrapper = await open()
    input().value = 'X'
    input().dispatchEvent(new Event('input'))
    submit().click()
    await flushPromises()
    expect(h.moderate).toHaveBeenCalledWith('s1', 'u-bob', { nickname: 'X' })
    expect(h.setMember).toHaveBeenCalledWith('s1', 'u-bob', { nickname: 'X' })
    expect(wrapper.emitted('done')).toBeTruthy()
  })

  it('submits null when the value is empty', async () => {
    await open()
    input().value = '   '
    input().dispatchEvent(new Event('input'))
    submit().click()
    await flushPromises()
    expect(h.moderate).toHaveBeenCalledWith('s1', 'u-bob', { nickname: null })
    expect(h.setMember).toHaveBeenCalledWith('s1', 'u-bob', { nickname: null })
  })

  it('shows an error toast and stays open when the RPC fails', async () => {
    h.moderate.mockRejectedValueOnce(new Error('denied'))
    const wrapper = await open()
    input().value = 'X'
    input().dispatchEvent(new Event('input'))
    submit().click()
    await flushPromises()
    expect(h.toast.error).toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeUndefined()
    expect(h.setMember).not.toHaveBeenCalled()
  })
})

describe('resetServerProfileWithConfirm', () => {
  const { resetServerProfileWithConfirm } = useModerateServerProfile()

  it('asks for confirmation, then resets and clears the cached fields', async () => {
    const ok = await resetServerProfileWithConfirm('s1', USER)
    expect(h.confirm).toHaveBeenCalledTimes(1)
    expect(h.moderate).toHaveBeenCalledWith('s1', 'u-bob', { resetProfile: true })
    expect(h.setMember).toHaveBeenCalledWith('s1', 'u-bob', { avatar_url: null, banner_url: null, bio: null, pronouns: null })
    expect(ok).toBe(true)
  })

  it('does nothing when the confirmation is declined', async () => {
    h.confirm.mockResolvedValue(false)
    const ok = await resetServerProfileWithConfirm('s1', USER)
    expect(h.moderate).not.toHaveBeenCalled()
    expect(ok).toBe(false)
  })
})
