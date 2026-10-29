package nl.giejay.android.tv.immich.plus.api

import arrow.core.Either
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.ApiClientFactory
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.SearchResponse
import nl.giejay.android.tv.immich.api.util.ApiUtil.executeAPICall
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import java.util.Locale

interface PlusApiService {
    // CLIP based search, the same endpoint the Immich web search uses
    @POST("search/smart")
    suspend fun smartSearch(@Body request: SmartSearchRequest): Response<SearchResponse>

    @POST("search/metadata")
    suspend fun metadataSearch(@Body request: PlusMetadataSearchRequest): Response<SearchResponse>

    // one representative asset per city, used by the "Places" page of the Immich web app
    @GET("search/cities")
    suspend fun cities(): Response<List<Asset>>

    // non-destructive edits (Immich 3): the original stays, Immich renders an edited version
    @GET("assets/{id}/edits")
    suspend fun edits(@Path("id") assetId: String): Response<AssetEdits>

    @PUT("assets/{id}/edits")
    suspend fun putEdits(@Path("id") assetId: String, @Body request: AssetEditsRequest): Response<AssetEdits>

    @DELETE("assets/{id}/edits")
    suspend fun deleteEdits(@Path("id") assetId: String): Response<Unit>
}

/** One edit of an asset: action "crop", "rotate" or "mirror" with its parameters (rotate: angle). */
data class AssetEdit(val action: String, val parameters: Map<String, Any>)

data class AssetEdits(val assetId: String?, val edits: List<AssetEdit>?)

data class AssetEditsRequest(val edits: List<AssetEdit>)

// Immich v3 rejects unknown properties, so only fields of SmartSearchDto are sent.
// Gson skips null values, optional filters therefore disappear from the request.
data class SmartSearchRequest(
    val query: String,
    val page: Int,
    val size: Int,
    // helps multilingual CLIP models (e.g. nllb) with non-English queries, ignored by others
    val language: String? = Locale.getDefault().language,
    val type: String? = null,
    val visibility: String? = "timeline",
    val withExif: Boolean = true
)

data class PlusMetadataSearchRequest(
    val page: Int,
    val size: Int,
    val type: String? = null,
    val isFavorite: Boolean? = null,
    val city: String? = null,
    val country: String? = null,
    val order: String = "desc",
    val visibility: String? = "timeline",
    val withExif: Boolean = true
)

/**
 * Calls for the Immich TV Plus pages. Kept apart from [nl.giejay.android.tv.immich.api.ApiClient]
 * so that upstream changes to the original client never conflict with these additions.
 */
object PlusApi {
    private var cached: Pair<ApiClientConfig, PlusApiService>? = null

    @Synchronized
    private fun service(): PlusApiService {
        val config = ApiClientConfig.fromPrefs()
        cached?.let { (cachedConfig, service) -> if (cachedConfig == config) return service }
        val service = Retrofit.Builder()
            .client(ApiClientFactory.getClient(config.disableSslVerification, config.apiKey, config.debugMode))
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl("${config.hostName}/api/")
            .build()
            .create(PlusApiService::class.java)
        cached = config to service
        return service
    }

    suspend fun smartSearch(query: String, page: Int, size: Int, contentType: ContentType): Either<String, List<Asset>> {
        val request = SmartSearchRequest(query = query, page = page, size = size, type = contentType.toApiType())
        return executeAPICall(200) { service().smartSearch(request) }.map { it.assets.items }
    }

    suspend fun favorites(page: Int, size: Int, contentType: ContentType): Either<String, List<Asset>> {
        val request = PlusMetadataSearchRequest(page = page, size = size, type = contentType.toApiType(), isFavorite = true)
        return executeAPICall(200) { service().metadataSearch(request) }.map { it.assets.items }
    }

    suspend fun assetsInCity(city: String, country: String?, page: Int, size: Int, contentType: ContentType): Either<String, List<Asset>> {
        val request = PlusMetadataSearchRequest(page = page, size = size, type = contentType.toApiType(), city = city, country = country)
        return executeAPICall(200) { service().metadataSearch(request) }.map { it.assets.items }
    }

    suspend fun cities(): Either<String, List<Asset>> {
        return executeAPICall(200) { service().cities() }
            .map { assets -> assets.filter { !it.exifInfo?.city.isNullOrBlank() } }
    }

    /**
     * Turns the asset by [quarterTurns] x 90 degrees clockwise, on top of what it already has.
     * A PUT replaces all edits, so the existing ones (crop, mirror) are read first and kept; a
     * rotation adding up to 360 degrees is dropped again, and without any edit left they are deleted.
     */
    suspend fun rotate(assetId: String, quarterTurns: Int): Either<String, Unit> {
        val current = executeAPICall(200) { service().edits(assetId) }
            .fold({ return Either.Left(it) }, { it.edits.orEmpty() })
        val index = current.indexOfFirst { it.action == ROTATE }
        val oldAngle = current.getOrNull(index)?.parameters?.get("angle")?.let { (it as? Number)?.toInt() } ?: 0
        val angle = Math.floorMod(oldAngle + quarterTurns * 90, 360)
        val rotation = AssetEdit(ROTATE, mapOf("angle" to angle))
        val edits = when {
            index < 0 && angle == 0 -> current
            index < 0 -> current + rotation
            angle == 0 -> current.filterIndexed { i, _ -> i != index }
            else -> current.mapIndexed { i, edit -> if (i == index) rotation else edit }
        }
        return if (edits.isEmpty()) {
            try {
                val response = service().deleteEdits(assetId)
                if (response.isSuccessful) Either.Right(Unit)
                else Either.Left("Invalid status (${response.code()}) returned by Immich Server")
            } catch (e: Exception) {
                Either.Left(e.message ?: e.toString())
            }
        } else {
            executeAPICall(200) { service().putEdits(assetId, AssetEditsRequest(edits)) }.map { }
        }
    }

    private const val ROTATE = "rotate"

    private fun ContentType.toApiType(): String? = if (this == ContentType.ALL) null else toString()
}
