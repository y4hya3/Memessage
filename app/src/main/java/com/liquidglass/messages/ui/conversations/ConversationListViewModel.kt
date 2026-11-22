package com.liquidglass.messages.ui.conversations

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.liquidglass.messages.MessagesApplication
import com.liquidglass.messages.data.local.DraftStore
import com.liquidglass.messages.data.model.Conversation
import com.liquidglass.messages.data.sms.SmsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the conversation list screen.
 *
 * Field names are part of the screen-layer contract — do not rename.
 *
 * @param conversations the (search-filtered) conversation threads, newest first
 * @param searchQuery   current text in the search field
 * @param isLoading     true until the repository emits its first list
 */
@Immutable
data class ConversationListUiState(
    val conversations: List<Conversation> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true
)

/**
 * Drives the conversation list: observes the repository's live conversation
 * stream, merges unsent drafts from [DraftStore], applies a case-insensitive
 * search filter, and exposes the result as a single [StateFlow] of
 * [ConversationListUiState].
 */
class ConversationListViewModel(
    private val repository: SmsRepository,
    private val draftStore: DraftStore? = null,
) : ViewModel() {

    /** Backing flow for the search box; drives re-filtering of the list. */
    private val _searchQuery = MutableStateFlow("")

    /** Current search text, exposed so the screen can render the field. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Combines the live conversation list, persisted drafts, and the current
     * search query into the filtered UI state. [SharingStarted.Lazily] keeps
     * the upstream observer alive while the inbox remains on the backstack so
     * SMS status broadcasts and draft edits sync immediately without waiting
     * for the user to pop back from a chat.
     */
    val uiState: StateFlow<ConversationListUiState> =
        combine(
            repository.observeConversations()
                .catch { emit(repository.cachedConversations().orEmpty()) },
            draftStore?.state ?: flowOf(DraftStore.State()),
            _searchQuery
        ) { conversations, draftState, query ->
            val withDrafts = conversations
                .distinctBy { it.threadId }
                .map { c ->
                    val draft = draftState.draftFor(c.threadId, c.address, c.isGroup)?.text
                    if (draft != c.draftText) c.copy(draftText = draft) else c
                }
            val trimmed = query.trim()
            val filtered = if (trimmed.isBlank()) {
                withDrafts
            } else {
                withDrafts.filter { it.matches(trimmed) }
            }
            ConversationListUiState(
                conversations = filtered,
                searchQuery = query,
                isLoading = false
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            // The cached inbox paints the first frame instantly (no empty flash).
            initialValue = repository.cachedConversations()
                ?.let { cached ->
                    val draftState = draftStore?.state?.value ?: DraftStore.State()
                    val withDrafts = cached.map { c ->
                        val draft = draftState.draftFor(c.threadId, c.address, c.isGroup)?.text
                        if (draft != c.draftText) c.copy(draftText = draft) else c
                    }
                    ConversationListUiState(conversations = withDrafts, isLoading = false)
                }
                ?: ConversationListUiState()
        )

    /** Updates the search query, re-triggering the combine above. */
    fun onSearchChange(q: String) {
        _searchQuery.value = q
    }

    /** Deletes an entire thread; clears any saved draft and removes it from the list. */
    fun deleteThread(threadId: Long) {
        draftStore?.clearThread(threadId)
        viewModelScope.launch { repository.deleteThread(threadId) }
    }

    /** Marks every message in a thread read; clears its unread badge. */
    fun markRead(threadId: Long) {
        viewModelScope.launch { repository.markThreadRead(threadId) }
    }

    /** iOS "Mark as Unread". */
    fun markUnread(threadId: Long) {
        viewModelScope.launch { repository.markThreadUnread(threadId) }
    }

    /** Case-insensitive match on display name, raw address, snippet, and draft text. */
    private fun Conversation.matches(query: String): Boolean {
        val q = query.lowercase()
        return displayName.lowercase().contains(q) ||
            address.lowercase().contains(q) ||
            snippet.lowercase().contains(q) ||
            (draftText?.lowercase()?.contains(q) == true)
    }

    companion object {
        /** ViewModel factory that pulls the repository and draft store from the application container. */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as MessagesApplication
                ConversationListViewModel(
                    repository = app.container.smsRepository,
                    draftStore = app.container.draftStore,
                )
            }
        }
    }
}
