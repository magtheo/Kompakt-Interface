package dev.magnor.kompakt.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.magnor.kompakt.data.repository.ChatRepository
import dev.magnor.kompakt.domain.ChatThread
import dev.magnor.kompakt.domain.EntityId
import dev.magnor.kompakt.domain.Message
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Instant

/** Chat list — conversation contexts, intentionally separate from agents (D003). */
class ChatListViewModel(
    chatRepository: ChatRepository,
    val now: Instant,
) : ViewModel() {
    val threads: StateFlow<List<ChatThread>> = chatRepository.observeThreads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** One conversation thread, resolved by navigation id. */
class ChatThreadViewModel(
    chatRepository: ChatRepository,
    threadId: EntityId,
) : ViewModel() {
    val thread: StateFlow<ChatThread?> = chatRepository.observeThread(threadId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<Message>> = chatRepository.observeMessages(threadId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
