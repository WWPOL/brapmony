# Native Android parity audit

## Scope and truth standard

This is a **gap audit, not a parity claim**. The reference is Harmony commit
`78fdc9605412fa53497f998e2e94071171675d8c` (official Vue/Tauri Android client and
new `android-native/` foundation). The official client is not merely the router:
its settings, menus, dialogs, media transports, encryption, device keys, background
services, permissions, and failure states are part of the requirement.

The native foundation at that commit is incomplete. No native screen has Android
emulator **visual and functional** evidence in this audit. Passing a JVM test,
HTTP contract, web Playwright test, or compiling a composable does not verify an
Android journey. Work added after the reference is identified separately below;
parent/sibling work in progress is not counted until it has evidence.

Status columns throughout:

- **Native**: `none` = no implementation at the audited commit; `partial` = source
  exists but not complete or not integrated; `slice` = this audit's later isolated
  implementation, not full parity. A menu item or explanatory text is not a feature.
- **Contract**: `U` = unverified; a test name is a verification target, not a pass.
- **Android**: `U/U` = functional / visual verification both unverified. A pass
  must name APK commit, API/device, steps, screenshot, and backend assertion.
- **Gate/failure** lists mandatory negative cases in addition to the happy path.
  Hiding a button is never a replacement for backend authorization.

Source paths are repository-relative, with these explicit prefix aliases:

- `components/` means `src/components/`. Component-family prefixes `a11y/`,
  `activitypub/`, `common/`, `drive/`, `emoji/`, `encryption/`, `invite/`,
  `messages/`, `moderation/`, `settings/`, `stickers/`, `updater/`, `voice/`,
  and `welcome/` mean the corresponding directory under `src/components/`.
- `views/`, `layouts/`, `router/`, `services/`, `composables/`, `stores/`, and
  `utils/` mean the corresponding directory under `src/`. Service-family
  prefixes `core/`, `search/`, and `uploads/` mean directories under
  `src/services/`. Service references that would conflict with a component
  prefix are written with their full `src/services/` path.
- Native-source prefixes `data/` and `ui/` mean directories under
  `android-native/app/src/main/java/lol/mony/harmony/`.
- `android/` means `src-tauri/gen/android/app/src/main/java/online/knowmad/harmony/`;
  `push/` means `src-tauri/crates/tauri-plugin-harmony-push/android/src/main/java/online/knowmad/harmony/push/`.
  Manifests retain full repository-relative paths; these aliases name Kotlin source only.

Brace lists such as `components/{MessageInput,MessageReply}.vue` expand to
separate paths with the same suffix. Mixed file extensions are listed separately.
`file::symbol` names a code symbol; `file::a/b` lists the separate symbols `a`
and `b`, not one slash-containing symbol. Official `.test.ts`, SQL, and e2e paths
are regression specifications; they do **not** establish native coverage.

Required negative tests and native hardening are requirements, not assertions
that the official implementation already guarantees them. Any stronger trust,
secret-storage, OS-entry, or background-call policy must be identified as an
additional native requirement rather than credited to the official Android baseline.

## Immediate blockers and security boundaries

| ID | Blocker / exact evidence | Required before a parity claim |
|---|---|---|
| B01 | `android-native/app/src/main/java/lol/mony/harmony/ui/HarmonyRoot.kt:53,118` uses `DEFAULT_INSTANCE_ANON_KEY` and `SignedInShell`, absent from this commit's build config / definitions. `ui/Shell.kt::Shell` is navigation chrome only. | Integrated build, install, sign in, reach each real feature; do not edit shared WIP just to make an isolated audit look green. |
| B02 | `data/api/RestApi.kt::select/rpc` executes OkHttp synchronously. Existing `data/repo/ChatRepository.kt` suspend methods do not themselves switch dispatcher. | Dispatch blocking work off Main; cancellation, refresh-token races, error propagation, socket reconnect, private realtime authorization. `suspend` alone does not make HTTP asynchronous. |
| B03 | Native has no counterpart to `src/services/encryption/`. **Harmony Megolm is not Matrix wire-compatible** (`src/services/encryption/MegolmService.ts:6-33`): custom HKDF `megolm_ratchet_${messageIndex}`, AES-GCM, signed v2/v3 envelopes, v3 AAD/epochs and rotation. | Cross-client message and recovery-KDF vectors; real web↔native encrypted channel/DM/thread and attachment-reference journeys. A Matrix SDK alone cannot supply this protocol. Never silently send plaintext into encrypted rooms; see the explicit secrecy/media limits below. |
| B04 | Device identity, backup/recovery, pairing, history keys and revocation are absent natively (`src/services/encryption/{DeviceIdentityService,RecoveryKeyService,MegolmKeyBackupService,devicePairing}.ts`). | Per-account isolation, approval expiry/denial, QR/code proof validation, backup integrity, recovery on another device, and honest copied-key revocation limits. Keystore-backed native private-key custody is an additional native security requirement, not a description of official WebCrypto storage. |
| B05 | Official Android message push implements FCM direct/relay and UnifiedPush (`src/services/androidPush.ts`; `src-tauri/crates/tauri-plugin-harmony-push/android/`). `android-native/app/src/main/AndroidManifest.xml` declares notification, Internet/network, mic and camera permissions but no push integration. | Registration/renewal/unregister, direct-FCM vs encrypted-relay privacy boundary, distributor choice, Firebase project matching, tap/read/cancel/dedup and actual background delivery/cold-start message routing. Killed-app incoming-call ringing is an additional requirement: the cited official push sources do not establish it. A running socket is not push. |
| B06 | No native LiveKit/P2P transport, call signaling or ongoing-call service. Official `src/services/{unifiedWebRTC,DMCallSignaling}.ts`, `src/services/encryption/VoiceE2EEService.ts`, `android/CallForegroundService.kt`. | Two actual participants; audio/video frame transfer; permission/refusal/end states; instance-configured transport and supported E2EE scope; network handoff; ongoing-call microphone service/audio routing on hardware. The foreground service maintains an existing call; it is not proof of incoming-call push wakeup. |
| B07 | Native has no safe attachment pipeline. Official `src/services/messageMediaUpload.ts::startMessageMediaUpload`, `src/services/uploads/storageUpload.ts`, `src/utils/stripMetadata.ts`. | Strip metadata by default before hashing, honor keep-details consent, MIME/bucket/account limits, RLS object names, resumable upload/cancel/retry, signed URL expiry and gone files. Encryption covers message attachment references/names/types, not stored file bytes; do not claim server-blind attachment storage or unrestricted raw-photo upload parity. |
| B08 | `data/session/SessionStore.kt:33-99` persists access/refresh tokens in plain Preferences DataStore; native manifest has `allowBackup="true"`. | Explicit secret storage / backup threat model and exclusions, no token/secret logs, sign-out clears account caches/keys/notifications, no restored session on another install without appropriate approval. This is an observed risk, not a claim that Android's sandbox is absent. |
| B09 | Native manifest has only launcher intent; no share/OAuth/recovery/invite routing, receivers, FileProvider, foreground service. | Native navigation/deep-link contracts and externally launched flows, correct origin validation, lifecycle and process-death tests. Official route availability must not be confused with an official Android intent filter; distinguish web-route support from OS intent support. |
| B10 | No per-screen Android evidence here. Reference has no test sources under `android-native/app/src/test` or `androidTest`. | Run every journey below against the real local backend, inspect every screen/dialog in light/dark/custom appearance, record failures honestly, include member/moderator/admin/outsider accounts. |

## Frozen native foundation inventory (78fdc960)

Paths in this paragraph are relative to `android-native/app/src/main/java/lol/mony/harmony/`.
`data/api/{AuthApi,Endpoint,HttpClient,RestApi}.kt`, models and the chat/profile/
server/settings repositories are contracts/scaffolding, not proof of functional
parity. `data/session/{AuthRepository,SessionStore}.kt` covers session intent;
`ui/screens/{instance/InstancePickerScreen,signin/AuthFormScreen}.kt` contains
instance and auth forms. `ui/{HarmonyAppState,HarmonyRoot,Shell}.kt` and
`ui/components/{Avatar,ServerRail,Wordmark}.kt` provide shared state/chrome.
`ui/theme/{HarmonyTheme,Oklch,VisualTheme}.kt` maps some theme data. There are no
native social/server-admin/settings-detail/chat/media/voice/encryption screens
in this frozen inventory. Parent-agent additions must be audited independently.

## Entry points and navigation (not the entire surface)

Reference: `src/router/index.ts:55-586`; route guards at `src/router/index.ts:595-683`,
`publicPageEntry`, and `src/layouts/{ChatLayout,SocialLayout}.vue`.

