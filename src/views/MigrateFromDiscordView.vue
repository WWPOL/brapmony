<template>
  <main class="mg-wrap" data-testid="migrate-view">
    <div class="mg-card">
      <div v-if="state === 'loading'" class="mg-muted" role="status">{{ t('discordMigration.migrate.loading') }}</div>

      <template v-else>
        <header v-if="server" class="mg-server">
          <img v-if="server.iconUrl" :src="server.iconUrl" alt="" class="mg-icon" />
          <span v-else class="mg-icon mg-icon-fallback" aria-hidden="true">{{ server.serverName.slice(0, 1).toUpperCase() }}</span>
          <h1 class="mg-title">{{ server.serverName }}</h1>
        </header>
        <h1 v-else class="mg-title">{{ t('discordMigration.migrate.title') }}</h1>

        <!-- Error -->
        <section v-if="state === 'error'" class="mg-section">
          <p class="mg-error" role="alert" data-testid="migrate-error">{{ errorMessage(errorCode) }}</p>
          <button v-if="canRestart" class="mg-btn mg-btn-primary" data-testid="migrate-restart" @click="restart">
            {{ t('discordMigration.migrate.startAgain') }}
          </button>
        </section>

        <!-- A session from another instance cannot be linked here -->
        <section v-else-if="state === 'federated'" class="mg-section" data-testid="migrate-federated">
          <p>{{ t('discordMigration.migrate.federated') }}</p>
          <button class="mg-btn" @click="signOutHere">{{ t('discordMigration.migrate.signOut') }}</button>
        </section>

        <!-- Landing -->
        <section v-else-if="state === 'landing'" class="mg-section">
          <p>{{ t('discordMigration.migrate.lead') }}</p>
          <template v-if="signedIn">
            <p>
              {{ t('discordMigration.migrate.linkTo', { username: myUsername }) }}
              <button class="mg-link" @click="signOutHere">{{ t('discordMigration.migrate.notYou') }}</button>
            </p>
            <button class="mg-btn mg-btn-primary" data-testid="migrate-signin-discord" @click="startDiscordSignIn(token)">
              {{ t('discordMigration.migrate.signInWithDiscord') }}
            </button>
          </template>
          <template v-else>
            <RouterLink class="mg-btn mg-btn-primary" data-testid="migrate-have-account" :to="loginTarget">
              {{ t('discordMigration.migrate.haveAccount') }}
            </RouterLink>
            <button class="mg-btn" data-testid="migrate-new-account" @click="startDiscordSignIn(token)">
              {{ t('discordMigration.migrate.newHere') }}
            </button>
          </template>
        </section>

        <!-- Claiming -->
        <section v-else-if="state === 'claiming'" class="mg-section" role="status">
          <p>{{ t('discordMigration.migrate.claiming') }}</p>
        </section>

        <!-- Result -->
        <section v-else-if="state === 'done'" class="mg-section" data-testid="migrate-done">
          <h2 class="mg-sub">{{ t('discordMigration.migrate.doneTitle') }}</h2>
          <div data-testid="migrate-roles">
            <template v-if="rolesGranted.length > 0">
              <p>{{ t('discordMigration.migrate.rolesReceived') }}</p>
              <ul class="mg-roles">
                <li v-for="name in rolesGranted" :key="name">{{ name }}</li>
              </ul>
            </template>
            <p v-else class="mg-muted">{{ t('discordMigration.migrate.noRoles') }}</p>
          </div>
          <h2 class="mg-sub">{{ t('discordMigration.sync.title') }}</h2>
          <DiscordSyncFields v-model="syncFields" :server-id="claimedServerId" />
          <button v-if="claimedServerId" class="mg-btn mg-btn-primary" data-testid="migrate-open-server" @click="openServer">
            {{ t('discordMigration.migrate.openServer') }}
          </button>
        </section>

        <!-- New account -->
        <section v-else-if="state === 'confirm-email'" class="mg-section" data-testid="migrate-confirm-email" role="status">
          <p>{{ t('discordMigration.migrate.confirmEmail') }}</p>
        </section>

        <form
          v-else-if="state === 'form'"
          class="mg-section"
          data-testid="migrate-form"
          novalidate
          @submit.prevent="submitAccount"
        >
          <p>{{ t('discordMigration.migrate.formLead') }}</p>
          <label class="mg-field">
            <span>{{ t('discordMigration.migrate.username') }}</span>
            <input
              v-model="username"
              data-testid="migrate-username"
              autocomplete="username"
              autocapitalize="none"
              spellcheck="false"
              required
            />
          </label>
          <label class="mg-field">
            <span>{{ t('discordMigration.migrate.email') }}</span>
            <input v-model="email" type="email" data-testid="migrate-email" autocomplete="email" required />
          </label>
          <label class="mg-field">
            <span>{{ t('discordMigration.migrate.password') }}</span>
            <input v-model="password" type="password" data-testid="migrate-password" autocomplete="new-password" required />
          </label>
          <p v-if="formError" class="mg-error" role="alert" data-testid="migrate-field-error">{{ errorMessage(formError) }}</p>
          <h2 class="mg-sub">{{ t('discordMigration.sync.title') }}</h2>
          <DiscordSyncFields v-model="syncFields" :server-id="null" />
          <button class="mg-btn mg-btn-primary" type="submit" data-testid="migrate-submit" :disabled="submitting">
            {{ t('discordMigration.migrate.createAccount') }}
          </button>
        </form>
      </template>
    </div>
  </main>
