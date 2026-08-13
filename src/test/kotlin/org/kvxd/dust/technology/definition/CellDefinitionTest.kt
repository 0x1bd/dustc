package org.kvxd.dust.technology.definition

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
import org.kvxd.dust.cell.timing.CellTiming
import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.block.ContainerBlockEntity
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.device.redstone.ComparatorMode
import org.kvxd.dust.emit.SchematicReader
import org.kvxd.dust.emit.SchematicWriter
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.technology.CellSize
import org.kvxd.dust.cell.library.BuiltinCells
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.technology.CellImplementation
import org.kvxd.dust.technology.MinecraftRedstone

class CellDefinitionTest {
    private val templates = mapOf(
        "support" to BlockType.LIME_WOOL.defaultState,
        "signal" to BlockType.REDSTONE_WIRE.defaultState,
    )

    @Test
    fun `parser builds a definition model from each section`() {
        val definition = parse(
            """
            cell not

            palette:
            # = support
            + = signal

            pins:
            a input @0,1,1 abut=false required=2
            y output @2,1,1 drive=12

            layers:
            @0
            ###
            #.#
            @1
            +++
            +.+
            """,
        )

        assertEquals("not", definition.name)
        assertEquals(listOf(CellPaletteEntry('#', "support"), CellPaletteEntry('+', "signal")), definition.palette)
        assertEquals(2, definition.pins.size)
        assertEquals(BlockPos(0, 1, 1), definition.pins.first().position)
        assertEquals(false, definition.pins.first().allowsHorizontalAbutment)
        assertEquals(2, definition.pins.first().requiredStrength)
        assertEquals(12, definition.pins.last().driveStrength)
        assertEquals(listOf(0, 1), definition.layers.map { it.y })
    }

    @Test
    fun `factory derives size from synthetic layers and pins`() {
        val definition = parse(
            """
            cell not
            palette:
            # = support
            pins:
            a input @0,2,3
            y output @4,2,3
            layers:
            @0
            #####
            .....
            .....
            .....
            """,
        )
        CellDefinitionValidator.validate(definition, templates.keys)

        val cell = StandardCellFactory(templates) { error("no includes expected") }
            .create(definition, BuiltinCells.not)

        assertEquals(CellSize(5, 3, 4), cell.size)
        assertEquals(setOf(BlockPos(0, 0, 0), BlockPos(1, 0, 0), BlockPos(2, 0, 0), BlockPos(3, 0, 0), BlockPos(4, 0, 0)), cell.blocks.map { it.first }.toSet())
    }

    @Test
    fun `factory includes synthetic cell geometry in derived size`() {
        val included = StandardCellFactory(templates) { error("no nested includes expected") }.create(
            parse(
                """
                cell not
                palette:
                # = support
                pins:
                a input @0,1,1
                y output @1,1,1
                layers:
                @0
                ##
                ..
                """,
            ),
            BuiltinCells.not,
        )
        val composite = parse(
            """
            cell and2
            pins:
            a input @0,2,4
            b input @2,2,4
            y output @5,2,4
            layout:
            include not @4,1,2
            """,
        )
        CellDefinitionValidator.validate(composite, templates.keys)

        val cell = StandardCellFactory(templates) { included }.create(composite, BuiltinCells.and2)

        assertEquals(CellSize(6, 3, 5), cell.size)
        assertTrue(BlockPos(4, 1, 2) in cell.blocks.map { it.first })
        assertTrue(BlockPos(5, 1, 2) in cell.blocks.map { it.first })
    }