| ID | Official screen / entry | Exact source | Native | Contract | Android | Gate / failure and required journey |
|---|---|---|---|---|---|---|
| R01 | `/` → `/chat`; empty/no-server landing | `src/router/index.ts`; `components/NoServersSplash.vue` | partial | U | U/U | Auth required; zero servers, inaccessible last channel, deleted server; J01/J03. |
| R02 | `/login`, instance choice, password/provider forms | `views/LoginView.vue`; `components/AuthComponent.vue`; `components/InstancePicker.vue` | partial | U | U/U | Bad URL/TLS/config, wrong password, closed provider, offline, approval/suspension; J01. |
| R03 | `/register`, rules/invite/signup review | `views/RegisterView.vue`; `components/AuthComponent.vue` | partial | U | U/U | Registration disabled, invite required/expired, email verification, username conflict/rate limit; J02. |
| R04 | `/reset-password`, `/auth/callback`, `/logout` | `views/{ResetPasswordView,AuthCallbackView,LogoutView}.vue`; `components/AuthComponent.vue::handleOAuthLogin`; `services/tauriOAuth.ts` (desktop popup only) | partial | U | U/U | Invalid/expired token, provider cancel, second factor, redirect origin, refresh revoked, logout offline. Android uses the normal redirect flow; OS callback support is not established by the desktop helper or web routes; J01/J02/J24. |
| R05 | `/new-profile` | `views/NewProfile.vue` | none | U | U/U | Auth user without profile; name/avatar constraints; no stale identity from another instance. |
| R06 | `/invite/:code` | `components/InviteAccept.vue`; `invite/InviteAcceptModal.vue` | none | U | U/U | Sign-in continuation, remote invite, expired/exhausted/revoked code, ban, already member; J03. |
| R07 | `/migrate/:token` | `views/MigrateFromDiscordView.vue`; `services/discordMigrationService.ts` | none | U | U/U | Public token expiry, claim once, wrong account, closed signup, cancel. |
| R08 | `/chat/:serverId/:channelId` | `views/ChatView.vue`; `components/ChatComponent.vue` | none | U | U/U | Member vs outsider/private/hidden/history boundaries, timeout, deleted channel; J04/J05. |
| R09 | `/chat/:serverId/thread/:threadId` | `views/ThreadFullView.vue`; `components/threads/ThreadView.vue` | none | U | U/U | Archived/locked/left/private parent, no history, deleted root; J06. |
| R10 | `/dm`, `/dm/:conversationId` | `views/DMView.vue`; `components/DMSidebar.vue` | none | U | U/U | Participant-only, left group, blocked user/DM policy, dismissed conversation, not found; J07. |
| R11 | `/social/home`, `/social/local`, `/social/public` | `views/TimelineView.vue`; `services/activityPubService.ts` | none | U | U/U | Follow-only vs local vs federated; blocks, deleted/unavailable posts, paging/offline; J08. |
| R12 | `/social/mentions`, legacy `/notifications` and `/social/notifications` | `views/MentionsView.vue`; `components/NotificationBell.vue` | none | U | U/U | Own notifications, read/unread consistency, vanished target; J09. |
| R13 | `/social/bookmarks` | `views/BookmarksView.vue` | none | U | U/U | Own bookmarks; removed/visibility-changed post; J08. |
| R14 | `/social/lists`, `/social/lists/:listId` | `views/{ListsView,ListDetailView}.vue` | none | U | U/U | Own/list visibility, deleted list, remote account resolution; J10. |
| R15 | `/social/followers`, `/social/following`, `/social/follow-requests` | `views/FollowersView.vue` | none | U | U/U | Own pending requests and accept/reject; remote unavailable, blocked; J10. |
| R16 | `/social/trending`, `/social/instances`, `/social/instances/:domain` | `views/{ExploreView,InstancePostsView}.vue`; `activitypub/{TrendingContent,InstancesContent}.vue` | none | U | U/U | Instance block/stale directory, limits, unavailable source, loading/empty; J10. |
| R17 | `/posts/:postId`, `/social/post/:postId`, `/social/posts/:handle/:noteId`, `/@:handle/:postId` | `views/PostView.vue`; `services/PostResolverService.ts`; `src/router/index.ts::UUID_PARAM` | none | U | U/U | The canonical handle/post route constrains postId to UUID; remote resolve, visibility, tombstone, reply context/highlight, unavailable root; J08. |
| R18 | `/@:handle`, `/users/:username`; profile legacy redirects | `views/UserProfileView.vue`; `router/index.ts::canonicalProfileParam/publicPageEntry` | none | U | U/U | Local/remote/moved/blocked/deleted actor, signed-out return target, canonical domain; J10. |
| R19 | `/tags/:tag`; hashtag legacy redirect | `views/HashtagView.vue`; `utils/permalinks.ts::tagPath` | none | U | U/U | Unicode/escaped tag, empty/blocked posts, paging; J08. |
| R20 | `/settings/:section?` | `views/UserSettings.vue:404-423` | none | U | U/U | Own account, capability-gated stickers; phone list/back vs direct section; J11-J15. |
| R21 | `/server/:serverId?section=…` | `views/ServerSettings.vue:509-551` | none | U | U/U | Exact per-permission sections; remote copy read-only/instance tools; J16/J17. |
| R22 | `/admin?tab=…` | `views/AdminPanel.vue:259-290` | none | U | U/U | Staff guard; moderator seven-tab subset vs admin; feature capabilities; J18. |
| R23 | `/drive/:section?/:id?`, `/drive/file/:fileId` | `views/{DriveView,DriveFileView}.vue` | none | U | U/U | Auth; Drive switch; owner/shared access; expired/gone state; J19/J20. |
| R24 | `/d/:token` | `views/DriveLinkView.vue`; `components/drive/{DrivePublicPage,DriveFilePage}.vue` | none | U | U/U | Public page vs authenticated reader; restricted/expired/reset token indistinguishable missing; J20. |
| R25 | `/authorize_interaction` | `views/AuthorizeInteractionView.vue` | none | U | U/U | Resolve external actor/post safely; invalid URI, blocked domain; sign-in continuation. |
| R26 | `/share` | `views/ShareView.vue`; `public/manifest.json::share_target` | none | U | U/U | Auth continuation, text/URL selection, target permissions, cancel; native ACTION_SEND is separate J24. |
| R27 | `/today` | `views/TodayView.vue`; `composables/useTodayDashboard.ts`; `src/services/TodayDigestService.ts` | none | U | U/U | Dashboard defaults on; flags are per-user/per-device localStorage, not account-synced. Mentions/DM/channel digest, unavailable targets/empty/offline; optional runtime-gated on-device AI is U24/J09. |
| R28 | `/404`, `/404-public`, catch-all | `views/NotFoundView.vue`; `components/error/NotFound404.vue` | none | U | U/U | Signed-in/out chrome, safe back/home; never leak inaccessible target. |
| R29 | `/demo`, `/audio-demo` | `components/demo/{RichTextDemo,AudioThemeShowcase}.vue` | none | U | U/U | Public developer/demo surfaces; audio permission/cancel. Not silently excluded from literal complete parity; agree scope explicitly if excluding them. |
| R30 | `/explore`, `/profile/:handle`, `/social/profile/:handle`, `/social/hashtag/:tag`, `/social/:timeline` | `src/router/index.ts` redirects | none | U | U/U | Preserve parameters/query/hash; no redirect loop or cross-instance token leak. |

## Chat, DMs, threads, menus, and expression

| ID | Surface / interactions to verify | Exact official code | Native | Contract | Android | Access controls / failure cases |
|---|---|---|---|---|---|---|
| C01 | Server rail, unread/mention badges, server folders/reorder, mark read/mute/leave | `components/serverRail/{ServerRail,ServerContextMenu}.vue`; `components/{ServerFolder,ServerFolderSettingsModal,ServerNotificationSettingsModal}.vue` | partial | U | U/U | Only joined servers, remote availability, deleted folder, undo/dismiss, phone long-press; J03/J04. |
| C02 | Channel/category navigation, collapsed/muted/read state, context menus | `components/{ChannelSidebar,ChannelContextMenu,CategoryContextMenu}.vue`; `services/categoryPermissions.ts` | none | U | U/U | VIEW_CHANNEL/history cutoff; denied private category, channel moves and stale permissions; J04/J16. |
| C03 | Message paging, jump/latest/unread marker, read receipts, typing/presence | `components/{MessageDisplay,TypingIndicator}.vue`; `services/{readState,TypingIndicatorService,RealtimeConnectionManager}.ts` | none | U | U/U | Private realtime joins; tab/app attention, reconnect without duplicate/gap, blocked sender, permission revoked; J04/J23. |
| C04 | Compose/send/edit drafts, reply mention/cancel, keyboard/IME, retry failed send | `components/{MessageInput,RichTextEditor,MessageReply}.vue`; `services/core/CoreMessageService.ts` | none | U | U/U | SEND_MESSAGES, max length/attachments, slow mode/timeout, AutoMod dropped-row refusal, no duplicate retry; J04/J05. |
| C05 | Structured rich content: mentions/user/role/here/everyone, links/markdown/code, spoiler, emoji, system events | `components/{UnifiedMessageContent,UnifiedContentRenderer,MarkdownContent,AutoSuggest}.vue`; `messages/{RoleMentionCard,SystemEventBody}.vue` | none | U | U/U | Mention permissions, safe links/HTML, blocked/hidden media, bridged authors, encrypted placeholder not ciphertext dump; J04/J05. |
| C06 | Reply/edit/delete/copy text/link/ID, embed suppression, report, pin/unpin | `components/MessageContextMenu.vue`; `messages/MessageFloatingActions.vue`; `components/PinnedMessagesPopup.vue` | none | U | U/U | Own edit/delete vs MANAGE_MESSAGES; remote restrictions; deleted reply/pin; destructive confirmation; J05. |
| C07 | Reactions, quick-react double tap, long-press users modal, remove own | `components/MessageReactions.vue`; `messages/ReactionsModal.vue`; `services/core/CoreInteractionService.ts` | none | U | U/U | Reaction limit, no permission, deleted emoji, duplicate toggle, encrypted content boundary; J05. |
| C08 | Emoji/GIF/sticker picker, favorites/search/custom source info/install packs | `components/{EmojiPopup,EmojiPickerContent,GifPickerContent,MediaPickerPopup}.vue`; `emoji/EmojiInfoCard.vue`; `stickers/StickerPickerSection.vue` | none | U | U/U | Server/personal capability/limits, safe animated media settings, provider outage/attribution, purged emoji; J05/J12. |
| C09 | Message polls: create options, multi/single vote, results, end | `components/polls/{PollCreateModal,PollCard}.vue`; `services/MessagePollService.ts` | none | U | U/U | Closed/deleted poll, nonparticipant, vote races, option constraints; J05. |
| C10 | Search modal/filter syntax, result jump, encrypted local history scan | `components/search/{MessageSearchModal,SearchQueryInput,SearchResultItem}.vue`; `services/{SearchService,search/encryptedHistoryScan,search/encryptedSearchIndex}.ts` | none | U | U/U | Channel visibility/history cutoff, keys locked, no remote encrypted plaintext query; progress/cancel/empty; J05/J14. |
| C11 | New DM picker, request policy, block/unblock, user/conversation mute, dismiss/undo, unread and call indicators | `components/DMSidebar.vue`; `components/dm/DMHeader.vue`; `services/DMCallPermissions.ts` | none | U | U/U | DM policy everyone/known/following, blocked/removed participant, nonmember enumeration; mute suppresses call ringing/popup rather than being the same as blocking; J07. |
| C12 | Group DM name/icon, invite/member removal, leave/ownership, group settings | `components/dm/{GroupChatInviteModal,GroupSettingsModal}.vue` | none | U | U/U | Owner vs member, max participants, left membership, closed invitation, denied mutate; J07. |
| C13 | Threads list/sidebar/full screen, create from root or channel, join/leave/mute | `components/threads/{AllThreadsModal,ThreadSidebar,ThreadView}.vue`; `services/{ThreadService,ThreadSidebarService}.ts` | none | U | U/U | Parent channel visibility, departures, membership/history and encryption; J06. |
| C14 | Thread rename/archive/unarchive/lock/delete/auto-archive | `components/{ThreadEditModal,threads/ThreadContextMenu}.vue` | none | U | U/U | Creator/moderator rights, locked/archive send, expired/deleted parent, refused edit; J06. |
| C15 | User/bot previews, server identity, status activity/custom status, nickname, role assignment | `components/{UserProfileModal,UserContextMenu,BotProfileModal,StatusPicker}.vue`; `moderation/ChangeNicknameDialog.vue` | none | U | U/U | Member roles/hierarchy, own vs moderator, remote identity, trusted links, expired status; J10/J16. |
| C16 | Bot slash commands, options/suggestions/invocation result/interaction error | `components/BotCommandBar.vue`; `messages/BotInteractionNotice.vue`; `services/botCommandService.ts` | none | U | U/U | Server bot scope, disabled/missing command, ephemeral notice, permission denied/timeouts; J05/J17. |
| C17 | Server member directory/sidebar search, counts, collapsible role/status groups, online/offline and member actions | `src/components/UserSidebar.vue` | none | U | U/U | Membership/visibility, role changes and presence refresh; bots/Discord/federated identities, long-press/keyboard/virtualized accessibility, empty search; J16/J25. |

## Social and discovery surfaces beyond routes

