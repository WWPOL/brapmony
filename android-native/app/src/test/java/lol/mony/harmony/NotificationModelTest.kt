package lol.mony.harmony

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.Notification
import lol.mony.harmony.data.model.NotificationActor
import lol.mony.harmony.data.model.NotificationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationModelTest {
    @Test
    fun rpcRowsDecodeNullableTimestampsAndPreserveUnknownTypes() {
        val rows = HarmonyJson.decodeFromString(
            ListSerializer(Notification.serializer()),
            """[
                {
                    "id": "notification-1", "user_id": "profile-owner",
                    "type": "future_notification_type", "data": {"new_field": "kept"},
                    "is_read": false, "is_clicked": false,
                    "created_at": "2026-10-01T10:00:00Z", "updated_at": null,
                    "expires_at": null, "read_at": null, "future_column": true
                },
                {
                    "id": "notification-2", "user_id": "profile-owner", "type": "activitypub_reply",
                    "is_read": true, "is_clicked": true,
                    "created_at": "2026-10-01T10:00:00Z", "updated_at": "2026-10-01T11:00:00Z",
                    "expires_at": "2026-11-01T10:00:00Z", "read_at": "2026-10-01T11:00:00Z"
                }
            ]""",
        )

        assertEquals("profile-owner", rows[0].userId)
        assertEquals("future_notification_type", rows[0].type)
        assertNull(rows[0].updatedAt)
        assertNull(rows[0].expiresAt)
        assertNull(rows[0].readAt)
        assertFalse(rows[0].isRead)
        assertFalse(rows[0].isClicked)
        assertEquals("2026-10-01T11:00:00Z", rows[1].updatedAt)
        assertEquals("2026-11-01T10:00:00Z", rows[1].expiresAt)
        assertEquals("2026-10-01T11:00:00Z", rows[1].readAt)
        assertTrue(rows[1].isRead)
        assertTrue(rows[1].isClicked)
    }

    @Test
    fun olderRowsMayOmitOptionalTimestampsAndData() {
        val row = HarmonyJson.decodeFromString(
            Notification.serializer(),
            """{"id":"old-row","user_id":"profile-owner","type":"system","created_at":"2026-10-01T10:00:00Z"}""",
        )

        assertNull(row.updatedAt)
        assertNull(row.expiresAt)
        assertNull(row.readAt)
        assertEquals(JsonObject(emptyMap()), row.data)
        assertFalse(row.isRead)
        assertFalse(row.isClicked)
    }

    @Test
    fun allSupportedNestedActorShapesUseProfileIdentityAndRemoteHandle() {
        for (key in listOf("sender", "actor", "reactor", "author", "user", "follower", "inviter")) {
            val row = notification(
                data = """{"$key":{
                    "user_id":"actor-profile", "username":"river", "display_name":"River",
                    "domain":"remote.example", "is_local":false, "avatar_url":"https://remote.example/avatar.png"
                }}""",
            )

            assertEquals(
                key,
                NotificationActor("actor-profile", "River", "@river@remote.example", "https://remote.example/avatar.png"),
                row.actor,
            )
        }
    }

    @Test
    fun nestedSenderTakesPrecedenceOverLegacyActorAndNormalizesExplicitHandle() {
        val row = notification(
            data = """{
                "sender":{"id":"nested-profile","username":"river","display_name":"Nested River",
                    "handle":"river@remote.example","avatar_url":"https://remote.example/nested.png"},
                "from_user_id":"legacy-profile","sender_username":"legacy","sender_display_name":"Legacy",
                "sender_avatar_url":"https://remote.example/legacy.png"
            }""",
        )

        assertEquals(
            NotificationActor("nested-profile", "Nested River", "@river@remote.example", "https://remote.example/nested.png"),
            row.actor,
        )
    }

    @Test
    fun legacySenderFieldsStillProduceAnActor() {
        val row = notification(
            data = """{
                "from_user_id":"legacy-profile","sender_username":"river","sender_display_name":"River",
                "sender_avatar_url":"https://remote.example/avatar.png"
            }""",
        )

        assertEquals(
            NotificationActor("legacy-profile", "River", "@river", "https://remote.example/avatar.png"),
            row.actor,
        )
    }

    @Test
    fun nestedFollowerSupportsBothIdFieldsAndTargetsTheProfile() {
        for (idField in listOf("id", "user_id")) {
            val row = notification(
                type = "activitypub_follow_request",
                data = """{"follower":{"$idField":"follower-profile","username":"river","handle":"@river@remote.example"}}""",
            )

            assertTrue(row.isFollowRequest)
            assertEquals("follower-profile", row.followerId)
            assertEquals("follower-profile", row.actor?.id)
            assertEquals("@river@remote.example", row.actor?.handle)
            assertEquals(NotificationTarget.Profile("follower-profile"), row.target)
        }
    }

    @Test
    fun legacyFollowerFieldsSupportFollowActionsAndProfileTargets() {
        val row = notification(
            type = "activitypub_follow",
            data = """{
                "follower_id":"follower-profile", "follower_username":"river", "follower_display_name":"River",
                "follower_handle":"river@remote.example", "follower_avatar_url":"https://remote.example/avatar.png"
            }""",
        )

        assertFalse(row.isFollowRequest)
        assertEquals("follower-profile", row.followerId)
        assertEquals(
            NotificationActor("follower-profile", "River", "@river@remote.example", "https://remote.example/avatar.png"),
            row.actor,
        )
        assertEquals(NotificationTarget.Profile("follower-profile"), row.target)
    }

    @Test
    fun explicitFollowerIdWinsOverNestedFollowerAndPostTarget() {
        val row = notification(
            type = "activitypub_follow_request",
            data = """{"follower_id":"requested-profile","follower":{"id":"other-profile"},"post_id":"not-the-target"}""",
        )

        assertEquals("requested-profile", row.followerId)
        assertEquals(NotificationTarget.Profile("requested-profile"), row.target)
    }

    @Test
    fun acceptedFollowTargetsTheSenderRatherThanAPost() {
        for (idField in listOf("id", "user_id")) {
            val row = notification(
                type = "activitypub_follow_accepted",
                data = """{"sender":{"$idField":"accepted-profile"},"post_id":"not-the-target"}""",
            )

            assertEquals(NotificationTarget.Profile("accepted-profile"), row.target)
        }
    }

    @Test
    fun activityPubPostTargetsSupportFlatAndNestedPayloads() {
        assertEquals(
            NotificationTarget.Post("flat-post"),
            notification(data = """{"post_id":"flat-post","post":{"id":"nested-post"}}""").target,
        )
        assertEquals(
            NotificationTarget.Post("nested-post"),
            notification(data = """{"post":{"id":"nested-post"}}""").target,
        )
        assertNull(notification(type = "system", data = """{"post_id":"not-supported"}""").target)
        assertNull(notification(data = """{"post_id":null,"post":{},"url":"https://remote.example/post"}""").target)
    }

    @Test
    fun absentOrMalformedActorObjectsDoNotInventIdentity() {
        assertNull(notification().actor)
        assertNull(notification(data = """{"sender":[],"actor":null,"sender_username":" "}""").actor)
        assertEquals(
            NotificationActor("known-profile", "Someone", null, null),
            notification(data = """{"from_user_id":"known-profile"}""").actor,
        )
    }

    @Test
    fun reportUpdatesHideResolverUnlessExplicitlyOptedInEvenWithNestedSender() {
        for (flag in listOf<String?>(null, "false", "null")) {
            val optIn = flag?.let { ",\"show_resolver\":$it" }.orEmpty()
            val row = notification(
                type = "report_update",
                data = """{
                    "resolver_display_name":"Private Moderator", "resolver_username":"private-mod",
                    "resolver_avatar_url":"https://remote.example/private.png",
                    "sender":{"id":"private-profile","username":"private-mod"}$optIn
                }""",
            )

            assertNull("show_resolver=$flag must not expose staff identity", row.actor)
            assertNull(row.target)
        }
    }

    @Test
    fun optedInReportResolverUsesOnlyPublicDisplayFields() {
        val row = notification(
            type = "report_update",
            data = """{
                "show_resolver":true,"resolver_display_name":"Moderator","resolver_username":"mod",
                "resolver_avatar_url":"https://remote.example/mod.png","sender":{"id":"private-profile"}
            }""",
        )

        assertEquals(NotificationActor(null, "Moderator", null, "https://remote.example/mod.png"), row.actor)
        assertEquals(
            NotificationActor(null, "mod", null, null),
            notification(type = "report_update", data = """{"show_resolver":true,"resolver_username":"mod"}""").actor,
        )
        assertEquals(
            NotificationActor(null, "Admin/Moderator", null, null),
            notification(type = "report_update", data = """{"show_resolver":true}""").actor,
        )
    }

    @Test
    fun moderationWarningsNeverExposeStaffActor() {
        assertNull(
            notification(
                type = "moderation_warning",
                data = """{"sender":{"id":"private-profile","username":"private-mod"},"show_resolver":true}""",
            ).actor,
        )
    }

    private fun notification(type: String = "activitypub_reaction", data: String = "{}"): Notification = Notification(
        id = "notification-1",
        userId = "profile-owner",
        type = type,
        data = HarmonyJson.decodeFromString(JsonObject.serializer(), data),
        createdAt = "2026-10-01T10:00:00Z",
    )
}
