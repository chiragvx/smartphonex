package com.smartphonex.core

data class LogEntry(val id: Long, val t: Long, val kind: String, val source: String, val code: String, val message: String)

/** Bounded in-memory logs. Events are for humans; the command log records every command. */
class EventLog(private val capacity: Int = 5000, private val clock: () -> Long = System::currentTimeMillis) {
    private val events = ArrayDeque<LogEntry>()
    private val commands = ArrayDeque<LogEntry>()
    private var nextId = 1L

    @Synchronized fun event(kind: String, source: String, code: String, message: String) =
        push(events, kind, source, code, message)

    @Synchronized fun command(kind: String, source: String, code: String, message: String) =
        push(commands, kind, source, code, message)

    private fun push(q: ArrayDeque<LogEntry>, kind: String, source: String, code: String, message: String) {
        q.addLast(LogEntry(nextId++, clock(), kind, source, code, message))
        if (q.size > capacity) q.removeFirst()
    }

    @Synchronized fun eventsSince(id: Long): List<LogEntry> = events.filter { it.id > id }
    @Synchronized fun allCommands(): List<LogEntry> = commands.toList()
    @Synchronized fun allEvents(): List<LogEntry> = events.toList()
}