| ID | Surface / interactions | Exact official code | Native | Contract | Android | Gate / failure |
|---|---|---|---|---|---|---|
| S01 | Post composer: rich text, reply/quote, CW/visibility, media/alt text, drafts, poll, edit/delete | `components/activitypub/{Composer,MediaEditDialog,MonyMediaUpload}.vue`; `services/core/CorePostService.ts` | none | U | U/U | Public/unlisted/followers/direct audience; size/media limit, offline/retry, author-only edit; J08. |
| S02 | Posts: like/reaction, boost/unboost, quote, bookmark, reply, copy/permalink, report/block | `components/activitypub/{MonyPost,PostReactions,PostInteractionsDialog}.vue`; `services/activityPubService.ts` | none | U | U/U | Visibility/follow rules; remote action restrictions, dedup reblogs, deleted target; J08. |
| S03 | Conversation ancestors/replies/highlight, remote replies refresh/status | `views/PostView.vue`; `activitypub/{ReplyContextPreview,RemoteRepliesStatus}.vue`; `services/ConversationService.ts` | none | U | U/U | Missing/private ancestor, cyclic/missing remote links, retry/import failure; J08. |
| S04 | Remote polls, vote/result refresh | `components/polls/RemotePollCard.vue`; `services/RemotePollService.ts` | none | U | U/U | Closed/multiple vote/remote host outage; no false locally successful vote; J08. |
| S05 | Profile posts/replies/media grid, metadata links/follow counts, moved account | `views/UserProfileView.vue`; `activitypub/{ProfileMediaGrid,MovedAccountNotice,UserCard}.vue` | none | U | U/U | Follow approval/private posts, moved target, blocked actor, deleted media; J10. |
| S06 | Follow/unfollow/request accept/deny, follower removal, lists create/rename/delete/member picker | `views/{FollowersView,ListsView,ListDetailView}.vue`; `activitypub/UserSearchModal.vue` | none | U | U/U | Own requests/list ownership; pending remote response, duplicate membership, cancelled confirmations; J10. |
| S07 | Instance source picker/detail, trending people/posts/tags, remote/local server discovery | `activitypub/{InstanceSourcePicker,InstanceDetailModal,TrendingContent}.vue`; `components/PublicServers/{RemoteDirectory,PublicServersContent}.vue` | none | U | U/U | Blocked/unreachable host, cached/stale data, disabled federation, reject remote membership; J03/J10. |
| S08 | Funding/support links, progress/supporter wall/message, badges | `components/{FundingModal,funding/SupportMessageComposer,funding/SupporterWall}.vue`; `services/FundingService.ts` | none | U | U/U | Instance configuration, private message validation, cancelled/failed external payment, supporter privacy; no fabricated billing success. |
| S09 | Announcements popup/history/read count, Today digest and linked conversation previews | `components/announcements/AnnouncementPopup.vue`; `settings/user/AnnouncementsSettings.vue`; `views/TodayView.vue` | none | U | U/U | User-only read state, announcement disabled/withdrawn, digest target access changes; J09. |

## User settings: each page, control family, dialog and persistence

Every row requires save/reload, cancel/reset, account-vs-device scope, signed-out
re-entry, and web/native round-trip assertions, not just opening the section.

| ID | Official page / non-route surfaces | Exact code | Native | Contract | Android | Gates / required negative cases |
|---|---|---|---|---|---|---|
| U01 | Account: display/username/bio/fields, avatar/banner/crop/focal point, Discord sync | `settings/user/UserAccountSettings.vue`; `common/{ImageCropDialog,FocalPointPicker}.vue`; `src/components/settings/user/DiscordSyncFields.vue` | none | U | U/U | Own profile; taken/invalid username, image limits/metadata, upload denied, provider-owned fields; J11. |
| U02 | Per-server profile: nickname/avatar/banner and Discord synced fields | `settings/user/ServerProfilesSettings.vue`; `services/serverProfileService.ts` | none | U | U/U | Member vs nonmember, remote restrictions, reset vs unsaved edits, upload failure; J11. |
| U03 | Security: set/change password and provider-managed restrictions | `settings/user/SecuritySettings.vue::changePassword`; `services/AccountSecurityService.ts`; `settings/user/UserAccountSettings.vue` (email display only) | none | U | U/U | Reauthentication, invalid current password, external-provider refusal. No email-change settings control exists here; adding one is beyond reference parity. Registration/reset email flows remain R03/R04/J02; J13. |
| U04 | TOTP enroll QR/secret, challenge, recovery codes, regenerate/disable | `settings/user/TwoFactorSettings.vue`; `components/AuthComponent.vue` | none | U | U/U | Wrong/stale OTP, step-up/AAL2, cancel enrollment, recovery-code single use, no secret log; J13. |
| U05 | Sessions list/device details, revoke other sessions | `settings/user/SessionsPanel.vue`; `services/SessionHeartbeat.ts` | none | U | U/U | Own sessions only; the current-session row has no revoke button (logout is separate). Test unauthorized/current-session API attempts separately, expired JWT and refresh after revoke; J13/J23. |
| U06 | Follow approval + DM audience policy, blocked and muted users, unblock/unmute | `settings/user/PrivacySettings.vue:22-74,152-215`; `components/UserProfileModal.vue::toggleMute`; `services/DMCallPermissions.ts` | none | U | U/U | Policy persistence/rollback on failure, unsupported capability, blocked vs muted message/call behavior, failed unblock/unmute; J07/J10. |
| U07 | URL tracker removal, outside-link consent/trusted sites, default photo metadata | `settings/user/PrivacySettings.vue:76-150`; `components/OutsideLinkDialog.vue`; `services/outsideLinks.ts` | none | U | U/U | Scheme/IDN/userinfo spoof, no browser installed, revoke trust, no accidental GPS upload; J12/J24. |
| U08 | Export data, migration/aliases/move, verified-origin profile-field copy, following import, destructive deletion | `settings/user/{DataExportPanel,AccountMigrationPanel,AdvancedSettings}.vue`; `services/{DataExportService,AccountMigrationService,AccountDeletionService}.ts` | none | U | U/U | Own authenticated account/provider auth; following-import capability; CSV/JSON/ZIP preview, job counts/failures/wait/cancel; selected profile-field copy confirmation; move/undo rules, failed export/save, strong deletion confirmation; J13/J26. |
| U09 | Encryption setup/recovery/unlock, devices/approval/revoke, maintenance and local backup dialogs | `components/encryption/{EncryptionSettings,RecoveryKeySetupWizard,KeyRecoveryModal,DeviceManager,DeviceApprovalPrompt}.vue` | none | U | U/U | See E01-E10/J14, including sync/diagnostics, manual backup, local import/export, destructive reset and retained pairing-copy removal. A privacy toggle is not encryption. |
| U10 | My bots create/edit/delete/token rotate/commands/config | `settings/user/UserBotsManagement.vue` | none | U | U/U | Own bot only, token visibility/copy consent, rotation invalidates old, rejected schema; J15. |
| U11 | Personal/server/instance emoji library, favorites/search/import/upload/rename/delete | `settings/user/MyEmojisSettings.vue`; `settings/EmojiLibraryManager.vue`; `services/UserEmojiService.ts` | none | U | U/U | Capability/quota/MIME/emoji name constraints, duplicate/import errors; J12/J15. |
| U12 | Stickers library upload/edit/delete, personal/server/instance scopes | `components/stickers/{StickerSettings,StickerLibraryManager}.vue` | none | U | U/U | Held stickers remain visible at zero cap; manager permissions and dimensions/format limits; J15. |
| U13 | Appearance: account/device sync plus which copy to adopt | `settings/user/AppearanceSettings.vue:14-51`; `composables/useVisualTheme.ts` | partial | U | U/U | Existing native theme/state maps only part of settings; device persistence absent for full theme; failed save rollback and second-device sync; J12. |
| U14 | Light/dark/system/custom, saved themes, presets, palette ideas, full color controls/live editor/import/export | `settings/user/{AppearanceSettings,ThemeCustomizerPanel,ThemePaletteIdeas}.vue`; `ui/theme` native folder is only partial mapping | partial | U | U/U | Bad imported theme, constrained palette values, cancel live preview/reset/delete confirmation, OS mode change; J12. |
| U15 | Skins/options, typography/font/size/zoom, high contrast/reduce motion/screen-reader/message announcements | `settings/user/AppearanceSettings.vue:463-637,939-1005`; `a11y/MessageAnnouncementSetting.vue` | partial | U | U/U | Large font/layout, TalkBack focus order and live regions; reduce motion genuinely suppresses motion, not only stores setting; J12/J25. |
| U16 | Message appearance: timestamps/24h/compact, bridge badges/custom display-name emoji/greentext/emoticons/animation/invite banner | `settings/user/AppearanceSettings.vue:640-805` | partial | U | U/U | Every display surface honors settings, timezone/DST/locale and bridged identities; J12. |
| U17 | Video player/floating video; mirror links; quick react double tap/emoji pack; blur/glass | `settings/user/AppearanceSettings.vue:698-727,807-958` | none | U | U/U | Native-equivalent behavior agreed, not ignored CSS settings; reduce motion, unsafe mirrors, device unavailable; J12/J21. |
| U18 | Notifications: overall/types/mentions/chat/social, sounds/previews, quiet/DND, registrations and haptic preferences | `settings/user/NotificationSettings.vue`; `components/ServerNotificationSettingsModal.vue`; `services/notificationSettings.ts`; `src/services/NotificationFormatter.ts` | none | U | U/U | Own prefs, muted server/channel, background policy/permission, per-device vs account; encrypted-message previews redact content; U23 haptic controls; J09/J22. |
| U19 | Android transport/distributor choice/status/test/remove | `settings/user/AndroidPushSettings.vue`; `services/androidPush.ts::chooseTransport` | none | U | U/U | No Play services/config, relay project mismatch, many/no distributors, five failures/reregister age, server unavailable; J22. |
| U20 | Voice/video/input/output, mic test/gain/PTT/VAD/processing/device selection | `settings/user/{VoiceVideoSettings,VoiceSettingsInline}.vue`; `voice/{VoiceSettingsPanel,DeviceSelector,VoiceInputModeSettings}.vue` | none | U | U/U | Permission denied/hardware absent/device disconnected, test ends on leave, device/local scope; J21. |
| U21 | Keybinds recorder/conflict/reset, audio themes/preview/import/manage/ringer volume | `settings/user/{KeybindSettings,AudioThemeSettings}.vue`; `settings/{AudioThemeManager,AudioThemePicker}.vue` | none | U | U/U | Desktop-only global shortcuts explicitly not Android; in-app keyboard/PTT where supported, invalid pack, audio focus/DND; J15/J21. |
| U22 | Language picker, advanced behavior/beta/debug/build/version/update footer | `settings/user/{LanguageSettings,AdvancedSettings,SettingsBuildFooter}.vue`; `updater/AndroidUpdateNotice.vue` | none | U | U/U | Locale/RTL/plural rules, unsupported option, persist/restart, missing/failed release check, beta gating; J15/J25. |
| U23 | Mobile haptics master, per-trigger controls and test | `src/components/settings/user/NotificationSettings.vue:496-616`; `src/utils/platform.ts::supportsHaptics`; `src/composables/useHapticSettings.ts` | none | U | U/U | Runtime supportsHaptics gate, not all desktops or Android devices by assumption. Messages/reactions/navigation/interactions/toggles/destructive/voice; disabled triggers do not vibrate; test actual hardware/persistence; J09/J25. |
| U24 | Today beta dashboard and on-device AI highlights | `src/components/settings/user/AdvancedSettings.vue`; `src/composables/useTodayDashboard.ts`; `src/services/TodayDigestService.ts::isOnDeviceAiSupported/isOnDeviceAiAvailable/getChannelHighlights` | none | U | U/U | Per-user/per-device local flags; dashboard defaults on, AI auto-on only when already available. Requires Summarizer.availability() == available, skips encrypted messages, no cloud fallback/model-download claim; unsupported Android runtime remains gated; J09. |

