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

package tech.libeufin.nexus

import EntryStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.util.*
import java.sql.Connection
import kotlin.reflect.typeOf

/**
 * This table holds the values that exchange gave to issue a payment,
 * plus a reference to the prepared pain.001 version of.  Note that
 * whether a pain.001 document was sent or not to the bank is indicated
 * in the PAIN-table.
 */
object TalerRequestedPaymentsTable : LongIdTable() {
    val facade = reference("facade", FacadesTable)
    val preparedPayment = reference("payment", PaymentInitiationsTable)
    val requestUid = text("requestUid")
    val amount = text("amount")
    val exchangeBaseUrl = text("exchangeBaseUrl")
    val wtid = text("wtid")
    val creditAccount = text("creditAccount")
}

class TalerRequestedPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerRequestedPaymentEntity>(TalerRequestedPaymentsTable)

    var facade by FacadeEntity referencedOn TalerRequestedPaymentsTable.facade
    var preparedPayment by PaymentInitiationEntity referencedOn TalerRequestedPaymentsTable.preparedPayment
    var requestUid by TalerRequestedPaymentsTable.requestUid
    var amount by TalerRequestedPaymentsTable.amount
    var exchangeBaseUrl by TalerRequestedPaymentsTable.exchangeBaseUrl
    var wtid by TalerRequestedPaymentsTable.wtid
    var creditAccount by TalerRequestedPaymentsTable.creditAccount
}

object TalerInvalidIncomingPaymentsTable : LongIdTable() {
    val payment = reference("payment", NexusBankTransactionsTable)
    val timestampMs = long("timestampMs")
    val refunded = bool("refunded").default(false)
}

class TalerInvalidIncomingPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerInvalidIncomingPaymentEntity>(TalerInvalidIncomingPaymentsTable)

    var payment by NexusBankTransactionEntity referencedOn TalerInvalidIncomingPaymentsTable.payment
    var timestampMs by TalerInvalidIncomingPaymentsTable.timestampMs
    // FIXME:  This should probably not be called refunded, and
    // we should have a foreign key to the payment that sends the
    // money back.
    var refunded by TalerInvalidIncomingPaymentsTable.refunded
}

object AnastasisIncomingPaymentsTable: LongIdTable() {
    val payment = reference("payment", NexusBankTransactionsTable)
    val subject = text("subject")
    val timestampMs = long("timestampMs")
    val debtorPaytoUri = text("incomingPaytoUri")
}

class AnastasisIncomingPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<AnastasisIncomingPaymentEntity>(AnastasisIncomingPaymentsTable)

    var payment by NexusBankTransactionEntity referencedOn AnastasisIncomingPaymentsTable.payment
    var subject by AnastasisIncomingPaymentsTable.subject
    var timestampMs by AnastasisIncomingPaymentsTable.timestampMs
    var debtorPaytoUri by AnastasisIncomingPaymentsTable.debtorPaytoUri
}

/**
 * This is the table of the incoming payments.  Entries are merely "pointers" to the
 * entries from the raw payments table.
 */
object TalerIncomingPaymentsTable : LongIdTable() {
    val payment = reference("payment", NexusBankTransactionsTable)
    val reservePublicKey = text("reservePublicKey")
    val timestampMs = long("timestampMs")
    val debtorPaytoUri = text("incomingPaytoUri")
}

class TalerIncomingPaymentEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TalerIncomingPaymentEntity>(TalerIncomingPaymentsTable)

    var payment by NexusBankTransactionEntity referencedOn TalerIncomingPaymentsTable.payment
    var reservePublicKey by TalerIncomingPaymentsTable.reservePublicKey
    var timestampMs by TalerIncomingPaymentsTable.timestampMs
    var debtorPaytoUri by TalerIncomingPaymentsTable.debtorPaytoUri
}

/**
 * This table logs all the balances as returned by the bank for all the bank accounts.
 */
