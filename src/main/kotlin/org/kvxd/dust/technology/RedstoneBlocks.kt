package org.kvxd.dust.technology

import org.kvxd.dust.device.redstone.AttachFace
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.block.BlockType
import org.kvxd.dust.device.redstone.ComparatorMode
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.device.redstone.WireSides

object RedstoneBlocks {

    val dust: BlockState = WireSides.CROSS.applyTo(BlockType.REDSTONE_WIRE.defaultState)

    val opaqueSupport: BlockState = BlockType.WHITE_WOOL.defaultState

    val glassSupport: BlockState = BlockType.WHITE_STAINED_GLASS.defaultState

    val cellSupport: BlockState = BlockType.LIME_WOOL.defaultState

    val lamp: BlockState = BlockType.REDSTONE_LAMP.defaultState

    val ioSign: BlockState = BlockType.OAK_WALL_SIGN.defaultState
        .with(Properties.FACING, Direction.NORTH)

    val floorLever: BlockState = BlockType.LEVER.defaultState
        .with(Properties.FACE, AttachFace.FLOOR)
        .with(Properties.FACING, Direction.NORTH)

    fun repeater(travel: Direction, delay: Int = Properties.DELAY.min): BlockState {
        require(travel.isHorizontal) { "a repeater cannot travel $travel" }
        require(Properties.DELAY.accepts(delay)) { "repeater delay $delay is out of range" }
        return BlockType.REPEATER.defaultState
            .with(Properties.FACING, travel.opposite)
            .with(Properties.DELAY, delay)
    }

    fun comparator(travel: Direction, mode: ComparatorMode): BlockState {
        require(travel.isHorizontal) { "a comparator cannot travel $travel" }
        return BlockType.COMPARATOR.defaultState
            .with(Properties.FACING, travel.opposite)
            .with(Properties.MODE, mode)
    }

    fun wallTorch(facing: Direction): BlockState {
        require(facing.isHorizontal) { "a wall torch cannot mount $facing" }
        return BlockType.REDSTONE_WALL_TORCH.defaultState.with(Properties.FACING, facing)
    }

    fun barrel(facing: Direction = Direction.NORTH): BlockState =
        BlockType.BARREL.defaultState.with(Properties.FACING, facing)
}
