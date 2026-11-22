package com.liquidglass.messages

import com.liquidglass.messages.data.local.DraftStore
import com.liquidglass.messages.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftAndStatusStateTest {

    @Test
    fun saveAndRestoreDraftByThreadId() {
        val state = DraftStore.State()
            .withDraft(threadId = 42L, address = "+1 555 123 4567", text = "See you at 6", updatedAt = 1_000L)

        assertEquals("See you at 6", state.draftFor(42L, "+1 555 123 4567")?.text)
    }

    @Test
    fun saveByAddressWhenThreadUnassignedAndMigrateWhenResolved() {
        val pending = DraftStore.State()
            .withDraft(threadId = -1L, address = "+1 (555) 234-5678", text = "Draft before thread", updatedAt = 1_000L)

        // Matches normalized number while threadId <= 0
        assertEquals("Draft before thread", pending.draftFor(-1L, "5552345678")?.text)

        // Migrating moves draft into byThread and clears byAddress
        val migrated = pending.migrateAddressToThread(threadId = 99L, address = "555-234-5678")
        assertEquals("Draft before thread", migrated.draftFor(99L, "+1 (555) 234-5678")?.text)
        assertTrue(migrated.byAddress.isEmpty())
    }

    @Test
    fun blankDraftAutomaticallyClearsSavedDraft() {
        val withDraft = DraftStore.State()
            .withDraft(threadId = 12L, address = "5551112222", text = "Hello")
        val cleared = withDraft
            .withDraft(threadId = 12L, address = "5551112222", text = "   ")

        assertNull(cleared.draftFor(12L, "5551112222"))
        assertTrue(cleared.byThread.isEmpty())
        assertTrue(cleared.byAddress.isEmpty())
    }

    @Test
    fun clearDraftAndDeleteThreadRemoveSavedDraft() {
        val state = DraftStore.State()
            .withDraft(threadId = 10L, address = "5550001111", text = "One")
            .withDraft(threadId = 20L, address = "5550002222", text = "Two")

        val afterClear = state.withoutDraft(threadId = 10L, address = "5550001111")
        assertNull(afterClear.draftFor(10L, "5550001111"))
        assertEquals("Two", afterClear.draftFor(20L, "5550002222")?.text)

        val afterDelete = afterClear.withoutThread(20L)
        assertNull(afterDelete.draftFor(20L, "5550002222"))
    }

    @Test
    fun groupChatsDoNotCollideWithDirectAddressDrafts() {
        val state = DraftStore.State()
            .withDraft(threadId = -1L, address = "5553334444", text = "DM draft", isGroup = false)

        assertNull(state.draftFor(threadId = 77L, address = "5553334444", isGroup = true))
        assertEquals("DM draft", state.draftFor(threadId = -1L, address = "5553334444", isGroup = false)?.text)
    }

    @Test
    fun conversationFlagsReflectDraftAndFailureStates() {
        val normal = Conversation(
            threadId = 1L,
            address = "5551234",
            contactName = "Alice",
            snippet = "Hey",
            timestamp = 1_000L,
            unreadCount = 0,
            messageCount = 1,
        )
        assertFalse(normal.hasDraft)
        assertFalse(normal.latestMessageFailed)

        val blankDraft = normal.copy(draftText = "   ")
        assertFalse(blankDraft.hasDraft)

        val activeDraftAndFailed = normal.copy(draftText = "Retry message", latestMessageFailed = true)
        assertTrue(activeDraftAndFailed.hasDraft)
        assertTrue(activeDraftAndFailed.latestMessageFailed)
    }
}
