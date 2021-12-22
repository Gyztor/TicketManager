package com.github.hoshikurama.ticketmanager.ticket

import com.github.hoshikurama.ticketmanager.TMLocale
import kotlinx.serialization.Serializable
import java.util.*

interface BasicTicket {
    val id: Int                             // Ticket ID 1+... -1 placeholder during ticket creation
    val creatorUUID: UUID?                  // UUID if player, null if Console
    val location: TicketLocation?           // TicketLocation if player, null if Console
    val priority: Priority                  // Priority 1-5 or Lowest to Highest
    val status: Status                      // Status OPEN or CLOSED
    val assignedTo: String?                 // Null if not assigned to anybody
    val creatorStatusUpdate: Boolean        // Determines whether player should be notified

    @Serializable
    data class TicketLocation(val world: String, val x: Int, val y: Int, val z: Int) {
        override fun toString() = "$world $x $y $z"

        companion object {
            fun fromString(s: String): TicketLocation = s.split(" ")
                .let { TicketLocation(it[0], it[1].toInt(), it[2].toInt(), it[3].toInt()) }
        }
    }

    @Serializable
    enum class Priority(val level: Byte) {
        LOWEST(1),
        LOW(2),
        NORMAL(3),
        HIGH(4),
        HIGHEST(5),
    }

    @Serializable
    enum class Status {
        OPEN, CLOSED
    }
}


fun BasicTicket.Priority.toLocaledWord(locale: TMLocale) = when (this) {
    BasicTicket.Priority.LOWEST -> locale.priorityLowest
    BasicTicket.Priority.LOW -> locale.priorityLow
    BasicTicket.Priority.NORMAL -> locale.priorityNormal
    BasicTicket.Priority.HIGH -> locale.priorityHigh
    BasicTicket.Priority.HIGHEST -> locale.priorityHighest
}

fun BasicTicket.Status.toLocaledWord(locale: TMLocale) = when (this) {
    BasicTicket.Status.OPEN -> locale.statusOpen
    BasicTicket.Status.CLOSED -> locale.statusClosed
}

fun BasicTicket.uuidMatches(other: UUID?) =
    creatorUUID?.equals(other) ?: (other == null)

operator fun BasicTicket.plus(actions: List<FullTicket.Action>): FullTicket {
    return FullTicket(id, creatorUUID, location, priority, status, assignedTo, creatorStatusUpdate, actions)
}