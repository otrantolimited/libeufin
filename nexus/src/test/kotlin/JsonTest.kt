import org.junit.Test
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.utils.io.jvm.javaio.*
import org.junit.Ignore
import tech.libeufin.nexus.server.CreateBankConnectionFromBackupRequestJson
import tech.libeufin.nexus.server.CreateBankConnectionFromNewRequestJson
import tech.libeufin.sandbox.NexusTransactions
import tech.libeufin.sandbox.sandboxApp

enum class EnumTest { TEST }
data class EnumWrapper(val enum_test: EnumTest)

class JsonTest {

    @Test
    fun testJackson() {
        val mapper = jacksonObjectMapper()
        val backupObj = CreateBankConnectionFromBackupRequestJson(
            name = "backup", passphrase = "secret", data = mapper.readTree("{}")
        )
        val roundTrip = mapper.readValue<CreateBankConnectionFromBackupRequestJson>(mapper.writeValueAsString(backupObj))
        assert(roundTrip.data.toString() == "{}" && roundTrip.passphrase == "secret" && roundTrip.name == "backup")
        val newConnectionObj = CreateBankConnectionFromNewRequestJson(
            name = "new-connection", type = "ebics", data = mapper.readTree("{}")
        )
        val roundTripNew = mapper.readValue<CreateBankConnectionFromNewRequestJson>(mapper.writeValueAsString(newConnectionObj))
        assert(roundTripNew.data.toString() == "{}" && roundTripNew.type == "ebics" && roundTripNew.name == "new-connection")
    }

    // Tests how Jackson+Kotlin handle enum types.  Fails if an exception is thrown
    @Test
    fun enumTest() {
        val m = jacksonObjectMapper()
         m.readValue<EnumWrapper>("{\"enum_test\":\"TEST\"}")
         m.readValue<EnumTest>("\"TEST\"")
    }

    /**
     * Ignored because this test was only used to check
     * the logs, as opposed to assert over values.  Consider
     * to remove the Ignore
     */
    @Ignore
    @Test
    fun testSandboxJsonParsing() {
        testApplication {
            application(sandboxApp)
            client.post("/admin/ebics/subscribers") {
                basicAuth("admin", "foo")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
    }

    data class CamtEntryWrapper(
        val unusedValue: String,
        val camtData: CamtBankAccountEntry
    )

    // Testing whether generating and parsing a CaMt JSON mapping works.
    @Test
    fun testCamtRoundTrip() {
        val obj = genNexusIncomingCamt(
            CurrencyAmount(value = "2", currency = "EUR"),
            subject = "round trip test"
        )
        val str = jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(obj)
        val map = jacksonObjectMapper().readValue(str, CamtBankAccountEntry::class.java)
        assert(str == jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(map))
    }

    @Test
    fun parseRawJson() {
        val camtModel = """
            {
              "amount" : "TESTKUDOS:22",
              "creditDebitIndicator" : "CRDT",
              "status" : "BOOK",
              "bankTransactionCode" : "mock",
              "batches" : [ {
                "batchTransactions" : [ {
                  "amount" : "TESTKUDOS:22",
                  "creditDebitIndicator" : "CRDT",
                  "details" : {
                    "debtor" : {
                      "name" : "Mock Payer"
                    },
                    "debtorAccount" : {
                      "iban" : "MOCK-IBAN"
                    },
                    "debtorAgent" : {
                      "bic" : "MOCK-BIC"
                    },
                    "unstructuredRemittanceInformation" : "raw"
                  }
                } ]
              } ]
            }
        """.trimIndent()
        val obj = jacksonObjectMapper().readValue(camtModel, CamtBankAccountEntry::class.java)
        assert(obj.getSingletonSubject() == "raw")
    }
}