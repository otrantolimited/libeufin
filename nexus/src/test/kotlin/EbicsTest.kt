import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Test
import org.w3c.dom.Document
import tech.libeufin.nexus.*
import tech.libeufin.nexus.bankaccount.addPaymentInitiation
import tech.libeufin.nexus.bankaccount.fetchBankAccountTransactions
import tech.libeufin.nexus.bankaccount.submitAllPaymentInitiations
import tech.libeufin.nexus.ebics.*
import tech.libeufin.nexus.iso20022.NexusPaymentInitiationData
import tech.libeufin.nexus.iso20022.createPain001document
import tech.libeufin.nexus.server.FetchLevel
import tech.libeufin.nexus.server.FetchSpecAllJson
import tech.libeufin.nexus.server.Pain001Data
import tech.libeufin.sandbox.*
import tech.libeufin.util.*
import tech.libeufin.util.ebics_h004.EbicsRequest
import tech.libeufin.util.ebics_h004.EbicsResponse
import tech.libeufin.util.ebics_h004.EbicsTypes
import tech.libeufin.util.ebics_h005.Ebics3Request

/**
 * These test cases run EBICS CCT and C52, mixing ordinary operations
 * and some error cases.
 */

/**
 * Data to make the test server return for EBICS
 * phases.  Currently only init is supported.
 */
data class EbicsResponses(
    val init: String,
    val download: String? = null,
    val receipt: String? = null
)

/**
 * Minimal server responding always the 'init' field of a EbicsResponses
 * object to a download EBICS message.  Suitable to set arbitrary data
 * in said response.  Signs the response assuming the client is the one
 * created in MakeEnv.kt.
 */
fun getCustomEbicsServer(r: EbicsResponses, endpoint: String = "/ebicsweb"): Application.() -> Unit {
    val ret: Application.() -> Unit = {
        install(ContentNegotiation) {
            register(ContentType.Text.Xml, XMLEbicsConverter())
            register(ContentType.Text.Plain, XMLEbicsConverter())
        }
        routing {
            post(endpoint) {
                val requestDocument = this.call.receive<Document>()
                val req = requestDocument.toObject<EbicsRequest>()
                val clientKey = CryptoUtil.loadRsaPublicKey(userKeys.enc.public.encoded)
                val msgId = EbicsOrderUtil.generateTransactionId()
                val resp: EbicsResponse = if (
                    req.header.mutable.transactionPhase == EbicsTypes.TransactionPhaseType.INITIALISATION
                ) {
                    val payload = prepareEbicsPayload(r.init, clientKey)
                    EbicsResponse.createForDownloadInitializationPhase(
                        msgId,
                        1,
                        4096,
                        payload.second, // for key material
                        payload.first // actual payload
                    )
                } else {
                    // msgId doesn't have to match the one used for the init phase.
                    EbicsResponse.createForDownloadReceiptPhase(msgId, true)
                }
                val sigEbics = XMLUtil.signEbicsResponse(
                    resp,
                    CryptoUtil.loadRsaPrivateKey(bankKeys.auth.private.encoded)
                )
                call.respond(sigEbics)
            }
        }
    }
    return ret
}

class DownloadAndSubmit {
    // Downloads a C52 report from the bank.
    @Test
    fun download() {
        withNexusAndSandboxUser {
            wireTransfer(
                "admin",
                "foo",
                "default",
                "Show up in logging!",
                "TESTKUDOS:1"
            )
            wireTransfer(
                "admin",
                "foo",
                "default",
                "Exist in logging!",
                "TESTKUDOS:5"
            )
            testApplication {
                application(sandboxApp)
                runBlocking {
                    fetchBankAccountTransactions(
                        client,
                        fetchSpec = FetchSpecAllJson(
                            level = FetchLevel.REPORT,
                            bankConnection = "foo"
                        ),
                        accountId = "foo"
                    )
                }
                transaction {
                    // FIXME: assert on the subject.
                    assert(
                        NexusBankTransactionEntity[1].amount == "1" &&
                                NexusBankTransactionEntity[2].amount == "5"
                    )
                }
            }
        }
    }

    // Uploads one payment instruction to the bank.
    @Test
    fun upload() {
        withNexusAndSandboxUser {
            testApplication {
                application(sandboxApp)
                val conn = EbicsBankConnectionProtocol()
                runBlocking {
                    // Create Pain.001 to be submitted.
                    addPaymentInitiation(
                        Pain001Data(
                            creditorIban = getIban(),
                            creditorBic = "SANDBOXX",
                            creditorName = "Tester",
                            subject = "test payment",
                            sum = "1",
                            currency = "TESTKUDOS"
                        ),
                        transaction {
                            NexusBankAccountEntity.findByName(
                                "foo"
                            ) ?: throw Exception("Test failed")
                        }
                    )
                    conn.submitPaymentInitiation(
                        client,
                        1L
                    )
                }
                transaction {
                    val payment = BankAccountTransactionEntity[1]
                    assert(payment.debtorIban == FOO_USER_IBAN &&
                            payment.subject == "test payment" &&
                            payment.direction == "DBIT"
                    )
                }
            }
        }
    }