## Encryption, cross-device history, and trust (release blockers)

Official limits are part of the privacy contract, not parity successes to infer:

- `src/services/encryption/MegolmService.ts:28-33`: deterministic per-index HKDF
  has **no forward secrecy**; a copied session key derives past/future message
  keys until rotation (100 messages or 7 days).
- `src/services/encryption/devicePairing.ts:31-72`: keeping a pairing copy retains
  extractable recovery-derived encryption/backup keys. Revocation clears local
  storage only when the device next starts; it cannot erase exfiltrated keys and
  does not itself rotate recovery keys. Resetting encryption rotates them.
- `src/locales/en.json` (`attachmentNote`) and
  `src/services/messageMediaUpload.ts::startMessageMediaUpload`: file bytes remain
  unencrypted; links/names/types are encrypted with message content. Storage RLS
  and signed URLs, not message E2EE, protect object access.
  `src/services/core/channelMessageEncryption.ts::encryptChannelContent` also
  retains plaintext media paths and mention metadata for authorization/delivery.
- `src/services/encryption/RecoveryKeyService.ts::deriveKeysFromMnemonic` uses
  PBKDF2 SHA-512 (100000 iterations, salt harmony-e2ee-recovery-v1), then HKDF
  SHA-256 with zero salt and Harmony-specific info strings. A generic BIP39 seed
  derivation is not equivalent; legacy non-checksum phrases remain supported.

| ID | Real feature / invariant | Exact official code | Native | Contract | Android | Required failure / adversarial evidence |
|---|---|---|---|---|---|---|
| E01 | Recovery mnemonic generation/checksum/confirmation, Harmony-specific derived keys, restore | `services/encryption/RecoveryKeyService.ts::validateMnemonic/validateMnemonicStrict/deriveKeysFromMnemonic`; `encryption/RecoveryKeySetupWizard.vue` | none | U | U/U | Bad word/count and wrong recovery key; newly generated checksum correctness; legacy non-checksum phrases remain restorable (checksum failure is a nonblocking typo hint). Verify Harmony PBKDF2/HKDF vectors, not standard BIP39 seed substitution; no secret logs or unconsented clipboard/server plaintext; J14. |
| E02 | Encrypted at-rest sessions and encrypted server backup/version merge | `services/encryption/{SecureSessionKeyStore,MegolmService,MegolmKeyBackupService}.ts` | none | U | U/U | Corrupt/wrong-owner backup, concurrent edits, restore on second device, lock/sign-out clears plaintext memory; J14. |
| E03 | P-256 ECDH share + device ECDSA identity/signature verification and TOFU key-change notice | `services/encryption/{DeviceIdentityService,MessageSigner,MegolmMessageEncryptionService}.ts`; `src/services/encryption/MegolmMessageEncryptionService.ts::checkAndPinSigningKey` | none | U | U/U | Official changed user signing keys are accepted/re-pinned with a nonblocking identity-change event, not mandatory verification or decryption refusal. Test notice/persistence, invalid signatures and room/sender/AAD binding; hard key-change rejection would be additional policy; J14. |
| E04 | Device pairing scan/show QR/code, sealed bundle/proof, approve/deny/expiry and pairing-copy choice | `services/encryption/{DevicePairingService,devicePairing}.ts`; `encryption/{DeviceLinkModal,DevicePairingPanel,QrScanner,PairingCodeField}.vue` | none | U | U/U | New-device code 10min, approver code 5min, 60s clock skew (`src/services/encryption/devicePairing.ts` constants); replay/wrong account/denial/cancel/approval race, no unsealed Realtime keys. DeviceLinkModal keepCopy defaults true; exercise both choices and post-reload approval; E10/J14. |
| E05 | Harmony Megolm envelopes/index serialization/session rotation and legacy compatibility | `services/encryption/MegolmService.ts:13-33`; `src/services/encryption/MegolmMessageEncryptionService.ts::encryptMessage/decryptMessage` | none | U | U/U | Signed v2/v3 cross-client vectors; v3 device key/epoch/AAD; unsigned v1 refusal. Official sending can fall back to signed v2 without a device signing key. Concurrent sends/index reuse, replay/stale epoch, 100-msg/7-day rotation, malformed payload; no forward-secrecy claim; J14. |
| E06 | Effective channel/server encryption inheritance and plaintext refusal race | `services/{ChannelEncryptionService,core/channelMessageEncryption}.ts`; `components/{ChannelEncryptionSection,settings/ServerEncryptionSettings}.vue` | none | U | U/U | Setup/unlock/failed/unavailable/changed reasons, no plaintext downgrade even if policy changes during send; J14/J16. |
| E07 | DM encryption and explicit opt-in plaintext fallback only where official policy allows | `services/conversationEncryption.ts`; `core/CoreMessageService.ts::SendOptions`; `components/EncryptionFallbackModal.vue` | none | U | U/U | Cancel fallback sends nothing; stale encrypted refusal, edit path cannot leak plaintext, mixed recipients; J07/J14. |
| E08 | Epoch membership/history grants/pinned-message keys, local encrypted search | `src/services/encryption/RoomEpochService.ts`; `search/{encryptedHistoryScan,encryptedSearchIndex}.ts`; `services/channelHistoryCutoff.ts` | none | U | U/U | Leave/rejoin and history grants, limited pinned keys, cache purge and epoch lookup failure/30s cache behavior. Revoking a device does not erase copied keys/history or itself rotate recovery keys; distinguish server refusal from cryptographic revocation; J14. |
| E09 | Encrypted attachment refs/names/types, locked/decryption-failed UI, retry after key received | `src/services/core/channelMessageEncryption.ts::encryptChannelContent`; `src/services/privateMedia.ts::messageMediaPathsIn`; `components/encryption/{EncryptionIndicator,EncryptedGlyphPreview}.vue` | none | U | U/U | File bytes remain unencrypted in storage; RLS/signed URL authorization is a separate boundary. Plaintext media_paths and mention IDs remain server-visible. Check minimized upload metadata, inaccessible object/no key and graceful placeholders; J14/J19. |
| E10 | Encryption maintenance/recovery-info, manual server backup, encrypted local backup import/export, destructive reset and pairing-copy removal | `src/components/encryption/EncryptionSettings.vue::syncKeys/runDiagnostics/createBackup/exportBackupFile/importBackupFile/resetEncryption/removePairingCopy`; `src/components/encryption/DeviceLinkModal.vue::keepCopy` | none | U | U/U | Corrupt/wrong-key/wrong-account file, save/picker cancel, concurrent sync/backup, reset cancel/confirm; retained extractable pairing-copy choice/default true, remove/reload/re-approve without copy requires recovery phrase. Revocation cannot erase an already-exfiltrated copy; J14. |

## Servers, membership, moderation and integrations

| ID | Surface / interactions | Exact official code | Native | Contract | Android | Access / failures |
|---|---|---|---|---|---|---|
| G01 | Create server/import template, public/local/remote discovery/join/leave/delete/ownership | `components/{CreateServer,PublicServers,JoinFederatedServer,ServerDropdown}.vue`; `services/{serverTemplateService,serverMembershipService,federation/FederationServerService}.ts` | none | U | U/U | Owner vs member, disabled federation, private invite, banned/refused host, duplicate join; J03/J16. |
| G02 | Invite creation/copy/revoke, duration/max uses/federated URLs; rules acceptance/onboarding | `components/InviteModal.vue`; `invite/ServerRulesModal.vue`; `welcome/{ServerWelcomeScreen,OnboardingServerSuggestions,RulesAcceptPrompt}.vue` | none | U | U/U | CREATE_INVITE, expired/exhausted/revoked invite, changed rules, optional suggestion permissions; J03. |
| G03 | Channel/category create/edit/delete/reorder/topic/type/slow mode/NSFW/privacy | `components/{CreateChannel,ChannelEditModal,CategoryCreator,CategoryEditModal,CategoryPrivacyFields}.vue` | none | U | U/U | MANAGE_CHANNELS, invalid names, role/member overrides, hidden category, remote restrictions; J16. |
| G04 | Permission sync, tri-state overrides and inherited encryption/voice policy | `components/{ChannelPermissionSync,ChannelPermissionTriState,ChannelEncryptionSection}.vue`; `services/permissionsService.ts` | none | U | U/U | Role hierarchy, overwrite deny/allow precedence, read-only copy, policy changed under editor; J16/J14. |
| G05 | Overview: name/icon/banner/description/rules/category, save/discard; system/newcomer/thread-sidebar defaults | `settings/ServerBasicInfo.vue`; `settings/server/{ServerSystemMessages,ServerNewcomerAlerts,ServerThreadSidebar}.vue` | none | U | U/U | MANAGE_SERVER, remote copies read-only; upload/validation errors and unsaved section switch; J16. |
| G06 | Welcome screen/rules/channels/suggestions settings and preview/template export | `settings/server/{ServerWelcomeSettings,ServerTemplateExport}.vue`; `services/ServerWelcomeService.ts` | none | U | U/U | Local host only, stale channel selection, invalid suggested server, unsaved preview; J16. |
| G07 | Roles list/order/create/edit/delete/colors/permissions, member grants | `settings/RoleManagement.vue`; `services/RoleService.ts` | none | U | U/U | MANAGE_ROLES and own hierarchy, cannot grant above power, managed/bot roles, deny database writes; J16. |
| G08 | Privacy/federation/join settings/server encryption | `settings/{ServerPrivacySettings,ServerEncryptionSettings}.vue` | none | U | U/U | Local owner/admin rights, metadata exposure, key lock and encrypted voice gates, unsaved confirmation; J14/J16. |
| G09 | Server emoji/sticker/soundboard upload/edit/delete/import and preview | `settings/ServerEmojiManagement.vue`; `stickers/StickerSettings.vue`; `settings/server/ServerSoundboardManagement.vue` | none | U | U/U | MANAGE_EMOJIS/caps, local-only sounds, limits/duration/audio convert errors, hash removed; J17/J21. |
| G10 | Bans, kick/ban/timeout/nickname/report, server audit log | `settings/server/{ServerBans,ServerAuditLog}.vue`; `moderation/{KickBanModal,ReportModal,ChangeNicknameDialog}.vue` | none | U | U/U | BAN_MEMBERS/moderation hierarchy, cannot moderate owner/self/above-role, reason/expiry/undo, audit pagination; J16/J18. |
| G11 | AutoMod opt-in/rules/edit/test/exemptions/action/log | `settings/server/{ServerAutoMod,AutoModRuleCard,AutoModOptInBanner}.vue`; `services/AutoModService.ts` | none | U | U/U | MANAGE_SERVER/local, blocked message isn't inserted, timeout surfaced, invalid regex/empty rule; J17. |
| G12 | Incoming/outgoing webhooks create/rotate/delete/test/deliveries | `components/{ChannelWebhooksSection,OutgoingWebhooksPanel}.vue`; `services/{channelWebhookService,outgoingWebhookService}.ts` | none | U | U/U | MANAGE_WEBHOOKS/capability/local, secrets not logged, SSRF/URL policy/error delivery; J17. |
| G13 | Server bots/install/manage permissions/config | `settings/ServerBotsSettings.vue`; `services/serverBotsService.ts` | none | U | U/U | MANAGE_SERVER/install rights, bot permissions/token rotation, removed bot command; J15/J17. |
| G14 | Discord bridge setup/status/mapping and full Discord migration wizard | `settings/DiscordBridgeSetup.vue`; `settings/discord-bridge/`; `settings/discord-migration/`; `services/discordMigrationService.ts` | none | U | U/U | MANAGE_SERVER, OAuth denial, bot permissions, unsupported mapping, interrupted progress/retry, unclaimable token; J17. |
| G15 | Advanced delete/transfer/settings; instance view of remote-copy block/remove member | `settings/ServerAdvancedSettings.vue`; `settings/server/{InstanceServerPanel,ServerCopyBanner}.vue`; `moderation/{InstanceBlockServerModal,InstanceRemoveMemberModal}.vue` | none | U | U/U | Host owner vs local instance staff; don't mutate remote host using local staff rights, confirmation/destructive failure; J16/J18. |
| G16 | Server-scoped reports queue, filters/evidence/actions/resolve | `src/views/ServerSettings.vue`; `src/components/admin/ReportsModeration.vue` with server-id | none | U | U/U | canModerateReports is separate from instance-staff access; scope cannot be widened via REST, inaccessible/deleted evidence, stale report/action race and rights revoked; J16/J18. |

