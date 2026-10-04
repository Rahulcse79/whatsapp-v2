package com.whatsappv2.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How big a group may be, and when it may be called.
 *
 * Two different limits that happen to share a number. A group this app **creates** is capped
 * so that it is always one it can also ring; a group it merely **finds** can be any size,
 * because it was made elsewhere — so whether the call buttons appear is asked separately
 * rather than assumed from the cap.
 */
class ChatGroupTest {

    private fun group(members: Int) = ChatGroup(
        id = ConversationId("g1"),
        name = "Test group",
        createdBy = "owner-ulid",
        members = List(members) { index ->
            ChatGroupMember(
                userId = "user-$index",
                role = if (index == 0) GroupRole.OWNER else GroupRole.MEMBER,
            )
        },
    )

    @Test
    fun `a group of four can be called, four being the app's conference ceiling everywhere`() {
        assertTrue(group(members = 2).isCallable)
        assertTrue(group(members = 3).isCallable)
        assertTrue(group(members = 4).isCallable)
    }

    @Test
    fun `a group of five cannot, so the buttons are absent rather than failing when pressed`() {
        // Not a cost the bridge imposes — a bridged leg is one leg whatever the room holds.
        // It is `ConferenceMesh.MAX_MESH`, held here so one ceiling applies app-wide.
        assertFalse(group(members = 5).isCallable)
        assertFalse(group(members = 9).isCallable)
    }

    @Test
    fun `a group with nobody else in it has nobody to call`() {
        // Freshly created, before any member is added. There is no call to place.
        assertFalse(group(members = 1).isCallable)
        assertFalse(group(members = 0).isCallable)
    }

    @Test
    fun `size counts the owner, because the owner is on the call too`() {
        assertEquals(4, group(members = 4).size)
        assertEquals(GroupRole.OWNER, group(members = 4).members.first().role)
    }

    @Test
    fun `an unrecognised role is a plain member, so a new one cannot hide somebody`() {
        assertEquals(GroupRole.OWNER, GroupRole.parse("OWNER"))
        assertEquals(GroupRole.OWNER, GroupRole.parse("owner"))
        assertEquals(GroupRole.MEMBER, GroupRole.parse("MEMBER"))
        assertEquals(GroupRole.MEMBER, GroupRole.parse("ADMIN"))
        assertEquals(GroupRole.MEMBER, GroupRole.parse(null))
    }

    @Test
    fun `a group with no name yet is not a group with no name`() {
        // `GET /conversations` returns GROUP rows with a null name — the name lives only on
        // the group endpoints, so null here means "not loaded", never "untitled".
        assertEquals(null, group(members = 2).copy(name = null).name)
    }
}
