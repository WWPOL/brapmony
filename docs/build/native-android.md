---
title: Native Android client design
description: Architecture, appearance compatibility and verification requirements for the native Compose client.
---

# Native Android client design

## Goal and completion criteria

Build a Kotlin/Jetpack Compose client alongside the existing Tauri Android client.
This is not a WebView reskin: Android renders the UI and the client talks to Harmony's
real auth, REST, storage, realtime and federation services. The existing client remains
unchanged. Full parity means implementing and testing the existing phone client's
screens, permissions and interactions, not just listing the destinations.

**Work in progress.** This document explains the intended architecture; a feature is not
implemented or verified merely because it is named below. Emulator interaction results,
server assertions and screenshots must substantiate completion. Unsupported capabilities
must be reported explicitly, not represented by success-shaped controls or placeholders.

## Build setup

The separate `android-native/` Gradle project uses AGP 9.5.1, Gradle wrapper 9.9.1,
built-in Kotlin (AGP 9), Kotlin Compose/serialization compiler plugins 2.4.21 and
Compose BOM 2026.09.00. These were resolved from the official Google/Maven repositories.
There is no `org.jetbrains.kotlin.android` plugin: AGP 9 supplies Kotlin itself.
The initial project uses compile SDK 37, target SDK 36 and minimum SDK 26; current
AndroidX libraries require compilation against SDK 37. Targeting a later SDK needs
behaviour-change verification rather than merely changing a number.

JDK 21 runs the build. Local SDK paths and generated outputs are ignored by Git.
Release signing credentials must never be committed. The debug APK is a different
application id from the existing Tauri client, so both can be installed for comparison.

## Data and security boundaries

- GoTrue authenticates the user. PostgREST requests carry the instance's public anon key
  and that user's access token, never an admin/service-role key.
- Instance selection discovers the published config. Production networking must require
  HTTPS; cleartext loopback is for local emulator testing only.
- Token refresh must be serialized; persisted secrets need Android-backed protection,
  backup exclusion and sign-out cleanup. All network I/O runs off the UI thread.
- Profile IDs and auth user IDs may differ. Membership/message ownership must follow
  the actual schema and existing client, not assume these IDs are interchangeable.
- RLS remains authoritative. Clients should surface permission failures and should not
  optimistically report successful writes that the backend rejected.
- History is paginated. People and membership details are batch loaded, not fetched for
  each message or server in startup's hot path.
- Encrypted-channel sends must never silently downgrade to plaintext. Interoperability
  requires the existing Megolm protocol, recovery and cross-device sharing flows.

The current source separates `data/api`, `data/model`, `data/repo`, `data/session`
and `ui`. The container assembles dependencies explicitly. Compose state observes
repository results. UI actions must preserve failures, loading and retry states.

## Android interpretation of Harmony

Use Material 3 top bars, bottom navigation, modal sheets, text fields, switches,
ripples and system back. On phones the server/channel tree opens as a drawer;
wide windows show list and detail side by side. Keep messages left aligned and
chronological rather than imposing generic chat bubbles on Harmony's message model.

Palette anchors are Harmony's sky blue `#0EA5E9`, secondary `#38BDF8`, dark surface
`#1A1A1E`, sidebar `#17181A`, text `#F2F3F5`, and presence green `#43B581`.
Typography is Android's Roboto for UI with Harmony's message-size preferences.
Rounded controls and native selection affordances provide the Android identity;
colour, hierarchy, server rail and chat composition carry Harmony's identity.
Do not replace Harmony's identity or choose wallpaper colours over an explicit theme.

### Appearance compatibility

`profiles.appearance_settings` is the compatibility contract, as defined in
`src/composables/useVisualTheme.types.ts`. Load its theme, custom colours,
lightness/chroma, font, message size, zoom, compact mode, contrast, timestamps,
24-hour time, motion and accessibility preferences. Preserve unknown keys on save.
CSS skins cannot be executed as native UI; their supported semantic settings need
native mapping and unsupported visual effects must be identified honestly.

The web client's per-device `appearance-sync` decision distinguishes account look
from device-only look. Saving shared message/accessibility preferences while a
device keeps its own look must preserve the account's look keys. Local persistence
needs account and instance scoping so one user's look cannot leak into another's.

OKLCH colour utilities allow theme generation without hard-coded RGB approximations.
Native palette role mapping is an interpretation, not a claim of pixel equality with
CSS. Verify text/control contrast, light/dark/custom themes, large fonts and narrow
screens using actual screenshots.

## Parity inventory and work split

1. Core: instance picker, login/register/reset, session restoration and MFA; app shell;
   servers/channels and DMs; paginated message history; sends, edits, deletes, replies,
   threads, reactions, pins, search, unread/read markers, live updates and drafts.
2. Social: home/local/federated timelines; post/reply/poll/media composition; favourite,
   boost, reactions, bookmark; profiles, follows/requests, lists, hashtags, explore;
   notification actions and read state.
3. Account and servers: profile and per-server profile edits; appearance and notification
   settings; privacy, security, sessions, language; server/channel/category creation,
   invites, members, roles/permissions, moderation and server settings.
4. Platform: attachments/storage/media; Drive; encryption/recovery/device approval;
   voice/video/LiveKit; foreground service and capture; push/deep links/share/downloads.

Independent feature areas can be implemented in parallel worktrees and integrated
into the shared app shell. Core API/model changes and navigation integration are
sequential to avoid incompatible abstractions.

## Verification strategy

Use the real local stack (`e2e/stack.sh`) with throwaway users and schema. Its gateway,
GoTrue and PostgREST must pass the existing `smoke` assertions, including RLS denies.
The reduced E2E stack explicitly lacks realtime and federation; those must be added
for any claim about live messages or federation, rather than treating fixture stubs
as a production server.

For the native app: compile/lint/unit tests plus emulator-driven UI interactions.
For each feature, record preconditions, actions, backend read-back, expected error
cases and screenshot locations. A clickable destination or a UI hierarchy dump is
not visual verification. Inspect rendered screenshots, not black framebuffer captures.
Test both members and non-members, account switching, offline/expired sessions,
rotation/back, keyboard resize, light/dark/custom/high-contrast and large text.

The existing web project's checks must also run before handoff. Store complete logs
of each build/test invocation and cite them accurately. Do not claim full parity
until the inventory has no unimplemented or unverified entries.
