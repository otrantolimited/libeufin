/*
 * This file is part of LibEuFin.
 * Copyright (C) 2020 Taler Systems S.A.
 *
 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.
 *
 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

package tech.libeufin.nexus.bankaccount

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.ApplicationCall
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.w3c.dom.Document
import tech.libeufin.nexus.*
import tech.libeufin.nexus.ebics.fetchEbicsBySpec
import tech.libeufin.nexus.ebics.submitEbicsPaymentInitiation
import tech.libeufin.nexus.iso20022.CamtParsingError
import tech.libeufin.nexus.iso20022.CreditDebitIndicator
import tech.libeufin.nexus.iso20022.parseCamtMessage
import tech.libeufin.nexus.server.FetchSpecJson
import tech.libeufin.nexus.server.Pain001Data
import tech.libeufin.nexus.server.requireBankConnection
import tech.libeufin.util.XMLUtil
import tech.libeufin.util.getNow
import tech.libeufin.util.millis
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun requireBankAccount(call: ApplicationCall, parameterKey: String): NexusBankAccountEntity {
    val name = call.parameters[parameterKey]
    if (name == null) {
        throw NexusError(HttpStatusCode.InternalServerError, "no parameter for bank account")
    }
    val account = transaction { NexusBankAccountEntity.findByName(name) }
    if (account == null) {
        throw NexusError(HttpStatusCode.NotFound, "bank connection '$name' not found")
    }
    return account
}


suspend fun submitPaymentInitiation(httpClient: HttpClient, paymentInitiationId: Long) {
    val r = transaction {
        val paymentInitiation = PaymentInitiationEntity.findById(paymentInitiationId)
        if (paymentInitiation == null) {
            throw NexusError(HttpStatusCode.NotFound, "prepared payment not found")
        }
        object {
            val type = paymentInitiation.bankAccount.defaultBankConnection?.type
            val submitted = paymentInitiation.submitted
        }
    }
    if (r.submitted) {
        return
    }
    when (r.type) {
        null -> throw NexusError(HttpStatusCode.NotFound, "no default bank connection")
        "ebics" -> submitEbicsPaymentInitiation(httpClient, paymentInitiationId)
    }
}

/**
 * Submit all pending prepared payments.
 */
suspend fun submitAllPaymentInitiations(httpClient: HttpClient, accountid: String) {
    data class Submission(
        val id: Long
    )
    logger.debug("auto-submitter started")
    val workQueue = mutableListOf<Submission>()
    transaction {
        val account = NexusBankAccountEntity.findByName(accountid) ?: throw NexusError(
            HttpStatusCode.NotFound,
            "account not found"
        )
        PaymentInitiationEntity.find {
            (PaymentInitiationsTable.submitted eq false) and (
                    PaymentInitiationsTable.bankAccount eq account.id)
        }.forEach {
            val defaultBankConnectionId = it.bankAccount.defaultBankConnection?.id ?: throw NexusError(
                HttpStatusCode.BadRequest,
                "needs default bank connection"
            )
            val bankConnection = NexusBankConnectionEntity.findById(defaultBankConnectionId) ?: throw NexusError(
                HttpStatusCode.InternalServerError,
                "Bank account '${it.id.value}' doesn't map to any bank connection (named '${defaultBankConnectionId}')"
            )
            if (bankConnection.type != "ebics") {
                logger.info("Skipping non-implemented bank connection '${bankConnection.type}'")
                return@forEach
            }
            workQueue.add(Submission(it.id.value))
        }
    }
    workQueue.forEach {
        submitPaymentInitiation(httpClient, it.id)
    }
}

/**
 * Check if the transaction is already found in the database.
 */
private fun findDuplicate(bankAccountId: String, acctSvcrRef: String): NexusBankTransactionEntity? {
    // FIXME: make this generic depending on transaction identification scheme
    val ati = "AcctSvcrRef:$acctSvcrRef"
    return transaction {
        val account = NexusBankAccountEntity.findByName((bankAccountId)) ?: return@transaction null
        NexusBankTransactionEntity.find {
            (NexusBankTransactionsTable.accountTransactionId eq ati) and (NexusBankTransactionsTable.bankAccount eq account.id)
        }.firstOrNull()
    }
}

