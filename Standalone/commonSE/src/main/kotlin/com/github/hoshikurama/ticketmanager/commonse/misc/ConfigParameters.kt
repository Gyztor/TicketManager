package com.github.hoshikurama.ticketmanager.commonse.misc

data class ConfigParameters(
    val MySQLHost: String?,
    val mySQLPort: String?,
    val mySQLDBName: String?,
    val mySQLUsername: String?,
    val mySQLPassword: String?,
    val pluginFolderPath: String,
    val memoryFrequency: Long?,
    val dbTypeAsStr: String?,
    val allowCooldowns: Boolean?,
    val cooldownSeconds: Long?,
    val localeHandlerColourCode: String?,
    val localeHandlerPreferredLocale: String?,
    val localeHandlerConsoleLocale: String?,
    val localeHandlerForceLocale: Boolean?,
    val allowUnreadTicketUpdates: Boolean?,
    val checkForPluginUpdates: Boolean?,
    val enableDiscord: Boolean?,
    val DiscordNotifyOnAssign: Boolean?,
    val DiscordNotifyOnClose: Boolean?,
    val DiscordNotifyOnCloseAll: Boolean?,
    val DiscordNotifyOnComment: Boolean?,
    val DiscordNotifyOnCreate: Boolean?,
    val DiscordNotifyOnReopen: Boolean?,
    val DiscordNotifyOnPriorityChange: Boolean?,
    val DiscordToken: String?,
    val DiscordChannelID: Long?,
    val printModifiedStacktrace: Boolean?,
    val printFullStacktrace: Boolean?,
    val enableAdvancedVisualControl: Boolean?,
    val enableProxyMode: Boolean?,
    val proxyServerName: String?,
    val autoUpdateConfig: Boolean?,
    val allowProxyUpdateChecks: Boolean?,
    val proxyUpdateFrequency: Long?,
    val pluginUpdateFrequency: Long?,
)