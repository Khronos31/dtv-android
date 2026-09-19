package dev.khronos31.mirakc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirakcJobCommandTest {
    @Test
    fun everyEpgJobIncludesTheUpstreamServiceFilterBlocks() {
        val rendered = renderMirakcJobCommands("/native/libmirakc-arib.so")
        val expectedCommands = listOf(
            "/system/bin/timeout 30 /native/libmirakc-arib.so scan-services",
            "/system/bin/timeout 30 /native/libmirakc-arib.so sync-clocks",
            "/system/bin/timeout 600 /native/libmirakc-arib.so collect-eits"
        )

        expectedCommands.forEach { command ->
            assertTrue(rendered.contains("command: $command$MIRAKC_JOB_FILTER_ARGS"))
        }
        assertEquals(3, rendered.split(MIRAKC_JOB_FILTER_ARGS).size - 1)
    }

    @Test
    fun filterBlocksKeepMustacheEmptyAndMultipleServiceContract() {
        assertEquals(
            "{{#sids}} --sids={{{.}}}{{/sids}}" +
                "{{#xsids}} --xsids={{{.}}}{{/xsids}}",
            MIRAKC_JOB_FILTER_ARGS
        )
        assertTrue(MIRAKC_JOB_FILTER_ARGS.contains("{{#sids}}"))
        assertTrue(MIRAKC_JOB_FILTER_ARGS.contains("{{/sids}}"))
        assertTrue(MIRAKC_JOB_FILTER_ARGS.contains("{{#xsids}}"))
        assertTrue(MIRAKC_JOB_FILTER_ARGS.contains("{{/xsids}}"))
    }
}
