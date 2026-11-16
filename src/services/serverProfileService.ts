/** Per-server member profile writes (set_my_server_profile, moderate_server_profile RPCs). */
import { supabase } from '@/supabase'
import { userDataService } from '@/services/userDataService'

export interface ServerProfileFields {
  nickname?: string | null
  avatar_url?: string | null
  banner_url?: string | null
  bio?: string | null
  pronouns?: string | null
}

/**
 * Reads one member's server profile row (all five fields) into the member cache. The member
 * lists carry only the nickname and avatar; the banner, bio and pronouns of a server load here,
 * when a profile card or the settings tab needs them. One row by primary key.
 */
export async function loadMemberServerProfile(serverId: string, userId: string): Promise<void> {
  const { data, error } = await supabase
    .from('user_servers')
    .select('user_id, server_id, nickname, avatar_url, banner_url, bio, pronouns')
    .eq('server_id', serverId)
    .eq('user_id', userId)
    .maybeSingle()
  if (error) throw error
  if (data) userDataService.applyMemberRow(serverId, data)
}

/** Sets the caller's own server profile. Present keys are set; a null value clears that field. */
export async function setMyServerProfile(serverId: string, fields: ServerProfileFields): Promise<unknown> {
  const { data, error } = await supabase.rpc('set_my_server_profile', {
    p_server_id: serverId,
    p_fields: fields,
  })
  if (error) throw error
  return data
}

export interface ModerateServerProfileOptions {
  /** Present (even null) sets the member's nickname; null clears it. */
  nickname?: string | null
  /** Clears all five server profile fields. */
  resetProfile?: boolean
}

/** Moderates another member's server profile (nickname change or full reset). */
export async function moderateServerProfile(
  serverId: string,
  userId: string,
  opts: ModerateServerProfileOptions = {},
): Promise<unknown> {
  const { data, error } = await supabase.rpc('moderate_server_profile', {
    p_server_id: serverId,
    p_user_id: userId,
    p_nickname: opts.nickname ?? null,
    p_set_nickname: 'nickname' in opts,
    p_reset_profile: !!opts.resetProfile,
  })
  if (error) throw error
  return data
}

/** The caller's own row: a server not open yet has none cached. */
export const loadMyServerProfile = loadMemberServerProfile

const CARD_RELOAD_MS = 5 * 60 * 1000
const cardLoads = new Map<string, number>()

/**
 * loadMemberServerProfile for a profile card, at most once per five minutes for a member:
 * realtime member updates keep the row fresh after that. A failed read is dropped, and the card
 * shows the nickname and avatar the list loaded.
 */
export function loadServerProfileForCard(serverId: string, userId: string): void {
  const key = `${serverId}:${userId}`
  const last = cardLoads.get(key)
  if (last !== undefined && Date.now() - last < CARD_RELOAD_MS) return
  cardLoads.set(key, Date.now())
  loadMemberServerProfile(serverId, userId).catch(() => cardLoads.delete(key))
}

export const serverProfileService = { setMyServerProfile, moderateServerProfile, loadMyServerProfile, loadMemberServerProfile }
