---
title: Drawing a message body in the native Android client
description: How the client decodes and draws Harmony's message parts, and why a deferred sheet reads a snapshot of its state.
---

# Drawing a message body

Two phone reports drove this change, and they turned out to have the same root
cause: a value the client declared too narrowly, read somewhere that could not
handle the shape the instance actually stores.

1. Opening a channel died with `Unexpected JSON token at offset 17640:
   expected beginning of string but got {`. `content[1].emoji` was an object.
2. Opening a thread from the room's Threads sheet died with a
   `NullPointerException` from `RoomScreen.kt:249`, in the lazy list's content.

## What the instance stores

`messages.content` is a JSON array of parts, and the web client's
`src/types/chat.ts` is the contract. An emoji part is not a string:

```json
{"type": "emoji", "emoji": {"id": null, "name": "blobcat",
 "url": "https://.../blobcat.png"}}
```

`id` is null for a bridged or round-tripped emoji, `url` is absent for one whose
file was purged, and `content` holds a unicode codepoint when the emoji wraps a
system emoji rather than an upload. Older clients wrote the bare string
`:name:`. The old model declared `emoji: String?`, so *one* ordinary emoji
failed the decode of the whole page -- and because `ChatRepository` decodes the
page in one call, one emoji emptied a channel's entire history.

## The decision: decode totally, draw explicitly

The client now decodes a part through `ContentPartSerializer`, which reads the
JSON object itself instead of binding fields by declared type:

- every known field is optional, and a field whose type does not match is
  dropped rather than thrown (`"id": 7` becomes `"7"`, `"count": "many"`
  becomes absent);
- the part's own JSON is kept in `raw`, so a part this build has never seen
  survives instead of being lost;
- an unknown part type degrades to a placeholder on its own line.

The alternative -- catching the decode error and showing "could not load" --
was rejected. It would have hidden the next schema change instead of the first,
and it would have left a channel unreadable for a reason the client could have
rendered. The test `MessagePartContractTest.hostileFieldTypesDoNotFailThePage`
exists to keep that door shut.

### The rest of the union

Fixing only `emoji` would have left the same bug in every other part kind, so
the model was audited against `MessagePart` in `src/types/chat.ts` and the
fields it names are now carried: a masked link's `label`, an `embed`'s
`provider`/`previewId`/`collapsed`, a system event's `timestamp` and
`initiated_by`, a hashtag's `last_updated`/`normalized`, a shared channel's
`messageId`, a sticker's `mimeType`/`domain`, and a file's `path`/`altText`/
`stickerId`. `everyPartKindCarriesTheFieldsTheWebContractNames` pins each one,
so a part kind cannot quietly lose a field the web client reads.

Two of those change what a person sees. A masked link reads as its label
rather than its URL, and a system event reads as `Carol joined` rather than
`[join]`, because the part already carries the person's name.

## Drawing: one flow, not one block per part

`MessageContent` groups the body into **runs**: text and the inline parts
between it (emoji, mention, role mention, channel reference, hashtag, link)
share one `FlowRow`, so `shipping :blobcat: today` is one sentence with the
picture in it, as the web draws it. Only a part that stands on its own -- an
attachment, a sticker, a poll, a system event -- breaks the line. The first
version of this put every part on its own row; the render capture showed
"shipping" and "today" on separate lines with a gap between them, which is
why `MessagePartRenderTest` captures the history rather than only asserting on
the tree.

An emoji draws in this order:

1. its **image**, when the row has a `url`;
2. its **glyph**, when it wraps a unicode codepoint;
3. its **shortcode**, when there is neither.

Every branch carries the same test tag and the shortcode as its content
description, so a screen reader and a test see the same node whichever way a
particular emoji happens to draw.

## The crash: deferred content reads a snapshot

`RoomScreen` guards a sheet with `if (threads != null)` and then read
`threads!!` **inside** the sheet's `LazyColumn`. A `LazyColumn`'s items, a
sheet's body and a dialog's buttons run after the composable body has returned,
so they close over the state as it was. The row's own click sets `threads =
null` and `thread = row`; the list's next recomposition then dereferenced null.

The fix is to bind each nullable to a local `val` before the deferred content
is built, and to read that instead:

```kotlin
val openThreads = threads          // in the composable body
if (openThreads != null) {         // the same value the body checked
    val rows = openThreads         // what the deferred content closes over
    ModalBottomSheet(...) { LazyColumn { items(rows) { ... } } }
}
```

`ChatWorkspace`, `DriveScreen`, `DriveDialogs`, `SocialHomeScreen` and the
notification screens had the same `x!!` shape and are fixed the same way.
`ThreadSheetJourneyTest` drives the route a thumb does -- open Threads, tap the
thread, back out, reopen -- and requires the thread's own messages on screen, so
a sheet that merely stopped crashing without opening the thread fails too.

## What this does not do

- It does not render attachments as thumbnails, polls as votable cards or
  stickers as images; those parts say what they are and stand on their own line.
- It does not execute markdown or HTML, which is the web client's `markdown`
  flag and a separate piece of work with its own safety questions.
- It does not prove the device draws any of this. The pictures in
  `docs/build/evidence/android-native-render/` come from Robolectric drawing the
  real composables on the JVM; the device evidence is the accessibility tree,
  because this host cannot get a frame out of the guest (see
  `native-android-verification.md`).

## Where it lives

- `data/model/Message.kt` -- `Emoji`, `ContentPart`, the two serializers.
- `ui/components/MessageContent.kt` -- the runs, the inline parts, the blocks.
- `ui/screens/chat/RoomScreen.kt` -- the snapshot bindings.
- `data/repo/MessagePartContractTest.kt`, `MessagePartRenderTest.kt`,
  `EmojiImageRenderTest.kt`, `ThreadSheetJourneyTest.kt` -- the regressions.
