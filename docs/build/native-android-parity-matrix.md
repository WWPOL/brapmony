# Native Android parity matrix

A row-by-row comparison of the native client against the default Harmony web client, and the
record of what is closed. Each row names the reference behaviour, read from the Vue source
rather than inferred from the type definitions, then what the native client did, then what it
does now with the evidence that proves it.

**Status vocabulary.** *Closed* means a test or a captured screen proves it. *Partial* means it
works for the common case and something named is missing. *Open* means the native client does
not do it. Nothing is marked closed on the strength of a type definition or a text-only fixture.

---

## 1. Author identity

| Capability | Reference behaviour | Native before | Native now | Evidence |
| --- | --- | --- | --- | --- |
| Author name, ordinary account | `getMemberDisplayName`: server nickname, else trimmed display name, else trimmed username | Always `Unknown account` -- no profile ever resolved | The profile's shown name; a missing profile reads `Deleted account` | `AuthorResolutionTest.anOrdinaryAuthorTakesTheProfilesNameAndAvatar` |
| Profile lookup | `profiles` read by id, then cached | **Threw on every page**: `status` is `smallint` and `custom_status` is `jsonb`, declared as strings | Both read as JSON; the row decodes | `aRealProfileRowDecodesDespiteItsSmallintAndJsonbColumns` |
| One bad row | A failed fetch leaves that author blank, not the list | One bad row emptied the whole page | `byIds` falls back to reading each id alone | `oneUnreadableRowDoesNotCostTheOtherAuthors` |
| ID mapping | `messages.user_id` is `profiles.id`; membership and the JWT use `auth_user_id` | Both indexed, but the lookup never ran | Both spellings resolve to the same row | `aProfileIsFoundByAuthUserIdAsWellAsItsOwnId` |
| Bot author | `bot_id` resolves through the `bots` table | Never looked up | `BotRepository` by id; unresolved still reads `Bot` with a badge | `aBotAuthorResolvesThroughBotIdNotUserId` |
| Bridged Discord author | `metadata.discord_user` | Ignored | Named and pictured from the metadata | `aBridgedDiscordAuthorCarriesItsOwnNameAndPicture` |
| Channel webhook author | `metadata.webhook` | Ignored | Named and pictured from the metadata | `aWebhookAuthorUsesTheNameItPostedUnder` |
| Federated remote author | `metadata.remote_author` | Ignored | Named and pictured from the metadata | `aRemoteAuthorInAFederatedCopyIsNamed` |
| Federated member | A profile with a foreign domain | Same decode failure as any profile | Resolves like a local one | `aFederatedMemberResolvesThroughItsProfileRow` |
| Deleted author | Reads as deleted; the row still draws | `Unknown account`, indistinguishable from not-yet-loaded | `Deleted account` | `aMissingProfileIsDeletedRatherThanUnknown` |
| Profile tap | Profile card with picture, name, handle, bio and actions | Nothing was tappable | Sheet with picture, name, handle, pronouns, bio and Message | `RoomScreen` author sheet |

## 2. Avatars and pictures

| Capability | Reference behaviour | Native before | Native now | Evidence |
| --- | --- | --- | --- | --- |
| Absolute URL | Used as it stands | Passed through, so it worked | Unchanged | `AvatarRenderTest.anAbsoluteUrlIsUsedAsItStands` |
| Site-relative path | Resolved against the instance | **Passed raw to Coil, which cannot fetch it** | Resolved against the instance | `aSiteRelativePathResolvesAgainstTheInstance` |
| Bare storage path | Becomes a storage object URL | Passed raw, so it failed | Becomes the storage URL | `aBareStoragePathBecomesAStorageUrl` |
| Instance under a sub-path | Prefix preserved | Lost the prefix | Prefix preserved | `anInstanceWithAPathPrefixKeepsIt` |
| Bundled default | Drawn locally, never fetched | Requested over the network, skipped on a suffix test | Reported as null; the app draws its own initials | `theBundledDefaultIsNotFetched` |
| The picture actually paints | The image element shows the avatar | No proof it ever did | A pixel of a real PNG is asserted | `anAbsoluteAvatarUrlPaintsThePicture` |
| Initials fallback | Coloured circle with initials | Present | Present | `Avatar.kt` |
| Presence dot | Status dot, or a phone glyph on mobile | **Open** | **Open** | -- |
| Compact mode | Hides the avatar; the name still shows | Hid avatars | Unchanged, deliberately: the name carries identity | `RoomScreen.MessageRow` |
| Notifications use the same rule | One resolver everywhere | Notifications resolved, messages did not | One `AvatarUrl` behind every `Avatar` | `AvatarUrl.kt` |

