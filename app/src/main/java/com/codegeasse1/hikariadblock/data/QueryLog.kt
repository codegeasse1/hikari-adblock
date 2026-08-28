package com.codegeasse1.hikariadblock.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object QueryLog {

    data class Entry(val host: String, val blocked: Boolean, val time: Long)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _blockedSession = MutableStateFlow(0L)
    val blockedSession: StateFlow<Long> = _blockedSession.asStateFlow()

    private val _totalSession = MutableStateFlow(0L)
    val totalSession: StateFlow<Long> = _totalSession.asStateFlow()

    fun onQuery(host: String, blocked: Boolean) {
        _totalSession.update { it + 1 }
        if (blocked) _blockedSession.update { it + 1 }
        _entries.update { (listOf(Entry(host, blocked, System.currentTimeMillis())) + it).take(500) }
    }

    fun resetSession() {
        _blockedSession.value = 0L
        _totalSession.value = 0L
    }
}
