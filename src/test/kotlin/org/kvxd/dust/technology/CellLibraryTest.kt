package org.kvxd.dust.technology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.netlist.Primitive
import org.kvxd.dust.physical.PhysicalCompiler
import org.kvxd.dust.netlist.booleanNetlist
import org.kvxd.dust.sim.GateLevelSimulator

class CellLibraryTest {
    private val technology = MinecraftRedstone.technology
    private fun cell(primitive: Primitive): StandardCell =
        checkNotNull(technology.physicalCell(primitive.cellType))

    @Test
    fun `combinational cells match their truth tables`() {
        val cases = listOf<Triple<Primitive, List<String>, (List<Boolean>) -> Boolean>>(
            Triple(Primitive.NOT, listOf("a")) { !it[0] },
            Triple(Primitive.AND2, listOf("a", "b")) { it[0] && it[1] },
            Triple(Primitive.OR2, listOf("a", "b")) { it[0] || it[1] },
            Triple(Primitive.XOR2, listOf("a", "b")) { it[0] xor it[1] },
            Triple(Primitive.MUX2, listOf("select", "low", "high")) { if (it[0]) it[2] else it[1] },
        )
        cases.forEach { (primitive, names, expected) ->
            val netlist = booleanNetlist(primitive.name.lowercase()) {
                val inputs = names.map { input(it) }
                val out = when (primitive) {
                    Primitive.NOT -> not(inputs[0])
                    Primitive.AND2 -> and(inputs[0], inputs[1])
                    Primitive.OR2 -> or(inputs[0], inputs[1])
                    Primitive.XOR2 -> xor(inputs[0], inputs[1])
                    Primitive.MUX2 -> mux(inputs[0], inputs[1], inputs[2])
                    Primitive.LATCH, Primitive.DFF, Primitive.ENABLED_DFF -> error("sequential")
                }
                output("y", out)
            }
            val design = PhysicalCompiler(technology).compile(netlist)
            val simulator = GateLevelSimulator(design.matrix)
            val bound = design.matrix.blockCount()
            simulator.settle(bound)

            repeat(1 shl names.size) { pattern ->
                val values = names.indices.map { pattern and (1 shl it) != 0 }
                names.forEachIndexed { index, name ->
                    simulator.setInput(checkNotNull(design.inputs[name]), values[index])
                }
                simulator.advanceUntilIdle(bound)
                assertEquals(
                    expected(values),
                    simulator.readOutput(checkNotNull(design.outputs["y"])),
                    "$primitive with $values",
                )
            }
            assertTrue(simulator.unsettled().isEmpty(), "$primitive left the world unsettled")
        }
    }

    @Test
    fun `latch follows data while open and retains it while held`() {
        val netlist = booleanNetlist("latch") {
            val data = input("data")
            val hold = input("hold")
            output("q", latch(data, hold))
        }
        val design = PhysicalCompiler(technology).compile(netlist)
        val simulator = GateLevelSimulator(design.matrix)
        val bound = design.matrix.blockCount()
        simulator.settle(bound)

        fun drive(data: Boolean, hold: Boolean): Boolean {
            simulator.setInput(checkNotNull(design.inputs["data"]), data)
            simulator.setInput(checkNotNull(design.inputs["hold"]), hold)
            simulator.advanceUntilIdle(bound)
            return simulator.readOutput(checkNotNull(design.outputs["q"]))
        }

        assertEquals(false, drive(data = false, hold = false), "open latch should pass a zero")
        assertEquals(true, drive(data = true, hold = false), "open latch should pass a one")
        assertEquals(true, drive(data = true, hold = true), "closing the latch should keep its one")
        assertEquals(true, drive(data = false, hold = true), "held latch should ignore new data")
        assertEquals(false, drive(data = false, hold = false), "reopened latch should follow to zero")
        assertEquals(false, drive(data = false, hold = true), "closing the latch should keep its zero")
        assertEquals(false, drive(data = true, hold = true), "held latch should ignore new data")
        assertEquals(true, drive(data = true, hold = false), "reopened latch should follow to one")
        assertTrue(simulator.unsettled().isEmpty())
    }

    @Test
    fun `routing edge tails stay compact`() {
        assertEquals(CellSize(3, 2, 2), cell(Primitive.NOT).size)
        assertEquals(CellSize(5, 2, 3), cell(Primitive.AND2).size)
        assertEquals(CellSize(13, 2, 7), cell(Primitive.XOR2).size)
        assertEquals(CellSize(9, 2, 6), cell(Primitive.MUX2).size)
    }

    @Test
    fun `debug pads use their controls and lamps as routing pins`() {
        val input = technology.debugInputPad
        assertEquals(CellSize(1, 2, 1), input.size)
        assertEquals(BlockType.LEVER, input.blocks.toMap().getValue(input.pin("y").position).type)

        val output = technology.debugOutputPad
        assertEquals(CellSize(2, 4, 1), output.size)
        assertEquals(BlockType.REDSTONE_LAMP, output.blocks.toMap().getValue(output.pin("a").position).type)
        assertTrue(output.blocks.none { (_, state) -> state.type == BlockType.REPEATER })
    }

    @Test
    fun `wall torches are mounted to solid blocks`() {
        Primitive.entries.map(::cell).forEach { cell ->
            val blocks = cell.blocks.toMap()
            cell.blocks.forEach { (pos, state) ->
                if (state.type == BlockType.REDSTONE_WALL_TORCH) {
                    val support = blocks[pos.offset(state[Properties.FACING].opposite)]
                    assertTrue(support?.type?.isSolid == true, "${cell.name} torch at $pos has support $support")
                }
            }
        }
    }

    @Test
    fun `xor compact tail has no stale supports`() {
        val blocks = cell(Primitive.XOR2).blocks.toMap()
        listOf(BlockPos(12, 0, 0), BlockPos(12, 0, 4), BlockPos(12, 0, 5)).forEach { pos ->
            assertTrue(pos !in blocks, "xor2 still contains stale support at $pos")
        }
    }

}