## Instance administration: not excluded on a phone

`views/AdminPanel.vue:259-290` exposes 17 tabs. Moderators can access Federation
(read-only), Users, Signups, Reports, Servers, Anti-spam and Webhooks; admin-only
controls inside those tabs still require their own authorization. A route guard
alone does not fulfill this matrix.

| ID | Tab / dialogs / actions | Exact official source (full paths or prefix aliases above) | Native | Contract | Android | Gate / failures |
|---|---|---|---|---|---|---|
| A01 | Overview stats/activity/health | `views/AdminPanel.vue`; `src/components/admin/ActivityLog.vue` | none | U | U/U | Admin; failed section independent loading, pagination/time range. |
| A02 | Federation domains/detail/block/allow/config, remote servers | `src/components/admin/FederationManagement.vue`; `src/components/admin/RemoteServersAdmin.vue` | none | U | U/U | Moderator read-only vs admin write, remote errors, federation off. |
| A03 | Users search/detail/suspend/unsuspend/delete/roles, bulk suspend | `src/components/admin/UserManagement.vue`; `src/components/admin/BulkSuspendDialog.vue`; `src/components/admin/StaffActionDialog.vue` | none | U | U/U | Self/admin-protection, staff hierarchy/reason, partial bulk failure. |
| A04 | Recent signups/review approve/reject | `src/components/admin/RecentSignups.vue` | none | U | U/U | Signup-review capability, moderator/admin bounds, changed pending state; signup-limit controls are in A05 (Invites), not this tab. |
| A05 | Instance invites create/revoke/copy/uses, signup limits | `src/components/admin/{InstanceInvites,SignupLimits}.vue`; `src/views/AdminPanel.vue:145-148` | none | U | U/U | Admin + instance-invites tab; SignupLimits separately requires signup-limits capability; exhausted/expired/revoked invite, secret URL, invalid limit/failed save. |
| A06 | Retention/cohorts | `src/components/admin/RetentionCohorts.vue` | none | U | U/U | Admin, minimum data/privacy, range/load errors. |
| A07 | Reports filters/detail/evidence/action/resolve | `src/components/admin/ReportsModeration.vue` | none | U | U/U | Moderator server-scoped vs instance scope, inaccessible/removed evidence, action race. |
| A08 | Hosted servers/detail/instance staff remove/block controls | `src/components/admin/HostedServersAdmin.vue`; `src/components/admin/HostedServerDetail.vue`; `src/components/admin/hostedServerStaff.ts` | none | U | U/U | Instance moderator vs admin, local vs remote, cannot claim host ownership. |
| A09 | Anti-spam queue/allow/deny/clear/config | `src/components/admin/AntiSpamAdmin.vue` | none | U | U/U | Moderator action/admin config, stale item, repeat operation/error. |
| A10 | Outgoing webhooks inventory/disable/details | `src/components/admin/WebhooksAdmin.vue` | none | U | U/U | Capability, moderator disable vs admin controls, no secrets exposed. |
| A11 | Content announcements/featured communities/welcome server | `src/components/admin/AnnouncementsAdmin.vue`; `src/components/admin/FeaturedCommunities.vue`; `src/components/admin/WelcomeServerAdmin.vue` | none | U | U/U | Admin, invalid audience/server, preview vs publish, withdrew announcement. |
| A12 | Instance emoji library/import/source/row moderation | `src/components/admin/AdminEmojiLibrary.vue`; `src/components/admin/AdminEmojiRow.vue` | none | U | U/U | Admin, upload/import failure/limits, in-use/removed emoji. |
| A13 | Instance stickers | `components/stickers/StickerSettings.vue` (outside admin folder) | none | U | U/U | Admin/custom-stickers capability, format/quota and stale counts. |
| A14 | Config (all fields), upload limits/dialog, newcomer/thread defaults | `src/components/admin/InstanceConfig.vue`; `src/components/admin/UploadLimitsAdmin.vue`; `src/components/admin/UploadLimitDialog.vue`; `src/components/admin/NewcomerAlertsDefault.vue`; `src/components/admin/ThreadSidebarDefault.vue` | none | U | U/U | Admin, typed validation, restart-dependent values, rollback/failure; test each actual control, not one generic JSON editor. |
| A15 | Funding supporters/tier/config/messages/wall | `src/components/admin/FundingSupporters.vue` | none | U | U/U | Admin, financial/private fields, membership changes and rejected inputs. |
| A16 | Drive quota/settings/files/moderate hashes/sweep state | `src/components/admin/DriveAdmin.vue` | none | U | U/U | Admin, blocked-hash future uploads, quota/Drive switch, purge pending; J19/J20. |
| A17 | Tools: performance metrics, time range, refresh and fullscreen | `views/AdminPanel.vue`; `src/components/admin/PerformanceMonitoring.vue` | none | U | U/U | Admin; missing aggregation/empty data, failed query/refresh and unsupported fullscreen. The cited component does not expose cache-clearing or server-operation controls; those would need separate source evidence. |

## Attachments, Drive, downloads, media and external links

| ID | Surface / lifecycle | Exact official reference | Native at reference | Contract | Android | Access / failures / tests |
|---|---|---|---|---|---|---|
| M01 | Pick/drop/paste files, multi-file previews/remove/retry/cancel/progress/transfers | `components/{FileUploadMenu,FilePreview,MessageInput}.vue`; `common/{MediaUploadProgress,TransfersIndicator}.vue`; `services/uploads/storageUpload.ts` | none | U | U/U | SAF cancellation, size/MIME/svg/account limits, upload stall/resume, URI permission expiry; J19. |
| M02 | Photo/video metadata details dialog/default strip vs explicit keep, GPS indicator/alt text | `common/PhotoDetailsControl.vue`; `messages/AltTextDialog.vue`; `utils/stripMetadata.ts`; `services/drive/drivePhotoDetails.ts` | none | U | U/U | Hash after strip, unknown-container handling, no false 'removed' claim; JPG/PNG/WebP/video tests; J19. |
| M03 | Private channel/DM/thread objects, signed originals/thumbnails/expiry refresh | `services/privateMedia.ts`; `src/services/attachmentRefresh.ts`; `utils/storageImageUtils.ts` | none | U | U/U | `c/<channel>/<authUID>/…`, `d/<conversation>/<authUID>/…`, `u/<authUID>/…`; RLS revoked/expired URL; J19/J20. |
| M04 | Image gallery/lightbox/zoom/swipe/alt text/download/escape/back | `common/{MessageMediaGallery,MediaLightbox,MediaLightboxHost}.vue` | none | U | U/U | Broken image, hidden media preference, multi-image ordering, phone rotation/TalkBack; J19. |
| M05 | Audio/video/custom controls/fullscreen/PIP/floating player/seek/speed/volume/captions where offered | `components/common/VideoControls.vue`; `components/embeds/FloatingVideoPlayer.vue`; `components/UnifiedMessageContent.vue` | none | U | U/U | Actual codec playback, audio focus, rotate/back exit, expired stream/Range and network loss; J21. |
| M06 | Voice-message record/pause/cancel/level/upload/playback | `components/{VoiceRecorder,VoiceMessagePlayer}.vue`; `services/{voiceRecordingService,voice/voiceMessageLevel}.ts` | none | U | U/U | Runtime mic denial, empty/short recording, interruption/release resources, upload failure; J21. |
| M07 | Link/provider embeds, music/video/social/profile/invite/Drive, outside-link consent | `components/embeds/`; `components/OutsideLinkDialog.vue`; `services/outsideLinks.ts` | none | U | U/U | Safe scheme/origin, no app handler, embed suppress/hidden media, provider outage; J24. |
| D01 | My Drive/root/folder/all/recent/temporary/trash plus server/channel/DM/fediverse/shared views | `views/DriveView.vue`; `components/drive/DriveSidebar.vue`; `services/drive/driveService.ts::listDriveFiles/getDriveContextTree` | none | U | U/U | Drive enabled gate, own vs shared; nonowner sees no mutation; paging/search/kind/sort/grid-list; J19/J20. |
| D02 | Folder create/rename/move/delete, nested breadcrumbs, multi-selection/drag move | `drive/{DriveItems,DriveFolderNode,DriveMoveDialog,DriveNameDialog,DriveConfirmDialog}.vue`; `stores/drive.ts` | none | U | U/U | Duplicate/120-char/slash/cycle errors, owner only, folder deletion trashes contents; J19. |
| D03 | File details/where-used/rename/move/keep temporary/trash/restore/delete/empty trash | `drive/{DriveDetails,DriveGoneFile,DriveUsageMeter}.vue`; `src/services/drive/driveService.ts::getDriveFileUsage` | none | U | U/U | Temporary delete is permanent; quota partial keep returns actual count; references show gone; no mutation of shared file; J19. |
| D04 | Share user/server/channel, gap offer when posting existing file, revoke | `drive/{DriveShareDialog,DriveShareOffer}.vue`; `composables/useDriveShareOffer.ts`; `src/services/drive/driveService.ts::shareDriveFile/getDriveShareGap` | none | U | U/U | Owner only; no public post-media grants, target membership/expiry, privacy explicit before broader grant; J20. |
| D05 | Restricted/anyone link, expiry/show-owner, copy/reset; token page/public preview | `drive/{DriveShareDialog,DrivePublicPage,DriveFilePage}.vue`; `services/drive/driveService.ts::setDriveLinkAccess/resetDriveLink/getDriveFileCard` | none | U | U/U | Link sharing off; reset invalidates old token; restricted/expired/missing same denial, no leaking ID/path to public-link viewers; J20. |
| D06 | Drive upload/hash dedup/quota/temporary count/photo metadata; Drive switch | `src/services/messageMediaUpload.ts`; `src/services/drive/driveService.ts`; migrations `db_schema/migrations/20261106500001_drive_switch.sql` | none | U | U/U | Disabled hides feature but preserves uploads/hash moderation; blocked hash/full quota, temporary expiry, worker purge; J19. |
| D07 | Drive picker/reuse in chat and copy private media to public post storage | `drive/DrivePickerModal.vue`; `src/services/drive/driveService.ts::copyDriveFileToPostMedia`; `utils/driveLinkContent.ts` | none | U | U/U | Message visibility vs explicit sharing; copying for a public post intentionally changes access; J19/J20. |
| D08 | Download naming/signed URL/Range/resume/native Downloads | `services/{downloads,drive/driveService}.ts`; `src-tauri/gen/android/app/src/main/java/online/knowmad/harmony/HarmonyApplication.kt::downloadUrl/downloadName` | none | U | U/U | Names with `& # +`, path/control sanitization, private signing refusal, no space/expired URL, notification/cancel; J19. |

