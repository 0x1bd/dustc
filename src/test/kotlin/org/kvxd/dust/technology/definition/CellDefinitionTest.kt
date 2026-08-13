package org.kvxd.dust.technology.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.technology.CellSize
import org.kvxd.dust.cell.library.BuiltinCells

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

    private fun parse(text: String): CellDefinition = CellDefinitionParser.parse("fixture.txt", text.trimIndent())
}
