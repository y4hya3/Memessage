package com.liquidglass.messages

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.liquidglass.messages.data.local.DraftStore
import com.liquidglass.messages.data.local.MessageMetaStore
import com.liquidglass.messages.data.mms.MmsCoordinator
import com.liquidglass.messages.data.mms.MmsStore
import com.liquidglass.messages.data.mms.MmsTransport
import com.liquidglass.messages.data.model.Contact
import com.liquidglass.messages.data.model.Conversation
import com.liquidglass.messages.data.model.Message
import com.liquidglass.messages.data.reaction.ReactionSyncManager
import com.liquidglass.messages.data.schedule.ScheduledMessageStore
import com.liquidglass.messages.data.sms.ContactsHelper
import com.liquidglass.messages.data.sms.SmsRepository
import com.liquidglass.messages.data.sms.SmsRepositoryImpl
import com.liquidglass.messages.data.sms.SmsSender
import com.liquidglass.messages.notification.NotificationHelper
import com.liquidglass.messages.ui.chat.ChatViewModel
import com.liquidglass.messages.ui.conversations.ConversationListViewModel
import com.liquidglass.messages.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = MessagesApplication::class)
class SmsRepositoryResilienceTest {

    @Test
    fun repositoryObserversDegradeGracefullyWithoutPermissionsOnApi29() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val contactsHelper = ContactsHelper(context)
        val mmsStore = MmsStore(context)
        val mmsTransport = MmsTransport(context)
        val notifications = NotificationHelper(context)
        val mms = MmsCoordinator(context, mmsStore, mmsTransport, contactsHelper, notifications)
        val repo = SmsRepositoryImpl(
            context = context,
            sender = SmsSender(context),
            contactsHelper = contactsHelper,
            ioDispatcher = Dispatchers.IO,
            mmsStore = mmsStore,
            mms = mms,
        )

        val conversations = repo.observeConversations().first()
        val messages = repo.observeMessages(1L).first()

        assertTrue(conversations.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun conversationListViewModelSurvivesSecurityExceptionAndDeduplicatesThreads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failingRepo = object : StubSmsRepository() {
            override fun observeConversations(): Flow<List<Conversation>> = flow {
                throw SecurityException("MIUI AppOps denied mms-sms observer")
            }

            override fun cachedConversations(): List<Conversation> = listOf(
                Conversation(
                    threadId = 7L,
                    address = "+15551234567",
                    contactName = "Alice",
                    snippet = "Hi",
                    timestamp = 1_000L,
                    unreadCount = 0,
                    messageCount = 1,
                ),
            )
        }

        val vm = ConversationListViewModel(failingRepo, DraftStore(context))
        val state = vm.uiState.first { !it.isLoading }
        assertEquals(1, state.conversations.size)
        assertEquals(7L, state.conversations.first().threadId)

        val duplicateRepo = object : StubSmsRepository() {
            override fun observeConversations(): Flow<List<Conversation>> = flowOf(
                listOf(
                    Conversation(7L, "5551234", "Alice", "Newest", 2_000L, 0, 2),
                    Conversation(7L, "5551234", "Alice", "Older dup", 1_000L, 0, 2),
                ),
            )
        }
        val dedupVm = ConversationListViewModel(duplicateRepo, DraftStore(context))
        val dedupState = dedupVm.uiState.first { !it.isLoading }
        assertEquals(1, dedupState.conversations.size)
    }

    @Test
    fun chatViewModelSurvivesObserverSecurityException() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failingRepo = object : StubSmsRepository() {
            override fun observeMessages(threadId: Long): Flow<List<Message>> = flow {
                throw SecurityException("MIUI denied observeMessages")
            }

            override suspend fun resolveContact(address: String): Contact {
                throw SecurityException("Contacts blocked")
            }
        }
        val metaStore = MessageMetaStore(context)
        val reactionSync = ReactionSyncManager(failingRepo, metaStore, SmsSender(context))
        val scheduler = ScheduledMessageStore(context)

        val vm = ChatViewModel(
            appContext = context,
            repository = failingRepo,
            metaStore = metaStore,
            reactionSync = reactionSync,
            scheduler = scheduler,
            threadId = 5L,
            address = "5551234",
            draftStore = DraftStore(context),
        )

        assertTrue(vm.uiState.value.messages.isEmpty())
    }

    @Test
    fun timeFormatHandlesZeroAndNegativeTimestamps() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("", TimeFormat.conversationStamp(context, 0L))
        assertEquals("", TimeFormat.conversationStamp(context, -500L))
    }

    private open class StubSmsRepository : SmsRepository {
        override fun observeConversations(): Flow<List<Conversation>> = flowOf(emptyList())
        override suspend fun getConversations(): List<Conversation> = emptyList()
        override fun observeMessages(threadId: Long): Flow<List<Message>> = flowOf(emptyList())
        override suspend fun getMessages(threadId: Long): List<Message> = emptyList()
        override suspend fun sendMessage(address: String, body: String, subscriptionId: Int): Result<Unit> = Result.success(Unit)
        override suspend fun resendMessage(messageId: Long): Result<Unit> = Result.success(Unit)
        override suspend fun persistIncomingSms(address: String, body: String, timestampMillis: Long, subscriptionId: Int): Long = -1L
        override suspend fun sendMms(recipients: List<String>, text: String, attachments: List<Uri>, subscriptionId: Int): Result<Unit> = Result.success(Unit)
        override suspend fun getRecipients(threadId: Long): List<String> = emptyList()
        override suspend fun markThreadRead(threadId: Long) = Unit
        override suspend fun markThreadUnread(threadId: Long) = Unit
        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0
        override suspend fun deleteThread(threadId: Long) = Unit
        override suspend fun restoreThread(threadId: Long) = Unit
        override suspend fun purgeThread(threadId: Long) = Unit
        override suspend fun deleteMessage(messageId: Long) = Unit
        override suspend fun restoreMessage(messageId: Long) = Unit
        override suspend fun purgeMessage(messageId: Long) = Unit
        override suspend fun purgeExpired() = Unit
        override suspend fun getOrCreateThreadId(address: String): Long = -1L
        override suspend fun getOrCreateGroupThreadId(addresses: List<String>): Long = -1L
        override suspend fun resolveContact(address: String): Contact = Contact(number = address)
        override suspend fun searchContacts(query: String): List<Contact> = emptyList()
    }
}
