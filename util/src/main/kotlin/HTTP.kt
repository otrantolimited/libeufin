package tech.libeufin.util

import UtilError
import io.ktor.http.*
import io.ktor.request.*
import logger

fun getHTTPBasicAuthCredentials(request: ApplicationRequest): Pair<String, String> {
    val authHeader = getAuthorizationHeader(request)
    return extractUserAndPassword(authHeader)
}

/**
 * Extracts the Authorization:-header line and throws error if not found.
 */
fun getAuthorizationHeader(request: ApplicationRequest): String {
    val authorization = request.headers["Authorization"]
    return authorization ?: throw UtilError(
        HttpStatusCode.BadRequest, "Authorization header not found",
        LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
    )
}

/**
 * This helper function parses a Authorization:-header line, decode the credentials
 * and returns a pair made of username and hashed (sha256) password.  The hashed value
 * will then be compared with the one kept into the database.
 */
fun extractUserAndPassword(authorizationHeader: String): Pair<String, String> {
    logger.debug("Authenticating: $authorizationHeader")
    val (username, password) = try {
        // FIXME/note: line below doesn't check for "Basic" presence.
        val split = authorizationHeader.split(" ")
        val plainUserAndPass = String(base64ToBytes(split[1]), Charsets.UTF_8)
        val ret = plainUserAndPass.split(":", limit = 2)
        if (ret.size < 2) throw java.lang.Exception(
            "HTTP Basic auth line does not contain username and password"
        )
        ret
    } catch (e: Exception) {
        throw UtilError(
            HttpStatusCode.BadRequest,
            "invalid Authorization:-header received: ${e.message}",
            LibeufinErrorCode.LIBEUFIN_EC_AUTHENTICATION_FAILED
        )
    }
    return Pair(username, password)
}