fun processCamtMessage(bankAccountId: String, camtDoc: Document, code: String): Int {
    logger.info("processing CAMT message")
    var newTransactions = 0
    transaction {
        val acct = NexusBankAccountEntity.findByName(bankAccountId)
        if (acct == null) {
            throw NexusError(HttpStatusCode.NotFound, "user not found")
        }
        val res = try {
            parseCamtMessage(camtDoc)
        } catch (e: CamtParsingError) {
            logger.warn("Invalid CAMT received from bank: $e")
            newTransactions = -1
            return@transaction
        }
        val stamp =
            ZonedDateTime.parse(res.creationDateTime, DateTimeFormatter.ISO_DATE_TIME).toInstant().toEpochMilli()
        when (code) {
            "C52" -> {
                val s = acct.lastReportCreationTimestamp
                if (s != null && stamp > s) {
                    acct.lastReportCreationTimestamp = stamp
                }
            }
            "C53" -> {
                val s = acct.lastStatementCreationTimestamp
                if (s != null && stamp > s) {
                    acct.lastStatementCreationTimestamp = stamp
                }
            }
        }
        val entries = res.reports.map { it.entries }.flatten()
        logger.info("found ${entries.size} money movements")
        txloop@ for (entry in entries) {
            val singletonBatchedTransaction = entry.batches?.get(0)?.batchTransactions?.get(0)
                ?: throw NexusError(
                    HttpStatusCode.InternalServerError,
                    "Singleton money movements policy wasn't respected"
                )
            val acctSvcrRef = entry.accountServicerRef
            if (acctSvcrRef == null) {
                // FIXME(dold): Report this!
                logger.error("missing account servicer reference in transaction")
                continue
            }
            val duplicate = findDuplicate(bankAccountId, acctSvcrRef)
            if (duplicate != null) {
                logger.info("Found a duplicate: $acctSvcrRef")
                // FIXME(dold): See if an old transaction needs to be superseded by this one
                // https://bugs.gnunet.org/view.php?id=6381
                continue@txloop
            }
            val rawEntity = NexusBankTransactionEntity.new {
                bankAccount = acct
                accountTransactionId = "AcctSvcrRef:$acctSvcrRef"
                amount = singletonBatchedTransaction.amount.value.toPlainString()
                currency = singletonBatchedTransaction.amount.currency
                transactionJson = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(entry)
                creditDebitIndicator = singletonBatchedTransaction.creditDebitIndicator.name
                status = entry.status
            }
            rawEntity.flush()
            newTransactions++
            // This block tries to acknowledge a former outgoing payment as booked.
            if (singletonBatchedTransaction.creditDebitIndicator == CreditDebitIndicator.DBIT) {
                val t0 = singletonBatchedTransaction.details
                val pmtInfId = t0.paymentInformationId
                if (pmtInfId != null) {
                    val paymentInitiation = PaymentInitiationEntity.find {
                        PaymentInitiationsTable.bankAccount eq acct.id and (
                                PaymentInitiationsTable.paymentInformationId eq pmtInfId)

                    }.firstOrNull()
                    if (paymentInitiation != null) {
                        logger.info("Could confirm one initiated payment: $pmtInfId")
                        paymentInitiation.confirmationTransaction = rawEntity
                    }
                }
            }
        }
    }
    return newTransactions
}

/**
 * Create new transactions for an account based on bank messages it
 * did not see before.
 */
fun ingestBankMessagesIntoAccount(bankConnectionId: String, bankAccountId: String): Int {
    var totalNew = 0
    transaction {
        val conn =
            NexusBankConnectionEntity.find { NexusBankConnectionsTable.connectionId eq bankConnectionId }.firstOrNull()
        if (conn == null) {
            throw NexusError(HttpStatusCode.InternalServerError, "connection not found")
        }
        val acct = NexusBankAccountEntity.findByName(bankAccountId)
        if (acct == null) {
            throw NexusError(HttpStatusCode.InternalServerError, "account not found")
        }
        var lastId = acct.highestSeenBankMessageSerialId
        NexusBankMessageEntity.find {
            (NexusBankMessagesTable.bankConnection eq conn.id) and
                    (NexusBankMessagesTable.id greater acct.highestSeenBankMessageSerialId)
        }.orderBy(Pair(NexusBankMessagesTable.id, SortOrder.ASC)).forEach {
            val doc = XMLUtil.parseStringIntoDom(it.message.bytes.toString(Charsets.UTF_8))
            val newTransactions = processCamtMessage(bankAccountId, doc, it.code)
            if (newTransactions == -1) {
                it.errors = true
                return@forEach
            }
            lastId = it.id.value
            totalNew += newTransactions
        }
        acct.highestSeenBankMessageSerialId = lastId
    }
    return totalNew
}

/**
 * Retrieve payment initiation from database, raising exception if not found.
 */
