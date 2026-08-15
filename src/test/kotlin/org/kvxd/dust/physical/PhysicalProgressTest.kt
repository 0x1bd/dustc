package org.kvxd.dust.physical

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.kvxd.dust.netlist.booleanNetlist
import org.kvxd.dust.physical.progress.PhysicalProgressEvent
import org.kvxd.dust.physical.progress.PhysicalProgressListener
import org.kvxd.dust.physical.progress.PhysicalProgressStage

class PhysicalProgressTest {
    @Test
    fun `physical compiler reports structured completed work`() {
        val events = arrayListOf<PhysicalProgressEvent>()
        val netlist = booleanNetlist("progress") {
            val a = input("a")
            val b = input("b")
            output("y", and(a, b))
        }
        PhysicalCompiler().compile(netlist, progress = PhysicalProgressListener(events::add))

        assertEquals(PhysicalProgressStage.PLACEMENT, events.first().stage)
        assertTrue(events.any { it.stage == PhysicalProgressStage.PLACEMENT && it.completed == it.total })
        assertTrue(events.any { it.stage == PhysicalProgressStage.ROUTING && it.netTotal == netlist.signals })
        assertTrue(events.any { it.stage == PhysicalProgressStage.ROUTING && it.candidateTotal != null })
        assertTrue(events.any { it.stage == PhysicalProgressStage.ROUTING && it.completed == it.total })
        val finalization = events.filter { it.stage == PhysicalProgressStage.ELECTRICAL_FINALIZATION }
        assertEquals(listOf(0, 1), finalization.map { it.completed })
        assertTrue(finalization.all { it.total == 1 })
    }
}
