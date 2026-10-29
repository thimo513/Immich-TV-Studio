package nl.giejay.android.tv.immich.plus

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDurationTest {

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("0:42", formatVideoDuration("0:00:42.000000"))
        assertEquals("12:05", formatVideoDuration("0:12:05.5"))
    }

    @Test
    fun showsHoursOnlyWhenNeeded() {
        assertEquals("1:02:03", formatVideoDuration("1:02:03.500000"))
    }

    @Test
    fun unknownLengthGivesEmptyBadge() {
        assertEquals("", formatVideoDuration(null))
        assertEquals("", formatVideoDuration("garbage"))
    }
}