    /**
     * Upload one payment instruction charging one IBAN
     * that does not belong to the requesting EBICS subscriber.
     */
    @Test
    fun unallowedDebtorIban() {
        withNexusAndSandboxUser {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    val bar = transaction { NexusBankAccountEntity.findByName("bar") }
                    val painMessage = createPain001document(
                        NexusPaymentInitiationData(
                            debtorIban = bar!!.iban,
                            debtorBic = bar.bankCode,
                            debtorName = bar.accountHolder,
                            currency = "TESTKUDOS",
                            amount = "1",
                            creditorIban = getIban(),
                            creditorName = "Get",
                            creditorBic = "SANDBOXX",
                            paymentInformationId = "entropy-0",
                            preparationTimestamp = 1970L,
                            subject = "Unallowed",
                            messageId = "entropy-1",
                            endToEndId = null,
                            instructionId = null
                        )
                    )
                    val unallowedSubscriber = transaction { getEbicsSubscriberDetails("foo") }
                    var thrown = false
                    try {
                        doEbicsUploadTransaction(
                            client,
                            unallowedSubscriber,
                            EbicsUploadSpec(
                                orderType = "CCT",
                                isEbics3 = false,
                                orderParams = EbicsStandardOrderParams()
                            ),
                            painMessage.toByteArray(Charsets.UTF_8)
                        )
                    } catch (e: EbicsProtocolError) {
                        if (e.ebicsTechnicalCode ==
                                EbicsReturnCode.EBICS_ACCOUNT_AUTHORISATION_FAILED
                        )
                            thrown = true
                    }
                    assert(thrown)
                }
            }
        }
    }

    /**
     * Submits one pain.001 document with the wrong currency and checks
     * that the bank responded with EBICS_PROCESSING_ERROR.
     */
    @Test
    fun unsupportedCurrency() {
        withNexusAndSandboxUser {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    // Create Pain.001 to be submitted.
                    addPaymentInitiation(
                        Pain001Data(
                            creditorIban = getIban(),
                            creditorBic = "SANDBOXX",
                            creditorName = "Tester",
                            subject = "test payment",
                            sum = "1",
                            currency = "EUR" // EUR not supported.
                        ),
                        transaction {
                            NexusBankAccountEntity.findByName("foo") ?: throw Exception("Test failed")
                        }
                    )
                    var thrown = false
                    try {
                        submitAllPaymentInitiations(client, "foo")
                    } catch (e: EbicsProtocolError) {
                        if (e.ebicsTechnicalCode == EbicsReturnCode.EBICS_PROCESSING_ERROR)
                            thrown = true
                    }
                    assert(thrown)
                }
            }
        }
    }

    /**
     * Test that pain.001 amounts ALSO have max 2 fractional digits, like Taler's.
     * That makes Sandbox however NOT completely compatible with the pain.001 standard,
     * since this allows up to 5 fractional digits.  */
    @Test
    fun testFractionalDigits() {
        withNexusAndSandboxUser {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    // Create Pain.001 with excessive amount.
                    addPaymentInitiation(
                        Pain001Data(
                            creditorIban = getIban(),
                            creditorBic = "SANDBOXX",
                            creditorName = "Tester",
                            subject = "test payment",
                            sum = "1.001", // wrong 3 fractional digits.
                            currency = "TESTKUDOS"
                        ),
                        "foo"
                    )
                    assertException<EbicsProtocolError>({ submitAllPaymentInitiations(client, "foo") })
                }
            }
        }
    }

    // Test the EBICS error message in case of debt threshold being surpassed
    @Test
    fun testDebit() {
        withNexusAndSandboxUser {
            testApplication {
                application(sandboxApp)
                runBlocking {
                    // Create Pain.001 with excessive amount.
                    addPaymentInitiation(
                        Pain001Data(
                            creditorIban = getIban(),
                            creditorBic = "SANDBOXX",
                            creditorName = "Tester",
                            subject = "test payment",
                            sum = "1000000",
                            currency = "TESTKUDOS"
                        ),
                        "foo"
                    )
                    assertException<EbicsProtocolError>(
                        { submitAllPaymentInitiations(client, "foo") },
                        {
                            val nexusEbicsException = it as EbicsProtocolError
                            assert(
                                EbicsReturnCode.EBICS_AMOUNT_CHECK_FAILED.errorCode ==
                                nexusEbicsException.ebicsTechnicalCode?.errorCode
                            )
                        }
                    )
                }
            }
        }
    }
}

class EbicsTest {

    @Test
    fun genEbics3Upload() {
        withTestDatabase {
            prepNexusDb()
            val foo = transaction { getEbicsSubscriberDetails("foo") }
            val uploadDoc = createEbicsRequestForUploadInitialization(
                subscriberDetails = foo,
                ebics3OrderService = Ebics3Request.OrderDetails.Service().apply {
                    serviceName = "OTH"
                    scope = "BIL"
                    serviceOption = "CH002LMF"
                    messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
                        value = "csv"
                    }
                },
                null,
                prepareUploadPayload(
                    foo,
                    "foo".toByteArray(),
                    isEbics3 = true
                )
            )
            assert(XMLUtil.validateFromString(uploadDoc))
        }
    }

    /**
     * Tests the validity of EBICS 3.0 messages.
     */
    @Test
    fun genEbics3Download() {
        withTestDatabase {
            prepNexusDb()
            val foo = transaction { getEbicsSubscriberDetails("foo") }
            val downloadDoc = createEbicsRequestForDownloadInitialization(
                subscriberDetails = foo,
                ebics3OrderService = Ebics3Request.OrderDetails.Service().apply {
                    messageName = Ebics3Request.OrderDetails.Service.MessageName().apply {
                        value = "camt.054"
                        version = "04"
                    }
                    scope = "CH"
                    serviceName = "REP"
                    container = Ebics3Request.OrderDetails.Service.Container().apply {
                        containerType = "ZIP"
                    }
                },
                orderParams = EbicsStandardOrderParams()
            )
            assert(XMLUtil.validateFromString(downloadDoc))
        }
    }
}