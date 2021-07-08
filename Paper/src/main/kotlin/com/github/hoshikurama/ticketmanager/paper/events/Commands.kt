package com.github.hoshikurama.ticketmanager.paper.events

import com.github.hoshikurama.componentDSL.*
import com.github.hoshikurama.ticketmanager.common.*
import com.github.hoshikurama.ticketmanager.common.databases.Database
import com.github.hoshikurama.ticketmanager.common.ticket.*
import com.github.hoshikurama.ticketmanager.paper.*
import com.github.hoshikurama.ticketmanager.paper.has
import com.github.hoshikurama.ticketmanager.paper.mainPlugin
import com.github.hoshikurama.ticketmanager.paper.pluginState
import com.github.hoshikurama.ticketmanager.paper.toTMLocale
import com.github.shynixn.mccoroutine.SuspendingCommandExecutor
import com.github.shynixn.mccoroutine.asyncDispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import net.kyori.adventure.extra.kotlin.text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.lang.Exception
import java.util.*

class Commands : SuspendingCommandExecutor {

    override suspend fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean = withContext(mainPlugin.asyncDispatcher) {

        val senderLocale = sender.toTMLocale()
        val argList = args.toList()

        if (argList.isEmpty()) {
            sender.sendMessage(text { formattedContent(senderLocale.warningsInvalidCommand) })
            return@withContext false
        }

        if (mainPlugin.pluginLocked.check()) {
            sender.sendMessage(text { formattedContent(senderLocale.warningsLocked)})
            return@withContext false
        }

        // Grabs BasicTicket. Only null if ID required but doesn't exist. Filters non-valid tickets
        val pseudoTicket = getBasicTicketHandlerAsync(argList, senderLocale).await()
        if (pseudoTicket == null) {
            sender.sendMessage(text { formattedContent(senderLocale.warningsInvalidID) })
            return@withContext false
        }

        // Async Calculations
        val hasValidPermission = async { hasValidPermission(sender, pseudoTicket, argList, senderLocale) }
        val isValidCommand = async { isValidCommand(sender, pseudoTicket, argList, senderLocale) }
        val notUnderCooldown = async { notUnderCooldown(sender, senderLocale, argList) }
        // Shortened Commands
        val executeCommand = suspend { executeCommand(sender, argList, senderLocale, pseudoTicket) }

        try {
            if (notUnderCooldown.await() && isValidCommand.await() && hasValidPermission.await()) {
                executeCommand()?.let { pushNotifications(sender, it, senderLocale, pseudoTicket) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            //postModifiedStacktrace(e)
            //sender.sendMessage(senderLocale.warningsUnexpectedError)
        }

        return@withContext true
    }

    private suspend fun getBasicTicketHandlerAsync(
        args: List<String>,
        senderLocale: TMLocale
    ): Deferred<BasicTicketHandler?> {

        suspend fun buildFromIDAsync(id: Int) = BasicTicketHandler.buildHandlerAsync(pluginState.database, id)

        return coroutineScope {
            when (args[0]) {
                senderLocale.commandWordAssign,
                senderLocale.commandWordSilentAssign,
                senderLocale.commandWordClaim,
                senderLocale.commandWordSilentClaim,
                senderLocale.commandWordClose,
                senderLocale.commandWordSilentClose,
                senderLocale.commandWordComment,
                senderLocale.commandWordSilentComment,
                senderLocale.commandWordReopen,
                senderLocale.commandWordSilentReopen,
                senderLocale.commandWordSetPriority,
                senderLocale.commandWordSilentSetPriority,
                senderLocale.commandWordTeleport,
                senderLocale.commandWordUnassign,
                senderLocale.commandWordSilentUnassign,
                senderLocale.commandWordView,
                senderLocale.commandWordDeepView ->
                    args.getOrNull(1)
                        ?.toIntOrNull()
                        ?.let { buildFromIDAsync(it) } ?: async { null }
                else -> async { BasicTicket(creatorUUID = null, location = null).run { BasicTicketHandler(pluginState.database, this) } } // Occurs when command does not need valid handler
            }
        }
    }

    private fun hasValidPermission(
        sender: CommandSender,
        basicTicket: BasicTicket,
        args: List<String>,
        senderLocale: TMLocale
    ): Boolean {
        try {
           if (sender !is Player) return true

           fun has(perm: String) = sender.has(perm)
           fun hasSilent() = has("ticketmanager.commandArg.silence")
           fun hasDuality(basePerm: String): Boolean {
               val senderUUID = sender.toUUIDOrNull()
               val ownsTicket = basicTicket.uuidMatches(senderUUID)
               return has("$basePerm.all") || (sender.has("$basePerm.own") && ownsTicket)
           }

           return senderLocale.run {
               when (args[0]) {
                   commandWordAssign, commandWordClaim, commandWordUnassign ->
                       has("ticketmanager.command.assign")
                   commandWordSilentAssign, commandWordSilentClaim,commandWordSilentUnassign ->
                       has("ticketmanager.command.assign") && hasSilent()
                   commandWordClose -> hasDuality("ticketmanager.command.close")
                   commandWordSilentClose -> hasDuality("ticketmanager.command.close") && hasSilent()
                   commandWordCloseAll -> has("ticketmanager.command.closeAll")
                   commandWordSilentCloseAll -> has("ticketmanager.command.closeAll") && hasSilent()
                   commandWordComment -> hasDuality("ticketmanager.command.comment")
                   commandWordSilentComment -> hasDuality("ticketmanager.command.comment") && hasSilent()
                   commandWordCreate -> has("ticketmanager.command.create")
                   commandWordHelp -> has("ticketmanager.command.help")
                   commandWordReload -> has("ticketmanager.command.reload")
                   commandWordList -> has("ticketmanager.command.list")
                   commandWordListAssigned -> has("ticketmanager.command.list")
                   commandWordReopen -> has("ticketmanager.command.reopen")
                   commandWordSilentReopen -> has("ticketmanager.command.reopen") && hasSilent()
                   commandWordSearch -> has("ticketmanager.command.search")
                   commandWordSetPriority -> has("ticketmanager.command.setPriority")
                   commandWordSilentSetPriority -> has("ticketmanager.command.setPriority") && hasSilent()
                   commandWordTeleport -> has("ticketmanager.command.teleport")
                   commandWordView -> hasDuality("ticketmanager.command.view")
                   commandWordDeepView -> hasDuality("ticketmanager.command.viewdeep")
                   commandWordConvertDB -> has("ticketmanager.command.convertDatabase")
                   commandWordHistory ->
                       sender.has("ticketmanager.command.history.all") ||
                               sender.has("ticketmanager.command.history.own").let { hasPerm ->
                                   if (args.size >= 2) hasPerm && args[1] == sender.name
                                   else hasPerm
                               }
                   else -> true
               }
           }
               .also { if (!it) sender.sendMessage(text { formattedContent(senderLocale.warningsNoPermission) }) }
       } catch (e: Exception) {
            sender.sendMessage(text { formattedContent(senderLocale.warningsNoPermission) })
            return false
       }
    }

    private fun isValidCommand(
        sender: CommandSender,
        basicTicket: BasicTicket,
        args: List<String>,
        senderLocale: TMLocale
    ): Boolean {
        fun sendMessage(formattedString: String) = sender.sendMessage(text { formattedContent(formattedString) })
        fun invalidCommand() = sendMessage(senderLocale.warningsInvalidCommand)
        fun notANumber() = sendMessage(senderLocale.warningsInvalidNumber)
        fun outOfBounds() = sendMessage(senderLocale.warningsPriorityOutOfBounds)
        fun ticketClosed() = sendMessage(senderLocale.warningsTicketAlreadyClosed)
        fun ticketOpen() = sendMessage(senderLocale.warningsTicketAlreadyOpen)

        return senderLocale.run {
            when (args[0]) {
                commandWordAssign, commandWordSilentAssign ->
                    check(::invalidCommand) { args.size >= 3 }
                        .thenCheck(::ticketClosed) { basicTicket.status != BasicTicket.Status.CLOSED }

                commandWordClaim, commandWordSilentClaim ->
                    check(::invalidCommand) { args.size == 2 }
                        .thenCheck(::ticketClosed) { basicTicket.status != BasicTicket.Status.CLOSED }

                commandWordClose, commandWordSilentClose ->
                    check(::invalidCommand) { args.size >= 2 }
                        .thenCheck(::ticketClosed) { basicTicket.status != BasicTicket.Status.CLOSED }

                commandWordComment, commandWordSilentComment ->
                    check(::invalidCommand) { args.size >= 3 }
                        .thenCheck(::ticketClosed) { basicTicket.status != BasicTicket.Status.CLOSED }

                commandWordCloseAll, commandWordSilentCloseAll ->
                    check(::invalidCommand) { args.size == 3 }
                        .thenCheck(::notANumber) { args[1].toIntOrNull() != null }
                        .thenCheck(::notANumber) { args[2].toIntOrNull() != null }

                commandWordReopen, commandWordSilentReopen ->
                    check(::invalidCommand) { args.size == 2 }
                        .thenCheck(::ticketOpen) { basicTicket.status != BasicTicket.Status.OPEN }

                commandWordSetPriority, commandWordSilentSetPriority ->
                    check(::invalidCommand) { args.size == 3 }
                        .thenCheck(::outOfBounds) { args[2].toByteOrNull() != null }
                        .thenCheck(::ticketClosed) { basicTicket.status != BasicTicket.Status.CLOSED }

                commandWordUnassign, commandWordSilentUnassign ->
                    check(::invalidCommand) { args.size == 2 }
                        .thenCheck(::ticketClosed) { basicTicket.status != BasicTicket.Status.CLOSED }

                commandWordView -> check(::invalidCommand) { args.size == 2 }

                commandWordDeepView -> check(::invalidCommand) { args.size == 2 }

                commandWordTeleport -> check(::invalidCommand) { args.size == 2 }

                commandWordCreate -> check(::invalidCommand) { args.size >= 2 }

                commandWordHistory ->
                    check(::invalidCommand) { args.isNotEmpty() }
                        .thenCheck(::notANumber) { if (args.size >= 3) args[2].toIntOrNull() != null else true}

                commandWordList ->
                    check(::notANumber) { if (args.size == 2) args[1].toIntOrNull() != null else true }

                commandWordListAssigned ->
                    check(::notANumber) { if (args.size == 2) args[1].toIntOrNull() != null else true }

                commandWordSearch -> check(::invalidCommand) { args.size >= 2}

                commandWordReload -> true
                commandWordVersion -> true
                commandWordHelp -> true

                commandWordConvertDB ->
                    check(::invalidCommand) { args.size == 2 }
                        .thenCheck( { sendMessage(senderLocale.warningsInvalidDBType) },
                            {
                                try { Database.Type.valueOf(args[1]); true }
                                catch (e: Exception) { false }
                            }
                        )
                        .thenCheck( { sendMessage(senderLocale.warningsConvertToSameDBType) } )
                        { pluginState.database.type != Database.Type.valueOf(args[1]) }

                else -> false.also { invalidCommand() }
            }
        }
    }

    private suspend fun notUnderCooldown(
        sender: CommandSender,
        senderLocale: TMLocale,
        args: List<String>
    ): Boolean {
        val underCooldown = when (args[0]) {
            senderLocale.commandWordCreate,
            senderLocale.commandWordComment,
            senderLocale.commandWordSilentComment ->
                pluginState.cooldowns.checkAndSetAsync(sender.toUUIDOrNull())
            else -> false
        }

        if (underCooldown)
            sender.sendMessage(text { formattedContent(senderLocale.warningsUnderCooldown) })

        return !underCooldown
    }

    private suspend fun executeCommand(
        sender: CommandSender,
        args: List<String>,
        senderLocale: TMLocale,
        ticketHandler: BasicTicketHandler
    ): NotifyParams? {
        return senderLocale.run {
            when (args[0]) {
                commandWordAssign -> assign(sender, args, false, senderLocale, ticketHandler)
                commandWordAssign -> assign(sender, args, false, senderLocale, ticketHandler)
                commandWordSilentAssign -> assign(sender, args, true, senderLocale, ticketHandler)
                commandWordClaim -> claim(sender, args, false, senderLocale, ticketHandler)
                commandWordSilentClaim -> claim(sender, args, true, senderLocale, ticketHandler)
                commandWordClose -> close(sender, args, false, ticketHandler)
                commandWordSilentClose -> close(sender, args, true, ticketHandler)
                commandWordCloseAll -> closeAll(sender, args, false, ticketHandler)
                commandWordSilentCloseAll -> closeAll(sender, args, true, ticketHandler)
                commandWordComment -> comment(sender, args, false, ticketHandler)
                commandWordSilentComment -> comment(sender, args, true, ticketHandler)
                commandWordCreate -> create(sender, args)
                commandWordHelp -> help(sender, senderLocale).let { null }
                commandWordHistory -> history(sender, args, senderLocale).let { null }
                commandWordList -> list(sender, args, senderLocale).let { null }
                commandWordListAssigned -> listAssigned(sender, args, senderLocale).let { null }
                commandWordReload -> reload(sender, senderLocale).let { null }
                commandWordReopen -> reopen(sender,args, false, ticketHandler)
                commandWordSilentReopen -> reopen(sender,args, true, ticketHandler)
                commandWordSearch -> search(sender, args, senderLocale).let { null }
                commandWordSetPriority -> setPriority(sender, args, false, ticketHandler)
                commandWordSilentSetPriority -> setPriority(sender, args, true, ticketHandler)
                commandWordTeleport -> teleport(sender, ticketHandler).let { null }
                commandWordUnassign -> unAssign(sender, args, false, senderLocale, ticketHandler)
                commandWordSilentUnassign -> unAssign(sender, args, true, senderLocale, ticketHandler)
                commandWordVersion -> version(sender, senderLocale).let { null }
                commandWordView -> view(sender, senderLocale, ticketHandler).let { null }
                commandWordDeepView -> viewDeep(sender, senderLocale, ticketHandler).let { null }
                commandWordConvertDB -> convertDatabase(args).let { null }
                else -> null
            }
        }
    }

    private fun pushNotifications(
        sender: CommandSender,
        params: NotifyParams,
        locale: TMLocale,
        basicTicket: BasicTicket
    ) {
        params.run {
            if (sendSenderMSG)
                sender.sendMessage(senderLambda!!.invoke(locale))

            if (sendCreatorMSG)
                basicTicket.creatorUUID
                    ?.run(Bukkit::getPlayer)
                    ?.let { creatorLambda!!(it.toTMLocale()) }
                    ?.run { creator!!.sendMessage(this) }

            if (sendMassNotifyMSG)
                pushMassNotify(massNotifyPerm, massNotifyLambda!!)
        }
    }

    private class NotifyParams(
        silent: Boolean,
        basicTicket: BasicTicket,
        sender: CommandSender,
        creatorAlertPerm: String,
        val massNotifyPerm: String,
        val senderLambda: ((TMLocale) -> Component)?,
        val creatorLambda: ((TMLocale) -> Component)?,
        val massNotifyLambda: ((TMLocale) -> Component)?,
    ) {
        val creator: Player? = basicTicket.creatorUUID?.let(Bukkit::getPlayer)
        val sendSenderMSG: Boolean = (!sender.has(massNotifyPerm) || silent)
                && senderLambda != null
        val sendCreatorMSG: Boolean = sender.nonCreatorMadeChange(basicTicket.creatorUUID)
                && !silent && (creator?.isOnline ?: false)
                && (creator?.has(creatorAlertPerm) ?: false)
                && (creator?.has(massNotifyPerm)?.run { !this } ?: false)
                && creatorLambda != null
        val sendMassNotifyMSG: Boolean = !silent
                && massNotifyLambda != null
    }

    /*-------------------------*/
    /*         Commands        */
    /*-------------------------*/

    private suspend fun allAssignVariations(
        sender: CommandSender,
        silent: Boolean,
        senderLocale: TMLocale,
        assignmentID: String,
        dbAssignment: String?,
        ticketHandler: BasicTicketHandler,
    ): NotifyParams = coroutineScope {
        val shownAssignment = dbAssignment ?: senderLocale.miscNobody

        launch { ticketHandler.setAssignedTo(dbAssignment) }
        launch { pluginState.database.addAction(
            ticketID = ticketHandler.id,
            action = FullTicket.Action(FullTicket.Action.Type.ASSIGN, sender.toUUIDOrNull(), dbAssignment)
        )}

        NotifyParams(
            silent = silent,
            sender = sender,
            basicTicket = ticketHandler,
            senderLambda = {
                val content = it.notifyTicketAssignSuccess
                    .replace("%id%", assignmentID)
                    .replace("%assign%", shownAssignment)
                text { formattedContent(content) }
            },
            massNotifyLambda = {
                val content = it.notifyTicketAssignEvent
                    .replace("%user%", sender.name)
                    .replace("%id%", assignmentID)
                    .replace("%assign%", shownAssignment)
                text { formattedContent(content) }
            },
            creatorLambda = null,
            creatorAlertPerm = "ticketmanager.notify.change.assign",
            massNotifyPerm = "ticketmanager.notify.massNotify.assign"
        )
    }

    // /ticket assign <ID> <Assignment>
    private suspend fun assign(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        senderLocale: TMLocale,
        ticketHandler: BasicTicketHandler,
    ): NotifyParams {
        val sqlAssignment = args.subList(2, args.size).joinToString(" ")
        return allAssignVariations(sender, silent, senderLocale, args[1], sqlAssignment, ticketHandler)
    }

    // /ticket claim <ID>
    private suspend fun claim(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        senderLocale: TMLocale,
        ticketHandler: BasicTicketHandler,
    ): NotifyParams {
        return allAssignVariations(sender, silent, senderLocale, args[1], sender.name, ticketHandler)
    }

    // /ticket close <ID> [Comment...]
    private suspend fun close(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        ticketHandler: BasicTicketHandler
    ): NotifyParams = coroutineScope {
        val newCreatorStatusUpdate = sender.nonCreatorMadeChange(ticketHandler.creatorUUID) && pluginState.allowUnreadTicketUpdates
        if (newCreatorStatusUpdate != ticketHandler.creatorStatusUpdate) {
            launch { ticketHandler.setCreatorStatusUpdate(newCreatorStatusUpdate) }
        }

        return@coroutineScope if (args.size >= 3)
            closeWithComment(sender, args, silent, ticketHandler)
        else closeWithoutComment(sender, args, silent, ticketHandler)
    }

    private suspend fun closeWithComment(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        ticketHandler: BasicTicketHandler,
    ): NotifyParams = coroutineScope {
        val message = args.subList(2, args.size)
            .joinToString(" ")
            .run(ChatColor::stripColor)!!

        launch {
            pluginState.database.run {
                addAction(
                    ticketID = ticketHandler.id,
                    action = FullTicket.Action(FullTicket.Action.Type.COMMENT, sender.toUUIDOrNull(), message)
                )
                addAction(
                    ticketID = ticketHandler.id,
                    action = FullTicket.Action(FullTicket.Action.Type.CLOSE, sender.toUUIDOrNull())
                )
                ticketHandler.setTicketStatus(BasicTicket.Status.CLOSED)
            }
        }

        NotifyParams(
            silent = silent,
            sender = sender,
            basicTicket = ticketHandler,
            senderLambda = {
                val content = it.notifyTicketCloseWCommentSuccess
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            massNotifyLambda = {
                val content = it.notifyTicketCloseWCommentEvent
                    .replace("%user%", sender.name)
                    .replace("%id%", args[1])
                    .replace("%message%", message)
                text { formattedContent(content) }
            },
            creatorLambda = {
                val content = it.notifyTicketModificationEvent
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            massNotifyPerm = "ticketmanager.notify.massNotify.close",
            creatorAlertPerm = "ticketmanager.notify.change.close"
        )
    }

    private suspend fun closeWithoutComment(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        ticketHandler: BasicTicketHandler
    ): NotifyParams = coroutineScope {
        launch {
            pluginState.database.addAction(
                ticketID = ticketHandler.id,
                action = FullTicket.Action(FullTicket.Action.Type.CLOSE, sender.toUUIDOrNull())
            )
            ticketHandler.setTicketStatus(BasicTicket.Status.CLOSED)
        }

        NotifyParams(
            silent = silent,
            sender = sender,
            basicTicket = ticketHandler,
            creatorLambda = {
                val content = it.notifyTicketModificationEvent
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            massNotifyLambda = {
                val content = it.notifyTicketCloseEvent
                    .replace("%user%", sender.name)
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            senderLambda = {
                val content = it.notifyTicketCloseSuccess
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            massNotifyPerm = "ticketmanager.notify.massNotify.close",
            creatorAlertPerm = "ticketmanager.notify.change.close"
        )
    }

    // /ticket closeall <Lower ID> <Upper ID>
    private suspend fun closeAll(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        basicTicket: BasicTicket
    ): NotifyParams = coroutineScope {
        val lowerBound = args[1].toInt()
        val upperBound = args[2].toInt()

        launch { pluginState.database.massCloseTickets(lowerBound, upperBound, sender.toUUIDOrNull()) }

        NotifyParams(
            silent = silent,
            sender = sender,
            basicTicket = basicTicket,
            creatorLambda = null,
            senderLambda = {
                val content =it.notifyTicketMassCloseSuccess
                    .replace("%low%", args[1])
                    .replace("%high%", args[2])
                text { formattedContent(content) }
            },
            massNotifyLambda = {
                val content = it.notifyTicketMassCloseEvent
                    .replace("%user%", sender.name)
                    .replace("%low%", args[1])
                    .replace("%high%", args[2])
                text { formattedContent(content) }
            },
            massNotifyPerm = "ticketmanager.notify.massNotify.massClose",
            creatorAlertPerm = "ticketmanager.notify.change.massClose"
        )
    }

    // /ticket comment <ID> <Comment…>
    private suspend fun comment(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        ticketHandler: BasicTicketHandler,
    ): NotifyParams = coroutineScope {
        val message = args.subList(2, args.size)
            .joinToString(" ")
            .run(ChatColor::stripColor)!!

        val newCreatorStatusUpdate = sender.nonCreatorMadeChange(ticketHandler.creatorUUID) && pluginState.allowUnreadTicketUpdates
        if (newCreatorStatusUpdate != ticketHandler.creatorStatusUpdate) {
            launch { ticketHandler.setCreatorStatusUpdate(newCreatorStatusUpdate) }
        }

        launch {
            pluginState.database.addAction(
                ticketID = ticketHandler.id,
                action = FullTicket.Action(FullTicket.Action.Type.COMMENT, sender.toUUIDOrNull(), message)
            )
        }

        NotifyParams(
            silent = silent,
            sender = sender,
            basicTicket = ticketHandler,
            creatorLambda = {
                val content = it.notifyTicketModificationEvent
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            senderLambda = {
                val content = it.notifyTicketCommentSuccess
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            massNotifyLambda = {
                val content = it.notifyTicketCommentEvent
                    .replace("%user%", sender.name)
                    .replace("%id%", args[1])
                    .replace("%message%", message)
                text { formattedContent(content) }
            },
            massNotifyPerm = "ticketmanager.notify.massNotify.comment",
            creatorAlertPerm = "ticketmanager.notify.change.comment"
        )
    }

    // /ticket convertdatabase <Target Database>
    private suspend fun convertDatabase(args: List<String>) {
        //TODO
        /*
        val type = args[1].run(Database.Type::valueOf)
        pluginState.database.migrateDatabase(type)

         */
    }

    // /ticket create <Message…>
    private suspend fun create(
        sender: CommandSender,
        args: List<String>,
    ): NotifyParams = coroutineScope {
        val message = args.subList(1, args.size)
            .joinToString(" ")
            .run(ChatColor::stripColor)!!

        val ticket = BasicTicket(creatorUUID = sender.toUUIDOrNull(), location = sender.toTicketLocationOrNull())

        val deferredID = pluginState.database.addNewTicketAsync(ticket, message)
        mainPlugin.ticketCountMetrics.run { set(check() + 1) }
        val id = deferredID.await().toString()

        NotifyParams(
            silent = false,
            sender = sender,
            basicTicket = ticket,
            creatorLambda = null,
            senderLambda = {
                val content = it.notifyTicketCreationSuccess
                    .replace("%id%", id)
                text { formattedContent(content) }
            },
            massNotifyLambda = {
                val content = it.notifyTicketCreationEvent
                    .replace("%user%", sender.name)
                    .replace("%id%", id)
                    .replace("%message%", message)
                text { formattedContent(content) }
            },
            creatorAlertPerm = "ticketmanager.NO NODE",
            massNotifyPerm = "ticketmanager.notify.massNotify.create",
        )
    }

    // /ticket help
    private fun help(
        sender: CommandSender,
        locale: TMLocale,
    ) {
        val hasSilentPerm = sender.has("ticketmanager.commandArg.silence")
        val cc = pluginState.localeHandler.mainColourCode

        val component = buildComponent {
            text { formattedContent(locale.helpHeader) }
            text { formattedContent(locale.helpLine1) }

            if (hasSilentPerm) {
                text { formattedContent(locale.helpLine2) }
                text { formattedContent(locale.helpLine3) }
            }
            text { formattedContent(locale.helpSep) }

            locale.run {
                listOf( // Triple(silence-able, format, permissions)
                    Triple(true, "$commandWordAssign &f<$parameterID> <$parameterAssignment...>", listOf("ticketmanager.command.assign")),
                    Triple(true, "$commandWordClaim &f<$parameterID>", listOf("ticketmanager.command.claim")),
                    Triple(true, "$commandWordClose &f<$parameterID> &7[$parameterComment...]", listOf("ticketmanager.command.close.all", "ticketmanager.command.close.own")),
                    Triple(true, "$commandWordCloseAll &f<$parameterLowerID> <$parameterUpperID>", listOf("ticketmanager.command.closeAll")),
                    Triple(true, "$commandWordComment &f<$parameterID> <$parameterComment...>", listOf("ticketmanager.command.comment.all", "ticketmanager.command.comment.own")),
                    Triple(false, "$commandWordConvertDB &f<$parameterTargetDB>", listOf("ticketmanager.command.convertDatabase")),
                    Triple(false, "$commandWordCreate &f<$parameterComment...>", listOf("ticketmanager.command.create")),
                    Triple(false, commandWordHelp, listOf("ticketmanager.command.help")),
                    Triple(false, "$commandWordHistory &7[$parameterUser] [$parameterPage]", listOf("ticketmanager.command.history.all", "ticketmanager.command.history.own")),
                    Triple(false, "$commandWordList &7[$parameterPage]", listOf("ticketmanager.command.list")),
                    Triple(false, "$commandWordListAssigned &7[$parameterPage]", listOf("ticketmanager.command.list")),
                    Triple(false, commandWordReload, listOf("ticketmanager.command.reload")),
                    Triple(true, "$commandWordReopen &f<$parameterID>", listOf("ticketmanager.command.reopen")),
                    Triple(false, "$commandWordSearch &f<$parameterConstraints...>", listOf("ticketmanager.command.search")),
                    Triple(true, "$commandWordSetPriority &f<$parameterID> <$parameterLevel>", listOf("ticketmanager.command.setPriority")),
                    Triple(false, "$commandWordTeleport &f<$parameterID>", listOf("ticketmanager.command.teleport")),
                    Triple(true, "$commandWordUnassign &f<$parameterID>", listOf("ticketmanager.command.assign")),
                    Triple(false, "$commandWordView &f<$parameterID>", listOf("ticketmanager.command.view.all", "ticketmanager.command.view.own")),
                    Triple(false, "$commandWordDeepView &f<$parameterID>", listOf("ticketmanager.command.viewdeep.all", "ticketmanager.command.viewdeep.own"))
                )
            }
                .filter { it.third.any(sender::has) }
                .run { this + Triple(false, locale.commandWordVersion, "NA") }
                .map {
                    val commandString = "$cc/${locale.commandBase} ${it.second}"
                    if (hasSilentPerm)
                        if (it.first) "\n&a[✓] $commandString"
                        else "\n&c[✕] $commandString"
                    else "\n$commandString"
                }
                .forEach { text { formattedContent(it) } }
        }

        sender.sendMessage(component)
    }

    // /ticket history [User] [Page]
    private suspend fun history(
        sender: CommandSender,
        args: List<String>,
        locale: TMLocale,
    ) {
        coroutineScope {
            val targetName =
                if (args.size >= 2) args[1].takeIf { it != locale.consoleName } else sender.name.takeIf { sender is Player }
            val requestedPage = if (args.size >= 3) args[2].toInt() else 1

            // Leaves console as null. Otherwise attempts UUID grab or [PLAYERNOTFOUND]
            fun String.attemptToUUIDString(): String? =
                if (equals(locale.consoleName)) null
                else Bukkit.getOfflinePlayers().asSequence()
                    .firstOrNull { equals(it.name) }
                    ?.run { uniqueId.toString() }
                    ?: "[PLAYERNOTFOUND]"

            val searchedUser = targetName?.attemptToUUIDString()

            val resultSize: Int
            val resultsChunked = pluginState.database.searchDatabase { it.creatorUUID.toString() == searchedUser }
                .toList()
                .sortedByDescending(BasicTicket::id)
                .also { resultSize = it.size }
                .chunked(6)

            val sentComponent = buildComponent {
                text {
                    formattedContent(
                        locale.historyHeader
                            .replace("%name%", targetName ?: locale.consoleName)
                            .replace("%count%", "$resultSize")
                    )
                }

                val actualPage = if (requestedPage >= 1 && requestedPage < resultsChunked.size) requestedPage else 1

                if (resultsChunked.isNotEmpty()) {
                    resultsChunked.getOrElse(requestedPage - 1) { resultsChunked[1] }.forEach { t ->
                        text {
                            formattedContent(
                                locale.historyEntry
                                    .let { "\n$it" }
                                    .replace("%id%", "${t.id}")
                                    .replace("%SCC%", t.status.colourCode)
                                    .replace("%status%", t.status.toLocaledWord(locale))
                                    .replace("%comment%", t.actions[0].message!!)
                                    .let { if (it.length > 80) "${it.substring(0, 81)}..." else it }
                            )
                            onHover { showText(Component.text(locale.clickViewTicket)) }
                            onClick {
                                action = ClickEvent.Action.RUN_COMMAND
                                value = locale.run { "/$commandBase $commandWordView ${t.id}" }
                            }
                        }
                    }

                    if (resultsChunked.size > 1) {
                        append(buildPageComponent(actualPage, resultsChunked.size, locale) {
                            "/${it.commandBase} ${it.commandWordHistory} ${targetName ?: it.consoleName} "
                        })
                    }
                }
            }

            sender.sendMessage(sentComponent)
        }
    }

    private fun buildPageComponent(
        curPage: Int,
        pageCount: Int,
        locale: TMLocale,
        baseCommand: (TMLocale) -> String,
    ): Component {

        fun Component.addForward() {
            color(NamedTextColor.WHITE)
            clickEvent(ClickEvent.runCommand(baseCommand(locale) + "${curPage + 1}"))
            hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.text(locale.clickNextPage)))
        }

        fun Component.addBack() {
            color(NamedTextColor.WHITE)
            clickEvent(ClickEvent.runCommand(baseCommand(locale) + "${curPage - 1}"))
            hoverEvent(HoverEvent.hoverEvent(HoverEvent.Action.SHOW_TEXT, Component.text(locale.clickBackPage)))
        }

        val back = Component.text("[${locale.pageBack}]")
        val next = Component.text("[${locale.pageNext}]")
        val separator = text {
            content("...............")
            color(NamedTextColor.DARK_GRAY)
        }
        val cc = pluginState.localeHandler.mainColourCode
        val ofSection = text { formattedContent("$cc($curPage${locale.pageOf}$pageCount)") }

        when (curPage) {
            1 -> {
                back.color(NamedTextColor.DARK_GRAY)
                next.addForward()
            }
            pageCount -> {
                back.addBack()
                next.color(NamedTextColor.DARK_GRAY)
            }
            else -> {
                back.addBack()
                back.addForward()
            }
        }

        return Component.text("\n")
            .append(back)
            .append(separator)
            .append(ofSection)
            .append(separator)
            .append(next)
    }

    private fun createListEntry(
        ticket: FullTicket,
        locale: TMLocale
    ): Component {
        val creator = ticket.creatorUUID.toName(locale)
        val fixedAssign = ticket.assignedTo ?: ""

        // Shortens comment preview to fit on one line
        val fixedComment = ticket.run {
            if (12 + id.toString().length + creator.length + fixedAssign.length + actions[0].message!!.length > 58)
                actions[0].message!!.substring(
                    0,
                    43 - id.toString().length - fixedAssign.length - creator.length
                ) + "..."
            else actions[0].message!!
        }

        return text {
            formattedContent(
                "\n${locale.listFormatEntry}"
                    .replace("%priorityCC%", ticket.priority.colourCode)
                    .replace("%ID%", "${ticket.id}")
                    .replace("%creator%", creator)
                    .replace("%assign%", fixedAssign)
                    .replace("%comment%", fixedComment)
            )
            onHover { showText(Component.text(locale.clickViewTicket)) }
            onClick {
                action = ClickEvent.Action.RUN_COMMAND
                value = locale.run { "/$commandBase $commandWordView ${ticket.id}" }
            }
        }
    }

    private suspend fun createGeneralList(
        args: List<String>,
        locale: TMLocale,
        headerFormat: String,
        getTickets: suspend (Database) -> List<FullTicket>,
        baseCommand: (TMLocale) -> String
    ): Component {
        val chunkedTickets = getTickets(pluginState.database).chunked(8)
        val page = if (args.size == 2 && args[1].toInt() in 1..chunkedTickets.size) args[1].toInt() else 1

        return buildComponent {
            text { formattedContent(headerFormat) }

            if (chunkedTickets.isNotEmpty()) {
                chunkedTickets[page - 1].forEach { append(createListEntry(it, locale)) }

                if (chunkedTickets.size > 1) {
                    append(buildPageComponent(page, chunkedTickets.size, locale, baseCommand))
                }
            }
        }
    }

    // /ticket list [Page]
    private suspend fun list(
        sender: CommandSender,
        args: List<String>,
        locale: TMLocale,
    ) {
        sender.sendMessage(
            createGeneralList(args, locale, locale.listFormatHeader,
                getTickets = { db -> db.getFullOpenAsFlow().toList() },
                baseCommand = locale.run{ { "/$commandBase $commandWordList " } }
            )
        )
    }

    // /ticket listassigned [Page]
    private suspend fun listAssigned(
        sender: CommandSender,
        args: List<String>,
        locale: TMLocale,
    ) {
        val groups = if (sender is Player)
            mainPlugin.perms.getPlayerGroups(sender).map { "::$it" }
        else listOf()

        sender.sendMessage(
            createGeneralList(args, locale, locale.listFormatAssignedHeader,
                getTickets = { db -> db.getFullOpenAssignedAsFlow(sender.name, groups).toList() },
                baseCommand = locale.run { { "/$commandBase $commandWordListAssigned " } }
            )
        )
    }

    // /ticket reload
    private suspend fun reload(
        sender: CommandSender,
        locale: TMLocale,
    ) {
        coroutineScope {
            mainPlugin.pluginLocked.set(true)
            pushMassNotify("ticketmanager.notify.info") {
                text { formattedContent(it.informationReloadInitiated.replace("%user%", sender.name)) }
            }

            // Eventually try making it wait for other tasks to finish. Will require keeping track of jobs
            //pushMassNotify("ticketmanager.notify.info", { it.informationReloadTasksDone } )
            pluginState.database.closeDatabase()
            mainPlugin.loadPlugin()

            pushMassNotify("ticketmanager.notify.info") {
                text { formattedContent(it.informationReloadSuccess) }
            }
            if (!sender.has("ticketmanager.notify.info")) {
                sender.sendMessage(text { formattedContent(locale.informationReloadSuccess) })
            }
        }
    }

    // /ticket reopen <ID>
    private suspend fun reopen(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        ticketHandler: BasicTicketHandler,
    ): NotifyParams = coroutineScope {
        val action = FullTicket.Action(FullTicket.Action.Type.REOPEN, sender.toUUIDOrNull())

        // Updates user status if needed
        val newCreatorStatusUpdate = sender.nonCreatorMadeChange(ticketHandler.creatorUUID) && pluginState.allowUnreadTicketUpdates
        if (newCreatorStatusUpdate != ticketHandler.creatorStatusUpdate) {
            launch { ticketHandler.setCreatorStatusUpdate(newCreatorStatusUpdate) }
        }

        launch {
            pluginState.database.addAction(ticketHandler.id, action)
            ticketHandler.setTicketStatus(BasicTicket.Status.OPEN)
        }

        NotifyParams(
            silent = silent,
            sender = sender,
            basicTicket = ticketHandler,
            creatorLambda = {
                val content = it.notifyTicketModificationEvent
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            senderLambda = {
                val content = it.notifyTicketReopenSuccess
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            massNotifyLambda = {
                val content = it.notifyTicketReopenEvent
                    .replace("%user%", sender.name)
                    .replace("%id%", args[1])
                text { formattedContent(content) }
            },
            creatorAlertPerm = "ticketmanager.notify.change.reopen",
            massNotifyPerm = "ticketmanager.notify.massNotify.reopen",
        )
    }

    // /ticket search <Constraints…>
    private suspend fun search(
        sender: CommandSender,
        args: List<String>,
        locale: TMLocale,
    ) {
        coroutineScope {
            fun String.attemptToUUIDString(): String? =
                if (equals(locale.consoleName)) null
                else Bukkit.getOfflinePlayers().asSequence()
                    .firstOrNull { equals(it.name) }
                    ?.run { uniqueId.toString() }
                    ?: "[PLAYERNOTFOUND]"

            // Beginning of code execution
            sender.sendMessage(text { formattedContent(locale.searchFormatQuerying) })
            val constraintTypes = locale.run {
                listOf(
                    searchAssigned,
                    searchCreator,
                    searchKeywords,
                    searchPriority,
                    searchStatus,
                    searchTime,
                    searchWorld,
                    searchPage,
                    searchClosedBy,
                    searchLastClosedBy,
                )
            }

            val localedConstraintMap = args.subList(1, args.size)
                .asSequence()
                .map { it.split(":", limit = 2) }
                .filter { it[0] in constraintTypes }
                .filter { it.size >= 2 }
                .associate { it[0] to it[1] }

            val searchFunctions = localedConstraintMap
                    .mapNotNull { entry ->
                when (entry.key) {
                    locale.searchWorld -> { t: FullTicket -> t.location?.world?.equals(entry.value) ?: false }
                    locale.searchAssigned ->  { t: FullTicket -> t.assignedTo == entry.value }

                    locale.searchCreator -> {
                        val searchedUser = entry.value.attemptToUUIDString();
                        { t: FullTicket -> t.creatorUUID?.toString() == searchedUser }
                    }

                    locale.searchPriority -> {
                        val searchedPriority = entry.value.toByteOrNull() ?: 0;
                        { t: FullTicket -> t.priority.level == searchedPriority }
                    }

                    locale.searchTime -> {
                        val creationTime = relTimeToEpochSecond(entry.value, locale);
                        { t: FullTicket -> t.actions[0].timestamp >= creationTime }
                    }

                    locale.searchStatus -> {
                        val constraintStatus = when (entry.value) {
                            locale.statusOpen -> BasicTicket.Status.OPEN.name
                            locale.statusClosed -> BasicTicket.Status.CLOSED.name
                            else -> entry.value
                        }
                        { t: FullTicket -> t.status.name == constraintStatus}
                    }

                    locale.searchKeywords -> {
                        val words = entry.value.split(",");

                        { t: FullTicket ->
                            val comments = t.actions
                                .filter { it.type == FullTicket.Action.Type.OPEN || it.type == FullTicket.Action.Type.COMMENT }
                                .map { it.message!! }
                            words.map { w -> comments.any { it.contains(w) } }
                                .all { it }
                        }
                    }

                    locale.searchLastClosedBy -> {
                        val searchedUser = entry.value.attemptToUUIDString();
                        { t: FullTicket ->
                            t.actions.lastOrNull { e -> e.type == FullTicket.Action.Type.CLOSE }
                                ?.run { user?.toString() == searchedUser }
                                ?: false
                        }

                    }

                    locale.searchClosedBy -> {
                        val searchedUser = entry.value.attemptToUUIDString();
                        { t: FullTicket -> t.actions.any{ it.type == FullTicket.Action.Type.CLOSE && it.user?.toString() == searchedUser } }
                    }

                    else -> null
                }
            }
                .asSequence()

            val composedSearch = { t: FullTicket -> searchFunctions.map { it(t) }.all { it } }

            // Results Computation
            val resultSize: Int
            val chunkedTickets = pluginState.database.searchDatabase(composedSearch)
                .toList()
                .sortedByDescending(BasicTicket::id)
                .apply { resultSize = size }
                .chunked(8)

            val page = localedConstraintMap[locale.searchPage]?.toIntOrNull()
                .let { if (it != null && it >= 1 && it < chunkedTickets.size) it else 1 }
            val fixMSGLength = { t: FullTicket -> t.actions[0].message!!.run { if (length > 25) "${substring(0,21)}..." else this } }

            val sentComponent = buildComponent {

                // Initial header
                text {
                    formattedContent(
                        locale.searchFormatHeader.replace("%size%", "$resultSize")
                    )
                }

                // Adds entries
                if (chunkedTickets.isNotEmpty()) {
                    chunkedTickets[page-1]
                        .map {
                            val content = "\n${locale.searchFormatEntry}"
                                .replace("%PCC%", it.priority.colourCode)
                                .replace("%SCC%", it.status.colourCode)
                                .replace("%id%", "${it.id}")
                                .replace("%status%", it.status.toLocaledWord(locale))
                                .replace("%creator%", it.creatorUUID.toName(locale))
                                .replace("%assign%", it.assignedTo ?: "")
                                .replace("%world%", it.location?.world ?: "")
                                .replace("%time%", it.actions[0].timestamp.toLargestRelativeTime(locale))
                                .replace("%comment%", fixMSGLength(it))
                            it.id to content
                        }
                        .forEach {
                            text {
                                formattedContent(it.second)
                                onHover { showText(Component.text(locale.clickViewTicket)) }
                                onClick {
                                    action = ClickEvent.Action.RUN_COMMAND
                                    value = locale.run { "/$commandBase $commandWordView ${it.first}" }
                                }
                            }
                        }
                }

                // Implements pages if needed
                if (chunkedTickets.size > 1) {
                    val pageComponent = buildPageComponent(page, chunkedTickets.size, locale) {
                        // Removes page constraint and converts rest to key:arg
                        val constraints = localedConstraintMap
                            .filter { it.key != locale.searchPage }
                            .map { (k, v) -> "$k:$v" }
                        "/${locale.commandBase} ${locale.commandWordSearch} $constraints ${locale.searchPage}:"
                    }
                    append(pageComponent)
                }
            }

            sender.sendMessage(sentComponent)
        }
    }

    // /ticket setpriority <ID> <Level>
    private suspend fun setPriority(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        ticketHandler: BasicTicketHandler,
    ): NotifyParams = coroutineScope {
        launch {
            pluginState.database.addAction(
                ticketID = ticketHandler.id,
                action = FullTicket.Action(FullTicket.Action.Type.SET_PRIORITY, sender.toUUIDOrNull(), args[2])
            )
            ticketHandler.setTicketPriority(byteToPriority(args[2].toByte()))
        }

        NotifyParams(
            silent = silent,
            sender = sender,
            basicTicket = ticketHandler,
            creatorLambda = null,
            senderLambda = {
                val content = it.notifyTicketSetPrioritySuccess
                    .replace("%id%", args[1])
                    .replace("%priority%", ticketHandler.run { priority.colourCode + priority.toLocaledWord(it) })
                text { formattedContent(content) }
            },
            massNotifyLambda = {
                val content = it.notifyTicketSetPriorityEvent
                    .replace("%user%", sender.name)
                    .replace("%id%", args[1])
                    .replace("%priority%", ticketHandler.run { priority.colourCode + priority.toLocaledWord(it) })
                text { formattedContent(content) }
            },
            creatorAlertPerm = "ticketmanager.notify.change.priority",
            massNotifyPerm =  "ticketmanager.notify.massNotify.priority"
        )
    }

    // /ticket teleport <ID>
    private fun teleport(
        sender: CommandSender,
        basicTicket: BasicTicket,
    ) {
        if (sender is Player && basicTicket.location != null) {
            val loc = basicTicket.location!!.run { Location(Bukkit.getWorld(world), x.toDouble(), y.toDouble(), z.toDouble()) }
            sender.teleportAsync(loc)
        }
    }

    // /ticket unassign <ID>
    private suspend fun unAssign(
        sender: CommandSender,
        args: List<String>,
        silent: Boolean,
        senderLocale: TMLocale,
        ticketHandler: BasicTicketHandler,
    ): NotifyParams {
        return allAssignVariations(sender, silent, senderLocale, args[1], null, ticketHandler)
    }

    // /ticket version
    private fun version(
        sender: CommandSender,
        locale: TMLocale,
    ) {
        val sentComponent = buildComponent {
            text {
                content("===========================\n")
                color(NamedTextColor.DARK_AQUA)
            }
            text {
                content("TicketManager:")
                decorate(TextDecoration.BOLD)
                color(NamedTextColor.DARK_AQUA)
                append(Component.text(" by HoshiKurama\n", NamedTextColor.GRAY))
            }
            text {
                content("      GitHub Wiki: ")
                color(NamedTextColor.DARK_AQUA)
            }
            text {
                content("HERE\n")
                color(NamedTextColor.GRAY)
                decorate(TextDecoration.UNDERLINED)
                clickEvent(ClickEvent.openUrl(locale.wikiLink))
                onHover { showText(Component.text(locale.clickWiki)) }
            }
            text {
                content("           V$pluginVersion\n")
                color(NamedTextColor.DARK_AQUA)
            }
            text {
                content("===========================")
                color(NamedTextColor.DARK_AQUA)
            }
        }

        sender.sendMessage(sentComponent)
    }

    // /ticket view <ID>
    private suspend fun view(
        sender: CommandSender,
        locale: TMLocale,
        ticketHandler: BasicTicketHandler,
    ) {
        coroutineScope {
            val fullTicket = ticketHandler.toFullTicketAsync().await()
            val baseComponent = buildTicketInfoComponent(fullTicket, locale)

            if (!sender.nonCreatorMadeChange(ticketHandler.creatorUUID) && ticketHandler.creatorStatusUpdate)
                launch { ticketHandler.setCreatorStatusUpdate(false) }

            val entries = fullTicket.actions.asSequence()
                .filter { it.type == FullTicket.Action.Type.COMMENT || it.type == FullTicket.Action.Type.OPEN }
                .map {
                    "\n${locale.viewFormatComment}"
                        .replace("%user%", it.user.toName(locale))
                        .replace("%comment%", it.message!!)
                }
                .map { text { formattedContent(it) } }
                .reduce(TextComponent::append)

            sender.sendMessage(baseComponent.append(entries))
        }
    }

    // /ticket viewdeep <ID>
    private suspend fun viewDeep(
        sender: CommandSender,
        locale: TMLocale,
        ticketHandler: BasicTicketHandler,
    ) {
        coroutineScope {
            val fullTicket = ticketHandler.toFullTicketAsync().await()
            val baseComponent = buildTicketInfoComponent(fullTicket, locale)

            if (!sender.nonCreatorMadeChange(ticketHandler.creatorUUID) && ticketHandler.creatorStatusUpdate)
                launch { ticketHandler.setCreatorStatusUpdate(false) }

            fun formatDeepAction(action: FullTicket.Action): String {
                val result = when (action.type) {
                    FullTicket.Action.Type.OPEN, FullTicket.Action.Type.COMMENT ->
                        "\n${locale.viewFormatDeepComment}"
                            .replace("%comment%", action.message!!)

                    FullTicket.Action.Type.SET_PRIORITY ->
                        "\n${locale.viewFormatDeepSetPriority}"
                            .replace("%priority%",
                                byteToPriority(action.message!!.toByte()).run { colourCode + toLocaledWord(locale) }
                            )

                    FullTicket.Action.Type.ASSIGN ->
                        "\n${locale.viewFormatDeepAssigned}"
                            .replace("%assign%", action.message ?: "")

                    FullTicket.Action.Type.REOPEN -> "\n${locale.viewFormatDeepReopen}"
                    FullTicket.Action.Type.CLOSE -> "\n${locale.viewFormatDeepClose}"
                    FullTicket.Action.Type.MASS_CLOSE -> "\n${locale.viewFormatDeepMassClose}"
                }
                return result
                    .replace("%user%", action.user.toName(locale))
                    .replace("%time%", action.timestamp.toLargestRelativeTime(locale))
            }

            val entries = fullTicket.actions.asSequence()
                .map(::formatDeepAction)
                .map { text { formattedContent(it) } }
                .reduce(TextComponent::append)

            sender.sendMessage(baseComponent.append(entries))
        }
    }



    private fun buildTicketInfoComponent(
        ticket: FullTicket,
        locale: TMLocale,
    ) = buildComponent {
        text {
            formattedContent(
                "\n${locale.viewFormatHeader}"
                    .replace("%num%", "${ticket.id}")
            )
        }
        text { formattedContent("\n${locale.viewFormatSep1}") }
        text {
            formattedContent(
                "\n${locale.viewFormatInfo1}"
                    .replace("%creator%", ticket.creatorUUID.toName(locale))
                    .replace("%assignment%", ticket.assignedTo ?: "")
            )
        }
        text {
            formattedContent(
                "\n${locale.viewFormatInfo2}"
                    .replace("%priority%", ticket.priority.run { colourCode + toLocaledWord(locale) })
                    .replace("%status%", ticket.status.run { colourCode + toLocaledWord(locale) })
            )
        }
        text {
            formattedContent(
                "\n${locale.viewFormatInfo3}"
                    .replace("%location%", ticket.location?.toString() ?: "")
            )

            if (ticket.location != null) {
                onHover { showText(Component.text(locale.clickTeleport)) }
                onClick {
                    action = ClickEvent.Action.RUN_COMMAND
                    value = locale.run { "/$commandBase $commandWordTeleport ${ticket.id}" }
                }
            }
        }
        text { formattedContent("\n${locale.viewFormatSep2}") }
    }

}

/*-------------------------*/
/*     Other Functions     */
/*-------------------------*/

private inline fun check(error: () -> Unit, predicate: () -> Boolean): Boolean {
    return if (predicate()) true else error().run { false }
}

private inline fun Boolean.thenCheck(error: () -> Unit, predicate: () -> Boolean): Boolean {
    return if (!this) false
    else if (predicate()) true
    else error().run { false }
}

private fun CommandSender.nonCreatorMadeChange(creatorUUID: UUID?): Boolean {
    if (creatorUUID == null) return false
    return this.toUUIDOrNull()?.notEquals(creatorUUID) ?: true
}

private fun CommandSender.toTicketLocationOrNull() = if (this is Player)
        location.run { BasicTicket.TicketLocation(world.name, blockX, blockY, blockZ) }
    else null