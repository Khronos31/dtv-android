package dev.khronos31.mirakc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UserlandStatusInventoryTest {
    @Test
    fun parsesPx4AndSianoReceiverDescriptorsAndProjectsActualSystems() {
        val px4 = UserlandStatusInventory.parse(status("px4-userland", receivers = """
            [{"descriptor":{"receiver":3,"systems":["ISDB-T","ISDB-S"],"binding":null},"present":true,"state":"free"}]
        """), "px4-userland", INSTANCE)
        val enclosure = Px4DeviceSelector.plan(listOf(
            Px4DeviceIdentity("usb", "123456789012345", Px4DeviceSelector.M1UR_PRODUCT_ID)
        )).enclosures.single()
        val tuner = px4TunersFromInventory(enclosure, px4).single()
        assertEquals(3, tuner.receiver)
        assertTrue(tuner.supportsTerrestrial)
        assertTrue(tuner.supportsSatellite)

        val siano = UserlandStatusInventory.parse(status("siano-userland", receivers = """
            [{"descriptor":{"receiver":0,"systems":["ISDB-T"],"binding":null},"present":true,"state":"free"}]
        """), "siano-userland", INSTANCE)
        assertTrue(siano.ready)
        assertEquals(setOf(BroadcastSystem.ISDB_T), siano.receivers!!.single().systems)
    }

    @Test
    fun rejectsSchemaFamilyInstanceEpochAndDuplicateReceiverIds() {
        val good = status("px4-userland", receivers = receiver)
        listOf(
            good.replace("userland.status", "userland.list"),
            good.replace("\"major\":1", "\"major\":2"),
            good.replace("px4-userland", "siano-userland"),
            good.replace(INSTANCE, "other-instance"),
            good.replace(EPOCH, "not-an-epoch"),
            status("px4-userland", receivers = "[$receiver, $receiver]"),
            status("px4-userland", receivers = "[$receiver]").replace("ISDB-T", "ISDB-X"),
            status("px4-userland", receivers = "[$receiver]").replace("[\"ISDB-T\"]", "[\"ISDB-T\",\"ISDB-T\"]"),
            good.replace("\"major\":1", "\"major\":1,\"major\":1"),
            good.dropLast(2)
        ).forEach { invalid ->
            try {
                UserlandStatusInventory.parse(invalid, "px4-userland", INSTANCE)
                fail("invalid status accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun strictJsonParserRejectsDuplicateNumbersAndUnpairedSurrogates() {
        val good = status("px4-userland", receivers = "[$receiver]")
        listOf(
            good.replace("\"minor\":0", "\"minor\":0.0"),
            good.replace("\"major\":1", "\"major\":1e0"),
            good.replace("\"receiver\":0", "\"receiver\":1.0"),
            good.replace("\"last_error\":null", "\"last_error\":\"\\ud800\"")
        ).forEach { invalid ->
            try {
                UserlandStatusInventory.parse(invalid, "px4-userland", INSTANCE)
                fail("invalid JSON number or surrogate accepted")
            } catch (_: IllegalArgumentException) { }
        }
        val escapedPair = good.replace(
            "\"last_error\":null",
            "\"last_error\":\"😀\""
        )
        assertTrue(UserlandStatusInventory.parse(escapedPair, "px4-userland", INSTANCE).ready)
    }

    @Test
    fun keepsAbsentReceiverCollectionsAndPartialTopologyUnknown() {
        val missing = UserlandStatusInventory.parse(status("px4-userland", receivers = null), "px4-userland", INSTANCE)
        assertEquals(null, missing.receivers)
        assertFalse(missing.ready)
        val partial = UserlandStatusInventory.parse(
            status("px4-userland", receivers = "[$receiver]").replace("complete", "partial"),
            "px4-userland", INSTANCE
        )
        assertFalse(partial.ready)
        val failedReceiver = UserlandStatusInventory.parse(
            status("px4-userland", receivers = "[$receiver]").replace("\"state\":\"free\"", "\"state\":\"error\""),
            "px4-userland", INSTANCE
        )
        assertFalse(failedReceiver.ready)
    }

    @Test
    fun ownerGenerationRetainsClientIndependentDaemonAndRejectsStaleReadiness() {
        val first = FakeOwner("usb-a")
        val set = SianoOwnerSet()
        val generation = set.begin()
        assertTrue(set.publish(generation.ticket, listOf(first)))

        var clientStops = 0
        val client = SianoTsClientLifetime(first) { clientStops++ }
        client.stop("ts-eof")
        client.stop("client-eof")
        assertEquals(1, clientStops)
        assertEquals(0, first.stops)
        assertTrue(set.isCurrent(generation.ticket))

        val detached = set.begin()
        detached.retired.single().stop()
        assertEquals(1, first.stops)
        val stale = FakeOwner("usb-a-old")
        assertFalse(set.publish(generation.ticket, listOf(stale)))
        stale.stop()
        assertFalse(set.isCurrent(generation.ticket))
        assertTrue(set.publish(detached.ticket, emptyList()))
        val startup = set.begin()
        val closing = FakeOwner("usb-starting")
        set.close()
        assertFalse(set.publish(startup.ticket, listOf(closing)))
        closing.stop()
        assertEquals(1, closing.stops)
        assertFalse(set.isCurrent(startup.ticket))
        set.close().forEach { it.stop() }
    }

    private class FakeOwner(override val deviceName: String) : SianoDaemonOwner {
        override val instance = "s1-test"
        override val inventory: UserlandStatusInventory? = null
        var stops = 0
        override fun start() = true
        override fun stop() { stops++ }
    }

    private fun status(product: String, receivers: String?) = """
        {"schema":{"name":"userland.status","major":1,"minor":0},
         "producer":{"product":"$product","version":"1.0","core_version":null},
         "context":{"epoch":"$EPOCH","instance":"$INSTANCE","state":"ready","last_error":null},
         "snapshot":{"sequence":"1","observed_at_ns":"2"},
         "scope":{"kind":"enclosure","id":null},"enclosure":{"id":0,"model":null,"serial":null},
         "topology":{"state":"complete","expected_function_count":1},
         "usb_functions":[],${if (receivers == null) "" else "\"receivers\":$receivers,"}
         "readers":[]}
    """

    private companion object {
        const val INSTANCE = "s1-test"
        const val EPOCH = "0123456789abcdef0123456789abcdef"
        const val receiver = "{\"descriptor\":{\"receiver\":0,\"systems\":[\"ISDB-T\"],\"binding\":null},\"present\":true,\"state\":\"free\"}"
    }
}
