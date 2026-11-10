/** Moderator actions on another member's server profile: nickname change and full reset. */
import { useToast } from 'vue-toastification'
import { useI18n } from 'vue-i18n'
import { useConfirmDialog } from '@/composables/useConfirmDialog'
import { moderateServerProfile } from '@/services/serverProfileService'
import { userDataService } from '@/services/userDataService'

export interface ModeratedMember {
  id: string
  username: string
  display_name?: string | null
}

/** The RPC's message when it gave one (permission, hierarchy, no-op), else the fallback. */
export function moderationErrorMessage(error: unknown, fallback: string): string {
  const message = (error as { message?: string } | null)?.message
  return message ? `${fallback} ${message}` : fallback
}

/** Call in setup; the returned actions run later, from event handlers. */
export function useModerateServerProfile() {
  const { t } = useI18n()
  const toast = useToast()
  const { confirm } = useConfirmDialog()

  /** Confirms, resets the member's server profile (keeps the nickname) and mirrors it in the cache. Resolves true once reset. */
  async function resetServerProfileWithConfirm(serverId: string, user: ModeratedMember): Promise<boolean> {
    const name = user.display_name || user.username

    const ok = await confirm({
      title: t('moderation.serverProfile.resetTitle'),
      message: t('moderation.serverProfile.resetMessage', { name }),
      confirmButtonText: t('moderation.serverProfile.resetConfirm'),
      dangerAction: true,
    })
    if (!ok) return false

    try {
      await moderateServerProfile(serverId, user.id, { resetProfile: true })
      userDataService.setMemberServerProfile(serverId, user.id, { avatar_url: null, banner_url: null, bio: null, pronouns: null })
      toast.success(t('moderation.serverProfile.resetDone', { name }))
      return true
    } catch (error) {
      toast.error(moderationErrorMessage(error, t('moderation.serverProfile.failed', { name })))
      return false
    }
  }

  return { resetServerProfileWithConfirm }
}
