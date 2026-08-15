package org.kvxd.dust.device

import kotlin.test.Test
import kotlin.test.assertEquals
import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.block.ContainerBlockEntity
import org.kvxd.dust.device.block.SignBlockEntity
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.emit.SchematicReader
import org.kvxd.dust.emit.SchematicWriter

class ContainerBlockEntityTest {
    @Test
    fun `barrel inventories encode every comparator strength exactly`() {
        (0..15).forEach { strength ->
            assertEquals(strength, ContainerBlockEntity.barrelSignal(strength).comparatorOutput)
        }
    }

    @Test
    fun `barrel inventory survives schematic round trip`() {
        val matrix = BlockMatrix(1, 1, 1)
        val position = BlockPos.ORIGIN
        val barrel = ContainerBlockEntity.barrelSignal(11)
        matrix.setBlockAt(position, BlockType.BARREL.defaultState.with(Properties.BLOCK_FACING, Direction.EAST))
        matrix.setBlockEntityAt(position, barrel)

        val read = SchematicReader().read(SchematicWriter().write(matrix, "barrel-round-trip"))

        assertEquals(matrix.blockAt(position), read.blockAt(position))
        assertEquals(barrel, read.blockEntityAt(position))
    }

    @Test
    fun `sign text survives schematic round trip`() {
        val matrix = BlockMatrix(1, 1, 1)
        val position = BlockPos.ORIGIN
        val sign = SignBlockEntity(listOf("IN \"controls\"", "select[0] \\"))
        matrix.setBlockAt(
            position,
            BlockType.OAK_WALL_SIGN.defaultState.with(Properties.FACING, Direction.NORTH),
        )
        matrix.setBlockEntityAt(position, sign)

        val read = SchematicReader().read(SchematicWriter().write(matrix, "sign-round-trip"))

        assertEquals(matrix.blockAt(position), read.blockAt(position))
        assertEquals(sign, read.blockEntityAt(position))
    }
}
