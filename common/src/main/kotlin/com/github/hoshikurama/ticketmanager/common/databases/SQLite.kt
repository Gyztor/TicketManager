package com.github.hoshikurama.ticketmanager.common.databases

import com.github.hoshikurama.ticketmanager.common.byteToPriority
import com.github.hoshikurama.ticketmanager.common.ticket.BasicTicket
import com.github.hoshikurama.ticketmanager.common.ticket.FullTicket
import com.github.hoshikurama.ticketmanager.common.ticket.toTicketLocation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotliquery.*
import java.sql.DriverManager
import java.time.Instant
import java.util.*


class SQLite(absoluteDataFolderPath: String) : Database {
    override val type = Database.Type.SQLite
    private val url: String = "jdbc:sqlite:$absoluteDataFolderPath/TicketManager-SQLite.db"


    private fun getSession() = Session(Connection(DriverManager.getConnection(url)))

    override suspend fun getActionsAsFlow(ticketID: Int): Flow<Pair<Int, FullTicket.Action>> {
        return using(getSession()) { getActions(ticketID, it) }
            .map { ticketID to it }
            .asFlow()
    }

    override suspend fun setAssignmentAsync(ticketID: Int, assignment: String?) {
        using(getSession()) {
            it.run(queryOf("UPDATE TicketManager_V4_Tickets SET ASSIGNED_TO = ? WHERE ID = $ticketID;", assignment).asUpdate)
        }
    }

    override suspend fun setCreatorStatusUpdateAsync(ticketID: Int, status: Boolean) {
        using(getSession()) {
            it.run(queryOf("UPDATE TicketManager_V4_Tickets SET STATUS_UPDATE_FOR_CREATOR = ? WHERE ID = $ticketID;", status).asUpdate)
        }
    }

    override suspend fun setPriorityAsync(ticketID: Int, priority: BasicTicket.Priority) {
        using(getSession()) {
            it.run(queryOf("UPDATE TicketManager_V4_Tickets SET PRIORITY = ? WHERE ID = $ticketID;", priority.level).asUpdate)
        }
    }

    override suspend fun setStatusAsync(ticketID: Int, status: BasicTicket.Status) {
        using(getSession()) {
            it.run(queryOf("UPDATE TicketManager_V4_Tickets SET STATUS = ? WHERE ID = $ticketID;", status.name).asUpdate)
        }
    }

    override suspend fun getBasicTicketAsync(ticketID: Int): Deferred<BasicTicket?> = coroutineScope {
        async {
            using(getSession()) { getBasicTicket(ticketID, it) }
        }
    }

    override suspend fun addAction(ticketID: Int, action: FullTicket.Action) {
        using(getSession()) {
            writeAction(action, ticketID, it)
        }
    }

    override suspend fun addFullTicket(fullTicket: FullTicket) {
        using(getSession()) { session ->
            writeTicket(fullTicket, session)

            fullTicket.actions.forEach {
                writeAction(it, fullTicket.id, session)
            }
        }
    }

    override suspend fun addNewTicketAsync(basicTicket: BasicTicket, message: String): Deferred<Int> = coroutineScope {
        async {
            using(getSession()) { session ->
                val id = writeTicket(basicTicket, session)!!.toInt()
                writeAction(FullTicket.Action(FullTicket.Action.Type.OPEN, basicTicket.creatorUUID, message), id, session)
                id
            }
        }
    }

    override suspend fun massCloseTickets(lowerBound: Int, upperBound: Int, uuid: UUID?) {
        using(getSession()) { session ->

            val idStatusPairs = session.run(queryOf("SELECT ID, STATUS FROM TicketManager_V4_Tickets WHERE ID BETWEEN $lowerBound AND $upperBound;")
                .map { it.int(1) to BasicTicket.Status.valueOf(it.string(2)) }
                .asList
            )

            idStatusPairs.asSequence()
                .filter { it.second == BasicTicket.Status.OPEN }
                .forEach {
                    writeAction(
                        FullTicket.Action(
                            type = FullTicket.Action.Type.MASS_CLOSE,
                            user = uuid,
                            message = null,
                            timestamp = Instant.now().epochSecond
                        ),
                        ticketID = it.first,
                        session = session
                    )
                }

            val idString = idStatusPairs.map { it.first }.joinToString(", ")
            session.run(queryOf("UPDATE TicketManager_V4_Tickets SET STATUS = ? WHERE ID IN ($idString);", BasicTicket.Status.CLOSED.name).asUpdate)
        }
    }