## Voice/video/calls, screen sharing and activities

| ID | Actual feature | Exact official code under `src/` | Native | Contract | Android | Mandatory evidence / failure |
|---|---|---|---|---|---|---|
| V01 | Voice channel preview/participants/join/leave/persistent dock/full overlay | `components/voice/{MobileVoiceChannelPreview,VoiceChannelParticipants,UnifiedVoiceDock,UnifiedVoiceOverlay}.vue`; `services/unifiedWebRTC.ts` | none | U | U/U | VIEW/CONNECT/SPEAK; full/locked/missing room, no grant, local/remote transport; J21. |
| V02 | DM/group outgoing/incoming/ringing/accept/decline/cancel/busy/missed call/chat notice | `services/{DMCallSignaling,GlobalDMCallListener,DMCallPermissions}.ts`; `components/dm/IncomingCallModal.vue`; `messages/CallJoinButton.vue` | none | U | U/U | Block/DM policy vs mute-suppressed ring, caller disappears, decline/end race. Official signaling uses running Realtime listeners; killed-app incoming-call wakeup/ringing is not established by generic message push and is additional native work; J21/J22. |
| V03 | Instance-configured LiveKit SFU or P2P signaling and ICE/TURN paths | `services/{livekitWebRTC,webrtcManager,unifiedWebRTC,livekitTokens}.ts`; `src/services/voice/transportPolicy.ts::selectCallTransport` | none | U | U/U | Test sfu/hybrid/p2p with/without configured SFU; all clients select the same transport. SFU connection failure must not silently fall back per-client to P2P. Two-client frames, NAT/TURN, token expiry/reconnect, no peers; J21/J23. |
| V04 | Mic/camera capture toggle/permission, camera switch; input/output select/audio route | `voice/{DeviceSelector,DeviceAccessNote,VoiceSettingsPanel}.vue`; `services/VoiceSettingsService.ts` | none | U | U/U | Denied/permanently denied/no device/hot unplug, audio focus/Bluetooth/wired headset on hardware; J21. |
| V05 | Mute/deafen/volume per person/own gain and mic test | `voice/{VolumeSlider,TileVolumeControl}.vue`; `voice/useMicTestStatus.ts`; `services/voice/{micGain,remoteAudioMixer}.ts` | none | U | U/U | Persist local prefs, deafen cannot leak audio, clip/gain extremes, stop mic test on exit; J21. |
| V06 | Voice activity/PTT/input mode/keybind/echo/noise processing/speaking indication | `voice/{VoiceInputModeSettings,PushToTalkButton,KeybindRecorder,RecentSpeakers}.vue`; `services/voice/speakingDetector.ts` | none | U | U/U | Stuck key/focus loss/background, actual VAD gates outbound audio, Android-specific availability; J21. |
| V07 | Video tiles/layout/pin/fullscreen/minimize, stream quality/adaptive receive | `voice/{VoiceTile,DockVideoStrip,StreamQualityPicker,StreamQualityOptions}.vue`; `services/voice/{streamQuality,adaptiveStreamSettings}.ts` | none | U | U/U | Orientation/large screen/bandwidth changes/codec failure, placeholder tracks; J21. |
| V08 | Screenshare source/quality/start/stop/PIP | `voice/ScreensharePIP.vue`; `services/{webrtcManager,livekitWebRTC}.ts` | none | U | U/U | Official Android availability must be checked at runtime; native MediaProjection consent/stop notification/lifecycle if supported. Do not assume desktop getDisplayMedia parity on Android. |
| V09 | Supported voice E2EE key gate/coordinator/wrap/request/rekey/badge | `services/encryption/VoiceE2EEService.ts`; `services/voice/voiceEncryptionGate.ts::callRequiresE2EE`; `voice/VoiceEncryptionBadge.vue` | none | U | U/U | E2EE requires SFU; P2P + required E2EE refuses join. Local channel/DM policies are not a promise of Megolm E2EE for federated/token joins. Setup/unlock refusal, participants-only key distribution, mismatched keys/no silent downgrade; J14/J21. |
| V10 | Voice moderation: server mute/deafen/move/disconnect and drag member | `voice/{VoiceUserContextMenu,VoiceModerationHost}.vue`; `voice/useVoiceMemberDrag.ts`; `services/voice/voiceModerationApi.ts` | none | U | U/U | Exact role grants/hierarchy, cannot forge local controls as server mute, revoked membership terminates call; J16/J21. |
| V11 | Call side chat/elsewhere/notification state | `voice/{CallSideChat,CallChatElsewhere,VoiceCallBanner}.vue`; `voice/{callChat,callChatFeed}.ts` | none | U | U/U | Private room, unread/read status, cross-navigation persistence, failed message send; J21. |
| V12 | Soundboard play/upload limits, live reactions/cooldown/mute | `voice/{SoundboardPopover,LiveReactionPopover,LiveReactionLayer}.vue`; `src/services/soundboard/{player,protocol,sounds}.ts`; `src/services/voice/liveReactions.ts` | none | U | U/U | Sound grants/rate limits, local and remote transport, muted preference, removed sound; J21. |
| V13 | Spatial audio panel/positions/volume | `voice/SpatialAudioPanel.vue`; `services/spatialAudio.ts` | none | U | U/U | Real audible positioning, unsupported audio path, reset/rejoin/state persistence; J21. |
| V14 | Activities launcher/invite/host/stage/controls/dock | `components/activities/`; `services/activities/{activityService,protocol}.ts` | none | U | U/U | Host/member grants, iframe/source origin and activity token, failed launch/close/host leaves; J21. |
| V15 | Ongoing foreground call/background/notification/end/hardware audio focus | `services/callForegroundService.ts`; official `src-tauri/gen/android/app/src/main/java/online/knowmad/harmony/CallForegroundService.kt` | none | U | U/U | Target SDK microphone foreground-service restrictions, background screen-off call, notification permission denied, interrupted call/hangup cleanup; J21/J23. |
| V16 | Call popout/desktop floating dock/global PTT/game overlay | `voice/callPopout.ts`; `services/{floatingDock,nativePtt,overlayBridge,nativePresence}.ts` | none | U | U/U | Desktop-only OS windows/global hooks are not Android features. Record platform gating; equivalent in-app Android call controls still required. |

## Native platform and lifecycle surface

Official native files below live in `src-tauri/gen/android/app/src/main/java/online/knowmad/harmony/`;
push files in `src-tauri/crates/tauri-plugin-harmony-push/android/src/main/java/online/knowmad/harmony/push/`.

