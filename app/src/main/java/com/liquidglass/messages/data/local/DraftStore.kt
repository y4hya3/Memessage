package com.liquidglass.messages.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistent store for unsent message drafts, keyed by Telephony thread id
 * (with normalized phone number fallback when a thread id is not yet assigned).
 *
 * Exposes an observable [StateFlow] so both the chat composer and the main
 * conversations list update immediately whenever a draft is saved or cleared.
 */
class DraftStore(context: Context) {

    data class DraftEntry(
        val threadId: Long,
        val address: String,
        val text: String,
        val updatedAt: Long,
    )

    data class State(
        val byThread: Map<Long, DraftEntry> = emptyMap(),
        val byAddress: Map<String, DraftEntry> = emptyMap(),
    ) {
        fun draftFor(threadId: Long, address: String, isGroup: Boolean = false): DraftEntry? {
            if (threadId > 0L) {
                byThread[threadId]?.let { return it }
            }
            if (!isGroup && address.isNotBlank()) {
                return byAddress[normalizeAddressKey(address)]
            }
            return null
        }

        fun withDraft(
            threadId: Long,
            address: String,
            text: String,
            isGroup: Boolean = false,
            updatedAt: Long = System.currentTimeMillis(),
        ): State {
            if (text.isBlank()) return withoutDraft(threadId, address, isGroup)
            val addrKey = if (!isGroup && address.isNotBlank()) normalizeAddressKey(address) else null
            return if (threadId > 0L) {
                val entry = DraftEntry(threadId = threadId, address = address, text = text, updatedAt = updatedAt)
                copy(
                    byThread = byThread + (threadId to entry),
                    byAddress = if (addrKey != null) byAddress - addrKey else byAddress,
                )
            } else if (addrKey != null) {
                val entry = DraftEntry(threadId = threadId, address = address, text = text, updatedAt = updatedAt)
                copy(byAddress = byAddress + (addrKey to entry))
            } else {
                this
            }
        }

        fun withoutDraft(threadId: Long, address: String, isGroup: Boolean = false): State {
            val addrKey = if (!isGroup && address.isNotBlank()) normalizeAddressKey(address) else null
            val nextThread = if (threadId > 0L) byThread - threadId else byThread
            val nextAddress = if (addrKey != null) byAddress - addrKey else byAddress
            return if (nextThread === byThread && nextAddress === byAddress) this
            else copy(byThread = nextThread, byAddress = nextAddress)
        }

        fun withoutThread(threadId: Long): State {
            if (threadId <= 0L || threadId !in byThread) return this
            return copy(byThread = byThread - threadId)
        }

        fun migrateAddressToThread(threadId: Long, address: String): State {
            if (threadId <= 0L || address.isBlank()) return this
            val addrKey = normalizeAddressKey(address)
            val existing = byAddress[addrKey] ?: return this
            val nextThread = if (threadId in byThread) byThread else byThread + (threadId to existing.copy(threadId = threadId))
            return copy(byThread = nextThread, byAddress = byAddress - addrKey)
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    fun getDraft(threadId: Long, address: String, isGroup: Boolean = false): String? =
        _state.value.draftFor(threadId, address, isGroup)?.text

    fun saveDraft(threadId: Long, address: String, text: String, isGroup: Boolean = false) =
        update { it.withDraft(threadId, address, text, isGroup) }

    fun clearDraft(threadId: Long, address: String, isGroup: Boolean = false) =
        update { it.withoutDraft(threadId, address, isGroup) }

    fun clearThread(threadId: Long) =
        update { it.withoutThread(threadId) }

    fun migrateAddressToThread(threadId: Long, address: String) =
        update { it.migrateAddressToThread(threadId, address) }

    private fun update(transform: (State) -> State) {
        synchronized(lock) {
            val current = _state.value
            val next = transform(current)
            if (next == current) return
            _state.value = next
            save(next)
        }
    }

    private fun save(state: State) {
        runCatching {
        val root = JSONObject()
        root.put("threads", JSONArray().apply {
            state.byThread.values.forEach { entry ->
                put(JSONObject().apply {
                    put("t", entry.threadId)
                    put("a", entry.address)
                    put("x", entry.text)
                    put("ts", entry.updatedAt)
                })
            }
        })
        root.put("addresses", JSONArray().apply {
            state.byAddress.values.forEach { entry ->
                put(JSONObject().apply {
                    put("t", entry.threadId)
                    put("a", entry.address)
                    put("x", entry.text)
                    put("ts", entry.updatedAt)
                })
            }
        })
        prefs.edit().putString(KEY_DRAFTS, root.toString()).apply()
        }
    }

    private fun load(): State = runCatching {
        val raw = prefs.getString(KEY_DRAFTS, null) ?: return State()
        val root = JSONObject(raw)
        val threadsArr = root.optJSONArray("threads") ?: JSONArray()
        val addressesArr = root.optJSONArray("addresses") ?: JSONArray()

        val byThread = LinkedHashMap<Long, DraftEntry>()
        for (i in 0 until threadsArr.length()) {
            val o = threadsArr.getJSONObject(i)
            val threadId = o.getLong("t")
            val text = o.optString("x")
            if (threadId > 0L && text.isNotBlank()) {
                byThread[threadId] = DraftEntry(
                    threadId = threadId,
                    address = o.optString("a"),
                    text = text,
                    updatedAt = o.optLong("ts"),
                )
            }
        }

        val byAddress = LinkedHashMap<String, DraftEntry>()
        for (i in 0 until addressesArr.length()) {
            val o = addressesArr.getJSONObject(i)
            val address = o.optString("a")
            val text = o.optString("x")
            if (address.isNotBlank() && text.isNotBlank()) {
                byAddress[normalizeAddressKey(address)] = DraftEntry(
                    threadId = o.optLong("t", -1L),
                    address = address,
                    text = text,
                    updatedAt = o.optLong("ts"),
                )
            }
        }

        State(byThread = byThread, byAddress = byAddress)
    }.getOrDefault(State())

    companion object {
        private const val PREFS = "message_drafts"
        private const val KEY_DRAFTS = "drafts_v1"

        internal fun normalizeAddressKey(address: String): String {
            val digits = address.filter(Char::isDigit)
            return if (digits.length >= 7) digits.takeLast(10) else address.trim().lowercase()
        }
    }
}
