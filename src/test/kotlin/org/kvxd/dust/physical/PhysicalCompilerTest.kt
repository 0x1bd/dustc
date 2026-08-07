package org.kvxd.dust.physical

import org.kvxd.dust.netlist.booleanNetlist
import org.kvxd.dust.device.BlockType
import org.kvxd.dust.sim.GateLevelSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalCompilerTest {
    @Test
    fun `routed inverter works electrically`() {
        val netlist = booleanNetlist("not") {
            val a = input("a")
            output("y", not(a))
        }
        val design = PhysicalCompiler().compile(netlist)
        val simulator = GateLevelSimulator(design.matrix)
        simulator.settle(design.matrix.blockCount())

        listOf(false, true).forEach { value ->
            simulator.setInput(checkNotNull(design.inputs["a"]), value)
            simulator.advanceUntilIdle(design.matrix.blockCount())
            assertEquals(!value, simulator.readOutput(checkNotNull(design.outputs["y"])))
        }
        assertTrue(simulator.unsettled().isEmpty())
    }

    @Test
    fun `routed two-input gate crosses routing channels without shorts`() {
        val netlist = booleanNetlist("and") {
            val a = input("a")
            val b = input("b")
            output("y", and(a, b))
        }
        val design = PhysicalCompiler().compile(netlist)
        val simulator = GateLevelSimulator(design.matrix)
        simulator.settle(design.matrix.blockCount())

        for (a in listOf(false, true)) for (b in listOf(false, true)) {
            simulator.setInput(checkNotNull(design.inputs["a"]), a)
            simulator.setInput(checkNotNull(design.inputs["b"]), b)
            simulator.advanceUntilIdle(design.matrix.blockCount())
            assertEquals(a && b, simulator.readOutput(checkNotNull(design.outputs["y"])), "a=$a b=$b")
        }
        assertTrue(design.laneCount < design.routes.size)
        assertTrue(simulator.unsettled().isEmpty())
    }

    @Test
    fun `routed rich logic cells match their truth tables`() {
        val netlist = booleanNetlist("rich-cells") {
            val a = input("a")
            val b = input("b")
            output("or", or(a, b))
            output("xor", xor(a, b))
        }
        val design = PhysicalCompiler().compile(netlist)
        val simulator = GateLevelSimulator(design.matrix)
        val tickBound = design.matrix.blockCount()
        simulator.settle(tickBound)

        for (a in listOf(false, true)) for (b in listOf(false, true)) {
            simulator.setInput(checkNotNull(design.inputs["a"]), a)
            simulator.setInput(checkNotNull(design.inputs["b"]), b)
            simulator.advanceUntilIdle(tickBound)
            assertEquals(a || b, simulator.readOutput(checkNotNull(design.outputs["or"])), "OR a=$a b=$b")
            assertEquals(a xor b, simulator.readOutput(checkNotNull(design.outputs["xor"])), "XOR a=$a b=$b")
        }
        assertTrue(simulator.unsettled().isEmpty())
    }

    @Test
    fun `routed latch retains data while held`() {
        val netlist = booleanNetlist("latch") {
            val data = input("data")
            val hold = input("hold")
            output("q", latch(data, hold))
        }
        val design = PhysicalCompiler().compile(netlist)
        val simulator = GateLevelSimulator(design.matrix)
        val tickBound = design.matrix.blockCount()
        simulator.settle(tickBound)

        fun drive(data: Boolean, hold: Boolean): Boolean {
            simulator.setInput(checkNotNull(design.inputs["data"]), data)
            simulator.setInput(checkNotNull(design.inputs["hold"]), hold)
            simulator.advanceUntilIdle(tickBound)
            return simulator.readOutput(checkNotNull(design.outputs["q"]))
        }

        assertEquals(false, drive(false, true))
        assertEquals(false, drive(true, true))
        assertEquals(true, drive(true, false))
        assertEquals(true, drive(true, true))
        assertEquals(true, drive(false, true))
        assertEquals(false, drive(false, false))
        assertTrue(simulator.unsettled().isEmpty())
    }

    @Test
    fun `plain terminals omit debug levers and lamps`() {
        val netlist = booleanNetlist("terminals") {
            val a = input("a")
            output("y", not(a))
        }
        val design = PhysicalCompiler().compile(netlist, PhysicalIo.TERMINALS)
        design.matrix.forEachPosition { _, _, _, state ->
            assertTrue(state.type != BlockType.LEVER)
            assertTrue(state.type != BlockType.REDSTONE_LAMP)
        }
        assertEquals(BlockType.REDSTONE_WIRE, design.matrix.blockAt(checkNotNull(design.inputs["a"])).type)
        assertEquals(BlockType.REDSTONE_WIRE, design.matrix.blockAt(checkNotNull(design.outputs["y"])).type)
        assertEquals(setOf(0), design.inputs.values.map { it.z }.toSet())
        assertEquals(1, design.outputs.values.map { it.z }.toSet().size)
        assertTrue(design.outputs.values.first().z > design.inputs.values.first().z)
    }
}
