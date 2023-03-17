import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.Test
import tech.libeufin.nexus.server.nexusApp

/**
 * This class tests the API offered by Nexus,
 * documented here: https://docs.taler.net/libeufin/api-nexus.html
 */
class NexusApiTest {

    // Testing the creation of scheduled tasks.
    @Test
    fun schedule() {
        withTestDatabase {
            prepNexusDb()
            testApplication {
                application(nexusApp)
                // POSTing omitted 'params', to test whether Nexus
                // expects it as 'null' for a 'submit' task.
                client.post("/bank-accounts/foo/schedule") {
                    contentType(ContentType.Application.Json)
                    expectSuccess = true
                    basicAuth("foo", "foo")
                    // NOTE: current API doesn't allow to omit the 'params' field.
                    setBody("""{
                        "name": "send-payments",
                        "cronspec": "* * *",
                        "type": "submit",
                        "params": null
                    }""".trimIndent())
                }
            }
        }
    }
}