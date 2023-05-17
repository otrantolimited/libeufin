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

package tech.libeufin.nexus.server

import CamtBankAccountEntry
import EntryStatus
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.databind.JsonNode
import tech.libeufin.util.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField


data class BackupRequestJson(
    val passphrase: String
)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "paramType"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = EbicsStandardOrderParamsDateJson::class, name = "standard-date-range"),
    JsonSubTypes.Type(value = EbicsStandardOrderParamsEmptyJson::class, name = "standard-empty"),
    JsonSubTypes.Type(value = EbicsGenericOrderParamsJson::class, name = "generic")
)
abstract class EbicsOrderParamsJson {
    abstract fun toOrderParams(): EbicsOrderParams
}

@JsonTypeName("generic")
class EbicsGenericOrderParamsJson(
    val params: Map<String, String>
) : EbicsOrderParamsJson() {
    override fun toOrderParams(): EbicsOrderParams {
        return EbicsGenericOrderParams(params)
    }
}

@JsonTypeName("standard-empty")
class EbicsStandardOrderParamsEmptyJson : EbicsOrderParamsJson() {
    override fun toOrderParams(): EbicsOrderParams {
        return EbicsStandardOrderParams(null)
    }
}

object EbicsDateFormat {
    var fmt = DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_DATE)
        .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
        .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
        .parseDefaulting(ChronoField.OFFSET_SECONDS, ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds.toLong())
        .toFormatter()!!
}

@JsonTypeName("standard-date-range")
class EbicsStandardOrderParamsDateJson(
    private val start: String,
    private val end: String
) : EbicsOrderParamsJson() {
    override fun toOrderParams(): EbicsOrderParams {
        val dateRange =
            EbicsDateRange(
                ZonedDateTime.parse(this.start, EbicsDateFormat.fmt),
                ZonedDateTime.parse(this.end, EbicsDateFormat.fmt)
            )
        return EbicsStandardOrderParams(dateRange)
    }
}

data class NexusErrorDetailJson(
    val type: String,
    val description: String
)
data class NexusErrorJson(
    val error: NexusErrorDetailJson
)
data class NexusMessage(
    val message: String
)

data class ErrorResponse(
    val code: Int,
    val hint: String,
    val detail: String,
)

data class BankConnectionInfo(
    val name: String,
    val type: String
)

data class BankConnectionsList(
    val bankConnections: MutableList<BankConnectionInfo> = mutableListOf()
)

data class BankConnectionDeletion(
    val bankConnectionId: String
)

data class EbicsHostTestRequest(
    val ebicsBaseUrl: String,
    val ebicsHostId: String
)

/**
 * This object is used twice: as a response to the backup request,
 * and as a request to the backup restore.  Note: in the second case
 * the client must provide the passphrase.
 */
data class EbicsKeysBackupJson(
    // Always "ebics"
    val type: String,
    val userID: String,
    val partnerID: String,
    val hostID: String,
    val ebicsURL: String,
    val authBlob: String,
    val encBlob: String,
    val sigBlob: String,
    val bankAuthBlob: String?,
    val bankEncBlob: String?,
    val dialect: String? = null
)

enum class PermissionChangeAction(@get:JsonValue val jsonName: String) {
    GRANT("grant"), REVOKE("revoke")
}

data class Permission(
    val subjectType: String,
    val subjectId: String,
    val resourceType: String,
    val resourceId: String,
    val permissionName: String
)

data class PermissionQuery(
    val resourceType: String,
    val resourceId: String,
    val permissionName: String,
)

data class ChangePermissionsRequest(
    val action: PermissionChangeAction,
    val permission: Permission
)

enum class FetchLevel(@get:JsonValue val jsonName: String) {
    REPORT("report"),
    STATEMENT("statement"),
    NOTIFICATION("notification"),
    ALL("all");
}

/**
 * Instructions on what range to fetch from the bank,
 * and which source(s) to use.
 *
 * Intended to be convenient to specify.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "rangeType"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = FetchSpecLatestJson::class, name = "latest"),
    JsonSubTypes.Type(value = FetchSpecAllJson::class, name = "all"),
    JsonSubTypes.Type(value = FetchSpecPreviousDaysJson::class, name = "previous-days"),
    JsonSubTypes.Type(value = FetchSpecSinceLastJson::class, name = "since-last")
)
abstract class FetchSpecJson(
    val level: FetchLevel,
    val bankConnection: String?
)

@JsonTypeName("latest")
class FetchSpecLatestJson(level: FetchLevel, bankConnection: String?) : FetchSpecJson(level, bankConnection)

@JsonTypeName("all")
class FetchSpecAllJson(level: FetchLevel, bankConnection: String?) : FetchSpecJson(level, bankConnection)

@JsonTypeName("since-last")
class FetchSpecSinceLastJson(level: FetchLevel, bankConnection: String?) : FetchSpecJson(level, bankConnection)

@JsonTypeName("previous-days")
class FetchSpecPreviousDaysJson(level: FetchLevel, bankConnection: String?, val number: Int) :
    FetchSpecJson(level, bankConnection)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "source"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = CreateBankConnectionFromBackupRequestJson::class, name = "backup"),
    JsonSubTypes.Type(value = CreateBankConnectionFromNewRequestJson::class, name = "new")
)
abstract class CreateBankConnectionRequestJson(
    val name: String
)

@JsonTypeName("backup")
class CreateBankConnectionFromBackupRequestJson(
    name: String,
    val passphrase: String?,
    val data: JsonNode
) : CreateBankConnectionRequestJson(name)

@JsonTypeName("new")
class CreateBankConnectionFromNewRequestJson(
    name: String,
    val type: String,
    val dialect: String? = null,
    val data: JsonNode
) : CreateBankConnectionRequestJson(name)

data class EbicsNewTransport(
    val userID: String,
    val partnerID: String,
    val hostID: String,
    val ebicsURL: String,
    val systemID: String?,
    val dialect: String? = null
)

/**
 * Credentials and URL to access Sandbox and talk JSON to it.
 * See https://docs.taler.net/design-documents/038-demobanks-protocol-suppliers.html#static-x-libeufin-bank-with-dynamic-demobank
 * for an introduction on x-libeufin-bank.
 */
