# Render evidence: the composables, drawn

These PNGs are the app's **own composables**, drawn by Robolectric's native
graphics on the JVM and captured with `captureToImage()`. Each one signed in to
the local Harmony stack as the fixture account first, so the rows come from
PostgREST, and each waited for text that only appears once that screen's
repositories had answered -- a capture can never show a spinner in place of the
screen.

They are **not device screenshots**. The emulator on this host cannot produce
one: every capture path returns black, for the reasons in
`docs/build/native-android-verification.md`. What these show is what the
composables draw and how the theme applies; what they cannot show is touch, IME,
rotation, system appearance or device rendering.

## The files

- `instance-picker.png` -- the signed-out entry: wordmark, instance field,
  Connect.
- `chats.png` -- Chats in the default dark look: the server rail, the DM list,
  the bottom navigation bar.
- `chats-light.png`, `chats-midnight.png` -- the same screen under Harmony's
  other two presets.
- `chats-custom.png` -- a custom account look: violet primary over a warm dark
  background.
- `social.png`, `notifications.png` -- the Social feed and the notification
  inbox, each with its own data.
- `you.png`, `you-appearance.png` -- the account screen, and the appearance
  panel with its four themes and live preview.
- `drive.png` -- the Drive browser with quota, filters and its empty state.
- `server-management*.png` -- server management across its Overview, Channels,
  Members, Roles, Invites and Bans tabs.

## The account's own look

`chats-account-appearance.png` and `chats-account-default.png` are the same
screen under two looks. The first is whatever the signed-in account stores in
`profiles.appearance_settings`; the second is the app's default. The account was
set, over PostgREST, to a custom light look with a crimson accent (`#B91C1C`),
17sp text and compact messages -- the same JSON the web client writes -- and the
app rendered it.

That pair is the evidence for the requirement that the client respect Harmony's
appearance settings: `AccountAppearanceRenderTest` captures both and asserts the
pixels differ, so a screen that ignored the stored setting could not pass.