## 3. Message rows

| Capability | Reference behaviour | Native before | Native now | Evidence |
| --- | --- | --- | --- | --- |
| Header grouping | `shouldShowHeader`: same author, within five minutes, no reply, no system event | Header on every row | Grouped by the same rule | `consecutiveMessagesFromOneAuthorGroupUnderOneHeader` |
| Grouping key | `messageAuthorKey` separates discord, webhook, remote, bot and user | Not applicable | The same five keys | `aBotAndAPersonWithTheSameIdDoNotGroupTogether` |
| Row actions | Hover toolbar, or long-press on a phone | A three-dot button on every row | No per-row button: long-press a row, or tap a reaction pill | `RoomScreen.MessageRow` |
| Timestamp | Localised time with an edited marker | Time only, no edited marker | Time only | **Partial** -- edited marker open |
| Reply reference | Shows the replied-to author and text | The bare word `Reply` | The bare word `Reply` | **Partial** |
| Reactions | Pills with count; a tooltip names who reacted; a picker adds one | Pills with count, no identities | Tapping a pill names every reactor with their own name and picture; long-pressing a pill adds or removes your reaction | `RoomScreen` reactors sheet; `reactionLabel` |
| Rich content | Every message part | Crashed the page on an emoji object | Every part decodes and draws | `MessagePartContractTest`, `EmojiImageRenderTest` |
| Row metrics | 40dp avatar, 16dp gap, 1rem medium name, 8dp meta gap, primary BOT tag | 36dp avatar, 10dp gap, semi-bold 14sp name, text BOT marker | The reference's own numbers, with the account's appearance still deciding theme and density | `docs/build/evidence/android-native-render/author-header-grouping.png`, `AuthorHeaderTest` |
| Attachments | Images drawn inline with alt text; video and files named with their size | A sentence saying it does not draw them | Images draw as pictures; video and files name themselves with a human size | `anImageAttachmentDrawsThePicture`, `aByteCountReadsAsAPersonWouldWriteIt` |

## 4. Navigation and layout

| Capability | Reference behaviour | Native before | Native now | Evidence |
| --- | --- | --- | --- | --- |
| Server rail and channel list | Rail, categories, channel list | Rail and channel list | Unchanged | -- |
| Member list | Per-server member list beside the channel | **Open** | **Open** | -- |
| Direct messages | List titled by the other person, avatar per conversation | List titled by participant | List, avatar now resolved | `ChatWorkspace.DirectMessages` |
| Threads | Thread pane with the parent message, replies and a composer | Sheet listing threads; opened on the **oldest** messages | Opens on the newest page and pages backwards | `ChatPagingTest.aThreadOpensOnItsNewestMessages`, `olderThreadMessagesPageBackwards` |
| Threads in the sidebar | Active threads nest under their channel | **Not listed at all** | Nested under their channel with their reply count | `AppStateCacheTest.anOpenThreadIsListedUnderItsOwnChannel`, `aNewThreadAppearsWithoutWaitingForAReload` |
| Search | Search with filters across the server | Text search in the open channel | Unchanged | **Partial** |
| Pinned messages | Popup naming the author of each pin | Sheet without authors | Sheet naming each author | `RoomScreen` pins sheet |
| Notifications | Inbox with actor, preview and filters | Inbox with actor and preview | Unchanged | `NotificationInboxTest` |
| Profile view | Full profile with posts and followers | Account settings only | Tapping an author's picture or name opens their sheet: picture, name, handle, pronouns, bio, Message | **Partial** -- not the full profile page |
| Caching | Lists are kept and revalidated, not re-read per navigation | Every navigation re-read the channel tree and the DM list | Both are kept; a refresh clears them; sign-out and an account change drop every list | `AppStateCacheTest.signingOutDropsEveryCachedList`, `aChannelTreeIsKeptSoNavigationDoesNotRefetchIt` |
| Settings | Appearance, notifications, privacy, security | All four panels | Unchanged | `AccountAppearanceRenderTest` |

