package tech.libeufin.nexus

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsRequest
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes
import tech.libeufin.util.ebics_s001.UserSignatureData
import java.math.BigInteger
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.*
import javax.xml.bind.JAXBElement
import javax.xml.datatype.XMLGregorianCalendar


/**
 * Wrapper around the lower decryption routine, that takes a EBICS response
 * object containing a encrypted payload, and return the plain version of it
 * (including decompression).
 */
fun decryptAndDecompressResponse(response: EbicsResponse, privateKey: RSAPrivateCrtKey): ByteArray {
    val er = CryptoUtil.EncryptionResult(
        response.body.dataTransfer!!.dataEncryptionInfo!!.transactionKey,
        (response.body.dataTransfer!!.dataEncryptionInfo as EbicsTypes.DataEncryptionInfo)
            .encryptionPubKeyDigest.value,
        Base64.getDecoder().decode(response.body.dataTransfer!!.orderData.value)
    )
    val dataCompr = CryptoUtil.decryptEbicsE002(
        er,
        privateKey
    )
    return EbicsOrderUtil.decodeOrderData(dataCompr)
}

fun createDownloadInitializationPhase(
    subscriberData: EbicsContainer,
    orderType: String,
    nonce: ByteArray,
    date: XMLGregorianCalendar
): EbicsRequest {
    return EbicsRequest.createForDownloadInitializationPhase(
        subscriberData.userId,
        subscriberData.partnerId,
        subscriberData.hostId,
        nonce,
        date,
        subscriberData.bankEncPub ?: throw BankKeyMissing(
            HttpStatusCode.PreconditionFailed
        ),
        subscriberData.bankAuthPub ?: throw BankKeyMissing(
            HttpStatusCode.PreconditionFailed
        ),
        orderType
    )
}

fun createDownloadInitializationPhase(
    subscriberData: EbicsContainer,
    orderType: String,
    nonce: ByteArray,
    date: XMLGregorianCalendar,
    dateStart: XMLGregorianCalendar,
    dateEnd: XMLGregorianCalendar
): EbicsRequest {
    return EbicsRequest.createForDownloadInitializationPhase(
        subscriberData.userId,
        subscriberData.partnerId,
        subscriberData.hostId,
        nonce,
        date,
        subscriberData.bankEncPub ?: throw BankKeyMissing(
            HttpStatusCode.PreconditionFailed
        ),
        subscriberData.bankAuthPub ?: throw BankKeyMissing(
            HttpStatusCode.PreconditionFailed
        ),
        orderType,
        dateStart,
        dateEnd
    )
}

fun createUploadInitializationPhase(
    subscriberData: EbicsContainer,
    orderType: String,
    cryptoBundle: CryptoUtil.EncryptionResult
): EbicsRequest {
    return EbicsRequest.createForUploadInitializationPhase(
        cryptoBundle,
        subscriberData.hostId,
        getNonce(128),
        subscriberData.partnerId,
        subscriberData.userId,
        getGregorianDate(),
        subscriberData.bankAuthPub!!,
        subscriberData.bankEncPub!!,
        BigInteger.ONE,
        orderType
    )
}

/**
 * Usually, queries must return lots of data from within a transaction
 * block.  For convenience, we wrap such data into a EbicsContainer, so
 * that only one object is always returned from the transaction block.
 */
fun containerInit(subscriber: EbicsSubscriberEntity): EbicsContainer {
    var bankAuthPubValue: RSAPublicKey? = null
    if (subscriber.bankAuthenticationPublicKey != null) {
        bankAuthPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankAuthenticationPublicKey?.toByteArray()!!
        )
    }
    var bankEncPubValue: RSAPublicKey? = null
    if (subscriber.bankEncryptionPublicKey != null) {
        bankEncPubValue = CryptoUtil.loadRsaPublicKey(
            subscriber.bankEncryptionPublicKey?.toByteArray()!!
        )
    }
    return EbicsContainer(
        bankAuthPub = bankAuthPubValue,
        bankEncPub = bankEncPubValue,

        ebicsUrl = subscriber.ebicsURL,
        hostId = subscriber.hostID,
        userId = subscriber.userID,
        partnerId = subscriber.partnerID,

        customerSignPriv = CryptoUtil.loadRsaPrivateKey(subscriber.signaturePrivateKey.toByteArray()),
        customerAuthPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray()),
        customerEncPriv = CryptoUtil.loadRsaPrivateKey(subscriber.authenticationPrivateKey.toByteArray())
    )
}

