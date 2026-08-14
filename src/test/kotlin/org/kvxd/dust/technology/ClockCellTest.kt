package org.kvxd.dust.technology

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cli.CircuitSourceLoader
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.device.redstone.ComparatorMode
import org.kvxd.dust.lang.DustCompileException
import org.kvxd.dust.lang.DustLanguage
import org.kvxd.dust.netlist.SequentialSimulator
import org.kvxd.dust.physical.PhysicalCompiler
import org.kvxd.dust.sim.GateLevelSimulator

class ClockCellTest {
    @Test
    fun `shipped clock example loads`() {
        val circuit = CircuitSourceLoader().load(Path.of("examples", "clock.dust"))

        assertEquals("main", circuit.name)
        assertTrue(circuit.lowerToBooleanNetlist().instances.any { it.type.id == CellTypeId("clock-10") })
    }

    @Test
    fun `clock accepts only periods represented by the comparator loop`() {
        listOf(1, 5, 7, 8, 12).forEach { period ->
            val error = assertFailsWith<DustCompileException> {
                clockCircuit(period)
            }
            assertTrue("supported periods are 6 + 4n ticks" in error.message.orEmpty(), error.message)
        }

        listOf(6, 10, 14, 18, 22, 258).forEach { period ->
            val netlist = clockCircuit(period).lowerToBooleanNetlist()
            assertTrue(netlist.instances.any { it.type.id == CellTypeId("clock-$period") })
        }
    }

    @Test
    fun `clock increases paired repeater delays before adding lane rows`() {
        mapOf(
            6 to listOf(1),
            10 to listOf(2),
            14 to listOf(3),
            18 to listOf(4),
            22 to listOf(4, 1),
            34 to listOf(4, 4),
            38 to listOf(4, 4, 1),
        ).forEach { (period, expectedDelays) ->
            val cell = MinecraftRedstone.technology.cellLibrary.specialize("clock", listOf(period)).physicalView
            val repeaters = cell.blocks.filter { (_, state) -> state.type == BlockType.REPEATER }
            assertEquals(expectedDelays.size * 2, repeaters.size, "clock<$period> repeater count")
            expectedDelays.forEachIndexed { index, delay ->
                val row = repeaters.filter { (position, _) -> position.z == index + 1 }
                assertEquals(2, row.size, "clock<$period> row ${index + 1}")
                assertEquals(setOf(delay), row.map { (_, state) -> state[Properties.DELAY] }.toSet())
                assertEquals(
                    setOf(Direction.NORTH, Direction.SOUTH),
                    row.map { (_, state) -> state[Properties.FACING] }.toSet(),
                )
            }
            val comparator = cell.blocks.single { (_, state) -> state.type == BlockType.COMPARATOR }.second
            assertEquals(ComparatorMode.SUBTRACT, comparator[Properties.MODE])
            assertEquals(period, cell.timing.generatedClockPeriodTicks)
        }
    }

    @Test
    fun `logical clock toggles at its requested period and stops low`() {
        val simulator = SequentialSimulator(clockCircuit(10).lowerToBooleanNetlist())
        assertEquals(false, simulator.step(mapOf("enabled" to false)).getValue("pulse"))

        val levels = List(30) { simulator.step(mapOf("enabled" to true)).getValue("pulse") }
        val rises = levels.indices.filter { index -> levels[index] && (index == 0 || !levels[index - 1]) }
        assertEquals(listOf(4, 14, 24), rises)

        assertEquals(false, simulator.step(mapOf("enabled" to false)).getValue("pulse"))
        repeat(12) {
            assertEquals(false, simulator.step(mapOf("enabled" to false)).getValue("pulse"))
        }
    }

    @Test
    fun `generated clock advances recursive state once per period`() {
        val circuit = DustLanguage.compile(
            """
            module main(input enabled: bit, output value: bits<3>) {
                let system_clock = clock<6>(enabled)
                let rec state = register<3>(increment<3>(state).result, system_clock)
                value = state
            }
            """.trimIndent(),
            "clocked-state.dust",
        ).single()
        val simulator = SequentialSimulator(circuit.lowerToBooleanNetlist())

        fun tick(): ULong = simulator.step(mapOf("enabled" to true)).let { output ->
            (0 until 3).fold(0uL) { value, bit ->
                if (output.getValue("value[$bit]")) value or (1uL shl bit) else value
            }
        }

        assertEquals(0uL, simulator.step(mapOf("enabled" to false)).values.toWord())
        assertEquals(listOf(0uL, 0uL, 1uL), List(3) { tick() })
        assertEquals(listOf(1uL, 1uL, 1uL, 1uL, 1uL, 2uL), List(6) { tick() })
    }

    @Test
    fun `physical comparator loop repeats at its declared period`() {
        val period = 10
        val example = CircuitSourceLoader().load(Path.of("examples", "clock.dust"))
        val design = PhysicalCompiler().compile(example.lowerToBooleanNetlist())
        val placedClock = design.cells.single { it.cell.logicalType.id == CellTypeId("clock-$period") }
        val placedDff = design.cells.single { it.cell.name == "dff" }
        val clockPin = placedDff.pin("clock")
        assertEquals(BlockType.REPEATER, design.matrix[clockPin.x, clockPin.y, clockPin.z + 1].type)
        assertTrue(design.matrix[clockPin.x, clockPin.y, clockPin.z + 2].type != BlockType.REPEATER)
        design.cells.filter { it.cell.name == "output-pad" }.forEach { output ->
            val pin = output.pin("a")
            assertTrue(
                design.matrix[pin.x, pin.y, pin.z + 1].type != BlockType.REPEATER,
                "output ${output.name} has an unnecessary repeater before its lamp",
            )
        }
        val clockPosition = placedClock.observations.getValue("clock")
        val simulator = GateLevelSimulator(design.matrix)
        simulator.settle(design.matrix.blockCount())
        assertEquals(false, simulator.levelAt(clockPosition))

        simulator.setInput(design.inputs.getValue("data"), true)
        simulator.advanceUntilIdle(design.matrix.blockCount())
        simulator.setInput(design.inputs.getValue("enabled"), true)
        val levels = List(80) {
            simulator.advance(1)
            simulator.levelAt(clockPosition)
        }
        val rises = levels.indices.filter { index -> levels[index] && (index == 0 || !levels[index - 1]) }
        val componentLevels = placedClock.cell.blocks.associate { (position, state) ->
            "$position:${state.type.id}" to simulator.signalAt(placedClock.origin + position)
        }
        assertTrue(rises.size >= 3, "clock rose only at $rises; final components: $componentLevels")
        assertTrue(rises.zipWithNext().all { (first, second) -> second - first == period }, "clock rose at $rises")
        assertEquals(true, simulator.readOutput(design.outputs.getValue("stored")))

        simulator.setInput(design.inputs.getValue("enabled"), false)
        simulator.advanceUntilIdle(design.matrix.blockCount())
        assertEquals(false, simulator.levelAt(clockPosition))
        assertEquals(true, simulator.readOutput(design.outputs.getValue("stored")))
    }

    private fun clockCircuit(period: Int) = DustLanguage.compile(
        "module main(input enabled: bit, output pulse: bit) { pulse = clock<$period>(enabled) }",
        "clock-$period.dust",
    ).single()

    private fun Collection<Boolean>.toWord(): ULong = foldIndexed(0uL) { bit, value, set ->
        if (set) value or (1uL shl bit) else value
    }
}
