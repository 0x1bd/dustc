package org.kvxd.dust.physical

import kotlin.math.abs
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.device.BlockType
import org.kvxd.dust.lang.DustLanguage
import org.kvxd.dust.netlist.booleanNetlist
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
    fun `tier placement attributes route producers on requested deep floors`() {
        val gateY = linkedMapOf<Int, Int>()
        for (tier in listOf(2, 3)) {
            val circuit = DustLanguage.compile(
                """
                module tiered(input a: bit, input b: bit, output y: bit) {
                    #[tier($tier)]
                    let p = a ^ b
                    y = p
                }
                """.trimIndent(),
                "tier-$tier.dust",
            ).single()
            val design = PhysicalCompiler().compile(circuit.lowerToBooleanNetlist())
            val gate = design.cells.single { it.name.startsWith("gate-") }
            gateY[tier] = gate.origin.y
            val simulator = GateLevelSimulator(design.matrix)
            val bound = design.matrix.blockCount()
            simulator.settle(bound)
            listOf(false to false, true to false, true to true, false to true, false to false).forEach { (a, b) ->
                simulator.setInput(design.inputs.getValue("a"), a)
                simulator.setInput(design.inputs.getValue("b"), b)
                simulator.advanceUntilIdle(bound)
                assertEquals(a xor b, simulator.readOutput(design.outputs.getValue("y")), "tier $tier")
            }
            assertTrue(simulator.unsettled().isEmpty())
        }
        assertTrue(gateY.getValue(3) > gateY.getValue(2))
    }

    @Test
    fun `mixed hard tiers split incompatible placement rows`() {
        val circuit = DustLanguage.compile(
            """
            module mixed_tiers(input a: bit, input b: bits<4>, output y: bits<4>) {
                #[tier(0)] let p0 = a & b[0]
                #[tier(1)] let p1 = a & b[1]
                #[tier(0)] let p2 = a & b[2]
                #[tier(1)] let p3 = a & b[3]
                y[0] = p0
                y[1] = p1
                y[2] = p2
                y[3] = p3
            }
            """.trimIndent(),
            "mixed-tiers.dust",
        ).single()
        val design = PhysicalCompiler().compile(circuit.lowerToBooleanNetlist())
        assertEquals(2, design.cells.filter { it.name.startsWith("gate-") }.map { it.origin.y }.toSet().size)
        val simulator = GateLevelSimulator(design.matrix)
        val bound = design.matrix.blockCount()
        simulator.settle(bound)
        listOf(0, 15, 5, 10, 3).forEach { pattern ->
            simulator.setInput(design.inputs.getValue("a"), pattern and 1 != 0)
            repeat(4) { bit ->
                simulator.setInput(design.inputs.getValue("b[$bit]"), pattern and (1 shl bit) != 0)
            }
            simulator.advanceUntilIdle(bound)
            repeat(4) { bit ->
                assertEquals(
                    pattern and 1 != 0 && pattern and (1 shl bit) != 0,
                    simulator.readOutput(design.outputs.getValue("y[$bit]")),
                )
            }
        }
        assertTrue(simulator.unsettled().isEmpty())
    }

    @Test
    fun `terminal tier constraint does not move producer logic`() {
        fun compile(attribute: String) = PhysicalCompiler().compile(
            DustLanguage.compile(
                """
                module terminal_tier(input a: bit, $attribute output y: bit) {
                    y = !a
                }
                """.trimIndent(),
                "terminal-tier.dust",
            ).single().lowerToBooleanNetlist(),
            PhysicalIo.TERMINALS,
        )

        val baseline = compile("")
        val constrained = compile("#[tier(2)]")
        val baselineGate = baseline.cells.single { it.name.startsWith("gate-") }
        val constrainedGate = constrained.cells.single { it.name.startsWith("gate-") }
        val constrainedOutput = constrained.cells.single { it.name == "output-y" }
        assertEquals(baselineGate.origin.y, constrainedGate.origin.y)
        assertTrue(constrainedOutput.origin.y > constrainedGate.origin.y)
    }

    @Test
    fun `near affinity changes actual placement`() {
        fun source(near: Boolean): String =
            """
            module affinity(input a: bits<8>, output y: bits<8>) {
                let h = ~a[0]
                let g1 = ~a[1]
                let g2 = ~a[2]
                let g3 = ~a[3]
                let g4 = ~a[4]
                let g5 = ~a[5]
                let g6 = ~a[6]
                ${if (near) "#[near(h)]" else ""}
                let p = ~a[7]
                y[0] = h
                y[1] = g1
                y[2] = g2
                y[3] = g3
                y[4] = g4
                y[5] = g5
                y[6] = g6
                y[7] = p
            }
            """.trimIndent()

        fun distance(near: Boolean): Int {
            val circuit = DustLanguage.compile(source(near), "near-$near.dust").single()
            val design = PhysicalCompiler().compile(circuit.lowerToBooleanNetlist())
            val first = design.cells.single { it.name.startsWith("gate-0-") }
            val last = design.cells.single { it.name.startsWith("gate-7-") }
            return abs(first.origin.x - last.origin.x) + abs(first.origin.z - last.origin.z)
        }

        assertTrue(distance(true) < distance(false))

        val multi = DustLanguage.compile(
            """
            module multi(input a: bit, input b: bit, output y: bit) {
                let h = ~a
                let k = ~b
                #[near(h, k)]
                let p = a ^ b
                y = p
            }
            """.trimIndent(),
            "near-multi.dust",
        ).single()
        PhysicalCompiler().compile(multi.lowerToBooleanNetlist())
    }

    @Test
    fun `hard io edge constraints place terminals on every cardinal side`() {
        PhysicalIoEdge.entries.forEach { edge ->
            val netlist = booleanNetlist("edge-${edge.name.lowercase()}") {
                val a = input("a")
                output("y", not(a))
            }
            val layout = PhysicalIoLayout(
                listOf(
                    PhysicalIoGroup("input", PhysicalIoDirection.INPUT, listOf("a"), edge),
                    PhysicalIoGroup("output", PhysicalIoDirection.OUTPUT, listOf("y")),
                ),
            )
            val design = PhysicalCompiler().compile(netlist, layout = layout)
            val terminal = design.cells.single { it.name == "input-a" }
            val gate = design.cells.single { it.name.startsWith("gate-") }
            when (edge) {
                PhysicalIoEdge.NORTH -> assertTrue(terminal.origin.z < gate.origin.z)
                PhysicalIoEdge.SOUTH -> assertTrue(terminal.origin.z > gate.origin.z)
                PhysicalIoEdge.WEST -> assertTrue(terminal.origin.x < gate.origin.x)
                PhysicalIoEdge.EAST -> assertTrue(terminal.origin.x > gate.origin.x)
            }
            val simulator = GateLevelSimulator(design.matrix)
            val bound = design.matrix.blockCount()
            simulator.settle(bound)
            listOf(false, true, false).forEach { value ->
                simulator.setInput(design.inputs.getValue("a"), value)
                simulator.advanceUntilIdle(bound)
                assertEquals(!value, simulator.readOutput(design.outputs.getValue("y")), edge.name)
            }
            assertTrue(simulator.unsettled().isEmpty())
        }
    }

    @Test
    fun `panel io groups form one compact ordered external interface`() {
        val circuit = DustLanguage.compile(
            """
            module adder4(
                #[panel] input operands {
                    a: bits<4>,
                    b: bits<4>,
                    cin: bit,
                },
                #[panel] output result {
                    sum: bits<4>,
                    cout: bit,
                },
            ) {
                let mut carry = cin
                for i in 0..4 {
                    let p = a[i] ^ b[i]
                    sum[i] = p ^ carry
                    carry = (a[i] & b[i]) | (p & carry)
                }
                cout = carry
            }
            """.trimIndent(),
            "panel-adder.dust",
        ).single()
        val layout = PhysicalIoLayout(
            listOf(
                PhysicalIoGroup(
                    "operands",
                    PhysicalIoDirection.INPUT,
                    List(4) { "a[$it]" } + List(4) { "b[$it]" } + "cin",
                    panel = true,
                ),
                PhysicalIoGroup(
                    "result",
                    PhysicalIoDirection.OUTPUT,
                    List(4) { "sum[$it]" } + "cout",
                    panel = true,
                ),
            ),
        )
        val design = PhysicalCompiler().compile(circuit.lowerToBooleanNetlist(), PhysicalIo.DEBUG_PADS, layout)
        val terminals = design.cells.filter { it.name.startsWith("input-") || it.name.startsWith("output-") }
        assertEquals(1, terminals.map { it.row }.toSet().size)
        assertEquals(1, terminals.map { it.origin.z }.toSet().size)
        assertEquals(
            listOf(
                "input-a[0]",
                "input-a[1]",
                "input-a[2]",
                "input-a[3]",
                "input-b[0]",
                "input-b[1]",
                "input-b[2]",
                "input-b[3]",
                "input-cin",
                "output-sum[0]",
                "output-sum[1]",
                "output-sum[2]",
                "output-sum[3]",
                "output-cout",
            ),
            terminals.sortedBy { it.origin.x }.map { it.name },
        )
        val firstX = terminals.minOf { it.origin.x }
        val lastX = terminals.maxOf { it.origin.x + it.cell.size.x }
        assertTrue(lastX - firstX <= 40)
        terminals.filter { it.name.startsWith("output-") }.forEach { cell ->
            val pin = cell.pin("a")
            val route = design.routes.single { it.signal == cell.nets.getValue("a") }
            val branchZ = pin.z + 2
            assertTrue(BlockPos(pin.x, pin.y, branchZ) in route.routeBlocks, cell.name)
            assertTrue(BlockPos(pin.x - 1, pin.y, branchZ) !in route.routeBlocks, cell.name)
            assertTrue(BlockPos(pin.x + 1, pin.y, branchZ) !in route.routeBlocks, cell.name)
            val branchZs = route.routeBlocks.filter { it.x == pin.x && it.y == pin.y }.map { it.z }.toSet()
            val branchRange = checkNotNull(branchZs.minOrNull())..checkNotNull(branchZs.maxOrNull())
            assertTrue(branchRange.all { it in branchZs }, cell.name)
            assertTrue(route.routeBlocks.none { it.y == pin.y && it.x != pin.x && it.z in branchRange }, cell.name)
        }

        val simulator = GateLevelSimulator(design.matrix)
        val bound = design.matrix.blockCount()
        simulator.settle(bound)
        val vectors = buildList {
            for (a in 0..15) for (b in 0..15) for (cin in 0..1) add(Triple(a, b, cin))
        }.shuffled(kotlin.random.Random(0x50414E454C))
        vectors.forEach { (a, b, cin) ->
            repeat(4) { bit ->
                simulator.setInput(design.inputs.getValue("a[$bit]"), a and (1 shl bit) != 0)
                simulator.setInput(design.inputs.getValue("b[$bit]"), b and (1 shl bit) != 0)
            }
            simulator.setInput(design.inputs.getValue("cin"), cin != 0)
            simulator.advanceUntilIdle(bound)
            val expected = a + b + cin
            repeat(4) { bit ->
                assertEquals(
                    expected and (1 shl bit) != 0,
                    simulator.readOutput(design.outputs.getValue("sum[$bit]")),
                    "a=$a b=$b cin=$cin bit=$bit",
                )
            }
            assertEquals(expected > 15, simulator.readOutput(design.outputs.getValue("cout")))
        }
        assertTrue(simulator.unsettled().isEmpty())
    }

    @Test
    fun `physical progress reports completed deterministic work`() {
        val netlist = booleanNetlist("progress") {
            val a = input("a")
            val b = input("b")
            output("y", xor(a, b))
        }
        val events = mutableListOf<PhysicalProgressEvent>()
        PhysicalCompiler().compile(netlist, progress = PhysicalProgressListener { events += it })
        assertTrue(events.any { it.stage == PhysicalProgressStage.PLACEMENT && it.completed == it.total })
        assertTrue(events.any { it.stage == PhysicalProgressStage.ROUTING && it.completed == it.total })
        assertTrue(
            events.any {
                it.stage == PhysicalProgressStage.ELECTRICAL_FINALIZATION && it.completed == 1 && it.total == 1
            },
        )
    }

    @Test
    fun `high fanout remains compact without detailed search`() {
        val width = 64
        val netlist = booleanNetlist("fanout-$width") {
            val shared = input("shared")
            repeat(width) { bit ->
                val data = input("data[$bit]")
                output("q[$bit]", and(shared, data))
            }
        }
        val design = PhysicalCompiler().compile(netlist, PhysicalIo.TERMINALS)
        val routed = design.routes.sumOf { it.routeBlocks.size }
        assertTrue(maxOf(design.matrix.width, design.matrix.length) < 400)
        assertTrue(routed < 10_000)
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
    }
}
