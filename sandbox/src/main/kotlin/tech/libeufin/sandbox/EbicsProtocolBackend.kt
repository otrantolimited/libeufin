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


package tech.libeufin.sandbox

import io.ktor.application.ApplicationCall
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.util.AttributeKey
import org.apache.xml.security.binding.xmldsig.RSAKeyValueType
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import tech.libeufin.util.*
import tech.libeufin.util.XMLUtil.Companion.signEbicsResponse
import tech.libeufin.util.ebics_h004.*
import tech.libeufin.util.ebics_hev.HEVResponse
import tech.libeufin.util.ebics_hev.SystemReturnCodeType
import tech.libeufin.util.ebics_s001.SignatureTypes
import tech.libeufin.util.ebics_s001.UserSignatureData
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.*
import java.util.zip.DeflaterInputStream
import java.util.zip.InflaterInputStream

val EbicsHostIdAttribute = AttributeKey<String>("RequestedEbicsHostID")

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.sandbox")

data class PainParseResult(
    val creditorIban: String,
    val creditorName: String,
    val creditorBic: String?,
    val debtorIban: String,
    val debtorName: String,
    val debtorBic: String?,
    val subject: String,
    val amount: Amount,
    val currency: String,
    val pmtInfId: String,
    val msgId: String
)

open class EbicsRequestError(
    val errorText: String,
    val errorCode: String
) : Exception("EBICS request  error: $errorText ($errorCode)")

class EbicsInvalidRequestError : EbicsRequestError(
    "[EBICS_INVALID_REQUEST] Invalid request",
    "060102"
)

/**
 * This error is thrown whenever the Subscriber's state is not suitable
 * for the requested action.  For example, the subscriber sends a EbicsRequest
 * message without having first uploaded their keys (#5973).
 */
class EbicsSubscriberStateError : EbicsRequestError(
    "[EBICS_INVALID_USER_OR_USER_STATE] Subscriber unknown or subscriber state inadmissible",
    "091002"
)

open class EbicsKeyManagementError(val errorText: String, val errorCode: String) :
    Exception("EBICS key management error: $errorText ($errorCode)")

private class EbicsInvalidXmlError : EbicsKeyManagementError(
    "[EBICS_INVALID_XML]",
    "091010"
)

private class EbicsInvalidOrderType : EbicsRequestError(
    "[EBICS_UNSUPPORTED_ORDER_TYPE] Order type not supported",
    "091005"
)

private suspend fun ApplicationCall.respondEbicsKeyManagement(
    errorText: String,
    errorCode: String,
    bankReturnCode: String,
    dataTransfer: CryptoUtil.EncryptionResult? = null,
    orderId: String? = null
) {
    val responseXml = EbicsKeyManagementResponse().apply {
        version = "H004"
        header = EbicsKeyManagementResponse.Header().apply {
            authenticate = true
            mutable = EbicsKeyManagementResponse.MutableHeaderType().apply {
                reportText = errorText
                returnCode = errorCode
                if (orderId != null) {
                    this.orderID = orderId
                }
            }
            _static = EbicsKeyManagementResponse.EmptyStaticHeader()
        }
        body = EbicsKeyManagementResponse.Body().apply {
            this.returnCode = EbicsKeyManagementResponse.ReturnCode().apply {
                this.authenticate = true
                this.value = bankReturnCode
            }
            if (dataTransfer != null) {
                this.dataTransfer = EbicsKeyManagementResponse.DataTransfer().apply {
                    this.dataEncryptionInfo = EbicsTypes.DataEncryptionInfo().apply {
                        this.authenticate = true
                        this.transactionKey = dataTransfer.encryptedTransactionKey
                        this.encryptionPubKeyDigest = EbicsTypes.PubKeyDigest().apply {
                            this.algorithm = "http://www.w3.org/2001/04/xmlenc#sha256"
                            this.version = "E002"
                            this.value = dataTransfer.pubKeyDigest
                        }
                    }
                    this.orderData = EbicsKeyManagementResponse.OrderData().apply {
                        this.value = Base64.getEncoder().encodeToString(dataTransfer.encryptedData)
                    }
                }
            }
        }
    }
    val text = XMLUtil.convertJaxbToString(responseXml)
    logger.info("responding with:\n${text}")
    if (!XMLUtil.validateFromString(text)) throw SandboxError(
        HttpStatusCode.InternalServerError,
        "Outgoint EBICS key management response is invalid"
    )
    respondText(text, ContentType.Application.Xml, HttpStatusCode.OK)
}

fun <T> expectNonNull(x: T?): T {
    if (x == null) {
        throw EbicsProtocolError(HttpStatusCode.BadRequest, "expected non-null value")
    }
    return x;
}

private fun getRelatedParty(branch: XmlElementBuilder, payment: RawPayment) {
    val otherParty = object {
        var ibanPath = "CdtrAcct/Id/IBAN"
        var namePath = "Cdtr/Nm"
        var iban = payment.creditorIban
        var name = payment.creditorName
        var bicPath = "CdtrAgt/FinInstnId/BIC"
        var bic = payment.creditorBic
    }
    if (payment.direction == "CRDT") {
        otherParty.iban = payment.debitorIban
        otherParty.ibanPath = "DbtrAcct/Id/IBAN"
        otherParty.namePath = "Dbtr/Nm"
        otherParty.name = payment.debitorName
        otherParty.bic = payment.debitorBic
        otherParty.bicPath = "DbtrAgt/FinInstnId/BIC"
    }
    branch.element("RltdPties") {
        element(otherParty.namePath) {
            text(otherParty.name)
        }
        element(otherParty.ibanPath) {
            text(otherParty.iban)
        }
    }
    val otherPartyBic = otherParty.bic
    if (otherPartyBic != null) {
        branch.element("RltdAgts") {
            element(otherParty.bicPath) {
                text(otherPartyBic)
            }
        }
    }
}

