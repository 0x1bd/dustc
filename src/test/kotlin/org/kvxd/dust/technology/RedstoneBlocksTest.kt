package org.kvxd.dust.technology

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.kvxd.dust.device.redstone.ComparatorMode
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties

class RedstoneBlocksTest {
    @Test
    fun `only the block vocabulary and the device model construct block states`() {
        val offenders = mainSources()
            .filter { path ->
                val relative = path.invariantSeparatorsPathString
                !relative.contains("/org/kvxd/dust/device/") &&
                    !relative.endsWith("/RedstoneBlocks.kt")
            }
            .flatMap { path ->
                path.readText().lineSequence().mapIndexedNotNull { index, line ->
                    val code = line.substringBefore("//")
                    if (".defaultState" in code) {
                        "${path.fileName}:${index + 1}${line.trim().let { " $it" }}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertEquals(
            emptyList(),
            offenders,
            "these sites choose blocks of their own; take them from RedstoneBlocks instead",
        )
    }

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
    fun `the two supports are not the same block`() {
        assertTrue(RedstoneBlocks.opaqueSupport.type.isSolid, "a via tread needs an opaque support")
        assertTrue(RedstoneBlocks.glassSupport.type.isTransparent, "a route bed must not conduct")
    }

    private fun mainSources(): Sequence<Path> =
        Path.of("src", "main", "kotlin").walk().filter { it.extension == "kt" }
}