data class XLibeufinBankTransport(
    val username: String,
    val password: String,
    val baseUrl: String
)

/** Response type of "GET /prepared-payments/{uuid}" */
data class PaymentStatus(
    val paymentInitiationId: String,
    val submitted: Boolean,
    val creditorIban: String,
    val creditorBic: String?,
    val creditorName: String,
    val amount: String,
    val subject: String,
    val submissionDate: String?,
    val preparationDate: String,
    val status: EntryStatus?
)

data class Transactions(
    val transactions: MutableList<CamtBankAccountEntry> = mutableListOf()
)

data class BankProtocolsResponse(
    val protocols: List<String>
)

/** Request type of "POST /prepared-payments" */
data class CreatePaymentInitiationRequest(
    val iban: String,
    val bic: String,
    val name: String,
    val amount: String,
    val subject: String,
    // When it's null, the client doesn't expect/need idempotence.
    val uid: String? = null
)

/** Response type of "POST /prepared-payments" */
data class PaymentInitiationResponse(
    val uuid: String
)

/** Response type of "GET /user" */
data class UserResponse(
    val username: String,
    val superuser: Boolean,
)

/** Request type of "POST /users" */
data class CreateUserRequest(
    val username: String,
    val password: String
)

data class ChangeUserPassword(
    val newPassword: String
)

data class UserInfo(
    val username: String,
    val superuser: Boolean
)

data class UsersResponse(
    val users: List<UserInfo>
)

/** Response (list's element) type of "GET /bank-accounts" */
data class BankAccount(
    var ownerName: String,
    var iban: String,
    var bic: String,
    var nexusBankAccountId: String
)

data class OfferedBankAccount(
    var ownerName: String,
    var iban: String,
    var bic: String,
    var offeredAccountId: String,
    var nexusBankAccountId: String?
)

data class OfferedBankAccounts(
    val accounts: MutableList<OfferedBankAccount> = mutableListOf()
)

/** Response type of "GET /bank-accounts" */
data class BankAccounts(
    var accounts: MutableList<BankAccount> = mutableListOf()
)

data class BankMessageList(
    val bankMessages: MutableList<BankMessageInfo> = mutableListOf()
)

data class BankMessageInfo(
    // x-libeufin-bank messages do not have any ID or code.
    val messageId: String?,
    val code: String?,
    val length: Long
)

data class FacadeShowInfo(
    val name: String,
    val type: String,
    // Taler wire gateway API base URL.
    // Different from the base URL of the facade.
    val baseUrl: String,
    val config: JsonNode
)

data class FacadeInfo(
    val name: String,
    val type: String,
    val bankAccountsRead: MutableList<String>? = mutableListOf(),
    val bankAccountsWrite: MutableList<String>? = mutableListOf(),
    val bankConnectionsRead: MutableList<String>? = mutableListOf(),
    val bankConnectionsWrite: MutableList<String>? = mutableListOf(),
    val config: TalerWireGatewayFacadeConfig /* To be abstracted to Any! */
)

data class TalerWireGatewayFacadeConfig(
    val bankAccount: String,
    val bankConnection: String,
    val reserveTransferLevel: String,
    val currency: String
)

data class Pain001Data(
    val creditorIban: String,
    val creditorBic: String?,
    val creditorName: String,
    val sum: String,
    val currency: String,
    val subject: String,
    val endToEndId: String? = null
)

data class AccountTask(
    val resourceType: String,
    val resourceId: String,
    val taskName: String,
    val taskType: String,
    val taskCronspec: String,
    val taskParams: String,
    val nextScheduledExecutionSec: Long?, // human-readable time (= Epoch when this value doesn't exist in DB)
    val prevScheduledExecutionSec: Long? // human-readable time (= Epoch when this value doesn't exist in DB)
)

data class CreateAccountTaskRequest(
    val name: String,
    val cronspec: String,
    val type: String,
    val params: JsonNode
)

data class ImportBankAccount(
    val offeredAccountId: String,
    val nexusBankAccountId: String
)

data class InitiatedPayments(
    val initiatedPayments: MutableList<PaymentStatus> = mutableListOf()
)
