<template>
  <BaseModal
    :show="show"
    :title="t('discordMigration.adminConfirm.title')"
    :subtitle="t('discordMigration.adminConfirm.lead')"
    role="alertdialog"
    @close="emit('cancel')"
  >
    <div data-testid="admin-confirm">
      <p class="dm-text">{{ t('discordMigration.adminConfirm.holders') }}</p>
      <ul v-if="holders.length" class="dm-holders" data-testid="admin-confirm-holders">
        <li v-for="holder in holders" :key="holder.user_id">
          {{ holder.display_name || holder.username || holder.user_id }}
          <span v-if="holder.display_name && holder.username" class="dm-muted">@{{ holder.username }}</span>
        </li>
      </ul>
      <p v-else class="dm-muted" data-testid="admin-confirm-none">{{ t('discordMigration.adminConfirm.noHolders') }}</p>
      <p class="dm-text">{{ t('discordMigration.adminConfirm.future') }}</p>
    </div>
    <template #footer>
      <button type="button" class="btn btn-secondary" data-testid="admin-confirm-cancel" @click="emit('cancel')">
        {{ t('common.cancel') }}
      </button>
      <button
        type="button"
        class="btn btn-danger"
        data-testid="admin-confirm-ok"
        :disabled="busy"
        @click="emit('confirm')"
      >
        {{ t('discordMigration.adminConfirm.confirm') }}
      </button>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import BaseModal from '@/components/common/BaseModal.vue'
import type { AdminHolder } from './migrationApi'

defineProps<{
  show: boolean
  holders: AdminHolder[]
  busy?: boolean
}>()

const emit = defineEmits<{ confirm: []; cancel: [] }>()
const { t } = useI18n()
</script>

<style scoped>
.dm-text {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--text-primary);
}

.dm-muted {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-secondary);
}

.dm-holders {
  margin: 0 0 12px;
  padding-left: 20px;
  max-height: 200px;
  overflow-y: auto;
  font-size: 14px;
  color: var(--text-primary);
}
</style>
