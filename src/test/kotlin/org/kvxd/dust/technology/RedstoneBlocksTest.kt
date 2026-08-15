package org.kvxd.dust.technology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.kvxd.dust.device.redstone.ComparatorMode
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties

class RedstoneBlocksTest {
    @Test
    fun `diodes face the source of their signal`() {
        Direction.ALL.filter { it.isHorizontal }.forEach { travel ->
            assertEquals(travel.opposite, RedstoneBlocks.repeater(travel)[Properties.FACING])
            assertEquals(
                travel.opposite,
                RedstoneBlocks.comparator(travel, ComparatorMode.SUBTRACT)[Properties.FACING],
            )
        }
        assertEquals(
            ComparatorMode.SUBTRACT,
            RedstoneBlocks.comparator(Direction.NORTH, ComparatorMode.SUBTRACT)[Properties.MODE],
        )
    }

    @Test
    fun `route supports preserve their conductivity roles`() {
        assertTrue(RedstoneBlocks.opaqueSupport.type.isSolid, "a via tread needs an opaque support")
        assertTrue(RedstoneBlocks.glassSupport.type.isTransparent, "a route bed must not conduct")
    }
}