object NexusBankBalancesTable : LongIdTable() {
    /**
     * Balance mentioned in the bank message referenced below.  NOTE: this is the
     * CLOSING balance (a.k.a. CLBD), namely the one obtained by adding the transactions
     * reported in the bank message to the _previous_ CLBD.
     */
    val balance = text("balance") // $currency:x.y
    val creditDebitIndicator = text("creditDebitIndicator") // CRDT or DBIT.
    val bankAccount = reference("bankAccount", NexusBankAccountsTable)
    val date = text("date") // in the YYYY-MM-DD format
}

class NexusBankBalanceEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusBankBalanceEntity>(NexusBankBalancesTable)
    var balance by NexusBankBalancesTable.balance
    var creditDebitIndicator by NexusBankBalancesTable.creditDebitIndicator
    var bankAccount by NexusBankAccountEntity referencedOn NexusBankBalancesTable.bankAccount
    var date by NexusBankBalancesTable.date
}

// This table holds the data to talk to Sandbox
// via the x-libeufin-bank protocol supplier.
object XLibeufinBankUsersTable : LongIdTable() {
    val username = text("username")
    val password = text("password")
    val baseUrl = text("baseUrl")
    val nexusBankConnection = reference("nexusBankConnection", NexusBankConnectionsTable)
}

class XLibeufinBankUserEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<XLibeufinBankUserEntity>(XLibeufinBankUsersTable)
    var username by XLibeufinBankUsersTable.username
    var password by XLibeufinBankUsersTable.password
    var baseUrl by XLibeufinBankUsersTable.baseUrl
    var nexusBankConnection by NexusBankConnectionEntity referencedOn XLibeufinBankUsersTable.nexusBankConnection
}

/**
 * Table that stores all messages we receive from the bank.
 * The nullable fields were introduced along the x-libeufin-bank
 * connection, as those messages are plain JSON object unlike
 * the more structured CaMt.
 */
object NexusBankMessagesTable : LongIdTable() {
    val bankConnection = reference("bankConnection", NexusBankConnectionsTable)
    val message = blob("message")
    val messageId = text("messageId").nullable()
    val fetchLevel = enumerationByName("fetchLevel", 16, FetchLevel::class)
    // true when the parser could not ingest one message:
    val errors = bool("errors").default(false)
}

class NexusBankMessageEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusBankMessageEntity>(NexusBankMessagesTable)
    var bankConnection by NexusBankConnectionEntity referencedOn NexusBankMessagesTable.bankConnection
    var messageId by NexusBankMessagesTable.messageId
    var fetchLevel by NexusBankMessagesTable.fetchLevel
    var message by NexusBankMessagesTable.message
    var errors by NexusBankMessagesTable.errors
}

/**
 * This table contains history "elements" as returned by the bank from a
 * CAMT message.
 */
object NexusBankTransactionsTable : LongIdTable() {
    /**
     * Identifier for the transaction that is unique among all transactions of the account.
     * The scheme for this identifier is the accounts transaction identification scheme.
     *
     * Note that this is *not* a unique ID per account, as the same underlying
     * transaction can show up multiple times with a different status.
     */
    val accountTransactionId = text("accountTransactionId")
    val bankAccount = reference("bankAccount", NexusBankAccountsTable)
    val creditDebitIndicator = text("creditDebitIndicator")
    val currency = text("currency")
    val amount = text("amount")
    val status = enumerationByName("status", 16, EntryStatus::class)
    // Another, later transaction that updates the status of the current transaction.
    val updatedBy = optReference("updatedBy", NexusBankTransactionsTable)
    val transactionJson = text("transactionJson")
}

class NexusBankTransactionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusBankTransactionEntity>(NexusBankTransactionsTable) {
        override fun new(init: NexusBankTransactionEntity.() -> Unit): NexusBankTransactionEntity {
            val ret = super.new(init)
            if (isPostgres()) {
                val channelName = buildChannelName(
                    NotificationsChannelDomains.LIBEUFIN_NEXUS_TX,
                    ret.bankAccount.bankAccountName
                )
                TransactionManager.current().postgresNotify(channelName, ret.creditDebitIndicator)
            }
            return ret
        }
    }
    var currency by NexusBankTransactionsTable.currency
    var amount by NexusBankTransactionsTable.amount
    var status by NexusBankTransactionsTable.status
    var creditDebitIndicator by NexusBankTransactionsTable.creditDebitIndicator
    var bankAccount by NexusBankAccountEntity referencedOn NexusBankTransactionsTable.bankAccount
    var transactionJson by NexusBankTransactionsTable.transactionJson
    var accountTransactionId by NexusBankTransactionsTable.accountTransactionId
    val updatedBy by NexusBankTransactionEntity optionalReferencedOn NexusBankTransactionsTable.updatedBy

    /**
     * It is responsibility of the caller to insert only valid
     * JSON into the database, and therefore provide error management
     * when calling the two helpers below.
     */

    inline fun <reified T> parseDetailsIntoObject(): T {
        val mapper = jacksonObjectMapper()
        return mapper.readValue(this.transactionJson, T::class.java)
    }
    fun parseDetailsIntoObject(): JsonNode {
        val mapper = jacksonObjectMapper()
        return mapper.readTree(this.transactionJson)
    }
}

// Represents a prepared payment.
object PaymentInitiationsTable : LongIdTable() {
    /**
     * Bank account that wants to initiate the payment.
     */
    val bankAccount = reference("bankAccount", NexusBankAccountsTable)
    val preparationDate = long("preparationDate")
    val submissionDate = long("submissionDate").nullable()
    val sum = text("sum") // the amount to transfer.
    val currency = text("currency")
    val endToEndId = text("endToEndId")
    val paymentInformationId = text("paymentInformationId")
    val instructionId = text("instructionId")
    val subject = text("subject")
    val creditorIban = text("creditorIban")
    val creditorBic = text("creditorBic").nullable()
    val creditorName = text("creditorName")
    val submitted = bool("submitted").default(false)
    var invalid = bool("invalid").nullable()
    val messageId = text("messageId")
    /**
     * Points at the raw transaction witnessing that this
     * initiated payment was successfully performed.
     */
    val confirmationTransaction = reference("rawConfirmation", NexusBankTransactionsTable).nullable()
}

class PaymentInitiationEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<PaymentInitiationEntity>(PaymentInitiationsTable)

    var bankAccount by NexusBankAccountEntity referencedOn PaymentInitiationsTable.bankAccount
    var preparationDate by PaymentInitiationsTable.preparationDate
    var submissionDate by PaymentInitiationsTable.submissionDate
    var sum by PaymentInitiationsTable.sum
    var currency by PaymentInitiationsTable.currency
    var endToEndId by PaymentInitiationsTable.endToEndId
    var subject by PaymentInitiationsTable.subject
    var creditorIban by PaymentInitiationsTable.creditorIban
    var creditorBic by PaymentInitiationsTable.creditorBic
    var creditorName by PaymentInitiationsTable.creditorName
    var submitted by PaymentInitiationsTable.submitted
    var invalid by PaymentInitiationsTable.invalid
    var paymentInformationId by PaymentInitiationsTable.paymentInformationId
    var instructionId by PaymentInitiationsTable.instructionId
    var messageId by PaymentInitiationsTable.messageId
    var confirmationTransaction by NexusBankTransactionEntity optionalReferencedOn PaymentInitiationsTable.confirmationTransaction
}

/**
 * This table contains the bank accounts that are offered by the bank.
 * The bank account label (as assigned by the bank) is the primary key.
 */
object OfferedBankAccountsTable : LongIdTable() {
    val offeredAccountId = text("offeredAccountId")
    val bankConnection = reference("bankConnection", NexusBankConnectionsTable)
    val iban = text("iban")
    val bankCode = text("bankCode")
    val accountHolder = text("holderName")

    // column below gets defined only WHEN the user imports the bank account.
    val imported = reference("imported", NexusBankAccountsTable).nullable()

    init {
        uniqueIndex(offeredAccountId, bankConnection)
    }
}

class OfferedBankAccountEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<OfferedBankAccountEntity>(OfferedBankAccountsTable)

    var offeredAccountId by OfferedBankAccountsTable.offeredAccountId
    var bankConnection by NexusBankConnectionEntity referencedOn OfferedBankAccountsTable.bankConnection
    var accountHolder by OfferedBankAccountsTable.accountHolder
    var iban by OfferedBankAccountsTable.iban
    var bankCode by OfferedBankAccountsTable.bankCode
    var imported by NexusBankAccountEntity optionalReferencedOn  OfferedBankAccountsTable.imported
}

/**
 * This table holds triples of <iban, bic, holder name>.
 * FIXME(dold):  Allow other account and bank identifications than IBAN and BIC
 */
object NexusBankAccountsTable : LongIdTable() {
    val bankAccountName = text("bankAccountId").uniqueIndex()
    val accountHolder = text("accountHolder")
    val iban = text("iban")
    val bankCode = text("bankCode")
    val defaultBankConnection = reference("defaultBankConnection", NexusBankConnectionsTable).nullable()
    val lastStatementCreationTimestamp = long("lastStatementCreationTimestamp").nullable()
    val lastReportCreationTimestamp = long("lastReportCreationTimestamp").nullable()
    val lastNotificationCreationTimestamp = long("lastNotificationCreationTimestamp").nullable()
    // Highest bank message ID that this bank account is aware of.
    val highestSeenBankMessageSerialId = long("highestSeenBankMessageSerialId")
    val pain001Counter = long("pain001counter").default(1)
}

class NexusBankAccountEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusBankAccountEntity>(NexusBankAccountsTable) {
        fun findByName(name: String): NexusBankAccountEntity? {
            return find { NexusBankAccountsTable.bankAccountName eq name }.firstOrNull()
        }
    }

    var bankAccountName by NexusBankAccountsTable.bankAccountName
    var accountHolder by NexusBankAccountsTable.accountHolder
    var iban by NexusBankAccountsTable.iban
    var bankCode by NexusBankAccountsTable.bankCode
    var defaultBankConnection by NexusBankConnectionEntity optionalReferencedOn NexusBankAccountsTable.defaultBankConnection
    var highestSeenBankMessageSerialId by NexusBankAccountsTable.highestSeenBankMessageSerialId
    var pain001Counter by NexusBankAccountsTable.pain001Counter
    var lastStatementCreationTimestamp by NexusBankAccountsTable.lastStatementCreationTimestamp
    var lastReportCreationTimestamp by NexusBankAccountsTable.lastReportCreationTimestamp
    var lastNotificationCreationTimestamp by NexusBankAccountsTable.lastNotificationCreationTimestamp
}

object NexusEbicsSubscribersTable : LongIdTable() {
    val ebicsURL = text("ebicsURL")
    val hostID = text("hostID")
    val partnerID = text("partnerID")
    val userID = text("userID")
    val systemID = text("systemID").nullable()
    val signaturePrivateKey = blob("signaturePrivateKey")
    val encryptionPrivateKey = blob("encryptionPrivateKey")
    val authenticationPrivateKey = blob("authenticationPrivateKey")
    val bankEncryptionPublicKey = blob("bankEncryptionPublicKey").nullable()
    val bankAuthenticationPublicKey = blob("bankAuthenticationPublicKey").nullable()
    val nexusBankConnection = reference("nexusBankConnection", NexusBankConnectionsTable)
    val ebicsIniState = enumerationByName("ebicsIniState", 16, EbicsInitState::class)
    val ebicsHiaState = enumerationByName("ebicsHiaState", 16, EbicsInitState::class)
}

class EbicsSubscriberEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<EbicsSubscriberEntity>(NexusEbicsSubscribersTable)

    var ebicsURL by NexusEbicsSubscribersTable.ebicsURL
    var hostID by NexusEbicsSubscribersTable.hostID
    var partnerID by NexusEbicsSubscribersTable.partnerID
    var userID by NexusEbicsSubscribersTable.userID
    var systemID by NexusEbicsSubscribersTable.systemID
    var signaturePrivateKey by NexusEbicsSubscribersTable.signaturePrivateKey
    var encryptionPrivateKey by NexusEbicsSubscribersTable.encryptionPrivateKey
    var authenticationPrivateKey by NexusEbicsSubscribersTable.authenticationPrivateKey
    var bankEncryptionPublicKey by NexusEbicsSubscribersTable.bankEncryptionPublicKey
    var bankAuthenticationPublicKey by NexusEbicsSubscribersTable.bankAuthenticationPublicKey
    var nexusBankConnection by NexusBankConnectionEntity referencedOn NexusEbicsSubscribersTable.nexusBankConnection
    var ebicsIniState by NexusEbicsSubscribersTable.ebicsIniState
    var ebicsHiaState by NexusEbicsSubscribersTable.ebicsHiaState
}

object NexusUsersTable : LongIdTable() {
    val username = text("username")
    val passwordHash = text("password")
    val superuser = bool("superuser")
}

class NexusUserEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusUserEntity>(NexusUsersTable)

    var username by NexusUsersTable.username
    var passwordHash by NexusUsersTable.passwordHash
    var superuser by NexusUsersTable.superuser
}

object NexusBankConnectionsTable : LongIdTable() {
    val connectionId = text("connectionId")
    val type = text("type")
    val dialect = text("dialect").nullable()
    val owner = reference("user", NexusUsersTable)
}

class NexusBankConnectionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusBankConnectionEntity>(NexusBankConnectionsTable) {
        fun findByName(name: String): NexusBankConnectionEntity? {
            return find { NexusBankConnectionsTable.connectionId eq name }.firstOrNull()
        }
    }

    var connectionId by NexusBankConnectionsTable.connectionId
    var type by NexusBankConnectionsTable.type
    var dialect by NexusBankConnectionsTable.dialect
    var owner by NexusUserEntity referencedOn NexusBankConnectionsTable.owner
}

object FacadesTable : LongIdTable() {
    val facadeName = text("facadeName")
    val type = text("type")
    val creator = reference("creator", NexusUsersTable)
    init { uniqueIndex(facadeName) }
}

class FacadeEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<FacadeEntity>(FacadesTable) {
        fun findByName(name: String): FacadeEntity? {
            return find { FacadesTable.facadeName eq name}.firstOrNull()
        }
    }
    var facadeName by FacadesTable.facadeName
    var type by FacadesTable.type
    var creator by NexusUserEntity referencedOn FacadesTable.creator
}

object FacadeStateTable : LongIdTable() {
    val bankAccount = text("bankAccount")
    val bankConnection = text("bankConnection")
    val currency = text("currency")

    // "statement", "report", "notification"
    val reserveTransferLevel = text("reserveTransferLevel")
    val facade = reference("facade", FacadesTable, onDelete = ReferenceOption.CASCADE)

    /**
     * Highest ID seen in the raw transactions table.
     */
    val highestSeenMsgSerialId = long("highestSeenMessageSerialId").default(0)
}

class FacadeStateEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<FacadeStateEntity>(FacadeStateTable)

    var bankAccount by FacadeStateTable.bankAccount
    var bankConnection by FacadeStateTable.bankConnection
    var currency by FacadeStateTable.currency

    /**
     *  "statement", "report", "notification"
     */
    var reserveTransferLevel by FacadeStateTable.reserveTransferLevel
    var facade by FacadeEntity referencedOn FacadeStateTable.facade
    var highestSeenMessageSerialId by FacadeStateTable.highestSeenMsgSerialId
}

object NexusScheduledTasksTable : LongIdTable() {
    val resourceType = text("resourceType")
    val resourceId = text("resourceId")
    val taskName = text("taskName")
    val taskType = text("taskType")
    val taskCronspec = text("taskCronspec")
    val taskParams = text("taskParams")
    val nextScheduledExecutionSec = long("nextScheduledExecutionSec").nullable()
    val prevScheduledExecutionSec = long("lastScheduledExecutionSec").nullable()
}

class NexusScheduledTaskEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusScheduledTaskEntity>(NexusScheduledTasksTable)

    var resourceType by NexusScheduledTasksTable.resourceType
    var resourceId by NexusScheduledTasksTable.resourceId
    var taskName by NexusScheduledTasksTable.taskName
    var taskType by NexusScheduledTasksTable.taskType
    var taskCronspec by NexusScheduledTasksTable.taskCronspec
    var taskParams by NexusScheduledTasksTable.taskParams
    var nextScheduledExecutionSec by NexusScheduledTasksTable.nextScheduledExecutionSec
    var prevScheduledExecutionSec by NexusScheduledTasksTable.prevScheduledExecutionSec
}

/**
 * Generic permissions table that determines access of a subject
 * identified by (subjectType, subjectName) to a resource (resourceType, resourceId).
 *
 * Subjects are typically of type "user", but this may change in the future.
 */
object NexusPermissionsTable : LongIdTable() {
    val resourceType = text("resourceType")
    val resourceId = text("resourceId")
    val subjectType = text("subjectType")
    val subjectId = text("subjectName")
    val permissionName = text("permissionName")

    init { uniqueIndex(resourceType, resourceId, subjectType, subjectId, permissionName) }
}

class NexusPermissionEntity(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<NexusPermissionEntity>(NexusPermissionsTable)

    var resourceType by NexusPermissionsTable.resourceType
    var resourceId by NexusPermissionsTable.resourceId
    var subjectType by NexusPermissionsTable.subjectType
    var subjectId by NexusPermissionsTable.subjectId
    var permissionName by NexusPermissionsTable.permissionName
}

fun dbDropTables(dbConnectionString: String) {
    connectWithSchema(dbConnectionString)
    if (isPostgres()) {
        val ret = execCommand(
            listOf(
                "libeufin-load-sql",
                "-d",
                getDatabaseName(),
                "-s",
                "nexus",
                "-r"
            ),
            throwIfFails = false
        )
        if (ret != 0)
            logger.warn("Dropping the nexus tables failed.  Was the DB filled before?")
        return
    }
    transaction {
        SchemaUtils.drop(
            NexusUsersTable,
            XLibeufinBankUsersTable,
            PaymentInitiationsTable,
            NexusEbicsSubscribersTable,
            NexusBankAccountsTable,
            NexusBankTransactionsTable,
            TalerIncomingPaymentsTable,
            TalerRequestedPaymentsTable,
            TalerInvalidIncomingPaymentsTable,
            NexusBankConnectionsTable,
            NexusBankMessagesTable,
            NexusBankBalancesTable,
            FacadesTable,
            FacadeStateTable,
            NexusScheduledTasksTable,
            OfferedBankAccountsTable,
            NexusPermissionsTable,
            AnastasisIncomingPaymentsTable
        )
    }
}

fun dbCreateTables(dbConnectionString: String) {
    connectWithSchema(dbConnectionString)
    if (isPostgres()) {
        execCommand(listOf(
            "libeufin-load-sql",
            "-d",
            getDatabaseName(),
            "-s",
            "nexus"
        ))
        return
    }
    // Still using the legacy way for other DBMSs, like SQLite.
    transaction {
        SchemaUtils.create(
            XLibeufinBankUsersTable,
            NexusScheduledTasksTable,
            NexusUsersTable,
            PaymentInitiationsTable,
            NexusEbicsSubscribersTable,
            NexusBankAccountsTable,
            NexusBankBalancesTable,
            NexusBankTransactionsTable,
            AnastasisIncomingPaymentsTable,
            TalerIncomingPaymentsTable,
            TalerRequestedPaymentsTable,
            FacadeStateTable,
            TalerInvalidIncomingPaymentsTable,
            NexusBankConnectionsTable,
            NexusBankMessagesTable,
            FacadesTable,
            OfferedBankAccountsTable,
            NexusPermissionsTable
        )
    }
}