</template>

<script setup lang="ts">
/**
 * /migrate/<token>, public. The gateway returns here from Discord with ?ticket= or ?error=.
 *   no ticket, no error   landing
 *   ?error=               a message (and "start again" where trying again can help)
 *   ?ticket=, signed in   claim, then the roles received and the sync toggles
 *   ?ticket=, signed out  new-account form
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import DiscordSyncFields from '@/components/settings/user/DiscordSyncFields.vue'
import {
  DiscordMigrationError,
  claim,
  createAccount,
  myLink,
  preview,
  saveSyncChoice,
  startDiscordSignIn,
  type MigrationPreview,
  type SyncField,
} from '@/services/discordMigrationService'
import { useAuthStore } from '@/stores/auth'
import { useProfileStore } from '@/stores/useProfile'
import { normalizeUsernameInput } from '@/utils/usernameRules'

type ViewState = 'loading' | 'landing' | 'error' | 'claiming' | 'done' | 'form' | 'confirm-email' | 'federated'

const KNOWN_ERRORS = new Set([
  'closed', 'migration_closed', 'not_member', 'harmony_banned', 'kicked_on_harmony', 'discord_banned_here',
  'discord_account_taken', 'already_linked_other_discord', 'already_linked_elsewhere', 'ticket_expired',
  'account_creation_disabled', 'denied', 'exchange_failed', 'unavailable', 'username_taken', 'email_exists',
  'username_invalid', 'invalid_request', 'invalid_ticket', 'network',
])
const RESTARTABLE = new Set(['ticket_expired', 'invalid_ticket', 'denied', 'exchange_failed', 'unavailable'])

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const profileStore = useProfileStore()

const token = String(route.params.token ?? '')
const queryValue = (key: string) => {
  const raw = route.query[key]
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value : ''
}
const ticket = queryValue('ticket')
const errorParam = queryValue('error').toLowerCase()

const state = ref<ViewState>('loading')
const server = ref<MigrationPreview | null>(null)
const errorCode = ref('')
const rolesGranted = ref<string[]>([])
const claimedServerId = ref<string | null>(null)
const syncFields = ref<SyncField[]>([])

const username = ref('')
const email = ref('')
const password = ref('')
const formError = ref('')
const submitting = ref(false)

const signedIn = computed(() => authStore.isLoggedIn)
const myUsername = computed(() => profileStore.profile?.username ?? '')
const isFederatedSession = computed(() => signedIn.value && profileStore.profile?.is_local === false)
const canRestart = computed(() => RESTARTABLE.has(errorCode.value))
const loginTarget = computed(() => `/login?redirect=${encodeURIComponent(`/migrate/${token}`)}`)

function errorMessage(code: string): string {
  return t(`discordMigration.migrate.errors.${KNOWN_ERRORS.has(code) ? code : 'unknown'}`)
}

function showError(code: string) {
  errorCode.value = code
  state.value = 'error'
}

function restart() {
  startDiscordSignIn(token)
}

async function signOutHere() {
  await authStore.logout()
  await router.replace(route.fullPath)
}

function openServer() {
  if (claimedServerId.value) void router.push(`/chat/${claimedServerId.value}`)
}

async function runClaim() {
  state.value = 'claiming'
  try {
    const result = await claim(ticket)
    rolesGranted.value = result.rolesGranted
    claimedServerId.value = result.serverId || null
    if (result.serverId) {
      const link = await myLink(result.serverId).catch(() => null)
      syncFields.value = link?.syncFields ?? []
    }
    state.value = 'done'
  } catch (err) {
    showError(err instanceof DiscordMigrationError ? err.code : 'unknown')
  }
}

async function submitAccount() {
  if (submitting.value) return
  formError.value = ''
  const name = normalizeUsernameInput(username.value)
  if (!name || !email.value.trim() || !password.value) {
    formError.value = 'invalid_request'
    return
  }
  submitting.value = true
  try {
    const { needsConfirmation } = await createAccount({
      ticket,
      username: name,
      email: email.value.trim(),
      password: password.value,
    })
    saveSyncChoice(ticket, syncFields.value)
    if (needsConfirmation) {
      state.value = 'confirm-email'
      return
    }
    await authStore.login(email.value.trim(), password.value)
    await router.replace({ path: '/new-profile', query: { username: name } })
  } catch (err) {
    formError.value = err instanceof DiscordMigrationError ? err.code : 'unknown'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (errorParam) {
    server.value = await preview(token).catch(() => null)
    showError(errorParam)
    return
  }
  if (isFederatedSession.value) {
    state.value = 'federated'
    return
  }
  if (ticket && signedIn.value) {
    void preview(token).then((p) => { server.value = p }).catch(() => {})
    await runClaim()
    return
  }
  try {
    server.value = await preview(token)
  } catch {
    server.value = null
  }
  if (!server.value && !ticket) {
    showError('closed')
    return
  }
  state.value = ticket ? 'form' : 'landing'
})
</script>

<style scoped>
.mg-wrap {
  min-height: calc(100 * var(--app-vh));
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--background-senary);
}
.mg-card {
  width: 100%;
  max-width: 440px;
  background: var(--background-secondary);
  border: 1px solid var(--border-primary);
  border-radius: var(--radius-xl);
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  color: var(--text-primary);
}
.mg-server { display: flex; align-items: center; gap: 12px; }
.mg-icon { width: 48px; height: 48px; border-radius: 12px; object-fit: cover; flex: none; }
.mg-icon-fallback { display: flex; align-items: center; justify-content: center; background: var(--harmony-primary); color: #fff; font-weight: 700; }
.mg-title { margin: 0; font-size: 1.5rem; font-weight: 600; }
.mg-sub { margin: 8px 0 0; font-size: 1rem; font-weight: 600; }
.mg-section { display: flex; flex-direction: column; gap: 12px; }
.mg-section p { margin: 0; }
.mg-muted { color: var(--text-muted); }
.mg-error { color: var(--error); }
.mg-roles { margin: 0; padding-left: 20px; }
.mg-field { display: flex; flex-direction: column; gap: 4px; font-size: 0.875rem; }
.mg-field input {
  padding: 8px 10px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-primary);
  background: var(--background-primary);
  color: var(--text-primary);
  font: inherit;
}
.mg-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 10px 16px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-primary);
  background: var(--background-tertiary);
  color: var(--text-primary);
  font: inherit;
  cursor: pointer;
  text-decoration: none;
}
.mg-btn-primary { background: var(--harmony-primary); border-color: transparent; color: #fff; }
.mg-btn:disabled { opacity: 0.6; cursor: default; }
.mg-link { background: none; border: 0; padding: 0; color: var(--text-link); cursor: pointer; font: inherit; text-decoration: underline; }
</style>
