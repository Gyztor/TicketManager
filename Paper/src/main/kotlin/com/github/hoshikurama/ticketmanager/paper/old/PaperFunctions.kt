package com.github.hoshikurama.ticketmanager.paper.old

import com.github.hoshikurama.componentDSL.buildComponent
import com.github.hoshikurama.componentDSL.formattedContent
import com.github.hoshikurama.ticketmanager.LocaleHandler
import com.github.hoshikurama.ticketmanager.TMLocale
import com.github.hoshikurama.ticketmanager.misc.pForEach
import com.github.hoshikurama.ticketmanager.platform.PlatformFunctions
import com.github.hoshikurama.ticketmanager.platform.Player
import com.github.hoshikurama.ticketmanager.platform.Sender
import com.github.hoshikurama.ticketmanager.ticket.BasicTicket
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.milkbowl.vault.permission.Permission
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import java.util.*
import java.util.logging.Level

class PaperFunctions(private val perms: Permission) : PlatformFunctions {

    override fun massNotify(localeHandler: LocaleHandler, permission: String, localeMsg: (TMLocale) -> Component) {
        Bukkit.getConsoleSender().sendMessage(localeMsg(localeHandler.consoleLocale))

        Bukkit.getOnlinePlayers().asSequence()
            .map { PaperPlayer(it, perms, localeHandler) }
            .filter { it.has(permission) }
            .forEach { localeMsg(it.locale).run(it::sendMessage) }
    }

    override fun buildPlayer(uuid: UUID, localeHandler: LocaleHandler): Player? {
        return Bukkit.getPlayer(uuid)?.run { PaperPlayer(this, perms, localeHandler) }
    }

    override fun getOnlinePlayers(localeHandler: LocaleHandler): List<Player> {
        return Bukkit.getOnlinePlayers().map { PaperPlayer(it, perms, localeHandler) }
    }

    override fun stripColour(msg: String): String {
        return ChatColor.stripColor(msg)!!
    }

    override fun offlinePlayerNameToUUIDOrNull(name: String): UUID? {
        return Bukkit.getOfflinePlayers().firstOrNull { it.name?.equals(name) ?: false }
            ?.run { uniqueId }
    }

    override fun nameFromUUID(uuid: UUID): String {
        return uuid.run(Bukkit::getOfflinePlayer).name ?: "UUID"
    }

    override fun teleportToTicketLocation(player: Player, loc: BasicTicket.TicketLocation) {
        val world = Bukkit.getWorld(loc.world)
        val paperPlayer = player as PaperPlayer

        world?.run {
            val location = Location(this, loc.x.toDouble(), loc.y.toDouble(), loc.z.toDouble())
            paperPlayer.pPlayer.teleport(location)
        }
    }

    override suspend fun postModifiedStacktrace(e: Exception, localeHandler: LocaleHandler) {
        Bukkit.getOnlinePlayers()
            .filter { perms.has(it, "ticketmanager.notify.warning") }
            .map { it as Audience to localeHandler.getOrDefault(it.locale().toString()) }
            .pForEach { (p, locale) ->
                p.sendMessage(
                    buildComponent {
                        // Builds header
                        listOf(
                            locale.stacktraceLine1,
                            locale.stacktraceLine2.replace("%exception%", e.javaClass.simpleName),
                            locale.stacktraceLine3.replace("%message%", e.message ?: "?"),
                            locale.stacktraceLine4,
                        )
                            .forEach { text { formattedContent(it) } }

                        // Adds stacktrace entries
                        e.stackTrace
                            .filter { it.className.startsWith("com.github.hoshikurama.ticketmanager") }
                            .map {
                                locale.stacktraceEntry
                                    .replace("%method%", it.methodName)
                                    .replace("%file%", it.fileName ?: "?")
                                    .replace("%line%", "${it.lineNumber}")
                            }
                            .forEach { text { formattedContent(it) } }
                    }
                )
            }
    }

    override fun pushInfoToConsole(message: String) {
        Bukkit.getLogger().log(Level.INFO, message)
    }

    override fun pushWarningToConsole(message: String) {
        Bukkit.getLogger().log(Level.WARNING, message)
    }

    override fun pushErrorToConsole(message: String) {
        Bukkit.getLogger().log(Level.SEVERE, message)
    }

    override fun getPermissionGroups(): List<String> {
        return perms.groups.toList()
    }

    override fun getOfflinePlayerNames(): List<String> {
        return Bukkit.getOfflinePlayers().mapNotNull(OfflinePlayer::getName)
    }

    override fun getOnlineSeenPlayerNames(sender: Sender): List<String> {
        return if (sender is PaperPlayer) {
            val player = sender.pPlayer
            Bukkit.getOnlinePlayers()
                .filter(player::canSee)
                .map { it.name }
        }
        else Bukkit.getOnlinePlayers().map { it.name }
    }

    override fun getWorldNames(): List<String> {
        return Bukkit.getWorlds().map { it.name }
    }
}