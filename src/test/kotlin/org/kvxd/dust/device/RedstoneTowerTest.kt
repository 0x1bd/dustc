package org.kvxd.dust.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.block.ContainerBlockEntity
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.device.redstone.AttachFace
import org.kvxd.dust.device.redstone.Redstone
import org.kvxd.dust.device.redstone.WireSides
import org.kvxd.dust.sim.GateLevelSimulator

class RedstoneTowerTest {
    @Test
    fun `glass dust tower carries power upward only`() {
        val matrix = BlockMatrix(5, 8, 5)
        val dust = WireSides.CROSS.applyTo(BlockType.REDSTONE_WIRE.defaultState)
        val glass = BlockType.WHITE_STAINED_GLASS.defaultState
        val wool = BlockType.WHITE_WOOL.defaultState
        val lever = BlockType.LEVER.defaultState
            .with(Properties.FACE, AttachFace.FLOOR)
            .with(Properties.FACING, Direction.NORTH)
        val levels = (1..5).map { y -> BlockPos(3, y, if ((y - 1) % 2 == 0) 1 else 2) }
        levels.forEach { pos ->
            matrix.setBlockAt(pos.offset(Direction.DOWN), glass)
            matrix.setBlockAt(pos, dust)
        }
        val bottom = BlockPos(2, 1, 1)
        val top = BlockPos(2, levels.last().y, levels.last().z)
        listOf(bottom, top).forEach { pos ->
            matrix.setBlockAt(pos.offset(Direction.DOWN), wool)
            matrix.setBlockAt(pos, lever)
        }
        val simulator = GateLevelSimulator(matrix)
        simulator.settle(100)
        simulator.setInput(bottom, true)
        simulator.advanceUntilIdle(100)
        assertEquals(listOf(15, 14, 13, 12, 11), levels.map(simulator::signalAt))
        simulator.setInput(bottom, false)
        simulator.setInput(top, true)
        simulator.advanceUntilIdle(100)
        assertEquals(listOf(0, 0, 0, 0, 15), levels.map(simulator::signalAt))
        assertTrue(simulator.unsettled().isEmpty())
    }

    @Test
    fun `glass route support does not relay strong power`() {
        val matrix = BlockMatrix(5, 5, 5)
        val support = BlockPos(2, 2, 2)
        val lever = BlockType.LEVER.defaultState
            .with(Properties.FACE, AttachFace.WALL)
            .with(Properties.FACING, Direction.EAST)
            .with(Properties.POWERED, true)
        matrix.setBlockAt(support, BlockType.WHITE_STAINED_GLASS.defaultState)
        matrix.setBlockAt(support.offset(Direction.EAST), lever)
        assertEquals(0, Redstone.redstonePower(matrix, support, Direction.NORTH))
        matrix.setBlockAt(support, BlockType.WHITE_WOOL.defaultState)
        assertEquals(Redstone.maximumSignalStrength, Redstone.redstonePower(matrix, support, Direction.NORTH))
    }

    @Test
    fun `wall torch does not read dust as its mounting block`() {
        val matrix = BlockMatrix(4, 4, 4)
        val torchPos = BlockPos(2, 2, 2)
        val dustPos = torchPos.offset(Direction.WEST)
        matrix.setBlockAt(
            torchPos,
            BlockType.REDSTONE_WALL_TORCH.defaultState
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.LIT, true),
        )
        matrix.setBlockAt(
            dustPos,
            WireSides.CROSS.applyTo(BlockType.REDSTONE_WIRE.defaultState.with(Properties.POWER, 15)),
        )
        assertEquals(false, Redstone.anyTorchShouldBeOff(matrix, torchPos))
    }

    @Test
    fun `standing torch reads its block below and does not power it`() {
        val matrix = BlockMatrix(5, 5, 5)
        val support = BlockPos(2, 1, 2)
        val torch = support.offset(Direction.UP)
        val lever = support.offset(Direction.EAST)
        matrix.setBlockAt(support, BlockType.WHITE_WOOL.defaultState)
        matrix.setBlockAt(torch, BlockType.REDSTONE_TORCH.defaultState)
        matrix.setBlockAt(
            lever,
            BlockType.LEVER.defaultState
                .with(Properties.FACING, Direction.EAST)
                .with(Properties.POWERED, true),
        )

        assertTrue(Redstone.anyTorchShouldBeOff(matrix, torch))
        assertEquals(0, Redstone.weakPower(matrix, torch, Direction.UP))
        assertEquals(Redstone.maximumSignalStrength, Redstone.weakPower(matrix, torch, Direction.NORTH))
        assertEquals(Redstone.maximumSignalStrength, Redstone.strongPower(matrix, torch, Direction.DOWN))
    }

    @Test
    fun `powered side comparator locks a repeater`() {
        val matrix = BlockMatrix(6, 3, 6)
        val repeater = BlockPos(2, 1, 2)
        val comparator = repeater.offset(Direction.EAST)
        val barrel = comparator.offset(Direction.EAST)
        matrix.setBlockAt(
            repeater,
            BlockType.REPEATER.defaultState.with(Properties.FACING, Direction.NORTH),
        )
        matrix.setBlockAt(
            comparator,
            BlockType.COMPARATOR.defaultState.with(Properties.FACING, Direction.EAST),
        )
        matrix.setBlockAt(
            barrel,
            BlockType.BARREL.defaultState.with(Properties.BLOCK_FACING, Direction.UP),
        )
        matrix.setBlockEntityAt(barrel, ContainerBlockEntity.barrelSignal(1))

        assertTrue(Redstone.repeaterShouldBeLocked(matrix, repeater, Direction.NORTH))
    }
}
