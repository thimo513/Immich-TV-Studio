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
        ApiUtil.getThumbnailUrl(id, "preview"),
        videoDuration = if (type == "VIDEO") formatVideoDuration(duration) else null
    )
}

/** "0:00:42.000000" -> "0:42", "1:02:03.5" -> "1:02:03"; videos without a length get an empty badge text. */
fun formatVideoDuration(duration: String?): String {
    val parts = duration?.substringBefore('.')?.split(':')?.mapNotNull { it.toIntOrNull() } ?: return ""
    if (parts.size != 3) return ""
    val (hours, minutes, seconds) = parts
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
