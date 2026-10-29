package nl.giejay.android.tv.immich.api.util

import arrow.core.Either
import com.google.gson.Gson
import kotlinx.coroutines.delay
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.model.ImmichErrorResponse
import nl.giejay.android.tv.immich.api.model.toDisplayMessage
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import retrofit2.HttpException
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.util.UUID

object ApiUtil {

    fun getThumbnailUrl(assetId: String?, format: String, loadEdited: Boolean = false, includeApiKey: Boolean = false): String? {
        return assetId?.let {
            val base = "${hostName().lowercase()}/api/assets/${it}/thumbnail?size=${format}&edited=${loadEdited}"
            // api key is added here for home screen channel poster art, since the launcher fetches it
            // in its own process, without our OkHttp interceptor
            if (includeApiKey) "${base}&apiKey=${PreferenceManager.get(API_KEY)}" else base
        }
    }

    private fun hostName(): String {
        return PreferenceManager.hostName
    }

    fun getFileUrl(assetId: String?, type: String, forceOriginal: Boolean = false, loadEdited: Boolean = false): String? {
        if(forceOriginal){
            return assetId?.let {
                // api key is added here if the user wants to open a video in an external video player, since the external player will not have access to the api key otherwise
                "${hostName().lowercase()}/api/assets/${it}/original?apiKey=${PreferenceManager.get(API_KEY)}"
            }
        }
        return when (type) {
            "VIDEO" ->
                assetId?.let {
                    // api key is added here if the user wants to open a video in an external video player, since the external player will not have access to the api key otherwise
                    "${hostName().lowercase()}/api/assets/${it}/video/playback?apiKey=${PreferenceManager.get(API_KEY)}"
                }

            else ->
                assetId?.let {
                    "${hostName().lowercase()}/api/assets/${it}/original?edited=${loadEdited}"
                }

        }
    }

    fun getPersonThumbnail(personId: UUID): String {
        return "${hostName().lowercase()}/api/people/$personId/thumbnail"
    }

    /**
     * Studio: a home server on Wi-Fi sometimes drops a request or briefly answers 401 (e.g. while
     * it restarts or the TV wakes up). Such calls are retried a couple of times before an error is
     * shown, and the error is a plain sentence instead of the raw server response.
     */
    suspend fun <T> executeAPICall(expectedStatus: Int, handler: suspend () -> Response<T>): Either<String, T> {
        var attempt = 0
        while (true) {
            val result = executeAPICallOnce(expectedStatus, handler)
            val retryable = result.fold({ it.startsWith(RETRYABLE) }, { false })
            if (!retryable || attempt >= RETRY_DELAYS_MS.size) {
                return result.mapLeft { it.removePrefix(RETRYABLE) }
            }
            Timber.w("API call failed (${result.leftOrNull()}), retrying")
            delay(RETRY_DELAYS_MS[attempt++])
        }
    }

    private const val RETRYABLE = "\u0000retry:"
    private val RETRY_DELAYS_MS = longArrayOf(700, 2000)

    private fun friendly(id: Int, fallback: String): String =
        ImmichApplication.appContext?.getString(id) ?: fallback

    private suspend fun <T> executeAPICallOnce(expectedStatus: Int, handler: suspend () -> Response<T>): Either<String, T> {
        try {
            val res = handler()
            logCorrelationId(res)
            return when (val code = res.code()) {
                expectedStatus -> {
                    res.body()?.let { Either.Right(it) }
                        ?: Either.Left("Did not receive a input from the server")
                }
                401 -> {
                    Timber.w("401 from server: ${res.errorBody()?.string()}")
                    Either.Left(RETRYABLE + friendly(R.string.studio_error_auth, "Invalid status (401) returned by Immich Server"))
                }
                408, 429, 500, 502, 503, 504 -> {
                    Timber.w("$code from server: ${res.errorBody()?.string()}")
                    Either.Left(RETRYABLE + friendly(R.string.studio_error_connection, "Invalid status ($code) returned by Immich Server"))
                }
                else -> {
                    val bodyString: String? = res.errorBody()?.string()
                    if(bodyString?.contains("required permission: all") == true){
                        Either.Left("API key is missing the permission \"all\". Please adapt your permissions in the Immich web interface.")
                    } else {
                        val parsed = parseErrorBodySafely(bodyString)
                        val baseMessage = parsed?.message?.toDisplayMessage(bodyString ?: "Unknown error") ?: (bodyString ?: "Unknown error")
                        val validationErrors = parsed?.errors?.map { it.message.toDisplayMessage("") }?.filter { it.isNotBlank() }?.joinToString(", ")
                        val message = if (!validationErrors.isNullOrBlank()) {
                            "$baseMessage: $validationErrors"
                        } else {
                            baseMessage
                        }
                        Either.Left("Invalid status ($code) returned by Immich Server: $message")
                    }
                }
            }
        } catch (e: HttpException) {
            Timber.e(e, "Could not fetch items due to http error")
            return Either.Left("Could not fetch data from API, status: ${e.code()}")
        } catch (e: IOException) {
            // no network, timeout, server not reachable
            Timber.e(e, "Could not reach the server")
            return Either.Left(RETRYABLE + friendly(R.string.studio_error_connection, "Could not fetch data from API, response: ${e.message}"))
        } catch (e: Exception) {
            Timber.e(e, "Could not fetch items, unknown error")
            return Either.Left("Could not fetch data from API, response: ${e.message}")
        }
    }

    private fun parseErrorBodySafely(body: String?): ImmichErrorResponse? {
        if (body.isNullOrBlank()) return null
        return try {
            Gson().fromJson(body, ImmichErrorResponse::class.java)
        } catch (e: Exception) {
            Timber.w(e, "Could not parse structured error response, falling back to raw message")
            null
        }
    }

    private fun logCorrelationId(res: Response<*>) {
        val correlationId = res.headers()["X-Correlation-ID"]
        if (correlationId != null) {
            Timber.d("X-Correlation-ID: $correlationId")
        }
    }
}