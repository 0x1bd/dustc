package org.kvxd.dust.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.kvxd.dust.compile
import org.kvxd.dust.device.block.ComponentKind
import org.kvxd.dust.netlist.SequentialSimulator
import org.kvxd.dust.netlist.booleanNetlist
import org.kvxd.dust.sim.GateLevelSimulator
import org.kvxd.dust.technology.CellSize

class ClockedStorageTest {
    @Test
    fun `recursive register bindings expose legal next-state feedback`() {
        val circuit = DustLanguage.compile(
            """
            module counter(input clock: bit, input clear: bit, output value: bits<3>) {
                let rec state = resettable_register<3>(increment<3>(state).result, clear, clock)
                value = state
            }
            """.trimIndent(),
            "counter.dust",
        ).single()
        val simulator = SequentialSimulator(circuit.lowerToBooleanNetlist())

        fun step(clock: Boolean, clear: Boolean = false): Int {
            val outputs = simulator.step(mapOf("clock" to clock, "clear" to clear))
            return (0 until 3).fold(0) { value, bit ->
                if (outputs.getValue("value[$bit]")) value or (1 shl bit) else value
            }
        }

        assertEquals(0, step(false))
        assertEquals(1, step(true))
        assertEquals(1, step(false))
        assertEquals(2, step(true))
        assertEquals(2, step(false, clear = true))
        assertEquals(0, step(true, clear = true))
    }

