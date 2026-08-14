package org.kvxd.dust.technology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.device.redstone.AttachFace
import org.kvxd.dust.sim.GateLevelSimulator

class DisplayMatrixCellTest {
    private val library = MinecraftRedstone.technology.cellLibrary

    @Test
    fun `display dimensions span eight through sixty four pixels`() {
        listOf(8 to 8, 32 to 16, 64 to 64).forEach { (width, height) ->
            val specialization = library.specialize("display_matrix", listOf(width, height))
            val cell = checkNotNull(MinecraftRedstone.technology.physicalCell(specialization.logicalType))
            assertEquals(CellSize(width * 2, height * 2 + 1, 7), cell.size)
            assertEquals(width * height, cell.pins.size)
            assertEquals(width * height, cell.observations.size)
            assertEquals(width * height * 4, cell.blocks.count { (_, state) -> state.type == BlockType.REDSTONE_LAMP })
            assertEquals(width * height * 4, cell.blocks.count { (_, state) -> state.type == BlockType.REDSTONE_WALL_TORCH })
            assertEquals(width * height * 2, cell.blocks.count { (_, state) -> state.type == BlockType.REPEATER })
            assertEquals(width * height * 2, cell.blocks.count { (_, state) -> state.type == BlockType.REDSTONE_WIRE })
            assertEquals(width * height * 21, cell.blocks.size)
            assertIs<CellImplementation.HardMacro>(cell.implementation)
        }

        listOf(7 to 8, 8 to 7, 65 to 8, 8 to 65).forEach { dimensions ->
            assertFailsWith<IllegalArgumentException> {
                library.specialize("display_matrix", listOf(dimensions.first, dimensions.second))
            }
        }
    }

    @Test
    fun `each matrix input drives exactly one four lamp pixel`() {
        val cell = library.specialize("display_matrix", listOf(8, 8)).physicalView
        val matrix = BlockMatrix(cell.size.x, cell.size.y, cell.size.z + 1)
        cell.blocks.forEach { (position, state) -> matrix.setBlockAt(position, state) }
        val selectedX = 3
        val selectedY = 5
        val selected = cell.pin("pixels[${selectedY * 8 + selectedX}]").position
        val input = selected.offset(Direction.SOUTH)
        matrix.setBlockAt(input.offset(Direction.DOWN), RedstoneBlocks.opaqueSupport)
        matrix.setBlockAt(
            input,
            BlockType.LEVER.defaultState
                .with(Properties.FACE, AttachFace.FLOOR)
                .with(Properties.FACING, Direction.NORTH),
        )
        val selectedLamps = pixelLamps(selectedX, selectedY)
        val otherLamps = pixelLamps(selectedX + 1, selectedY) + pixelLamps(selectedX, selectedY - 1)
        val simulator = GateLevelSimulator(matrix)
        val bound = matrix.blockCount() * 2
        simulator.settle(bound)
        assertEquals(List(4) { false }, selectedLamps.map(simulator::levelAt))

        simulator.setInput(input, true)
        simulator.advanceUntilIdle(bound)
        assertEquals(List(4) { true }, selectedLamps.map(simulator::levelAt))
        assertEquals(List(8) { false }, otherLamps.map(simulator::levelAt))

        simulator.setInput(input, false)
        simulator.advanceUntilIdle(bound)
        assertEquals(List(4) { false }, selectedLamps.map(simulator::levelAt))
    }

    private fun pixelLamps(x: Int, y: Int): List<BlockPos> {
        val origin = BlockPos(x * 2, y * 2, 0)
        return listOf(
            origin + BlockPos(0, 1, 0),
            origin + BlockPos(1, 1, 0),
            origin + BlockPos(0, 2, 0),
            origin + BlockPos(1, 2, 0),
        )
    }
}
