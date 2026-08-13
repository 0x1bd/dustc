package org.kvxd.dust.technology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.definition.CellPort
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.library.BuiltinCells
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.cell.library.CellParameter
import org.kvxd.dust.cell.library.CellProvider
import org.kvxd.dust.cell.timing.CellTiming
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.booleanNetlist
import org.kvxd.dust.physical.PhysicalCompiler
import org.kvxd.dust.sim.GateLevelSimulator

class OpenCellLibraryTest {
    @Test
    fun `parameterized providers validate arguments and cache specializations`() {
        var logicalBuilds = 0
        var physicalBuilds = 0
        val provider = CellProvider(
            "display",
            listOf(
                CellParameter("width", 8..32),
                CellParameter("height", 8..32, default = 8),
            ),
            logicalView = { parameters ->
                logicalBuilds++
                bufferType("display-${parameters.getValue("width")}x${parameters.getValue("height")}")
            },
            physicalView = { logical, _ ->
                physicalBuilds++
                bufferCell(logical)
            },
        )
        val library = CellLibrary(listOf(provider))

        val first = library.specialize("display", listOf(13))
        val cached = library.specialize("display", listOf(13, 8))
        val other = library.specialize("display", listOf(16, 9))

        assertSame(first, cached)
        assertNotSame(first, other)
        assertEquals(CellTypeId("display-13x8"), first.logicalType.id)
        assertEquals(2, logicalBuilds)
        assertEquals(2, physicalBuilds)
        assertSame(first.physicalView, library.physicalView(first.logicalType))
        assertFailsWith<IllegalArgumentException> { library.specialize("display") }
        assertFailsWith<IllegalArgumentException> { library.specialize("display", listOf(7)) }
        assertFailsWith<IllegalArgumentException> { library.specialize("missing") }
    }

    @Test
    fun `physical views require complete unique direction-correct pins`() {
        val logical = bufferType("validated")

        assertFailsWith<IllegalArgumentException> {
            bufferCell(logical, pins = listOf(CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 1))))
        }
        assertFailsWith<IllegalArgumentException> {
            bufferCell(
                logical,
                pins = listOf(
                    CellPin("a", PinDirection.OUTPUT, BlockPos(0, 1, 1)),
                    CellPin("y", PinDirection.OUTPUT, BlockPos(2, 1, 1)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            bufferCell(
                logical,
                pins = listOf(
                    CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 1)),
                    CellPin("also-a", PinDirection.INPUT, BlockPos(1, 1, 1), port = "a"),
                    CellPin("y", PinDirection.OUTPUT, BlockPos(2, 1, 1)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            bufferCell(
                logical,
                pins = listOf(
                    CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 1)),
                    CellPin("y", PinDirection.OUTPUT, BlockPos(0, 1, 1)),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            bufferCell(
                logical,
                pins = listOf(
                    CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 1), requiredStrength = 0),
                    CellPin("y", PinDirection.OUTPUT, BlockPos(2, 1, 1)),
                ),
            )
        }
    }

    @Test
    fun `registered non-primitive hard macro is placed routed and simulated`() {
        val logical = inverterType("registered-inverter")
        val template = checkNotNull(MinecraftRedstone.technology.physicalCell(BuiltinCells.not))
        val provider = CellProvider.fixed(
            logical,
            physicalView = {
                StandardCell(
                    it.id.value,
                    it,
                    template.size,
                    template.pins,
                    template.blocks,
                    CellImplementation.HardMacro(
                        exclusiveRow = true,
                        visibleEdge = InterfaceEdge.NORTH,
                    ),
                    observations = listOf(CellObservation("output", template.pin("y").position)),
                )
            },
        )
        val library = CellLibrary(listOf(provider))
        val registered = library.specialize("registered-inverter")
        val technology = technologyWith(library)
        val netlist = booleanNetlist("registered") {
            val input = input("a")
            val output = instance(
                registered.logicalType,
                mapOf("a" to listOf(input)),
                name = "hard-buffer",
            ).getValue("y").single()
            output("y", output)
        }

        assertTrue(netlist.gates.isEmpty(), "the test cell must not be a closed Boolean primitive")
        assertEquals(false, netlist.evaluate(mapOf("a" to true)).getValue("y"))
        assertEquals(0b10L.inv(), netlist.evaluateWords(mapOf("a" to 0b10L)).getValue("y"))
        val design = PhysicalCompiler(technology).compile(netlist)
        val placed = design.cells.single { it.name == "hard-buffer" }
        assertEquals(0, placed.row, "a north-facing hard macro must occupy the outermost row")
        assertEquals(1, design.cells.count { it.row == placed.row }, "an exclusive hard macro must own its row")
        assertEquals(placed.pin("y"), placed.observations.getValue("output"))
        assertEquals(placed.pin("y"), design.observations.getValue("hard-buffer.output"))

        val simulator = GateLevelSimulator(design.matrix)
        val bound = design.matrix.blockCount()
        simulator.settle(bound)
        listOf(true, false, true).forEach { value ->
            simulator.setInput(design.inputs.getValue("a"), value)
            simulator.advanceUntilIdle(bound)
            assertEquals(!value, simulator.readOutput(design.outputs.getValue("y")))
        }
        assertTrue(simulator.unsettled().isEmpty())
    }

    private fun technologyWith(library: CellLibrary): RedstoneTechnology {
        val base = MinecraftRedstone.technology
        return RedstoneTechnology(
            cellLibrary = library,
            debugInputPad = base.debugInputPad,
            debugOutputPad = base.debugOutputPad,
            inputTerminal = base.inputTerminal,
            outputTerminal = base.outputTerminal,
            ioSign = base.ioSign,
            wire = base.wire,
            routeSupport = base.routeSupport,
            viaSupport = base.viaSupport,
            isolation = base.isolation,
            lowerPlaneY = base.lowerPlaneY,
            viaSignalOffsets = base.viaSignalOffsets,
        )
    }

    private companion object {
        fun bufferType(id: String): CellType = CellType(
            CellTypeId(id),
            listOf(
                CellPort("a", 1, PortDirection.INPUT),
                CellPort("y", 1, PortDirection.OUTPUT),
            ),
            CellBehavior.Combinational { values -> mapOf("y" to values.getValue("a").copyOf()) },
            CellTiming.NONE,
        )

        fun inverterType(id: String): CellType = CellType(
            CellTypeId(id),
            listOf(
                CellPort("a", 1, PortDirection.INPUT),
                CellPort("y", 1, PortDirection.OUTPUT),
            ),
            CellBehavior.Combinational { values ->
                mapOf("y" to booleanArrayOf(!values.getValue("a").single()))
            },
            BuiltinCells.not.timing,
        )

        fun bufferCell(
            logical: CellType,
            pins: List<CellPin> = listOf(
                CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 1)),
                CellPin("y", PinDirection.OUTPUT, BlockPos(2, 1, 1), driveStrength = 13),
            ),
            implementation: CellImplementation = CellImplementation.Standard,
        ): StandardCell = StandardCell(
            logical.id.value,
            logical,
            CellSize(3, 2, 2),
            pins,
            blocks = buildList {
                repeat(3) { x ->
                    add(BlockPos(x, 0, 1) to RedstoneBlocks.cellSupport)
                    add(BlockPos(x, 1, 1) to RedstoneBlocks.dust)
                }
            },
            implementation = implementation,
        )
    }
}