| ID | Platform behavior | Exact reference | Native | Contract | Android | Verification / failure |
|---|---|---|---|---|---|---|
| P01 | Notification permission, FCM availability/config/project, encrypted relay/UnifiedPush delivery | `src/services/androidPush.ts`; `push/{PushPlugin,FcmSupport,FcmRelay,HarmonyFirebaseService,HarmonyUnifiedPushService,UnifiedPushEvents}.kt`; `federation-backend/src/services/pushPolicy.ts::appPushData` | none | U | U/U | Direct FCM data includes readable title/body/routing; do not generalize RFC8291 relay encryption to it. Real transports, OS denial, no Play Services, distributor uninstall, relay decryption failure/server outage; J22. |
| P02 | Notification channels/group/message style/avatar/sound, tap/read/cancel/dedup and phone ringer/DND behavior | `push/{Notifier,PushInbox,PushPayload,PushState,Ringer}.kt`; `src/utils/notificationRoute.ts` | none | U | U/U | Foreground/background/killed message launch, stale IDs/read elsewhere/wrong instance/malicious URL/permission. Ringer mode/buzz controls WebView alert audio; it is not background call ringing. Verify encrypted previews redact content; J09/J22. |
| P03 | Status/navigation bars, edge-to-edge/safe cutout/IME resize/back/fullscreen orientation | `android/{MainActivity,FullscreenChromeClient,FullscreenVideo,FullscreenOrientation}.kt` | partial | U | U/U | Native Compose has basic edge-to-edge; inspect API 26/33/35+ keyboard/rotation/back and large text; J25. |
| P04 | Auth redirects/instance switching/external links; separately scoped OS entry contracts | `src/components/AuthComponent.vue::handleOAuthLogin`; `src/services/{tauriOAuth,outsideLinks,instanceConfig}.ts`; `android/HarmonyApplication.kt::openUrl`; `src/router/index.ts` | partial | U | U/U | tauriOAuth popup is desktop-only; Android takes normal redirect flow. Official app manifest does not establish OAuth/share/invite deep-link intent filters. Additional native OS-entry work must be labeled; origin/TLS/no handler/cancel/refresh/no cross-host bearer; J01/J24. |
| P05 | Download manager/MediaStore/content URI/FileProvider/video thumbnail | official `android/HarmonyApplication.kt::downloadUrl/videoThumbnail`; official `src-tauri/gen/android/app/src/main/AndroidManifest.xml::FileProvider`; `src/services/downloads.ts` | none | U | U/U | Storage full, URI permissions, safe filenames, private URLs expiry, thumbnails failed codec; J19/J21. |
| P06 | Connectivity/offline/realtime reconnect/badge/read/presence attention | `src/services/{RealtimeConnectionManager,UserEventChannel,ViewContextTracker,StatePersistence}.ts`; `components/OfflineBanner.vue` | partial | U | U/U | Native shared flows alone are not a transport; airplane mode/restart/process death/401/logout/instance switch clears caches; J23. |
| P07 | Android release notice/update/support/build version | `src/services/androidReleaseNotice.ts`; `components/updater/AndroidUpdateNotice.vue`; `settings/user/SettingsBuildFooter.vue` | none | U | U/U | No update/bad network, wrong channel/artifact, browser unavailable; must not claim desktop updater on Android. |
| P08 | Accessibility/localization/RTL/reduce motion/keyboard/TalkBack | `src/components/a11y/`; `src/utils/a11y/`; `src/i18n.ts`; native `ui/theme/` | partial | U | U/U | Labels/focus/traversal/errors/48dp targets, huge font/contrast/RTL, live messages, closed drawer inert, dialog back restore focus; J25. |
| P09 | Security at rest/backup/screenshot/credential/log lifecycle | `android-native/app/src/main/java/lol/mony/harmony/data/session/SessionStore.kt`; `android-native/app/src/main/AndroidManifest.xml`; `src/services/encryption/SecureSessionKeyStore.ts` | partial | U | U/U | Explicit threat model; native secret/backup rules and screenshot policy are additional security requirements, not inferred official guarantees. Account separation/redacted logs/cleanup/revoke/reauth; copied-key limits E04/E08/E10; J13/J14/J23. |
| P10 | PWA install/update/offline cache vs native package installation | `src/services/{PWAManager,ServiceWorkerManager}.ts`; `components/{PWAInstallBanner,PWAInstallPrompt,PWAUpdateNotification}.vue`; `public/service-worker.js` | none | U | U/U | PWA install prompts are web-only, not native Compose screens. Native offline/error/update behaviors need their own implementation; do not fake service-worker parity. |
| P11 | Desktop tray/autostart/game presence/hotkeys/windows vs Android TV | `src-tauri/src/lib.rs::setup_desktop`; official manifest `LEANBACK_LAUNCHER`; `components/{RunOnLoginInstructionsModal,RunOnLoginPrompt}.vue` | none | U | U/U | Desktop-only excluded by platform gating, not silently counted complete. Official Android TV launcher/focus behavior remains separately unverified. |
| P12 | Native cross-instance capability negotiation, refreshed instance info and older-instance notice | `src/services/instanceCapabilities.ts::KNOWN_CAPABILITIES/hasCapability/checkInstanceVersion`; `src/services/instanceConfig.ts::refreshStoredInstance` | none | U | U/U | Web returns true for capabilities; Tauri requires the stored instance list. Test absent capability/older instance/offline refresh/switch instance; visibility is not authorization. Version gap alone without missing capabilities must not show the notice; J26. |

## Executable verification journeys and acceptance records

For every journey save the **full** command/test log in the runner's temporary
directory; do not pipe a failed run through `tail` and rerun it. Store screenshots
and the exact APK/source commit alongside the record, never JWTs/passwords/recovery
words. The reference server is the real local Harmony gateway
`http://localhost:54480` (emulator reaches host at `10.0.2.2`). Do not substitute a
stub API for the requirement. Web fixtures and backend assertions are necessary
but not sufficient for Android visual/functional verification.

| Journey | Actions + positive backend assertion | Negative/access-control variants and visual checks | Status / evidence |
|---|---|---|---|
| J01 Instance/auth/lifecycle | Enter instance; inspect config/name; password sign in; app restart refreshes session; switch instance; logout clears state. | Bad URL/TLS/config/offline/wrong password/revoked refresh; provider OAuth cancel and callback origin; phone keyboard/error focus; no tokens in logs. | U (foundation source only). |
| J02 Registration/recovery | Register real account; email/signup-review/invite requirements; profile creation; reset link → change password; login new password. | Closed signup/invalid invite/username conflict/rate limit/expired link; provider-managed account; rotate/back/cancel never falsely confirms. | U. |
| J03 Discovery/server membership | Local & remote directory/search/details; create server/template; invite accept/rules/welcome; confirm user_servers membership; leave. | Banned/outsider/private/expired/exhausted invite, refused remote host, already member; no-server landing and back. | U. |
| J04 Channel messages/realtime | Pick server/channel; page history; send rich/reply; second official client receives; second-client send arrives; read/typing/badges update; restart. | READ/SEND denied, private category, timeout/slowmode, AutoMod block, disconnect/reconnect/gaps, new membership history cutoff; IME not covering composer. | U. |
| J05 Message actions/expression/search | Edit/delete/pin/reaction/users modal/poll/bot command/GIF/sticker/custom emoji; search filters jump to actual message; verify rows and permission results. | Own vs moderator/other; deleted root, max limit/closed poll/provider failure/forbidden pin; long-press/tap copy/back/dialog focus. | U. |
| J06 Threads | Create from message and channel; sidebar/fullscreen; send reply; join/leave/mute; edit/archive/lock/auto-archive; confirm counters and memberships. | Parent invisible/locked/archived/deleted root, insufficient grants; encrypted thread and history limits; phone back stack. | U. |
| J07 DMs/groups | Start direct; policy request; message/read; group create/invite/name/icon/remove/leave/dismiss+undo; user/conversation mute/unmute; verify participant-only REST access. | Block vs mute-suppressed call ring/popup, known/following policy, nonparticipant ID/left group, member vs owner, encrypted fallback cancel; call indicator. | U. |
| J08 Social | Home/local/federated/tag; post visibility/CW/poll/media/alt; reply/quote/boost/like/bookmark/edit/delete; open remote tree/permalink. | Private audience/blocked actor; unavailable/tombstoned parent; remote vote failure; no accidental public upload; backend audience assertion. | U. |
| J09 Notifications/Today/announcements | Trigger actual DM/channel mention/social follow/reply; filters/read all/individual; tap correct target; Today flags/digest and optional local AI; announcements read/history. | Own rows/target access/duplicates/read elsewhere/mutes; unread badge persists; encrypted-preview redaction, haptic master and all triggers/test; AI unavailable means no highlights/no cloud fallback; per-user/device flags and phone dialogs. | U. |
| J10 Profiles/follows/lists/explore | Local/remote/moved profile/media; request/approve/deny/unfollow/remove; list CRUD/member picker; instance/source/trending. | Outsider can't read private list/posts; blocked/deleted actor; handle URI Unicode normalization/remote offline; screens and modals. | U. |
| J11 Account/profile | Edit avatar/banner/crop/focal/bio/fields/username; server profile and reset; verify profile rows + official web render after restart. | Provider-owned fields/duplicate name/bad image/failed upload/insufficient membership; discard unsaved change; crop dialogs in both modes. | U. |
| J12 Appearance/privacy/media preference | Light/dark/system/custom; palette/live editor/save/import/export/skin/font/zoom; account vs device adopt; timestamps/compact/emoji/video prefs. Verify exact settings on web and second device. | Bad import/server save failure; contrast/reduce motion/TalkBack/RTL; metadata default and outside-link trust revoked; screenshots **every screen/dialog** not only settings. | U. |
| J13 Security/data/deletion | Set/change password; 2FA QR/challenge/recovery; list/revoke other sessions; export; aliases/move; verified-origin profile copy; following CSV/JSON/ZIP import preview/job/counts/failures/cancel; disposable-account deletion. | Bad OTP/reused recovery/revoked refresh/provider-managed; invalid import/missing capability/unverified origin; secrets absent logs/backup; cancel destructive actions and confirm effects on a second device. Email is read-only in official account settings, not an email-change journey. | U. |
| J14 Encryption/interoperability | Web↔native channel/DM/thread/edit/reply/attachment refs; Harmony recovery-KDF and signed v2/v3 vectors; setup/unlock/legacy phrase restore; both QR/code pairing directions and keepCopy choices; manual server/local backup import/export; sync/diagnostics/reset; rotation/epochs/search. | Unsigned v1/wrong signature/AAD/key/no keys/plaintext refusal and no silent downgrade; checksum typo warning vs valid legacy phrase; identity-change notice/repin; denied/revoked/expired device, history grants, malicious/replayed pairing and corrupt backup; after-reload copy removal/approval; honest non-FS and copied-key revocation limits, unencrypted attachment bytes. | U; B03/B04 release blockers. |
| J15 Personal libraries/bots/preferences | Bots/token/commands; emoji/sticker library; language/audio packs/keybinds/advanced/build/announcements settings; backend persistence and reload. | Capability/zero-cap held content; invalid uploads/pack; token rotation old credential fails; audio interruption, unsaved/error focus. | U. |
| J16 Server/roles/moderation | G01-G10/G15/G16 screens: channels/categories/overrides, overview/welcome/roles/privacy/encryption, bans/audit/transfer/delete; member directory/search/actions; server-scoped reports/evidence/resolve. | Owner/mod/member/outsider and role hierarchy; report moderator without instance-staff role; remote-copy/read-only vs instance staff; malicious REST denied, rights revoked while dialog open. | U. |
| J17 Integrations/AutoMod | Server emoji/stickers/sounds; incoming/outgoing webhook & deliveries; bots; Discord bridge/migration wizard; AutoMod rules/test/action/log. | Local-only permissions/capabilities, failed external OAuth/job, secret rotation, blocked insert no ghost success, retry/cancel jobs. | U. |
| J18 Instance admin | As admin visit **each A01-A17** tab and every mutation/confirmation; repeat as moderator/member/outsider; backend confirms each allowed mutation. | Forbidden REST as well as hidden controls; self/owner protection, stale/report/moderation races, failed sections and partial bulk results; no generic 'Admin' placeholder. | U. |
| J19 Attachments/Drive owner lifecycle | SAF multi-select; metadata strip; upload/hash reuse; Drive folders/views/search/sort/paging; details/rename/move/temporary/keep/trash/restore/permanent delete; preview/save/voice recording. | Limits/blocked hash/quota/disabled Drive/temporary expiry/storage failure/cancel/resume; changed files/gone refs; safe filename and exact saved bytes/hash; Android photo-details dialogs. | U at reference. Later isolated slice coverage listed below; not a full journey pass. |
| J20 Drive access/sharing | User/server/channel share, posting gap consent, revoke; restricted/public link+expiry/show-owner/reset; guest opens `/d` and gets permitted metadata/bytes only; private→public post copy. | Outsider/no share/no membership/expired/reset token get denial; link off/Drive off; public viewer never sees internal ID/path; own vs shared action UI. | U at reference. |
| J21 Actual voice/video/media | Two clients exchange actual audio/video frames on instance-selected transport; incoming/outgoing/group/voice room, devices/mute/deafen/PTT/gain/quality/camera/screen share if supported, side chat/sounds/reactions/activity/spatial; record/play/save media. | Mic/camera/hardware/policy/grants/revocation failures; configured SFU vs P2P/ICE/TURN/server unavailable, no per-client SFU→P2P fallback; E2EE locked, required-E2EE P2P refusal, supported SFU rekey; fullscreen/rotation/back/focus/headset/codec; screen-off/background ongoing-call service. | U; B06 release blocker. Hardware/emulator limitations must be stated. |
| J22 Actual background message push | FCM direct or relay **and** UnifiedPush where available; register/test, actual server event, foreground/background/process absent; cold-start message target; tap/read-cancel, unregister/logout and renew. | Permission/no Play Services/project mismatch/distributor removal; stale/expiry/failure renewal; direct-FCM vs encrypted-relay privacy and preview redaction; default offline-only suppression while another account device is active, security-event exception. Killed-app incoming-call ringing remains additional/unverified work, not a proven official push baseline. | U; B05 release blocker. Record transport without tokens; AOSP alone cannot prove FCM. |
| J23 Resilience/account separation | Airplane mode/reconnect/network handoff, background/resume/process kill/reboot, token expiry/revoke; switch accounts/instances and compare caches/read markers/keys/notifications. | No duplicate sends/gaps, no Main-thread networking, stale list/error retained visibly, sign-out while request in flight discards old account result. | U. |
| J24 Native/web entry and external apps | Test Android auth redirect and web recovery/invite/permalink/Drive navigation; separately label added OS OAuth/deep-link/ACTION_SEND handlers; external-link consent/trust, safe download/open result. | Desktop OAuth popup and PWA share_target are not Android intent support. Untrusted origin/scheme/malicious notification URL, no handler/cancel/signed-out continuation/private data/wrong instance; added OS handlers need their own evidence. | U. |
| J25 Every screen visual/accessibility matrix | For every R/C/S/U/E/G/A/M/D/V/P surface: 360dp phone + wide/landscape, dark/light/custom and system changes, large text/RTL/TalkBack, keyboard, back/rotation/window inset. | Readable contrast, no clipped dialog/actions, touch targets, closed drawer/focus restoration, loading/empty/error/retry and permission states. Record actual inspected screenshots, not only generated captures. | U. No all-screen visual claim. |
| J26 Instance capability/version matrix | Start/switch between instances with current and older capability lists; refresh stored instance info; inspect each KNOWN_CAPABILITIES feature including following-import, encrypted-history and voice-announce; verify older-version/missing-capability notice behavior. | Missing/unknown capability, failed refresh/stale cache, same version vs older/newer, notice dedup by origin/version; absent APIs must not be called. Native gates differ from web; hidden controls never replace backend authorization. | U. |

