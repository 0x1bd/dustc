package org.kvxd.dust.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.geometry.BlockPos

class BlockMatrixTest {
    @Test
    fun `sparse chunks preserve blocks across boundaries and remove empty chunks`() {
        val matrix = BlockMatrix(35, 19, 34)
        val positions = listOf(
            BlockPos(0, 0, 0),
            BlockPos(15, 15, 15),
            BlockPos(16, 16, 16),
            BlockPos(34, 18, 33),
        )
        positions.forEach { matrix.setBlockAt(it, BlockType.WHITE_WOOL.defaultState) }

        assertEquals(positions.size, matrix.blockCount())
        assertEquals(positions, buildList {
            matrix.forEachOccupiedPosition { position, _ -> add(position) }
        })
        positions.forEach { assertEquals(BlockType.WHITE_WOOL, matrix.blockAt(it).type) }

        positions.forEach { matrix.setBlockAt(it, BlockState.AIR) }
        assertEquals(0, matrix.blockCount())
        assertEquals(emptyList(), buildList {
            matrix.forEachOccupiedPosition { position, _ -> add(position) }
        })
    }

    @Test
    fun `copy has independent sparse state and dense iteration order`() {
        val matrix = BlockMatrix(18, 2, 3)
        matrix[17, 1, 2] = BlockType.REDSTONE_LAMP.defaultState
        matrix[0, 0, 0] = BlockType.WHITE_WOOL.defaultState
        val copy = matrix.copy()

        assertNotSame(matrix, copy)
        copy[0, 0, 0] = BlockState.AIR
        assertEquals(BlockType.WHITE_WOOL, matrix[0, 0, 0].type)
        assertEquals(BlockType.AIR, copy[0, 0, 0].type)
        assertEquals(matrix.volume, buildList {
            matrix.forEachPosition { x, y, z, _ -> add(BlockPos(x, y, z)) }
        }.size)
        assertEquals(
            listOf(BlockPos(0, 0, 0), BlockPos(17, 1, 2)),
            buildList { matrix.forEachOccupiedPosition { position, _ -> add(position) } },
        )
    }

    @Test
    fun `dense chunks retain their counts and support removal`() {
        val matrix = BlockMatrix(16, 16, 16)
        repeat(1_200) { index ->
            val x = index % 16
            val z = (index / 16) % 16
            val y = index / (16 * 16)
            matrix[x, y, z] = BlockType.WHITE_WOOL.defaultState
        }

        assertEquals(1_200, matrix.blockCount())
        repeat(1_200) { index ->
            val x = index % 16
            val z = (index / 16) % 16
            val y = index / (16 * 16)
            assertEquals(BlockType.WHITE_WOOL, matrix[x, y, z].type)
            matrix[x, y, z] = BlockState.AIR
        }
        assertEquals(0, matrix.blockCount())
    }
}