fun buildCamtString(type: Int, subscriberIban: String, history: List<RawPayment>): String {
    /**
     * ID types required:
     *
     * - Message Id
     * - Statement / Report Id
     * - Electronic sequence number
     * - Legal sequence number
     * - Entry Id by the Servicer
     * - Payment information Id
     * - Proprietary code of the bank transaction
     * - Id of the servicer (Issuer and Code)
     */
    val now = getNow()
    val dashedDate = now.toDashedDate()
    val zonedDateTime = now.toZonedString()
    return constructXml(indent = true) {
        root("Document") {
            attribute("xmlns", "urn:iso:std:iso:20022:tech:xsd:camt.0${type}.001.02")
            attribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
            attribute(
                "xsi:schemaLocation",
                "urn:iso:std:iso:20022:tech:xsd:camt.0${type}.001.02 camt.0${type}.001.02.xsd"
            )
            element("BkToCstmrStmt") {
                element("GrpHdr") {
                    element("MsgId") {
                        text("sandbox-${now.millis()}")
                    }
                    element("CreDtTm") {
                        text(zonedDateTime)
                    }
                    element("MsgPgntn") {
                        element("PgNb") {
                            text("001")
                        }
                        element("LastPgInd") {
                            text("true")
                        }
                    }
                }
                element(if (type == 52) "Rpt" else "Stmt") {
                    element("Id") {
                        text("0")
                    }
                    element("ElctrncSeqNb") {
                        text("0")
                    }
                    element("LglSeqNb") {
                        text("0")
                    }
                    element("CreDtTm") {
                        text(zonedDateTime)
                    }
                    element("Acct") {
                        // mandatory account identifier
                        element("Id/IBAN") {
                            text(subscriberIban)
                        }
                        element("Ccy") {
                            text("EUR")
                        }
                        element("Ownr/Nm") {
                            text("Debitor/Owner Name")
                        }
                        element("Svcr/FinInstnId") {
                            element("Nm") {
                                text("Libeufin Bank")
                            }
                            element("Othr") {
                                element("Id") {
                                    text("0")
                                }
                                element("Issr") {
                                    text("XY")
                                }
                            }
                        }
                    }
                    element("Bal") {
                        element("Tp/CdOrPrtry/Cd") {
                            /* Balance type, in a coded format.  PRCD stands
                               for "Previously closed booked" and shows the
                               balance at the time _before_ all the entries
                               reported in this document were posted to the
                               involved bank account.  */
                            text("PRCD")
                        }
                        element("Amt") {
                            attribute("Ccy", "EUR")
                            text(Amount(0).toPlainString())
                        }
                        element("CdtDbtInd") {
                            // a temporary value to get the camt to validate.
                            // Should be fixed along #6269
                            text("CRDT")
                        }
                        element("Dt/Dt") {
                            // date of this balance
                            text(dashedDate)
                        }
                    }
                    element("Bal") {
                        element("Tp/CdOrPrtry/Cd") {
                            /* CLBD stands for "Closing booked balance", and it
                               is calculated by summing the PRCD with all the
                               entries reported in this document */
                            text("CLBD")
                        }
                        element("Amt") {
                            attribute("Ccy", "EUR")
                            text(Amount(0).toPlainString())
                        }
                        element("CdtDbtInd") {
                            // a temporary value to get the camt to validate.
                            // Should be fixed along #6269
                            text("DBIT")
                        }
                        element("Dt/Dt") {
                            text(dashedDate)
                        }
                    }
                    history.forEach {
                        this.element("Ntry") {
                            element("Amt") {
                                attribute("Ccy", it.currency)
                                text(it.amount)
                            }
                            element("CdtDbtInd") {
                                text(
                                    if (subscriberIban.equals(it.creditorIban))
                                        "CRDT" else "DBIT"
                                )
                            }
                            element("Sts") {
                                /* Status of the entry (see 2.4.2.15.5 from the ISO20022 reference document.)
                                    * From the original text:
                                    * "Status of an entry on the books of the account servicer" */
                                text("BOOK")
                            }
                            element("BookgDt/Dt") {
                                text(dashedDate)
                            } // date of the booking
                            element("ValDt/Dt") {
                                text(dashedDate)
                            } // date of assets' actual (un)availability
                            element("AcctSvcrRef") {
                                val uid = if (it.uid != null) it.uid.toString() else {
                                    throw EbicsRequestError(
                                        errorCode = "091116",
                                        errorText = "EBICS_PROCESSING_ERROR"
                                    )
                                }
                                text(uid)
                            }
                            element("BkTxCd") {
                                /*  "Set of elements used to fully identify the type of underlying
                                 *   transaction resulting in an entry".  */
                                element("Domn") {
                                    element("Cd") {
                                        text("PMNT")
                                    }
                                    element("Fmly") {
                                        element("Cd") {
                                            text("ICDT")
                                        }
                                        element("SubFmlyCd") {
                                            text("ESCT")
                                        }
                                    }
                                }
                                element("Prtry") {
                                    element("Cd") {
                                        text("0")
                                    }
                                    element("Issr") {
                                        text("XY")
                                    }
                                }
                            }
                            element("NtryDtls/TxDtls") {
                                element("Refs") {
                                    element("MsgId") {
                                        text(it.msgId ?: "NOTPROVIDED")
                                    }
                                    element("PmtInfId") {
                                        text(it.pmtInfId ?: "NOTPROVIDED")
                                    }
                                    element("EndToEndId") {
                                        text("NOTPROVIDED")
                                    }
                                }
                                element("AmtDtls/TxAmt/Amt") {
                                    attribute("Ccy", "EUR")
                                    text(it.amount)
                                }
                                element("BkTxCd") {
                                    element("Domn") {
                                        element("Cd") {
                                            text("PMNT")
                                        }
                                        element("Fmly") {
                                            element("Cd") {
                                                text("ICDT")
                                            }
                                            element("SubFmlyCd") {
                                                text("ESCT")
                                            }
                                        }
                                    }
                                    element("Prtry") {
                                        element("Cd") {
                                            text("0")
                                        }
                                        element("Issr") {
                                            text("XY")
                                        }
                                    }
                                }
                                getRelatedParty(this, it)
                                element("RmtInf/Ustrd") {
                                    text(it.subject)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds CAMT response.
 *
 * @param type 52 or 53.
 */
private fun constructCamtResponse(type: Int, subscriber: EbicsSubscriberEntity): String {

    /**
     *  Currently unused: see #6243.
     *

    val dateRange = (header.static.orderDetails?.orderParams as EbicsRequest.StandardOrderParams).dateRange
    val (start: ZonedDateTime, end: ZonedDateTime) = if (dateRange != null) {
        Pair(
            importZonedDateFromMillis(dateRange.start.toGregorianCalendar().timeInMillis),
            importZonedDateFromMillis(dateRange.end.toGregorianCalendar().timeInMillis)
        )

    } else Pair(dashedDateToZonedDateTime("1970-01-01"), getNow())

     */
    
    val bankAccount = getBankAccountFromSubscriber(subscriber)
    logger.info("getting history for account with iban ${bankAccount.iban}")
    val history = historyForAccount(bankAccount.iban)
    return buildCamtString(type, bankAccount.iban, history)
}

/**
 * TSD (test download) message.
 *
 * This is a non-standard EBICS order type use by LibEuFin to
 * test download transactions.
 *
 * In the future, additional parameters (size, chunking, inject fault for retry) might
 * be added to the order parameters.
 */
private fun handleEbicsTSD(): ByteArray {
    return "Hello World\n".repeat(1024).toByteArray()
}

private fun handleEbicsPTK(): ByteArray {
    return "Hello I am a dummy PTK response.".toByteArray()
}

private fun parsePain001(paymentRequest: String): PainParseResult {
    val painDoc = XMLUtil.parseStringIntoDom(paymentRequest)
    return destructXml(painDoc) {
        requireRootElement("Document") {
            requireUniqueChildNamed("CstmrCdtTrfInitn") {
                val msgId = requireUniqueChildNamed("GrpHdr") {
                    requireUniqueChildNamed("MsgId") { focusElement.textContent }
                }
                requireUniqueChildNamed("PmtInf") {
                    val debtorName = requireUniqueChildNamed("Dbtr"){
                        requireUniqueChildNamed("Nm") {
                            focusElement.textContent
                        }
                    }
                    val debtorIban = requireUniqueChildNamed("DbtrAcct"){
                        requireUniqueChildNamed("Id") {
                            requireUniqueChildNamed("IBAN") {
                                focusElement.textContent
                            }
                        }
                    }
                    val debtorBic = requireUniqueChildNamed("DbtrAgt"){
                        requireUniqueChildNamed("FinInstnId") {
                            requireUniqueChildNamed("BIC") {
                                focusElement.textContent
                            }
                        }
                    }
                    val pmtInfId = requireUniqueChildNamed("PmtInfId") { focusElement.textContent }
                    val txDetails = requireUniqueChildNamed("CdtTrfTxInf") {
                        object {
                            val creditorIban = requireUniqueChildNamed("CdtrAcct") {
                                requireUniqueChildNamed("Id") {
                                    requireUniqueChildNamed("IBAN") { focusElement.textContent }
                                }
                            }
                            val creditorName = requireUniqueChildNamed("Cdtr") {
                                requireUniqueChildNamed("Nm") {
                                    focusElement.textContent
                                }
                            }
                            val creditorBic = maybeUniqueChildNamed("CdtrAgt") {
                                requireUniqueChildNamed("FinInstnId") {
                                    requireUniqueChildNamed("BIC") {
                                        focusElement.textContent
                                    }
                                }

                            }
                            val amt = requireUniqueChildNamed("Amt") {
                                requireOnlyChild { focusElement }
                            }
                            val subject = requireUniqueChildNamed("RmtInf") {
                                requireUniqueChildNamed("Ustrd") { focusElement.textContent }
                            }

                        }
                    }
                    PainParseResult(
                        currency = txDetails.amt.getAttribute("Ccy"),
                        amount = Amount(txDetails.amt.textContent),
                        subject = txDetails.subject,
                        debtorIban = debtorIban,
                        debtorName = debtorName,
                        debtorBic = debtorBic,
                        creditorName = txDetails.creditorName,
                        creditorIban = txDetails.creditorIban,
                        creditorBic = txDetails.creditorBic,
                        pmtInfId = pmtInfId,
                        msgId = msgId
                    )
                }
            }
        }
    }
}

/**
 * Process a payment request in the pain.001 format.
 */
private fun handleCct(paymentRequest: String) {
    logger.debug("Handling CCT")
    logger.debug("Pain.001: $paymentRequest")
    val parseResult = parsePain001(paymentRequest)
    transaction {
        try {
            BankAccountTransactionsTable.insert {
                it[account] = getBankAccountFromIban(parseResult.debtorIban).id
                it[creditorIban] = parseResult.creditorIban
                it[creditorName] = parseResult.creditorName
                it[creditorBic] = parseResult.creditorBic
                it[debtorIban] = parseResult.debtorIban
                it[debtorName] = parseResult.debtorName
                it[debtorBic] = parseResult.debtorBic
                it[subject] = parseResult.subject
                it[amount] = parseResult.amount.toString()
                it[currency] = parseResult.currency
                it[date] = getNow().millis()
                it[pmtInfId] = parseResult.pmtInfId
                it[accountServicerReference] = "sandboxref-getRandomString(16)"
                it[direction] = "DBIT"
            }
        } catch (e: ExposedSQLException) {
            logger.warn("Could not insert new payment into the database: ${e}")
            throw EbicsRequestError(
                "[EBICS_PROCESSING_ERROR] ${e.sqlState}",
                "091116"
            )
        }
    }
}

private fun handleEbicsC53(requestContext: RequestContext): ByteArray {
    logger.debug("Handling C53 request")
    val camt = constructCamtResponse(
        53,
        requestContext.subscriber
    )
   if (!XMLUtil.validateFromString(camt)) throw SandboxError(
            HttpStatusCode.InternalServerError,
            "CAMT document was generated invalid"
   )
    return listOf(camt.toByteArray(Charsets.UTF_8)).zip()
}

private suspend fun ApplicationCall.handleEbicsHia(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    val plainOrderData = InflaterInputStream(orderData.inputStream()).use {
        it.readAllBytes()
    }
    println("hia order data: ${plainOrderData.toString(Charsets.UTF_8)}")

    val keyObject = EbicsOrderUtil.decodeOrderDataXml<HIARequestOrderData>(orderData)
    val encPubXml = keyObject.encryptionPubKeyInfo.pubKeyValue.rsaKeyValue
    val authPubXml = keyObject.authenticationPubKeyInfo.pubKeyValue.rsaKeyValue
    val encPub = CryptoUtil.loadRsaPublicKeyFromComponents(encPubXml.modulus, encPubXml.exponent)
    val authPub = CryptoUtil.loadRsaPublicKeyFromComponents(authPubXml.modulus, authPubXml.exponent)

    val ok = transaction {
        val ebicsSubscriber = findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            logger.warn("ebics subscriber not found")
            throw EbicsInvalidRequestError()
        }
        when (ebicsSubscriber.state) {
            SubscriberState.NEW -> {}
            SubscriberState.PARTIALLY_INITIALIZED_INI -> {}
            SubscriberState.PARTIALLY_INITIALIZED_HIA, SubscriberState.INITIALIZED, SubscriberState.READY -> {
                return@transaction false
            }
        }

        ebicsSubscriber.authenticationKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = ExposedBlob(authPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.encryptionKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = ExposedBlob(encPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.state = when (ebicsSubscriber.state) {
            SubscriberState.NEW -> SubscriberState.PARTIALLY_INITIALIZED_HIA
            SubscriberState.PARTIALLY_INITIALIZED_INI -> SubscriberState.INITIALIZED
            else -> throw Exception("internal invariant failed")
        }
        return@transaction true
    }
    if (ok) {
        respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000")
    } else {
        respondEbicsKeyManagement("[EBICS_INVALID_USER_OR_USER_STATE]", "091002", "000000")
    }
}

private suspend fun ApplicationCall.handleEbicsIni(header: EbicsUnsecuredRequest.Header, orderData: ByteArray) {
    val plainOrderData = InflaterInputStream(orderData.inputStream()).use {
        it.readAllBytes()
    }
    println("ini order data: ${plainOrderData.toString(Charsets.UTF_8)}")

    val keyObject = EbicsOrderUtil.decodeOrderDataXml<SignatureTypes.SignaturePubKeyOrderData>(orderData)
    val sigPubXml = keyObject.signaturePubKeyInfo.pubKeyValue.rsaKeyValue
    val sigPub = CryptoUtil.loadRsaPublicKeyFromComponents(sigPubXml.modulus, sigPubXml.exponent)

    val ok = transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            logger.warn("ebics subscriber ('${header.static.partnerID}' / '${header.static.userID}' / '${header.static.systemID}') not found")
            throw EbicsInvalidRequestError()
        }
        when (ebicsSubscriber.state) {
            SubscriberState.NEW -> {}
            SubscriberState.PARTIALLY_INITIALIZED_HIA -> {}
            SubscriberState.PARTIALLY_INITIALIZED_INI, SubscriberState.INITIALIZED, SubscriberState.READY -> {
                return@transaction false
            }
        }
        ebicsSubscriber.signatureKey = EbicsSubscriberPublicKeyEntity.new {
            this.rsaPublicKey = ExposedBlob(sigPub.encoded)
            state = KeyState.NEW
        }
        ebicsSubscriber.state = when (ebicsSubscriber.state) {
            SubscriberState.NEW -> SubscriberState.PARTIALLY_INITIALIZED_INI
            SubscriberState.PARTIALLY_INITIALIZED_HIA -> SubscriberState.INITIALIZED
            else -> throw Error("internal invariant failed")
        }
        return@transaction true
    }
    logger.info("Signature key inserted in database _and_ subscriber state changed accordingly")
    if (ok) {
        respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000")
    } else {
        respondEbicsKeyManagement("[EBICS_INVALID_USER_OR_USER_STATE]", "091002", "000000")
    }
}

private suspend fun ApplicationCall.handleEbicsHpb(
    ebicsHostInfo: EbicsHostPublicInfo,
    requestDocument: Document,
    header: EbicsNpkdRequest.Header
) {
    val subscriberKeys = transaction {
        val ebicsSubscriber =
            findEbicsSubscriber(header.static.partnerID, header.static.userID, header.static.systemID)
        if (ebicsSubscriber == null) {
            throw EbicsInvalidRequestError()
        }
        if (ebicsSubscriber.state != SubscriberState.INITIALIZED) {
            throw EbicsSubscriberStateError()
        }
        val authPubBlob = ebicsSubscriber.authenticationKey!!.rsaPublicKey
        val encPubBlob = ebicsSubscriber.encryptionKey!!.rsaPublicKey
        val sigPubBlob = ebicsSubscriber.signatureKey!!.rsaPublicKey
        SubscriberKeys(
            CryptoUtil.loadRsaPublicKey(authPubBlob.bytes),
            CryptoUtil.loadRsaPublicKey(encPubBlob.bytes),
            CryptoUtil.loadRsaPublicKey(sigPubBlob.bytes)
        )
    }
    val validationResult =
        XMLUtil.verifyEbicsDocument(requestDocument, subscriberKeys.authenticationPublicKey)
    logger.info("validationResult: $validationResult")
    if (!validationResult) {
        throw EbicsKeyManagementError("invalid signature", "90000")
    }
    val hpbRespondeData = HPBResponseOrderData().apply {
        this.authenticationPubKeyInfo = EbicsTypes.AuthenticationPubKeyInfoType().apply {
            this.authenticationVersion = "X002"
            this.pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.authenticationPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.authenticationPublicKey.modulus.toByteArray()
                }
            }
        }
        this.encryptionPubKeyInfo = EbicsTypes.EncryptionPubKeyInfoType().apply {
            this.encryptionVersion = "E002"
            this.pubKeyValue = EbicsTypes.PubKeyValueType().apply {
                this.rsaKeyValue = RSAKeyValueType().apply {
                    this.exponent = ebicsHostInfo.encryptionPublicKey.publicExponent.toByteArray()
                    this.modulus = ebicsHostInfo.encryptionPublicKey.modulus.toByteArray()
                }
            }
        }
        this.hostID = ebicsHostInfo.hostID
    }

    val compressedOrderData = EbicsOrderUtil.encodeOrderDataXml(hpbRespondeData)

    val encryptionResult = CryptoUtil.encryptEbicsE002(compressedOrderData, subscriberKeys.encryptionPublicKey)

    respondEbicsKeyManagement("[EBICS_OK]", "000000", "000000", encryptionResult, "OR01")
}

/**
 * Find the ebics host corresponding to the one specified in the header.
 */
private fun ApplicationCall.ensureEbicsHost(requestHostID: String): EbicsHostPublicInfo {
    return transaction {
        val ebicsHost =
            EbicsHostEntity.find { EbicsHostsTable.hostID.upperCase() eq requestHostID.toUpperCase() }.firstOrNull()
        if (ebicsHost == null) {
            logger.warn("client requested unknown HostID ${requestHostID}")
            throw EbicsKeyManagementError("[EBICS_INVALID_HOST_ID]", "091011")
        }
        val encryptionPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.encryptionPrivateKey.bytes)
        val authenticationPrivateKey = CryptoUtil.loadRsaPrivateKey(ebicsHost.authenticationPrivateKey.bytes)
        EbicsHostPublicInfo(
            requestHostID,
            CryptoUtil.getRsaPublicFromPrivate(encryptionPrivateKey),
            CryptoUtil.getRsaPublicFromPrivate(authenticationPrivateKey)
        )
    }
}

private suspend fun ApplicationCall.receiveEbicsXml(): Document {
    val body: String = receiveText()
    logger.debug("Data received: $body")
    val requestDocument: Document? = XMLUtil.parseStringIntoDom(body)
    if (requestDocument == null || (!XMLUtil.validateFromDom(requestDocument))) {
        println("Problematic document was: $requestDocument")
        throw EbicsInvalidXmlError()
    }
    val requestedHostID = requestDocument.getElementsByTagName("HostID")
    this.attributes.put(
        EbicsHostIdAttribute,
        requestedHostID.item(0).textContent
    )
    return requestDocument
}

private fun makePartnerInfo(subscriber: EbicsSubscriberEntity): EbicsTypes.PartnerInfo {
    val bankAccount = getBankAccountFromSubscriber(subscriber)
    return EbicsTypes.PartnerInfo().apply {
        this.accountInfoList = listOf(
            EbicsTypes.AccountInfo().apply {
                this.id = bankAccount.label
                this.accountHolder = bankAccount.name
                this.accountNumberList = listOf(
                    EbicsTypes.GeneralAccountNumber().apply {
                        this.international = true
                        this.value = bankAccount.iban
                    }
                )
                this.currency = "EUR"
                this.description = "Ordinary Bank Account"
                this.bankCodeList = listOf(
                    EbicsTypes.GeneralBankCode().apply {
                        this.international = true
                        this.value = bankAccount.bic
                    }
                )
            }
        )
        this.addressInfo = EbicsTypes.AddressInfo().apply {
            this.name = "Address Info Object"
        }
        this.bankInfo = EbicsTypes.BankInfo().apply {
            this.hostID = subscriber.hostId
        }
        this.orderInfoList = listOf(
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "Transactions statement"
                this.orderType = "C53"
                this.transferType = "Download"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "Transactions report"
                this.orderType = "C52"
                this.transferType = "Download"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "Payment initiation (ZIPped payload)"
                this.orderType = "CCC"
                this.transferType = "Upload"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "Payment initiation (plain text payload)"
                this.orderType = "CCT"
                this.transferType = "Upload"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "vmk"
                this.orderType = "VMK"
                this.transferType = "Download"
            },
            EbicsTypes.AuthOrderInfoType().apply {
                this.description = "sta"
                this.orderType = "STA"
                this.transferType = "Download"
            }
        )
    }
}

private fun handleEbicsHtd(requestContext: RequestContext): ByteArray {
    val htd = HTDResponseOrderData().apply {
        this.partnerInfo = makePartnerInfo(requestContext.subscriber)
        this.userInfo = EbicsTypes.UserInfo().apply {
            this.name = "Some User"
            this.userID = EbicsTypes.UserIDType().apply {
                this.status = 5
                this.value = requestContext.subscriber.userId
            }
            this.permissionList = listOf(
                EbicsTypes.UserPermission().apply {
                    this.orderTypes = "C53 C52 CCC VMK STA"
                }
            )
        }
    }
    val str = XMLUtil.convertJaxbToString(htd)
    return str.toByteArray()
}

private fun handleEbicsHkd(requestContext: RequestContext): ByteArray {
    val hkd = HKDResponseOrderData().apply {
        this.partnerInfo = makePartnerInfo(requestContext.subscriber)
        this.userInfoList = listOf(
            EbicsTypes.UserInfo().apply {
                this.name = "Some User"
                this.userID = EbicsTypes.UserIDType().apply {
                    this.status = 1
                    this.value = requestContext.subscriber.userId
                }
                this.permissionList = listOf(
                    EbicsTypes.UserPermission().apply {
                        this.orderTypes = "C54 C53 C52 CCC"
                    }
                )
            })
    }

    val str = XMLUtil.convertJaxbToString(hkd)
    return str.toByteArray()
}


private data class RequestContext(
    val ebicsHost: EbicsHostEntity,
    val subscriber: EbicsSubscriberEntity,
    val clientEncPub: RSAPublicKey,
    val clientAuthPub: RSAPublicKey,
    val clientSigPub: RSAPublicKey,
    val hostEncPriv: RSAPrivateCrtKey,
    val hostAuthPriv: RSAPrivateCrtKey,
    val requestObject: EbicsRequest,
    val uploadTransaction: EbicsUploadTransactionEntity?,
    val downloadTransaction: EbicsDownloadTransactionEntity?
)

private fun handleEbicsDownloadTransactionTransfer(requestContext: RequestContext): EbicsResponse {
    val segmentNumber =
        requestContext.requestObject.header.mutable.segmentNumber?.value ?: throw EbicsInvalidRequestError()
    val transactionID = requestContext.requestObject.header.static.transactionID ?: throw EbicsInvalidRequestError()
    val downloadTransaction = requestContext.downloadTransaction ?: throw AssertionError()
    return EbicsResponse.createForDownloadTransferPhase(
        transactionID,
        downloadTransaction.numSegments,
        downloadTransaction.segmentSize,
        downloadTransaction.encodedResponse,
        segmentNumber.toInt()
    )
}


private fun handleEbicsDownloadTransactionInitialization(requestContext: RequestContext): EbicsResponse {
    val orderType =
        requestContext.requestObject.header.static.orderDetails?.orderType ?: throw EbicsInvalidRequestError()
    logger.debug("handling initialization for order type $orderType")
    val response = when (orderType) {
        "HTD" -> handleEbicsHtd(requestContext)
        "HKD" -> handleEbicsHkd(requestContext)
        /* Temporarily handling C52/C53 with same logic */
        "C53" -> handleEbicsC53(requestContext)
        "C52" -> handleEbicsC53(requestContext) // why?
        "TSD" -> handleEbicsTSD()
        "PTK" -> handleEbicsPTK()
        else -> throw EbicsInvalidXmlError()
    }

    val transactionID = EbicsOrderUtil.generateTransactionId()

    val compressedResponse = DeflaterInputStream(response.inputStream()).use {
        it.readAllBytes()
    }

    val enc = CryptoUtil.encryptEbicsE002(compressedResponse, requestContext.clientEncPub)
    val encodedResponse = Base64.getEncoder().encodeToString(enc.encryptedData)

    val segmentSize = 4096
    val totalSize = encodedResponse.length
    val numSegments = ((totalSize + segmentSize - 1) / segmentSize)

    EbicsDownloadTransactionEntity.new(transactionID) {
        this.subscriber = requestContext.subscriber
        this.host = requestContext.ebicsHost
        this.orderType = orderType
        this.segmentSize = segmentSize
        this.transactionKeyEnc = ExposedBlob(enc.encryptedTransactionKey)
        this.encodedResponse = encodedResponse
        this.numSegments = numSegments
        this.receiptReceived = false
    }
    return EbicsResponse.createForDownloadInitializationPhase(
        transactionID,
        numSegments,
        segmentSize,
        enc,
        encodedResponse
    )
}


private fun handleEbicsUploadTransactionInitialization(requestContext: RequestContext): EbicsResponse {
    val orderType =
        requestContext.requestObject.header.static.orderDetails?.orderType ?: throw EbicsInvalidRequestError()
    val transactionID = EbicsOrderUtil.generateTransactionId()
    val oidn = requestContext.subscriber.nextOrderID++
    if (EbicsOrderUtil.checkOrderIDOverflow(oidn)) throw NotImplementedError()
    val orderID = EbicsOrderUtil.computeOrderIDFromNumber(oidn)
    val numSegments =
        requestContext.requestObject.header.static.numSegments ?: throw EbicsInvalidRequestError()
    val transactionKeyEnc =
        requestContext.requestObject.body.dataTransfer?.dataEncryptionInfo?.transactionKey
            ?: throw EbicsInvalidRequestError()
    val encPubKeyDigest =
        requestContext.requestObject.body.dataTransfer?.dataEncryptionInfo?.encryptionPubKeyDigest?.value
            ?: throw EbicsInvalidRequestError()
    val encSigData = requestContext.requestObject.body.dataTransfer?.signatureData?.value
        ?: throw EbicsInvalidRequestError()
    val decryptedSignatureData = CryptoUtil.decryptEbicsE002(
        CryptoUtil.EncryptionResult(
            transactionKeyEnc,
            encPubKeyDigest,
            encSigData
        ), requestContext.hostEncPriv
    )
    val plainSigData = InflaterInputStream(decryptedSignatureData.inputStream()).use {
        it.readAllBytes()
    }
    logger.debug("creating upload transaction for transactionID $transactionID")
    EbicsUploadTransactionEntity.new(transactionID) {
        this.host = requestContext.ebicsHost
        this.subscriber = requestContext.subscriber
        this.lastSeenSegment = 0
        this.orderType = orderType
        this.orderID = orderID
        this.numSegments = numSegments.toInt()
        this.transactionKeyEnc = ExposedBlob(transactionKeyEnc)
    }
    logger.debug("after SQL flush")
    val sigObj = XMLUtil.convertStringToJaxb<UserSignatureData>(plainSigData.toString(Charsets.UTF_8))
    logger.debug("got UserSignatureData: ${plainSigData.toString(Charsets.UTF_8)}")
    for (sig in sigObj.value.orderSignatureList ?: listOf()) {
        logger.debug("inserting order signature for orderID $orderID and orderType $orderType")
        EbicsOrderSignatureEntity.new {
            this.orderID = orderID
            this.orderType = orderType
            this.partnerID = sig.partnerID
            this.userID = sig.userID
            this.signatureAlgorithm = sig.signatureVersion
            this.signatureValue = ExposedBlob(sig.signatureValue)
        }
    }
    return EbicsResponse.createForUploadInitializationPhase(transactionID, orderID)
}

private fun handleEbicsUploadTransactionTransmission(requestContext: RequestContext): EbicsResponse {
    val uploadTransaction = requestContext.uploadTransaction ?: throw EbicsInvalidRequestError()
    val requestObject = requestContext.requestObject
    val requestSegmentNumber =
        requestContext.requestObject.header.mutable.segmentNumber?.value?.toInt() ?: throw EbicsInvalidRequestError()
    val requestTransactionID = requestObject.header.static.transactionID ?: throw EbicsInvalidRequestError()
    if (requestSegmentNumber == 1 && uploadTransaction.numSegments == 1) {
        val encOrderData =
            requestObject.body.dataTransfer?.orderData ?: throw EbicsInvalidRequestError()
        val zippedData = CryptoUtil.decryptEbicsE002(
            uploadTransaction.transactionKeyEnc.bytes,
            Base64.getDecoder().decode(encOrderData),
            requestContext.hostEncPriv
        )
        val unzippedData =
            InflaterInputStream(zippedData.inputStream()).use { it.readAllBytes() }
        logger.debug("got upload data: ${unzippedData.toString(Charsets.UTF_8)}")

        val sigs = EbicsOrderSignatureEntity.find {
            (EbicsOrderSignaturesTable.orderID eq uploadTransaction.orderID) and
                    (EbicsOrderSignaturesTable.orderType eq uploadTransaction.orderType)
        }
        if (sigs.count() == 0L) {
            throw EbicsInvalidRequestError()
        }
        for (sig in sigs) {
            if (sig.signatureAlgorithm == "A006") {

                val signedData = CryptoUtil.digestEbicsOrderA006(unzippedData)
                val res1 = CryptoUtil.verifyEbicsA006(
                    sig.signatureValue.bytes,
                    signedData,
                    requestContext.clientSigPub
                )
                if (!res1) {
                    throw EbicsInvalidRequestError()
                }

            } else {
                throw NotImplementedError()
            }
        }
        if (getOrderTypeFromTransactionId(requestTransactionID) == "CCT") {
            logger.debug("Attempting a payment.")
            handleCct(unzippedData.toString(Charsets.UTF_8))
        }
        return EbicsResponse.createForUploadTransferPhase(
            requestTransactionID,
            requestSegmentNumber,
            true,
            uploadTransaction.orderID
        )
    } else {
        throw NotImplementedError()
    }
}
// req.header.static.hostID.
private fun makeRequestContext(requestObject: EbicsRequest): RequestContext {
    val staticHeader = requestObject.header.static
    val requestedHostId = staticHeader.hostID
    val ebicsHost =
        EbicsHostEntity.find { EbicsHostsTable.hostID.upperCase() eq requestedHostId.toUpperCase() }
            .firstOrNull()
    val requestTransactionID = requestObject.header.static.transactionID
    var downloadTransaction: EbicsDownloadTransactionEntity? = null
    var uploadTransaction: EbicsUploadTransactionEntity? = null
    val subscriber = if (requestTransactionID != null) {
        println("finding subscriber by transactionID $requestTransactionID")
        downloadTransaction = EbicsDownloadTransactionEntity.findById(requestTransactionID.toUpperCase())
        if (downloadTransaction != null) {
            downloadTransaction.subscriber
        } else {
            uploadTransaction = EbicsUploadTransactionEntity.findById(requestTransactionID)
            uploadTransaction?.subscriber
        }
    } else {
        val partnerID = staticHeader.partnerID ?: throw EbicsInvalidRequestError()
        val userID = staticHeader.userID ?: throw EbicsInvalidRequestError()
        findEbicsSubscriber(partnerID, userID, staticHeader.systemID)
    }

    if (ebicsHost == null) throw EbicsInvalidRequestError()

    /**
     * NOTE: production logic must check against READY state (the
     * one activated after the subscriber confirms their keys via post)
     */
    if (subscriber == null || subscriber.state != SubscriberState.INITIALIZED)
        throw EbicsSubscriberStateError()

    val hostAuthPriv = CryptoUtil.loadRsaPrivateKey(
        ebicsHost.authenticationPrivateKey.bytes
    )
    val hostEncPriv = CryptoUtil.loadRsaPrivateKey(
        ebicsHost.encryptionPrivateKey.bytes
    )
    val clientAuthPub =
        CryptoUtil.loadRsaPublicKey(subscriber.authenticationKey!!.rsaPublicKey.bytes)
    val clientEncPub =
        CryptoUtil.loadRsaPublicKey(subscriber.encryptionKey!!.rsaPublicKey.bytes)
    val clientSigPub =
        CryptoUtil.loadRsaPublicKey(subscriber.signatureKey!!.rsaPublicKey.bytes)

    return RequestContext(
        hostAuthPriv = hostAuthPriv,
        hostEncPriv = hostEncPriv,
        clientAuthPub = clientAuthPub,
        clientEncPub = clientEncPub,
        clientSigPub = clientSigPub,
        ebicsHost = ebicsHost,
        requestObject = requestObject,
        subscriber = subscriber,
        downloadTransaction = downloadTransaction,
        uploadTransaction = uploadTransaction
    )
}

suspend fun ApplicationCall.ebicsweb() {
    val requestDocument = receiveEbicsXml()
    logger.info("Processing ${requestDocument.documentElement.localName}")
    when (requestDocument.documentElement.localName) {
        "ebicsUnsecuredRequest" -> {
            val requestObject = requestDocument.toObject<EbicsUnsecuredRequest>()
            logger.info("Serving a ${requestObject.header.static.orderDetails.orderType} request")

            val orderData = requestObject.body.dataTransfer.orderData.value
            val header = requestObject.header

            when (header.static.orderDetails.orderType) {
                "INI" -> handleEbicsIni(header, orderData)
                "HIA" -> handleEbicsHia(header, orderData)
                else -> throw EbicsInvalidXmlError()
            }
        }
        "ebicsHEVRequest" -> {
            val hevResponse = HEVResponse().apply {
                this.systemReturnCode = SystemReturnCodeType().apply {
                    this.reportText = "[EBICS_OK]"
                    this.returnCode = "000000"
                }
                this.versionNumber = listOf(HEVResponse.VersionNumber.create("H004", "02.50"))
            }

            val strResp = XMLUtil.convertJaxbToString(hevResponse)
            logger.debug("HEV response: $strResp")
            if (!XMLUtil.validateFromString(strResp)) throw SandboxError(
                HttpStatusCode.InternalServerError,
                "Outgoing HEV response is invalid"
            )
            respondText(strResp, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        "ebicsNoPubKeyDigestsRequest" -> {
            val requestObject = requestDocument.toObject<EbicsNpkdRequest>()
            val hostInfo = ensureEbicsHost(requestObject.header.static.hostID)
            when (requestObject.header.static.orderDetails.orderType) {
                "HPB" -> handleEbicsHpb(hostInfo, requestDocument, requestObject.header)
                else -> throw EbicsInvalidXmlError()
            }
        }
        "ebicsRequest" -> {
            logger.debug("ebicsRequest ${XMLUtil.convertDomToString(requestDocument)}")
            val requestObject = requestDocument.toObject<EbicsRequest>()
            val responseXmlStr = transaction {
                // Step 1 of 3:  Get information about the host and subscriber
                val requestContext = makeRequestContext(requestObject)
                // Step 2 of 3:  Validate the signature
                val verifyResult = XMLUtil.verifyEbicsDocument(requestDocument, requestContext.clientAuthPub)
                if (!verifyResult) {
                    throw EbicsInvalidRequestError()
                }
                // Step 3 of 3:  Generate response
                val ebicsResponse: EbicsResponse = when (requestObject.header.mutable.transactionPhase) {
                    EbicsTypes.TransactionPhaseType.INITIALISATION -> {
                        if (requestObject.header.static.numSegments == null) {
                            handleEbicsDownloadTransactionInitialization(requestContext)
                        } else {
                            handleEbicsUploadTransactionInitialization(requestContext)
                        }
                    }
                    EbicsTypes.TransactionPhaseType.TRANSFER -> {
                        if (requestContext.uploadTransaction != null) {
                            handleEbicsUploadTransactionTransmission(requestContext)
                        } else if (requestContext.downloadTransaction != null) {
                            handleEbicsDownloadTransactionTransfer(requestContext)
                        } else {
                            throw AssertionError()
                        }
                    }
                    EbicsTypes.TransactionPhaseType.RECEIPT -> {
                        val requestTransactionID =
                            requestObject.header.static.transactionID ?: throw EbicsInvalidRequestError()
                        if (requestContext.downloadTransaction == null)
                            throw EbicsInvalidRequestError()
                        val receiptCode =
                            requestObject.body.transferReceipt?.receiptCode ?: throw EbicsInvalidRequestError()
                        EbicsResponse.createForDownloadReceiptPhase(requestTransactionID, receiptCode == 0)
                    }
                }
                signEbicsResponse(ebicsResponse, requestContext.hostAuthPriv)
            }
            if (!XMLUtil.validateFromString(responseXmlStr)) throw SandboxError(
                HttpStatusCode.InternalServerError,
                "Outgoing EBICS XML is invalid"
            )
            respondText(responseXmlStr, ContentType.Application.Xml, HttpStatusCode.OK)
        }
        else -> {
            /* Log to console and return "unknown type" */
            logger.info("Unknown message, just logging it!")
            respond(
                HttpStatusCode.NotImplemented,
                SandboxError(
                    HttpStatusCode.NotImplemented,
                    "Not Implemented"
                )
            )
        }
    }
}