## Evidence ledger for changes after 78fdc960

This section intentionally does not promote the frozen-reference statuses above.

- Audit author: isolated worktree/session `f85f312c-9eb4-4eec-a42c-906f36773142`.
  An independent source crosscheck corrected official-baseline claims and
  platform boundaries. Static reference validation resolved all 580 expanded
  source-file references (repeated references counted) using the documented
  aliases; all 166 frozen feature rows retain `Contract U / Android U/U`, with
  26 verification journeys. Log:
  `/var/tmp/harness-1000/f85f312c-9eb4-4eec-a42c-906f36773142/parity-reference-check-03.log`.
  Path existence/status checks are not Android or protocol verification.
- Parent owns `docs/build/native-android.md`, shared API/shell/build/manifests and
  integration. This audit does not change those files.
- **Drive implementation:** commit `adf439c4` plus access-toggle follow-up
  `2dd73dd4`, based on parent transport/identity prerequisite `e056c821`.
  The follow-up permits restricting an existing public link after the instance
  disables new public sharing; Android UI verification is still pending. New isolated `data/model/Drive.kt`,
  `data/repo/DriveRepository.kt`, `ui/screens/drive/{DriveScreen,DriveDialogs}.kt`
  and three `data/Drive*Test.kt` test files under `android-native/app/src/`.
  Entry: `DriveScreen(state: AppState, onBack: () -> Unit,
  onOpenMessage: ((DriveMessageUsage) -> Unit)? = null,
  onOpenPost: ((DrivePostUsage) -> Unit)? = null)`. Shared session/authentication;
  repository calls return `Result` and run on IO; no shared shell/build/manifest
  changes by this audit author.
- **Verified data/repository evidence:** all **24 tests passed** (12 model,
  11 MockWebServer repository, one opt-in actual local-server contract) with the
  actual Kotlin model/repository/shared transport sources in an isolated JVM
  Gradle project. Command: `env -u JAVA_HOME GRADLE_USER_HOME=/tmp/gradle-home
  HARMONY_DRIVE_CONTRACT_URL=http://localhost:54480 gradle --no-daemon
  -p /tmp/drive-jvm test`. Full output read and preserved at
  `/var/tmp/harness-1000/f85f312c-9eb4-4eec-a42c-906f36773142/drive-jvm-contract-03.log`.
  The runner compiles production data/transport code, **not Android/Compose UI**.
- **Actual local contract:** two disposable GoTrue users, with auth IDs distinct
  from profile IDs; real PostgREST/storage RPCs; folder duplicate/cycle/ownership
  rejection; storage tracking; outsider private-list/card/sign/mutation denial;
  rename/move; signed download with exact byte comparison; public-card metadata
  redaction; restricted/public/reset link authorization, future expiry accepted
  and past expiry rejected (no timed-expiry wait); trash/restore/delete;
  temporary-file keep; recursive folder deletion. Final cleanup requests remove
  remaining files/folders and log out sessions on a best-effort basis; those
  cleanup responses are not asserted. Disposable auth/profile rows remain.
  Credentials remained in memory and were not printed or persisted. Raw upload
  in this test creates a fixture; it is **not native upload implementation**.
- **Implemented but Android-unverified:** Drive usage/disabled state, folder and
  context views, search/type/sort/paging, folder CRUD/move with cycle exclusion,
  file rename/move/trash/restore/delete/keep, owner usage/existing grant revoke,
  restricted/public links with expiry/owner visibility/reset, fresh-signed image
  preview without Coil disk/memory caching, and foreground SAF streaming save
  with best-effort incomplete-document cleanup. Owner affordances fail closed;
  backend RLS remains authoritative. Signing responses reject unexpected
  origin/object paths; repository signing/download requests reject redirects;
  signed download GETs have no account headers and repository media-client URL
  logging is removed. Image rendering uses Coil and is not covered by the JVM
  HTTP assertions. Downloads are foreground-only, not
  persistent/resumable; blocked IO cancellation can wait for network timeout.
- **Not implemented by this slice:** upload/EXIF removal/TUS/resume/metadata
  consent, audio/video playback, multiselect/grid/drag-and-drop, new share-target
  picker, guest public file page/deep links, background/resumable download,
  encryption, voice or push. Contracts do not prove all user/server/channel
  sharing scopes, quota exhaustion, Drive-disabled mutations, moderation,
  scheduled expiry/deletion workers, public federation bytes, codecs or Android
  lifecycle. These remain incomplete/unverified, not waived.
- **Android compilation/runtime/visual evidence:** none from this audit session.
  Commit `adf439c4` was delivered to the parent for integration and actual
  Android build/emulator checks; those results must be recorded separately.
  No frozen-reference row changes from `U/U` on the strength of JVM/HTTP tests.

## Native evidence (this session)

Two kinds of evidence were produced against the live local stack on
`http://localhost:54480` (fixture server `E2E Server`, accounts `fx_alice` and
`fx_bob`).

### On the emulator: the APK, driven by taps

The debug APK was installed on an AOSP API 30 x86_64 emulator and driven with
real `adb shell input tap` at the centre of each node's own reported bounds --
not by calling into a composable. Every step's accessibility tree is kept in
`docs/build/evidence/android-native-emulator/`, with a README naming the tap
that produced each one.

Verified on the device this way: signing in as `fx_alice`; the Chats
destination with the server rail, DM list and bottom bar; opening the `Bob E2E`
direct message; typing into the composer and sending, after which the message
appears as its own `message-<uuid>` node and the composer empties; the same
message read back over PostgREST, so the composable's row and the instance's row
are the same row; the server tile opening the channel column; `#general`
opening with its messages; the message-actions sheet offering reactions, reply,
copy, thread, pin and edit; server management with its Overview/Channels/Members
tabs; the Members list with both accounts and their per-member actions; Social;
Alerts with real notification rows; You; the appearance panel with its four
themes and live preview; and Drive with its quota, filters and empty state.

**No screenshots exist.** Every capture path returns a uniform black 480x960
image (0 of 460800 pixels non-black): `screencap` over `exec-out`, `screencap`
to a file then `adb pull`, the emulator console's own screenshot, and
`screenrecord` (which refuses to start). SurfaceFlinger lists the app's layers
and `dumpsys window` names the activity as focused, so the app is drawing; the
guest's GL output never reaches the host without KVM. A black PNG is not a
screen, so none is recorded as one. Visual verification is therefore **not**
established for any row.

### On the JVM: the composables, drawn

`ScreenRenderHarnessTest` draws the app's own composables through Robolectric's
native graphics, signed in to the same instance, after each screen's
repositories answered: the instance picker, Chats in dark, light, midnight and
a custom account look, Social, Alerts, You, the appearance panel, Drive, and
all six server-management tabs. The PNGs are committed in
`docs/build/evidence/android-native-render/`.

`AccountAppearanceRenderTest` covers the appearance requirement directly: it
reads the account's stored `appearance_settings`, renders Chats with it, then
with the app's default, and requires the pixels to differ. Against an account
set to a custom light look with a crimson accent, that pair shows the app
wearing the account's look rather than its own.

`NativeJourneyTest` drives the semantics tree for the bottom bar, the rail, a
DM and a channel message (both read back from the instance), a refused
sign-in, the register and reset forms, and an appearance change measured by
pixel difference. 193 tests pass.

These are supporting evidence. They show what the composables draw and which
nodes they expose, given real rows; they cannot show touch, IME, lifecycle,
device appearance or performance, and they are not emulator runs.

### Still unverified, and keeping its `U/U`

- **Visual verification of every screen.** No screenshot from a device exists.
  The JVM renders are the only pictures, and they are not the device.
- Touch feel, gestures, the software keyboard and its insets, system back,
  process death and restore, rotation, and window sizes other than the one
  captured width.
- Device appearance: the system light/dark setting, dynamic colour, and the
  status and navigation bar icon polarity the app sets through the window insets
  controller.
- Push (B05), voice and video (B06), Harmony's own Megolm encryption (B03/B04),
  the attachment upload pipeline (B07), and every deep link (B09). These are not
  implemented natively; no harness can make them so.
- Performance, battery and memory.

## Completion rule

Complete parity requires every applicable feature above implemented and all
journeys exercised against the real server on Android, including negative access
controls and each dialog/settings sub-screen. Explicitly platform-gated desktop
or PWA behavior must be recorded as not applicable with source/runtime evidence,
not disguised as implemented. Emulator hardware limitations, absent external
push/federation/Discord/payment infrastructure, and unimplemented encryption or
voice are **blockers/unverified work**, not successful parity. Do not put a
nonfunctional switch/button on a screen to convert a `none` into `partial`.