fun getPaymentInitiation(uuid: Long): PaymentInitiationEntity {
    return transaction {
        PaymentInitiationEntity.findById(uuid)
    } ?: throw NexusError(
        HttpStatusCode.NotFound,
        "Payment '$uuid' not found"
    )
}


/**
 * Insert one row in the database, and leaves it marked as non-submitted.
 * @param debtorAccount the mnemonic id assigned by the bank to one bank
 * account of the subscriber that is creating the pain entity.  In this case,
 * it will be the account whose money will pay the wire transfer being defined
 * by this pain document.
 */
fun addPaymentInitiation(paymentData: Pain001Data, debtorAccount: NexusBankAccountEntity): PaymentInitiationEntity {
    return transaction {
        val now = getNow().millis()
        val nowHex = now.toString(16)
        val painCounter = debtorAccount.pain001Counter++
        val painHex = painCounter.toString(16)
        val acctHex = debtorAccount.id.value.toString(16)
        PaymentInitiationEntity.new {
            bankAccount = debtorAccount
            subject = paymentData.subject
            sum = paymentData.sum
            creditorName = paymentData.creditorName
            creditorBic = paymentData.creditorBic
            creditorIban = paymentData.creditorIban
            preparationDate = now
            endToEndId = "leuf-e-$nowHex-$painHex-$acctHex"
            messageId = "leuf-mp1-$nowHex-$painHex-$acctHex"
            paymentInformationId = "leuf-p-$nowHex-$painHex-$acctHex"
            instructionId = "leuf-i-$nowHex-$painHex-$acctHex"
        }
    }
}

suspend fun fetchBankAccountTransactions(client: HttpClient, fetchSpec: FetchSpecJson, accountId: String): Int {
    val res = transaction {
        val acct = NexusBankAccountEntity.findByName(accountId)
        if (acct == null) {
            throw NexusError(
                HttpStatusCode.NotFound,
                "Account not found"
            )
        }
        val conn = acct.defaultBankConnection
        if (conn == null) {
            throw NexusError(
                HttpStatusCode.BadRequest,
                "No default bank connection (explicit connection not yet supported)"
            )
        }
        return@transaction object {
            val connectionType = conn.type
            val connectionName = conn.connectionId
        }
    }
    when (res.connectionType) {
        "ebics" -> {
            fetchEbicsBySpec(
                fetchSpec,
                client,
                res.connectionName,
                accountId
            )
        }
        else -> throw NexusError(
            HttpStatusCode.BadRequest,
            "Connection type '${res.connectionType}' not implemented"
        )
    }
    val newTransactions = ingestBankMessagesIntoAccount(res.connectionName, accountId)
    ingestTalerTransactions()
    return newTransactions
}

fun importBankAccount(call: ApplicationCall, offeredBankAccountId: String, nexusBankAccountId: String) {
    transaction {
        val conn = requireBankConnection(call, "connid")
        // first get handle of the offered bank account
        val offeredAccount = OfferedBankAccountsTable.select {
            OfferedBankAccountsTable.offeredAccountId eq offeredBankAccountId and
                    (OfferedBankAccountsTable.bankConnection eq conn.id.value)
        }.firstOrNull() ?: throw NexusError(
            HttpStatusCode.NotFound, "Could not find offered bank account '${offeredBankAccountId}'"
        )
        // detect name collisions first.
        NexusBankAccountEntity.findByName(nexusBankAccountId).run {
            val importedAccount = when (this) {
                is NexusBankAccountEntity -> {
                    if (this.iban != offeredAccount[OfferedBankAccountsTable.iban]) {
                        throw NexusError(
                            HttpStatusCode.Conflict,
                            // different accounts == different IBANs
                            "Cannot import two different accounts under one label: $nexusBankAccountId"
                        )
                    }
                    this
                }
                else -> {
                    val newImportedAccount = NexusBankAccountEntity.new {
                        bankAccountName = nexusBankAccountId
                        iban = offeredAccount[OfferedBankAccountsTable.iban]
                        bankCode = offeredAccount[OfferedBankAccountsTable.bankCode]
                        defaultBankConnection = conn
                        highestSeenBankMessageSerialId = 0
                        accountHolder = offeredAccount[OfferedBankAccountsTable.accountHolder]
                    }
                    logger.info("Account ${newImportedAccount.id} gets imported")
                    newImportedAccount
                }
            }
            OfferedBankAccountsTable.update(
                {
                    OfferedBankAccountsTable.offeredAccountId eq offeredBankAccountId and
                            (OfferedBankAccountsTable.bankConnection eq conn.id.value)
                }
            ) {
                it[imported] = importedAccount.id
            }
        }
    }
}