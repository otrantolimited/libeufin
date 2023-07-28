/*
 * This file is part of LibEuFin.
 * Copyright (C) 2019 Stanisci and Dold.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.nexus

import com.github.ajalt.clikt.output.CliktHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tech.libeufin.util.CryptoUtil.hashpw
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.parameters.types.int
import execThrowableOrTerminate
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import startServer
import tech.libeufin.nexus.iso20022.NexusPaymentInitiationData
import tech.libeufin.nexus.iso20022.createPain001document
import tech.libeufin.nexus.iso20022.parseCamtMessage
import tech.libeufin.nexus.server.EbicsDialects
import tech.libeufin.nexus.server.nexusApp
import tech.libeufin.util.*
import java.io.File
import kotlin.system.exitProcess

val logger: Logger = LoggerFactory.getLogger("tech.libeufin.nexus")
const val NEXUS_DB_ENV_VAR_NAME = "LIBEUFIN_NEXUS_DB_CONNECTION"

class NexusCommand : CliktCommand() {
    init { versionOption(getVersion()) }
    override fun run() = Unit
}

class Serve : CliktCommand("Run nexus HTTP server") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
    private val localhostOnly by option(
        "--localhost-only",
        help = "Bind only to localhost.  On all interfaces otherwise"
    ).flag("--no-localhost-only", default = true)
    private val ipv4Only by option(
        "--ipv4-only",
        help = "Bind only to ipv4"
    ).flag(default = false)
    // Prevent IPv6 mode:
    // private val host by option().default("127.0.0.1")
    private val port by option().int().default(5001)
    private val withUnixSocket by option(
        help = "Bind the Sandbox to the Unix domain socket at PATH.  Overrides" +
                " --port, when both are given", metavar = "PATH"
    )
    private val logLevel by option()
    override fun run() {
        setLogLevel(logLevel)
        execThrowableOrTerminate { dbCreateTables(getDbConnFromEnv(NEXUS_DB_ENV_VAR_NAME)) }
        CoroutineScope(Dispatchers.IO).launch(fallback) { whileTrueOperationScheduler() }
        if (withUnixSocket != null) {
            startServer(
                withUnixSocket!!,
                app = nexusApp
            )
            exitProcess(0)
        }
        logger.info("Starting Nexus on port ${this.port}")
        startServerWithIPv4Fallback(
            options = StartServerOptions(
                ipv4OnlyOpt = this.ipv4Only,
                localhostOnlyOpt = this.localhostOnly,
                portOpt = this.port
            ),
            app = nexusApp
        )
    }
}

/**
 * This command purpose is to let the user then _manually_
 * tune the pain.001, to upload it to online verifiers.
 */
class GenPain : CliktCommand(
    "Generate random pain.001 document for 'pf' dialect, printing to STDOUT."
) {
    private val logLevel by option(
        help = "Set the log level to: 'off', 'error', 'warn', 'info', 'debug', 'trace', 'all'"
    )
    private val dialect by option(
        help = "EBICS dialect using the pain.001 being generated.  Defaults to 'pf' (PostFinance)",
    ).default("pf")
    override fun run() {
        setLogLevel(logLevel)
        val pain001 = createPain001document(
            NexusPaymentInitiationData(
                debtorIban = "CH0889144371988976754",
                debtorBic = "POFICHBEXXX",
                debtorName = "Sample Debtor Name",
                currency = "CHF",
                amount = "5.00",
                creditorIban = "CH9789144829733648596",
                creditorName = "Sample Creditor Name",
                creditorBic = "POFICHBEXXX",
                paymentInformationId = "8aae7a2ded2f",
                preparationTimestamp = getNow().toInstant().toEpochMilli(),
                subject = "Unstructured remittance information",
                instructionId = "InstructionId",
                endToEndId = "71cfbdaf901f",
                messageId = "2a16b35ed69c"
            ),
            dialect = this.dialect
        )
        println(pain001)
    }
}
class ParseCamt : CliktCommand("Parse camt.05x file, outputs JSON in libEufin internal representation.") {
    private val logLevel by option(
        help = "Set the log level to: 'off', 'error', 'warn', 'info', 'debug', 'trace', 'all'"
    )
    private val withPfDialect by option(
        help = "Set the dialect to 'pf' (PostFinance).  If not given, it defaults to GLS."
    ).flag(default = false)
    private val filename by argument("FILENAME", "File in CAMT format")
    override fun run() {
        setLogLevel(logLevel)
        val camtText = File(filename).readText(Charsets.UTF_8)
        val dialect = if (withPfDialect) EbicsDialects.POSTFINANCE.dialectName else null
        val res = parseCamtMessage(XMLUtil.parseStringIntoDom(camtText), dialect)
        println(jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(res))
    }
}

class ResetTables : CliktCommand("Drop all the tables from the database") {
    init {
        context {
            helpFormatter = CliktHelpFormatter(showDefaultValues = true)
        }
    }
    override fun run() {
        val dbConnString = getDbConnFromEnv(NEXUS_DB_ENV_VAR_NAME)
        execThrowableOrTerminate {
            dbDropTables(dbConnString)
            dbCreateTables(dbConnString)
        }
    }
}

class Superuser : CliktCommand("Add superuser or change pw") {
    private val username by argument("USERNAME", "User name of superuser")
    private val password by option().prompt(requireConfirmation = true, hideInput = true)
    override fun run() {
        execThrowableOrTerminate {
            dbCreateTables(getDbConnFromEnv(NEXUS_DB_ENV_VAR_NAME))
        }
        transaction {
            val hashedPw = hashpw(password)
            val user = NexusUserEntity.find { NexusUsersTable.username eq username }.firstOrNull()
            if (user == null) {
                NexusUserEntity.new {
                    this.username = this@Superuser.username
                    this.passwordHash = hashedPw
                    this.superuser = true
                }
            } else {
                if (!user.superuser) {
                    System.err.println("Can only change password for superuser with this command.")
                    throw ProgramResult(1)
                }
                user.passwordHash = hashedPw
            }
        }
    }
}

fun main(args: Array<String>) {
    NexusCommand()
        .subcommands(Serve(), Superuser(), ParseCamt(), ResetTables(), GenPain())
        .main(args)
}