    override suspend fun getBasicOpenAsFlow(): Flow<BasicTicket> {
        return using(getSession()) { session ->
            session.run(
                queryOf("SELECT * FROM TicketManager_V4_Tickets WHERE STATUS = ?;", BasicTicket.Status.OPEN.toString())
                    .map { it.toBasicTicket() }
                    .asList
            )
        }.asFlow()
    }

    override suspend fun getBasicOpenAssignedAsFlow(
        assignment: String,
        groupAssignment: List<String>
    ): Flow<BasicTicket> {
        return getBasicOpenAsFlow().filter { it.assignedTo == assignment || it.assignedTo in groupAssignment }
    }

    override suspend fun getBasicsWithUpdatesAsFlow(): Flow<BasicTicket> {
        return using(getSession()) { session ->
            session.run(
                queryOf("SELECT * FROM TicketManager_V4_Tickets WHERE STATUS_UPDATE_FOR_CREATOR = ?;", true)
                    .map { it.toBasicTicket() }
                    .asList
            )
        }.asFlow()
    }

    override suspend fun getFullOpenAsFlow(): Flow<FullTicket> {
        val basicTickets = getBasicOpenAsFlow().toList()

        return using(getSession()) { session ->
            basicTickets.map { it.toFullTicket(session) }.asFlow()
        }
    }

    override suspend fun getFullOpenAssignedAsFlow(assignment: String, groupAssignment: List<String>): Flow<FullTicket> {
        return getFullOpenAsFlow().filter { it.assignedTo == assignment || it.assignedTo in groupAssignment }
    }

    override suspend fun getIDsWithUpdatesAsFlowFor(uuid: UUID): Flow<Int> {
        return using(getSession()) { session ->
            session.run(
                queryOf("SELECT ID FROM TicketManager_V4_Tickets WHERE STATUS_UPDATE_FOR_CREATOR = ? AND CREATOR_UUID = ?;", true, uuid)
                    .map { it.int(1) }
                    .asList
            )
        }.asFlow()
    }

    override suspend fun searchDatabase(searchFunction: (FullTicket) -> Boolean): Flow<FullTicket> {
        val matchedTickets = mutableListOf<FullTicket>()

        using(getSession()) { session ->
            session.forEach(queryOf("SELECT * FROM TicketManager_V4_Tickets")) { row ->
                row.toBasicTicket().toFullTicket(session).takeIf(searchFunction)?.apply(matchedTickets::add)
            }
        }

        return matchedTickets.asFlow()
    }

    override suspend fun closeDatabase() {
        // NOT needed as database makes individual connections
    }