    @Test
    fun `parser rejects malformed pin options`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            parse(
                """
                cell bad
                pins:
                a input @0,0,0 abut=perhaps
                """,
            )
        }
        assertTrue("abut must be true or false" in exception.message.orEmpty())
    }

    @Test
    fun `validator rejects undefined layer symbols`() {
        val definition = parse(
            """
            cell bad
            pins:
            a input @0,0,0
            layers:
            @0
            X
            """,
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            CellDefinitionValidator.validate(definition, templates.keys)
        }
        assertTrue("undefined symbol 'X'" in exception.message.orEmpty())
    }

    @Test
    fun `validator rejects ragged layers`() {
        val definition = parse(
            """
            cell bad
            palette:
            # = support
            pins:
            a input @0,0,1
            layers:
            @0
            ##
            #
            """,
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            CellDefinitionValidator.validate(definition, templates.keys)
        }
        assertTrue("is ragged" in exception.message.orEmpty())
    }

    @Test
    fun `parameterized macro expands nested loops expressions states entities observations and metadata`() {
        val definition = CellDefinitionParser.parse(
            "generated-cell.txt",
            GENERATED_CELL,
            mapOf("WIDTH" to 3, "HEIGHT" to 2),
        )
        CellDefinitionValidator.validate(definition, templates.keys)
        val cell = StandardCellFactory(templates) { _: String -> error("no includes expected") }
            .create(definition, generatedType(3))

        assertEquals(listOf("a[0]", "y[0]", "a[1]", "y[1]", "a[2]", "y[2]"), cell.pins.map { it.name })
        assertEquals(7, cell.observations.size)
        assertEquals(BlockPos(2, 0, 1), cell.observations.single { it.name == "pixel[2,1]" }.position)
        assertEquals(4, cell.blocks.toMap().getValue(BlockPos(0, 1, 0))[Properties.DELAY])
        assertEquals(ComparatorMode.SUBTRACT, cell.blocks.toMap().getValue(BlockPos(1, 1, 0))[Properties.MODE])
        assertEquals(9, (cell.blockEntities.getValue(BlockPos(2, 1, 0)) as ContainerBlockEntity).comparatorOutput)
        assertEquals(3, cell.timing.arcs.single().rise.maxTicks)
        assertEquals(CellImplementation.HardMacro(true, InterfaceEdge.NORTH), cell.implementation)
    }

    @Test
    fun `generated nested inclusions retain parameter arguments`() {
        val definition = CellDefinitionParser.parse(
            "includes.txt",
            """
            cell wrapper<COUNT: int in 1..3 = 2>
            pins:
            a input @0,1,3
            y output @8,1,3
            layout:
            for x in 0..COUNT {
                include tile<x + 1> @(x * 3),0,0
            }
            """.trimIndent(),
        )
        val arguments = mutableListOf<List<Int>>()
        val tile = MinecraftRedstone.technology.physicalCell(BuiltinCells.not) ?: error("missing not cell")
        val cell = StandardCellFactory(templates) { name, values ->
            assertEquals("tile", name)
            arguments += values
            tile
        }.create(definition, BuiltinCells.not)

        assertEquals(listOf(listOf(1), listOf(2)), arguments)
        assertTrue(cell.blocks.isNotEmpty())
    }

    @Test
    fun `generated definitions are deterministic and loader caches by parameter values`() {
        val firstDefinition = CellDefinitionParser.parse("generated-cell.txt", GENERATED_CELL)
        val secondDefinition = CellDefinitionParser.parse("generated-cell.txt", GENERATED_CELL)
        assertEquals(firstDefinition, secondDefinition)

        val loader = CellDefinitionLoader(templates) { name, arguments ->
            assertEquals("generated-test", name)
            generatedType(arguments.getValue("WIDTH"))
        }
        val first = loader.load("generated-test", listOf(2))
        val cached = loader.load("generated-test", listOf(2))
        val other = loader.load("generated-test", listOf(3))
        assertSame(first, cached)
        assertNotSame(first, other)

        val providerLoader = CellDefinitionLoader(templates) { _, arguments -> generatedType(arguments.getValue("WIDTH")) }
        val library = CellLibrary(
            listOf(
                providerLoader.provider(
                    "generated-test",
                    logicalView = { arguments -> generatedType(arguments.getValue("WIDTH")) },
                ),
            ),
        )
        val registered = library.specialize("generated-test", listOf(2))
        assertSame(registered, library.specialize("generated-test", listOf(2)))
        assertSame(registered.physicalView, library.physicalView(registered.logicalType))
    }

    @Test
    fun `invalid parameters and generated collisions report definition locations`() {
        val invalid = assertFailsWith<IllegalArgumentException> {
            CellDefinitionParser.parse("bad-parameter.cell", GENERATED_CELL, mapOf("WIDTH" to 7, "HEIGHT" to 2))
        }
        assertTrue(invalid.message.orEmpty().startsWith("bad-parameter.cell:1:"))

        val collision = parse(
            """
            cell not
            palette:
            # = support
            + = signal
            pins:
            a input @0,1,1
            y output @2,1,1
            layout:
            block # @0,0,0
            block + @0,0,0
            """,
        )
        CellDefinitionValidator.validate(collision, templates.keys)
        val overlap = assertFailsWith<IllegalArgumentException> {
            StandardCellFactory(templates) { _: String -> error("no includes expected") }
                .create(collision, BuiltinCells.not)
        }
        assertTrue(overlap.message.orEmpty().startsWith("fixture.txt:10:"))
    }

    @Test
    fun `placed generated macro round trips blocks and block entities`() {
        val definition = CellDefinitionParser.parse("generated-cell.txt", GENERATED_CELL)
        CellDefinitionValidator.validate(definition, templates.keys)
        val cell = StandardCellFactory(templates) { _: String -> error("no includes expected") }
            .create(definition, generatedType(2))
        val origin = BlockPos(2, 1, 3)
        val matrix = BlockMatrix(cell.size.x + 4, cell.size.y + 2, cell.size.z + 5)
        MinecraftRedstone.technology.placeCell(matrix, cell, origin)

        val read = SchematicReader().read(SchematicWriter().write(matrix, "generated"))
        cell.blocks.forEach { (position, state) -> assertEquals(state, read.blockAt(origin + position)) }
        cell.blockEntities.forEach { (position, entity) -> assertEquals(entity, read.blockEntityAt(origin + position)) }
    }

    private fun generatedType(width: Int): CellType = CellType(
        CellTypeId("generated-$width"),
        listOf(
            CellPort("a", width, PortDirection.INPUT),
            CellPort("y", width, PortDirection.OUTPUT),
        ),
        CellBehavior.Combinational { values -> mapOf("y" to values.getValue("a").copyOf()) },
        CellTiming.NONE,
    )

    private fun parse(text: String): CellDefinition = CellDefinitionParser.parse("fixture.txt", text.trimIndent())

    private companion object {
        val GENERATED_CELL = """
            cell generated<WIDTH: int in 1..4 = 2, HEIGHT: int in 1..3 = 2>
            palette:
            # = minecraft:lime_wool
            R = minecraft:repeater[delay=4,facing=north,locked=false,powered=false]
            C = minecraft:comparator[facing=north,mode=subtract,powered=false]
            B = minecraft:barrel[facing=north,open=false]
            pins:
            for x in 0..WIDTH {
                a[x] input @(x * 2), 1, (HEIGHT + 1)
                y[x] output @((WIDTH + x) * 2), 1, (HEIGHT + 1)
            }
            layout:
            for y in 0..HEIGHT {
                for x in 0..WIDTH {
                    block # @x, 0, y
                }
            }
            block R @0,1,0
            block C @1,1,0
            block B @2,1,0
            block-entities:
            barrel @2,1,0 signal=(WIDTH * HEIGHT + 3)
            observations:
            for y in 0..HEIGHT {
                for x in 0..WIDTH {
                    pixel[x,y] @x,0,y
                }
            }
            clock @0,1,0
            timing:
            arc a -> y rise=1..WIDTH fall=2..3
            placement:
            exclusive-row=true
            visible-edge=north
        """.trimIndent()
    }
}
