package org.kvxd.dust.emit

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.block.ContainerBlockEntity
import org.kvxd.dust.device.block.ItemStack
import org.kvxd.dust.device.block.SignBlockEntity

class SchematicReader {
    fun read(bytes: ByteArray): BlockMatrix {
        val body = BinaryTagIO.unlimitedReader()
            .readNamed(bytes.inputStream(), BinaryTagIO.Compression.GZIP)
            .value
        val width = body.getShort("Width").toInt()
        val height = body.getShort("Height").toInt()
        val length = body.getShort("Length").toInt()
        require(width > 0 && height > 0 && length > 0)

        val paletteTag = body.getCompound("Palette")
        val palette = paletteTag.keySet().associate { key ->
            paletteTag.getInt(key) to BlockState.parse(key)
        }
        val data = body.getByteArray("BlockData")
        val matrix = BlockMatrix(width, height, length)
        var cursor = 0
        matrix.forEachPosition { x, y, z, _ ->
            var value = 0
            var shift = 0
            do {
                val byte = data[cursor++].toInt()
                value = value or ((byte and 127) shl shift)
                shift += 7
            } while (byte and 128 != 0)
            matrix[x, y, z] = checkNotNull(palette[value])
        }
        require(cursor == data.size) { "schematic contains trailing block data" }
        body.getList("BlockEntities", BinaryTagTypes.COMPOUND).forEach { raw ->
            val tag = raw as CompoundBinaryTag
            val pos = tag.getIntArray("Pos")
            require(pos.size == 3)
            when (val id = tag.getString("Id")) {
                "minecraft:barrel" -> {
                    val items = tag.getList("Items", BinaryTagTypes.COMPOUND).map { rawItem ->
                        val item = rawItem as CompoundBinaryTag
                        ItemStack(
                            item.getByte("Slot").toInt() and 0xff,
                            item.getString("id"),
                            item.getByte("Count").toInt() and 0xff,
                        )
                    }
                    matrix.setBlockEntityAt(
                        BlockPos(pos[0], pos[1], pos[2]),
                        ContainerBlockEntity(ContainerBlockEntity.BARREL_SLOTS, items, id),
                    )
                }

                "minecraft:sign" -> {
                    val front = tag.getCompound("front_text")
                    val lines = front.getList("messages", BinaryTagTypes.STRING).map { message ->
                        parseTextComponent((message as StringBinaryTag).value())
                    }.dropLastWhile(String::isEmpty)
                    matrix.setBlockEntityAt(
                        BlockPos(pos[0], pos[1], pos[2]),
                        SignBlockEntity(
                            lines,
                            front.getString("color"),
                            front.getByte("has_glowing_text").toInt() != 0,
                            id,
                        ),
                    )
                }

                else -> error("unsupported block entity '$id'")
            }
        }
        return matrix
    }

    private fun parseTextComponent(component: String): String {
        val prefix = "{\"text\":\""
        require(component.startsWith(prefix) && component.endsWith("\"}")) {
            "unsupported sign text component '$component'"
        }
        val escaped = component.substring(prefix.length, component.length - 2)
        return buildString {
            var index = 0
            while (index < escaped.length) {
                val character = escaped[index++]
                if (character != '\\') {
                    append(character)
                    continue
                }
                require(index < escaped.length) { "unterminated sign text escape" }
                when (val escape = escaped[index++]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> {
                        require(index + 4 <= escaped.length) { "short sign unicode escape" }
                        append(escaped.substring(index, index + 4).toInt(16).toChar())
                        index += 4
                    }
                    else -> error("unsupported sign text escape '$escape'")
                }
            }
        }
    }
}