/**
 * Inserts spaces every 2 characters, and a newline after 8 pairs.
 */
fun chunkString(input: String): String {
    val ret = StringBuilder()
    var columns = 0
    for (i in input.indices) {
        if ((i + 1).rem(2) == 0) {
            if (columns == 7) {
                ret.append(input[i] + "\n")
                columns = 0
                continue
            }
            ret.append(input[i] + " ")
            columns++
            continue
        }
        ret.append(input[i])
    }
    return ret.toString()
}

fun expectId(param: String?): String {
    return param ?: throw NotAnIdError(HttpStatusCode.BadRequest)
}

fun signOrder(
    orderBlob: ByteArray,
    signKey: RSAPrivateCrtKey,
    partnerId: String,
    userId: String
): UserSignatureData {
    val ES_signature = CryptoUtil.signEbicsA006(
        CryptoUtil.digestEbicsOrderA006(orderBlob),
        signKey
    )
    val userSignatureData = UserSignatureData().apply {
        orderSignatureList = listOf(
            UserSignatureData.OrderSignatureData().apply {
                signatureVersion = "A006"
                signatureValue = ES_signature
                partnerID = partnerId
                userID = userId
            }
        )
    }
    return userSignatureData
}

/**
 * @return null when the bank could not be reached, otherwise returns the
 * response already converted in JAXB.
 */
suspend inline fun HttpClient.postToBank(url: String, body: String): String {
    LOGGER.debug("Posting: $body")
    val response = try {
        this.post<String>(
            urlString = url,
            block = {
                this.body = body
            }
        )
    } catch (e: Exception) {
        throw UnreachableBankError(HttpStatusCode.InternalServerError)
    }
    return response
}

/**
 * DO verify the bank's signature
 */
suspend inline fun <reified T, reified S> HttpClient.postToBankSignedAndVerify(
    url: String,
    body: T,
    pub: RSAPublicKey,
    priv: RSAPrivateCrtKey
): JAXBElement<S> {
    val doc = XMLUtil.convertJaxbToDocument(body)
    XMLUtil.signEbicsDocument(doc, priv)
    val response: String = this.postToBank(url, XMLUtil.convertDomToString(doc))
    LOGGER.debug("About to verify: ${response}")
    val responseDocument = try {
        XMLUtil.parseStringIntoDom(response)
    } catch (e: Exception) {
        throw UnparsableResponse(
            HttpStatusCode.BadRequest,
            response
        )
    }
    if (!XMLUtil.verifyEbicsDocument(responseDocument, pub)) {
        throw BadSignature(HttpStatusCode.NotAcceptable)
    }
    try {
        return XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {
        throw UnparsableResponse(
            HttpStatusCode.BadRequest,
            response
        )
    }
}

suspend inline fun <reified T, reified S> HttpClient.postToBankSigned(
    url: String,
    body: T,
    priv: PrivateKey
): JAXBElement<S> {
    val doc = XMLUtil.convertJaxbToDocument(body)
    XMLUtil.signEbicsDocument(doc, priv)
    val response: String = this.postToBank(url, XMLUtil.convertDomToString(doc))
    try {
        return XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {
        throw UnparsableResponse(
            HttpStatusCode.BadRequest,
            response
        )
    }
}

/**
 * do NOT verify the bank's signature
 */
suspend inline fun <reified T, reified S> HttpClient.postToBankUnsigned(
    url: String,
    body: T
): JAXBElement<S> {
    val response: String = this.postToBank(url, XMLUtil.convertJaxbToString(body))
    try {
        return XMLUtil.convertStringToJaxb(response)
    } catch (e: Exception) {
        throw UnparsableResponse(
            HttpStatusCode.BadRequest,
            response
        )
    }
}

/**
 * @param size in bits
 */
fun getNonce(size: Int): ByteArray {
    val sr = SecureRandom()
    val ret = ByteArray(size / 8)
    sr.nextBytes(ret)
    return ret
}