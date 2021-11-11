package com.github.hoshikurama.ticketmanager.platform

import com.github.hoshikurama.ticketmanager.data.GlobalPluginState
import com.github.hoshikurama.ticketmanager.data.InstancePluginState
import com.github.hoshikurama.ticketmanager.misc.toColouredAdventure
import com.github.hoshikurama.ticketmanager.pluginVersion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

abstract class PlayerJoinEvent(
    private val globalPluginState: GlobalPluginState,
    protected val instanceState: InstancePluginState,
) {

    suspend fun whenPlayerJoins(player: Player) = coroutineScope {
        if (globalPluginState.pluginLocked.get()) return@coroutineScope

        // Plugin Update Checking
        if (instanceState.pluginUpdateChecker.canCheck) {
            launch {
                val newerVersion = instanceState.pluginUpdateChecker.latestVersionOrNull ?: return@launch // Only present if newer version is available and plugin can check
                if (!player.has("ticketmanager.notify.pluginUpdate")) return@launch

                player.locale.notifyPluginUpdate
                    .replace("%current%", pluginVersion)
                    .replace("%latest%", newerVersion)
                    .run(::toColouredAdventure)
                    .run(player::sendMessage)
            }
        }

        // Unread Updates
        launch {
            if (!player.has("ticketmanager.notify.unreadUpdates.onJoin")) return@launch
            val ids = instanceState.database.getTicketIDsWithUpdatesFor(player.uniqueID)

            if (ids.isEmpty()) return@launch
            val template = if (ids.size == 1) player.locale.notifyUnreadUpdateSingle else player.locale.notifyUnreadUpdateMulti
            val tickets = ids.joinToString(", ")

            template.replace("%num%", tickets)
                .run(player::sendMessage)
        }

        // View Open-Count and Assigned-Count Tickets
        launch {
            if (!player.has("ticketmanager.notify.openTickets.onJoin")) return@launch
            val open = instanceState.database.countOpenTickets()
            val assigned = instanceState.database.countOpenTicketsAssignedTo(player.name, player.permissionGroups)

            if (open != 0)
                player.locale.notifyOpenAssigned
                    .replaceFirst("%open%", open.toString())
                    .replaceFirst("%assigned%", assigned.toString())
                    .run(player::sendMessage)
        }
    }
}