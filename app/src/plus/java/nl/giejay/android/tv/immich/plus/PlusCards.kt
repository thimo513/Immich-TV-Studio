package nl.giejay.android.tv.immich.plus

import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.util.ApiUtil
import nl.giejay.android.tv.immich.card.Card
import java.text.DateFormat
import java.util.Locale

/** Photo card: the date taken (in the device language, e.g. 28.10.2024) and the place instead of the file name. */
fun Asset.toDateCard(): Card {
    val date = exifInfo?.dateTimeOriginal ?: fileCreatedAt ?: fileModifiedAt
    val caption = date?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(it) } ?: ""
    return Card(
        caption,
        listOfNotNull(exifInfo?.city, exifInfo?.country).joinToString(", "),
        id,
        ApiUtil.getThumbnailUrl(id, "thumbnail"),
        ApiUtil.getThumbnailUrl(id, "preview")
    )
}