## 5. What this round changed

- `Profile.status` and `Profile.custom_status` are `JsonElement`, matching `smallint` and `jsonb`. **This is the reported bug.**
- `ProfileRepository.byIds` survives one unreadable row instead of losing the page.
- `BotRepository` reads `bots` by id, so bot authors resolve.
- `MessageAuthor.kt` resolves all five author kinds in the reference order.
- `AvatarUrl` resolves every stored URL form against the instance and treats the bundled default as local.
- `Avatar` resolves through `AvatarUrl` itself, so no caller can pass a raw path again.
- `MessageRow` groups by author and time, marks bots, and opens a profile sheet on tap.
- `AppState.resolveAuthors` resolves profiles and bots for a page in one place.
- `ChatRepository.threadMessages` pages backwards from the newest message, as the web client does.
- `MessageRow` has no per-row three-dot button: a long press opens the actions, and a reaction pill names its reactors.
- `AppState` keeps the server tree, the DM list and the threads per channel, and drops all of them on sign-out or an account change.
- The channel list nests each channel's open threads under it, with their reply counts.

## 6. The seven reports from using the app

| Report | What was wrong | What it does now | Evidence |
| --- | --- | --- | --- |
| 1. Cannot see who reacted | A pill carried a count and nothing else | Tapping a pill names every reactor with their own display name and picture | `RoomScreen` reactors sheet |
| 2. A three-dot menu on every message | The actions were behind a per-row button | No per-row button: long-press a row, or long-press a reaction pill to toggle it | `RoomScreen.MessageRow` |
| 3. Newest thread messages fail to load | `threadMessages` ordered ascending, so a thread opened on its oldest page | Orders descending and takes the newest page, then pages backwards | `ChatPagingTest` (4 tests) |
| 4. Tapping an author does nothing | Nothing was tappable | Tapping the picture or the name opens the profile sheet | `RoomScreen` author sheet |
| 5. Generic Android, not Harmony | 36dp avatar, 10dp gap, semi-bold name, text BOT marker | The reference's metrics and a primary-coloured BOT tag | `author-header-grouping.png` |
| 6. Open threads missing from the sidebar | Threads were only in a sheet | Each channel nests its threads with reply counts; a new thread appears at once | `AppStateCacheTest` (3 thread tests) |
| 7. Lists reload on every navigation | The channel tree and DM list were re-read each visit | Both are kept, cleared by a refresh, and dropped on sign-out or an account change | `AppStateCacheTest` (2 cache tests) |

## 8. Genuine remaining limitations

These are not hidden behind a label. They are what the native client still does not do, in the
order they matter for daily messaging.

1. **Video and audio play nowhere.** An image draws; a video or audio attachment names itself and its size but does not play or expand.
2. **No member list.** There is no way to browse who is in a server.
3. **There is no emoji picker.** Tapping a pill names who reacted and long-pressing toggles a reaction you already have, but choosing a new emoji is not offered.
4. **No edited marker**, and the reply reference does not show the replied-to text.
5. **Author colour is uniform.** Role colour and per-account colour are not applied, so a display name is always the same colour rather than the account's own.
6. **No presence dot** on avatars.
7. **Voice and video calls are not implemented** and the UI says so.
8. **The profile surface is a sheet on a message**, not the full profile screen with posts and followers.
9. **A tapped image does not open full screen.** The web client opens a lightbox; here the picture draws at its inline size only.

## 9. How each claim was checked

- Author and avatar resolution: JVM unit tests against `MockWebServer` with real row shapes.
- Painting: a Robolectric render test asserting a pixel of a known-colour PNG.
- Journeys: `NativeJourneyTest` and the per-screen render tests.
- Device: an emulator run with representative data, captured under `docs/build/evidence/android-native-emulator/`.
