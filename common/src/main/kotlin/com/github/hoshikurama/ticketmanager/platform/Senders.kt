package com.github.hoshikurama.ticketmanager.platform

import com.github.hoshikurama.ticketmanager.TMLocale
import com.github.hoshikurama.ticketmanager.ticket.BasicTicket
import net.kyori.adventure.text.Component
import java.util.*

sealed class Sender(
    val name: String,
    val locale: TMLocale
) {
    abstract fun sendMessage(msg: String)
    abstract fun sendMessage(component: Component)
    abstract fun has(permission: String): Boolean

    fun toUUIDOrNull() = if (this is Player) uniqueID else null
}

abstract class Player(
    val uniqueID: UUID,
    val permissionGroups: List<String>,
    name: String,
    locale: TMLocale
) : Sender(name, locale) {
    abstract fun getTicketLocFromCurLoc(): BasicTicket.TicketLocation
}

abstract class Console(locale: TMLocale) : Sender(locale.consoleName, locale) {
    override fun has(permission: String) = true
}