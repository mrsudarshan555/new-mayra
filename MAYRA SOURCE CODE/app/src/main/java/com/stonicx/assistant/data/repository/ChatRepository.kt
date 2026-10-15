package com.stonicx.assistant.data.repository

import com.stonicx.assistant.data.model.ChatTurn
import com.stonicx.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatRepository(private val preferences: AppPreferences) {

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        _turns.value = preferences.loadChatHistory()
    }

    @Synchronized
    fun addTurn(userText: String, stonicxText: String) {
        val cleanUser = userText.trim()
        val cleanStonicX = stonicxText.trim()

        if (cleanUser.isEmpty() && cleanStonicX.isEmpty()) return

        val currentList = _turns.value.toMutableList()

        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.userTranscript == cleanUser && last.stonicxResponse == cleanStonicX) {
                return
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser.ifEmpty { "(Audio query)" },
            stonicxResponse = cleanStonicX.ifEmpty { "(Audio response)" }
        )

        currentList.add(newTurn)
        if (currentList.size > 100) {
            currentList.removeAt(0)
        }

        _turns.value = currentList
        preferences.saveChatHistory(currentList)
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        preferences.clearChatHistory()
    }
}
