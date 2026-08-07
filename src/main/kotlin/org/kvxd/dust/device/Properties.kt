package org.kvxd.dust.device

object Properties {
    val FACING = EnumProperty("facing", Direction.HORIZONTALS)
    val POWERED = BooleanProperty("powered")
    val LIT = BooleanProperty("lit")
    val LOCKED = BooleanProperty("locked")
    val DELAY = IntProperty("delay", 1, 4)
    val POWER = IntProperty("power", 0, 15)
    val FACE = EnumProperty("face", AttachFace.entries.toList())
    val MODE = EnumProperty("mode", ComparatorMode.entries.toList())
    val OPEN = BooleanProperty("open")
    val TRIGGERED = BooleanProperty("triggered")
    val WATERLOGGED = BooleanProperty("waterlogged")
    val WIRE_NORTH = wireSide("north")
    val WIRE_SOUTH = wireSide("south")
    val WIRE_EAST = wireSide("east")
    val WIRE_WEST = wireSide("west")

    fun wireSide(direction: Direction): EnumProperty<WireConnection> = when (direction) {
        Direction.NORTH -> WIRE_NORTH
        Direction.SOUTH -> WIRE_SOUTH
        Direction.EAST -> WIRE_EAST
        Direction.WEST -> WIRE_WEST
        else -> throw IllegalArgumentException("$direction is not horizontal")
    }

    private fun wireSide(name: String) = EnumProperty(name, WireConnection.entries.toList())
}
