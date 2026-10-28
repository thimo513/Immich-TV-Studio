package nl.giejay.android.tv.immich.shared.prefs

import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.model.Asset

enum class PhotosOrder(val sort: Comparator<Asset>): EnumWithTitle {
    NEWEST_OLDEST(
        compareByDescending<Asset, java.util.Date?>(nullsFirst()) { it.exifInfo?.dateTimeOriginal ?: it.fileModifiedAt }
    ) {
        override fun getTitle(): String {
            return ImmichApplication.appContext!!.getString(R.string.order_newest_oldest)
        }
    },
    OLDEST_NEWEST(
        compareBy<Asset, java.util.Date?>(nullsLast()) { it.exifInfo?.dateTimeOriginal ?: it.fileModifiedAt }
    ) {
        override fun getTitle(): String {
            return ImmichApplication.appContext!!.getString(R.string.order_oldest_newest)
        }
    };

    companion object {
        fun valueOfSafe(name: String, default: PhotosOrder): PhotosOrder{
            return entries.find { it.toString() == name } ?: default
        }
    }
}