    override suspend fun initialiseDatabase() {
        //Creates table if doesn't exist
        using(getSession()) {
            if (!tableExists("TicketManager_V4_Tickets", it)) {
                it.run(
                    queryOf("""
                        CREATE TABLE TicketManager_V4_Tickets (
                        ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        CREATOR_UUID VARCHAR(36) COLLATE NOCASE,
                        PRIORITY TINYINT NOT NULL,
                        STATUS VARCHAR(10) COLLATE NOCASE NOT NULL,
                        ASSIGNED_TO VARCHAR(255) COLLATE NOCASE,
                        STATUS_UPDATE_FOR_CREATOR BOOLEAN NOT NULL,
                        LOCATION VARCHAR(255) COLLATE NOCASE
                        );""".trimIndent()
                    ).asExecute
                )
                it.run(queryOf("CREATE INDEX STATUS_V4 ON TicketManager_V4_Tickets (STATUS)").asExecute)
                it.run(queryOf("CREATE INDEX STATUS_UPDATE_FOR_CREATOR_V4 ON TicketManager_V4_Tickets (STATUS_UPDATE_FOR_CREATOR)").asExecute)
            }

            if (!tableExists("TicketManager_V4_Actions", it)) {
                it.run(
                    queryOf("""
                        CREATE TABLE TicketManager_V4_Actions (
                        ACTION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        TICKET_ID INTEGER NOT NULL,
                        ACTION_TYPE VARCHAR(20) COLLATE NOCASE NOT NULL,
                        CREATOR_UUID VARCHAR(36) COLLATE NOCASE,
                        MESSAGE TEXT COLLATE NOCASE,
                        TIMESTAMP BIGINT NOT NULL
                        );""".trimIndent()
                    ).asExecute
                )
            }
        }
    }

    override suspend fun updateNeededAsync(): Deferred<Boolean> {
        return coroutineScope {
            async {
                using(getSession()) {
                    tableExists("TicketManagerTicketsV2", it)
                }
            }
        }
    }

    override suspend fun migrateDatabase(
        to: Database.Type,
        onBegin: suspend () -> Unit,
        onComplete: suspend () -> Unit
    ) {
        //TODO use more functions like () -> Database
        /*
        @Suppress("BlockingMethodInNonBlockingContext")
        runBlocking {
            when (to) {
                Database.Type.SQLite -> {} // SQLite -> SQLite is not permitted

                Database.Type.MySQL -> {
                    pushMassNotify("ticketmanager.notify.info", {
                        it.informationDBConvertInit
                            .replace("%fromDB%", Database.Types.SQLite.name)
                            .replace("%toDB%", Database.Types.MySQL.name)
                    } )

                    try {
                        val config = mainPlugin.config
                        val mySQL = MySQL(
                            config.getString("MySQL_Host")!!,
                            config.getString("MySQL_Port")!!,
                            config.getString("MySQL_DBName")!!,
                            config.getString("MySQL_Username")!!,
                            config.getString("MySQL_Password")!!
                        )

                        // Writes to MySQL
                        using(getSession()) { session ->
                            session.forEach(queryOf("SELECT * FROM TicketManager_V4_Tickets")) { row ->  //NOTE: During conversion, ticket ID is not guaranteed to be preserved
                                row.toTicket(session).apply {
                                    val newID = mySQL.addTicket(this, actions[0])
                                    if (actions.size > 1) actions.subList(1, actions.size)
                                        .forEach { mySQL.addAction(newID, it) }
                                }
                            }
                        }

                        pushMassNotify("ticketmanager.notify.info", { it.informationDBConvertSuccess } )

                    } catch (e: Exception) {
                        e.printStackTrace()
                        postModifiedStacktrace(e)
                    }
                }
            }
        }

         */
    }

    override suspend fun updateDatabase(
        onBegin: suspend () -> Unit,
        onComplete: suspend () -> Unit,
        offlinePlayerNameToUuidOrNull: (String) -> UUID?
    ) {
        coroutineScope {
            onBegin()

            using(getSession()) { session ->
                session.forEach(queryOf("SELECT * FROM TicketManagerTicketsV2;")) { row ->
                    val ticket = FullTicket(
                        id = row.int(1),
                        priority = byteToPriority(row.byte(3)),
                        creatorStatusUpdate = row.boolean(10),
                        status = BasicTicket.Status.valueOf(row.string(2)),
                        assignedTo = row.stringOrNull(6),

                        creatorUUID = row.string(5).let {
                            if (it.lowercase() == "console") null
                            else UUID.fromString(it)
                        },
                        location = row.string(7).run {
                            if (equals("NoLocation")) null
                            else toTicketLocation()
                        },
                        actions = row.string(9)
                            .split("/MySQLNewLine/")
                            .filter { it.isNotBlank() }
                            .map { it.split("/MySQLSep/") }
                            .mapIndexed { index, action ->
                                FullTicket.Action(
                                    message = action[1],
                                    type = if (index == 0) FullTicket.Action.Type.OPEN else FullTicket.Action.Type.COMMENT,
                                    timestamp = row.long(8),
                                    user =
                                    if (action[0].lowercase() == "console") null
                                    else offlinePlayerNameToUuidOrNull(action[0])
                                )
                            }
                    )
                    val id = writeTicket(ticket, session)
                    ticket.actions.forEach { writeAction(it, id!!.toInt(), session) }
                }

                session.run(queryOf("DROP INDEX STATUS;").asExecute)
                session.run(queryOf("DROP INDEX UPDATEDBYOTHERUSER;").asExecute)
                session.run(queryOf("DROP TABLE TicketManagerTicketsV2;").asUpdate)

            }

            onComplete()
        }

    }


