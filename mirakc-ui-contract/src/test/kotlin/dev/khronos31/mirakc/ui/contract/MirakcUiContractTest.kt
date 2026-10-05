package dev.khronos31.mirakc.ui.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MirakcUiContractTest {
    @Test
    fun snapshotsCopyCollectionsAndExposeReadOnlyLists() {
        val channels = mutableListOf(13, 14)
        val prepared = PreparedChannelsUi(channels, 0, 0)
        channels += 15

        assertEquals(listOf(13, 14), prepared.terrestrialChannels)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (prepared.terrestrialChannels as MutableList<Int>).add(16)
        }
    }
}