    @Test
    fun `recursive bindings require an explicit storage boundary`() {
        val error = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(input a: bit, output y: bit) { let rec loop = ~loop y = loop }",
                "invalid-recursion.dust",
            )
        }

        assertTrue("must be initialized by a register call" in error.message.orEmpty())
    }

    @Test
    fun `DFF captures only on a rising edge`() {
        val circuit = DustLanguage.compile(
            "module bit_dff(input d: bit, input clock: bit, output q: bit) { q = dff(d, clock) }",
            "bit-dff.dust",
        ).single()
        val netlist = circuit.lowerToBooleanNetlist()
        val simulator = SequentialSimulator(netlist)

        fun step(d: Boolean, clock: Boolean): Boolean =
            simulator.step(mapOf("d" to d, "clock" to clock)).getValue("q")

        assertEquals(false, step(false, false))
        assertEquals(false, step(true, false), "low clock must not capture")
        assertEquals(true, step(true, true), "rising edge must capture")
        assertEquals(true, step(false, true), "high clock must retain")
        assertEquals(true, step(false, false), "falling edge must retain")
        assertEquals(false, step(false, true), "next rising edge must capture")
    }

    @Test
    fun `register helpers infer width and compose enables and synchronous reset`() {
        val circuit = DustLanguage.compile(
            """
            module registers(
                input data: bits<4>,
                input enable: bit,
                input reset: bit,
                input clock: bit,
                output plain: bits<4>,
                output enabled: bits<4>,
                output resettable: bits<4>,
            ) {
                plain = register(data, clock)
                enabled = enabled_register(data, enable, clock)
                resettable = resettable_register(data, reset, clock)
            }
            """.trimIndent(),
            "registers.dust",
        ).single()
        val netlist = circuit.lowerToBooleanNetlist()
        assertEquals(1, netlist.clockSignals.size, "all register bits must share one clock net")
        val simulator = SequentialSimulator(netlist)

        fun step(data: Int, enable: Boolean, reset: Boolean, clock: Boolean): List<Int> {
            val inputs = buildMap {
                repeat(4) { bit -> put("data[$bit]", data and (1 shl bit) != 0) }
                put("enable", enable)
                put("reset", reset)
                put("clock", clock)
            }
            val outputs = simulator.step(inputs)
            return listOf("plain", "enabled", "resettable").map { name ->
                (0 until 4).fold(0) { value, bit ->
                    if (outputs.getValue("$name[$bit]")) value or (1 shl bit) else value
                }
            }
        }

        assertEquals(listOf(0, 0, 0), step(9, enable = true, reset = false, clock = false))
        assertEquals(listOf(9, 9, 9), step(9, enable = true, reset = false, clock = true))
        assertEquals(listOf(9, 9, 9), step(2, enable = false, reset = true, clock = true))
        assertEquals(listOf(9, 9, 9), step(2, enable = false, reset = true, clock = false))
        assertEquals(listOf(2, 9, 0), step(2, enable = false, reset = true, clock = true))
    }

    @Test
    fun `DFF feedback samples simultaneously`() {
        val netlist = booleanNetlist("simultaneous") {
            val clock = input("clock")
            val q1 = wire()
            val q2 = wire()
            val d1 = not(q2)
            connect(
                org.kvxd.dust.cell.library.BuiltinCells.dff,
                mapOf("d" to listOf(d1), "clock" to listOf(clock), "q" to listOf(q1)),
                "first",
            )
            connect(
                org.kvxd.dust.cell.library.BuiltinCells.dff,
                mapOf("d" to listOf(q1), "clock" to listOf(clock), "q" to listOf(q2)),
                "second",
            )
            output("q1", q1)
            output("q2", q2)
        }
        val simulator = SequentialSimulator(netlist)

        assertEquals(mapOf("q1" to false, "q2" to false), simulator.step(mapOf("clock" to false)))
        assertEquals(mapOf("q1" to true, "q2" to false), simulator.step(mapOf("clock" to true)))
    }

    @Test
    fun `eight register bits use one balanced physical clock net`() {
        val circuit = DustLanguage.compile(
            """
            module register8(
                input d: bits<8>,
                input clock: bit,
                output q: bits<8>,
            ) {
                q = register(d, clock)
            }
            """.trimIndent(),
            "register8.dust",
        ).single()
        val netlist = circuit.lowerToBooleanNetlist()
        val clock = netlist.clockSignals.single()
        val compiled = circuit.compile()

        assertEquals(8, compiled.physical.cells.count { it.cell.name == "dff" })
        assertEquals(1, compiled.physical.routes.count { it.signal == clock })
        assertTrue(
            compiled.timing.maximumClockSkewTicks <= 1,
            "clock skew was ${compiled.timing.maximumClockSkewTicks} ticks",
        )
        assertTrue(compiled.timing.clockSkewViolations.isEmpty())
    }

    @Test
    fun `comparator and repeater DFF captures at block level`() {
        val circuit = DustLanguage.compile(
            "module physical_dff(input d: bit, input clock: bit, output q: bit) { q = dff(d, clock) }",
            "physical-dff.dust",
        ).single()
        val design = circuit.compile().physical
        val dffCell = design.cells.single { it.cell.name == "dff" }.cell
        assertEquals(CellSize(12, 2, 6), dffCell.size)
        assertEquals(1, dffCell.blocks.count { it.second.type.component == ComponentKind.COMPARATOR })
        assertEquals(3, dffCell.blocks.count { it.second.type.component == ComponentKind.REPEATER })
        val simulator = GateLevelSimulator(design.matrix)
        val tickBound = design.matrix.blockCount()
        simulator.settle(tickBound)

        fun drive(d: Boolean, clock: Boolean): Boolean {
            simulator.setInput(design.inputs.getValue("d"), d)
            simulator.setInput(design.inputs.getValue("clock"), clock)
            simulator.advanceUntilIdle(tickBound)
            return simulator.readOutput(design.outputs.getValue("q"))
        }

        assertEquals(false, drive(d = false, clock = false))
        assertEquals(false, drive(d = true, clock = false), "low clock must not capture")
        assertEquals(true, drive(d = true, clock = true), "rising edge must capture")
        assertEquals(true, drive(d = false, clock = true), "high clock must retain")
        assertEquals(true, drive(d = false, clock = false), "falling edge must retain")
        assertEquals(false, drive(d = false, clock = true), "second rising edge must capture")
        assertEquals(emptyList(), simulator.unsettled())
    }
}