    private fun getBasicTicket(ticketID: Int, session: Session): BasicTicket? {
        return session.run(
            queryOf("SELECT * FROM TicketManager_V4_Tickets WHERE ID = $ticketID;")
                .map { it.toBasicTicket() }
                .asSingle
        )
    }

    private fun writeTicket(ticket: BasicTicket, session: Session): Long? {
        return session.run(queryOf("INSERT INTO TicketManager_V4_Tickets (CREATOR_UUID, PRIORITY, STATUS, ASSIGNED_TO, STATUS_UPDATE_FOR_CREATOR, LOCATION) VALUES (?,?,?,?,?,?);",
            ticket.creatorUUID,
            ticket.priority.level,
            ticket.status.name,
            ticket.assignedTo,
            ticket.creatorStatusUpdate,
            ticket.location?.toString()
        ).asUpdateAndReturnGeneratedKey)
    }

    private fun writeAction(action: FullTicket.Action, ticketID: Int, session: Session) {
        session.run(queryOf("INSERT INTO TicketManager_V4_Actions (TICKET_ID,ACTION_TYPE,CREATOR_UUID,MESSAGE,TIMESTAMP) VALUES (?,?,?,?,?);",
            ticketID,
            action.type.name,
            action.user?.toString(),
            action.message,
            action.timestamp
        ).asExecute)
    }

    private fun Row.toBasicTicket(): BasicTicket {
        return BasicTicket(
            id = int(1),
            creatorUUID = stringOrNull(2)?.let(UUID::fromString),
            priority = byteToPriority(byte(3)),
            status = BasicTicket.Status.valueOf(string(4)),
            assignedTo = stringOrNull(5),
            creatorStatusUpdate = boolean(6),
            location = stringOrNull(7)?.split(" ")?.let {
                BasicTicket.TicketLocation(
                    world = it[0],
                    x = it[1].toInt(),
                    y = it[2].toInt(),
                    z = it[3].toInt()
                )
            }
        )
    }

    private fun Row.toAction(): FullTicket.Action {
        return FullTicket.Action(
            type = FullTicket.Action.Type.valueOf(string(2)),
            user = stringOrNull(3)?.let { UUID.fromString(it) },
            message = stringOrNull(4),
            timestamp = long(5)
        )
    }

    private fun getActions(ticketID: Int, session: Session): List<FullTicket.Action> {
        return session.run(queryOf("SELECT ACTION_ID, ACTION_TYPE, CREATOR_UUID, MESSAGE, TIMESTAMP FROM TicketManager_V4_Actions WHERE TICKET_ID = $ticketID;")
            .map { row -> row.toAction() }
            .asList
        )
    }

    private fun Row.toTicketIDActionPair() = int(1) to toAction()

    private fun tableExists(table: String, session: Session): Boolean {
        return using(session.connection.underlying.metaData.getTables(null, null, table, null)) {
            while (it.next())
                if (it.getString("TABLE_NAME")?.equals(table) == true) return@using true
            return@using false
        }
    }

    private fun BasicTicket.toFullTicket(session: Session) = FullTicket(this, getActions(id, session))
}