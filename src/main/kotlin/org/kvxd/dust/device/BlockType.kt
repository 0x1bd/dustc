package org.kvxd.dust.device

enum class BlockType(
    val id: String,
    val properties: List<Property<*>>,
    val component: ComponentKind,
    val isSolid: Boolean = false,
    val isCube: Boolean = false,
    val isTransparent: Boolean = false,
) {
    AIR("minecraft:air", emptyList(), ComponentKind.NONE),
    LIME_WOOL("minecraft:lime_wool", emptyList(), ComponentKind.SUBSTRATE, isSolid = true, isCube = true),
    WHITE_WOOL("minecraft:white_wool", emptyList(), ComponentKind.SUBSTRATE, isSolid = true, isCube = true),
    WHITE_STAINED_GLASS(
        "minecraft:white_stained_glass",
        emptyList(),
        ComponentKind.SUBSTRATE,
        isSolid = true,
        isCube = true,
        isTransparent = true,
    ),
    REDSTONE_WIRE(
        "minecraft:redstone_wire",
        listOf(
            Properties.WIRE_EAST,
            Properties.WIRE_NORTH,
            Properties.POWER,
            Properties.WIRE_SOUTH,
            Properties.WIRE_WEST,
        ),
        ComponentKind.WIRE,
    ),
    REDSTONE_WALL_TORCH(
        "minecraft:redstone_wall_torch",
        listOf(Properties.FACING, Properties.LIT),
        ComponentKind.TORCH,
    ),
    REPEATER(
        "minecraft:repeater",
        listOf(Properties.DELAY, Properties.FACING, Properties.LOCKED, Properties.POWERED),
        ComponentKind.REPEATER,
    ),
    COMPARATOR(
        "minecraft:comparator",
        listOf(Properties.FACING, Properties.MODE, Properties.POWERED),
        ComponentKind.COMPARATOR,
    ),
    BARREL(
        "minecraft:barrel",
        listOf(Properties.FACING, Properties.OPEN),
        ComponentKind.CONTAINER,
        isSolid = true,
        isCube = true,
    ),
    LEVER(
        "minecraft:lever",
        listOf(Properties.FACE, Properties.FACING, Properties.POWERED),
        ComponentKind.LEVER,
    ),
    REDSTONE_LAMP(
        "minecraft:redstone_lamp",
        listOf(Properties.LIT),
        ComponentKind.LAMP,
        isSolid = true,
        isCube = true,
    ),
    OAK_WALL_SIGN(
        "minecraft:oak_wall_sign",
        listOf(Properties.FACING, Properties.WATERLOGGED),
        ComponentKind.NONE,
        isTransparent = true,
    ),
    ;

    val defaultState: BlockState by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BlockState(this, properties.map { defaultValueOf(it) })
    }

    fun property(name: String): Property<*>? = properties.firstOrNull { it.name == name }

    private fun defaultValueOf(property: Property<*>): Any = when (property) {
        Properties.LIT -> this == REDSTONE_WALL_TORCH
        Properties.DELAY -> 1
        Properties.FACE -> AttachFace.WALL
        Properties.MODE -> ComparatorMode.COMPARE
        else -> when (property) {
            is BooleanProperty -> false
            is IntProperty -> property.min
            is EnumProperty<*> -> property.values.first()
        }
    }

    companion object {
        private val byId = entries.associateBy { it.id }

        fun fromId(id: String): BlockType? = byId[id] ?: byId[if (':' in id) id else "minecraft:$id"]
    }
}
