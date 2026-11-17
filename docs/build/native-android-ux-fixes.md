# Native Android: system bars, the collapsing rail, reaction gestures, server branding

Four reports from using the built APK, and what each one turned out to be. They are recorded
together because three of them share a cause worth naming: **the app drew itself as if the
phone's window were the whole screen and as if every stored image were an absolute URL.**

## 1. The rail sat under the status bar and the cutout

`MainActivity` calls `enableEdgeToEdge()`, so the Compose root starts at y=0 and the system
bars are drawn *over* it. `Shell`'s rail was a plain `fillMaxHeight()` column with a
`navigationBarsPadding()` at the bottom, which handled the navigation bar and nothing at the
top. On a phone with a status bar and a camera cutout, `Chats` -- the first destination --
was behind the clock.

**Fix.** The rail pads itself by the safe drawing area on its own side:

```kotlin
.windowInsetsPadding(safeArea.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
```

`safeArea` is a parameter defaulting to `WindowInsets.safeDrawing`, so production uses the real
insets and a test can pass a synthetic notch and assert exact pixel bounds.

**Alternatives rejected.** `statusBarsPadding()` alone handles the clock but not a cutout;
padding only the first child fixes `Chats` and leaves the last destination under the
navigation bar; letting each screen pad itself leaves the rail -- which is outside every
screen -- unpadded, which is the bug.

## 2. Choosing a category or server left the rail open

The rail was a permanent 76dp column at every width. Tapping a destination changed the middle
column while the rail kept a fifth of a phone screen, so the thing just chosen was squeezed
into the rest.

**Fix.** The rail is a drawer: `SignedInShell` owns `railOpen`, and choosing a destination or a
server closes it. Closed, a single handle in the top-start corner -- itself inside the status
bar and cutout inset -- brings it back. Back closes it before it leaves the screen. The rule
lives in one place, `ShellNavigation`, rather than being a boolean each call site remembers:

```kotlin
object ShellNavigation {
    fun railOpenAfterChoosing(): Boolean = false
    fun railOpenAfterArriving(): Boolean = false
}
```

A server tile also clears the open room, so choosing a server cannot land on a channel left
open in a different server.

**Alternatives rejected.** A `ModalNavigationDrawer` is the Material answer, but this rail is
a permanent column on a tablet and a drawer on a phone, and the drawer's scrim and swipe
gestures would fight the message row's own horizontal reply drag. Hiding the rail only below a
width breakpoint would have left the phone case reported here unfixed at any width a tablet
also uses.

## 3. Reaction tap and long press were inverted

The pill was:

```kotlin
.combinedClickable(onClick = { onShowReactors(emoji) }, onLongClick = { onReact(emoji) })
```

so a tap opened the reactor list and a long press added or removed the reaction -- the
opposite of the web client and of every messaging app. Two further faults came out while
fixing it:

- The toggle matched on `reaction.emoji` (the *display* label) and wrote that back. For a
  custom emoji the label was the literal string `:custom:`, so two different server emoji were
  one pill and a tap could remove the wrong one.
- `reactionLabel` drew `:custom:` for every custom emoji, so they were indistinguishable on
  the row as well.

**Fix.** `onClick` toggles, `onLongClick` lists. `combinedClickable` does not fire `onClick`
after a long press, which is what "avoid triggering the tap action after a long press" asks
for; the pill also fires its own haptic so the press is felt before the sheet appears. A
`Reaction` now has one identity, `key` (the custom emoji's row id, else the stored content),
used by the pill, the toggle and the reactor filter, and `content` is what is written back so
joining an existing pill sends the content that pill already carries. The label draws the
real shortcode.

Both gestures are also exposed as accessibility actions, so a screen-reader user is not
required to hold a finger still.

## 4. A server's cover and icon never drew

`servers.icon` and `servers.banner` hold bare object paths (`<server-id>/banner-<ms>.png`),
which the uploader writes after normalising an absolute URL back to a path. This client joined
them to **`server-icons`** and **`server-banners`**. The buckets are **`server_icons`** and
**`server_banners`** (`src/utils/serverUtils.ts`), so every relative branding path was a 404
and the header drew a name with no picture.

**Fix.** `AvatarUrl.resolveServerIcon` and `resolveServerBanner` name the right bucket each,
and `ServerBranding` is the one composable that draws a server's cover, icon and name -- used
by the channel header and by the tests. A server with no branding gets its monogram and no
reserved strip, rather than a band of nothing above the channel list.

## 5. A preferences file that could not be decoded bricked the app

Found by the emulator run of the four reports above, not by reasoning: the real APK launched to
**"Could not load this / Unable to parse preferences proto. / Try again"**, and *Try again*
re-read the same broken file. `SessionStore`'s DataStore had no corruption handler, so one
truncated write — a kill or crash in the middle of `edit` — made every read throw, and nothing
in the app could clear it. The store now replaces an undecodable file with an empty one; the
cost is a sign-in, and what it buys is an app that starts. The reasoning and the alternatives
are in [`native-android-preferences-recovery.md`](native-android-preferences-recovery.md).

## How this is verified, and what is not

- **JVM render and interaction tests** (`ShellRailTest`, `ReactionGestureTest`,
  `ServerBrandingRenderTest`) draw the real composables under Robolectric's native graphics,
  inject real touches, and assert pixels and node bounds. Each was mutation-checked: reverting
  the inset padding, the collapse rule, the gesture mapping or the bucket names fails them.
- **The branding test fetches over HTTP** from a local server that 404s the hyphenated bucket
  names, so a wrong bucket is a missing picture rather than a passing string comparison.
- **Emulator evidence** is two runs, and they are worth different things.
  `docs/build/evidence/android-native-emulator/` is the `aosp_atd` run: the real debug APK
  against a stub instance, driven with `adb shell input`, recording the accessibility tree and
  the rail's measured bounds. That image cannot take a screenshot, so it is trees and
  measurements.
  `docs/build/evidence/android-native-ux/` holds what the `ui30small` run measured — the real
  insets the app is given, a 48 px status bar and a 96 px emulated cutout — and the tree of a
  separate failure it found: a truncated preferences file that bricked the app. That run was
  blocked before sign-in (first by `SystemUI`'s own ANR dialog, then by the AOSP keyboard's
  contacts prompt), so it does **not** hold the rail's bounds under those insets; the JVM test
  is what pins the rail. The directory's README says which file is which.
- **Not verified:** a physical phone. Every run is on an emulator under TCG, at one density
  and one screen size; rotation, gesture navigation and a real device's particular cutout shape
  are untested.
- **Not fixed:** the cover renders at 96dp with `ContentScale.Crop`, but the app does not ask
  the instance's image proxy for a cover-sized variant the way the web client does
  (`bannerRenderSize`), so a large upload is downloaded whole and scaled on the device. That is
  a bandwidth cost, not a missing picture.
