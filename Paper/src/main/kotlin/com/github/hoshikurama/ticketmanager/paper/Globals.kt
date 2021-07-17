package com.github.hoshikurama.ticketmanager.paperimport com.github.hoshikurama.componentDSL.buildComponentimport com.github.hoshikurama.componentDSL.formattedContentimport com.github.hoshikurama.ticketmanager.common.ConfigStateimport com.github.hoshikurama.ticketmanager.common.TMLocaleimport com.github.shynixn.mccoroutine.asyncDispatcherimport net.kyori.adventure.audience.Audienceimport net.kyori.adventure.text.Componentimport org.bukkit.Bukkitimport org.bukkit.ChatColorimport org.bukkit.command.CommandSenderimport org.bukkit.entity.Playerimport java.util.*import java.util.logging.Levelimport kotlin.coroutines.CoroutineContextinternal fun consoleLog(level: Level, message: String) = Bukkit.getLogger().log(level, ChatColor.stripColor(message))internal val mainPlugin: TicketManagerPlugin    get() = TicketManagerPlugin.plugininternal val configState: ConfigState    get() = mainPlugin.configStateIinternal val asyncContext: CoroutineContext    get() = mainPlugin.asyncDispatcherinternal fun pushMassNotify(permission: String, localeMsg: (TMLocale) -> Component) {    Bukkit.getConsoleSender().sendMessage(localeMsg(mainPlugin.configStateI.localeHandler.consoleLocale))    Bukkit.getOnlinePlayers().asSequence()        .filter { it.has(permission) }        .forEach { localeMsg(it.toTMLocale()).run(it::sendMessage) }}internal fun Player.has(permission: String) = mainPlugin.perms.has(this, permission)internal fun CommandSender.has(permission: String): Boolean = if (this is Player) has(permission) else trueinternal fun Player.toTMLocale() = configState.localeHandler.getOrDefault(locale().toString())internal fun CommandSender.toTMLocale() = if (this is Player) toTMLocale() else configState.localeHandler.consoleLocaleinternal fun CommandSender.toUUIDOrNull() = if (this is Player) this.uniqueId else nullinternal fun UUID?.toName(locale: TMLocale): String {    if (this == null) return locale.consoleName    return this.run(Bukkit::getOfflinePlayer).name ?: "UUID"}internal fun postModifiedStacktrace(e: Exception) {    val onlinePlayers = Bukkit.getOnlinePlayers()        .asSequence()        .filter { it.has("ticketmanager.notify.warning") }        .map { it as Audience to it.toTMLocale() }    val console = Bukkit.getConsoleSender() as Audience to configState.localeHandler.consoleLocale    val playersAndConsole = onlinePlayers + sequenceOf(console)    playersAndConsole.forEach { pair ->        val audience = pair.first        val locale = pair.second        audience.sendMessage(            buildComponent {                // Builds header                listOf(                    locale.stacktraceLine1,                    locale.stacktraceLine2.replace("%exception%", e.javaClass.simpleName),                    locale.stacktraceLine3.replace("%message%", e.message ?: "?"),                    locale.stacktraceLine4,                )                    .forEach { text { formattedContent(it) } }                // Adds stacktrace entries                e.stackTrace                    .filter { it.className.startsWith("com.github.hoshikurama.ticketmanager") }                    .map {                        locale.stacktraceEntry                            .replace("%method%", it.methodName)                            .replace("%file%", it.fileName ?: "?")                            .replace("%line%", "${it.lineNumber}")                    }                    .forEach { text { formattedContent(it) } }            }        )    }}