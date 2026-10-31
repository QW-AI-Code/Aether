package studio.cluvex.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementStoreTest {

    private val v150 = AnnouncementStore.versionKey("1.5.0", 18)
    private val v151 = AnnouncementStore.versionKey("1.5.1", 19)

    @Test fun freshInstallShows() =
        assertTrue(AnnouncementStore.shouldShow(v150, suppressedVersion = null))

    @Test fun sameVersionWithoutTickShowsAgain() =
        assertTrue(AnnouncementStore.shouldShow(v150, suppressedVersion = v151))

    @Test fun tickedForThisVersionStaysQuiet() =
        assertFalse(AnnouncementStore.shouldShow(v150, suppressedVersion = v150))

    @Test fun upgradeShowsEvenIfOlderVersionWasSilenced() =
        assertTrue(AnnouncementStore.shouldShow(v151, suppressedVersion = v150))

    @Test fun versionKeyIncludesCode() =
        assertEquals("1.5.0 (18)", AnnouncementStore.versionKey("1.5.0", 18))
}
