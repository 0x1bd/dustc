package org.kvxd.dust.emit

import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag
import org.kvxd.dust.device.BlockMatrix
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.device.ContainerBlockEntity
import org.kvxd.dust.device.ItemStack
import org.kvxd.dust.device.SignBlockEntity
import java.io.ByteArrayOutputStream
import java.util.AbstractMap

class SchematicWriter {
    fun write(matrix: BlockMatrix, name: String): ByteArray {
        val palette = LinkedHashMap<String, Int>()
        val data = ByteArrayOutputStream(matrix.volume)
        matrix.forEachPosition { _, _, _, state ->
            writeVarInt(data, palette.getOrPut(state.toString()) { palette.size })
        }

        val paletteTag = CompoundBinaryTag.builder().apply {
            palette.forEach { (state, index) -> putInt(state, index) }
        }.build()
        val metadata = CompoundBinaryTag.builder()
            .putString("Name", name)
            .putString("Author", "dustc")
            .putInt("WEOffsetX", 0)
            .putInt("WEOffsetY", 0)
            .putInt("WEOffsetZ", 0)
            .build()
        val blockEntities = ListBinaryTag.builder(BinaryTagTypes.COMPOUND).apply {
            matrix.blockEntities().forEach { (pos, blockEntity) ->
                add(
                    when (blockEntity) {
                        is ContainerBlockEntity -> containerTag(pos, blockEntity)
                        is SignBlockEntity -> signTag(pos, blockEntity)
                    },
                )
            }
        }.build()
        val body = CompoundBinaryTag.builder()
            .putInt("Version", SPONGE_VERSION)
            .putInt("DataVersion", DATA_VERSION_1_20_4)
            .put("Metadata", metadata)
            .putShort("Width", matrix.width.toShort())
            .putShort("Height", matrix.height.toShort())
            .putShort("Length", matrix.length.toShort())
            .putIntArray("Offset", intArrayOf(0, 0, 0))
            .putInt("PaletteMax", palette.size)
            .put("Palette", paletteTag)
            .putByteArray("BlockData", data.toByteArray())
            .put("BlockEntities", blockEntities)
            .build()

        return ByteArrayOutputStream().also { output ->
            BinaryTagIO.writer().writeNamed(
                AbstractMap.SimpleImmutableEntry(ROOT_NAME, body),
                output,
                BinaryTagIO.Compression.GZIP,
            )
        }.toByteArray()
    }

    private fun containerTag(pos: BlockPos, container: ContainerBlockEntity): CompoundBinaryTag =
        CompoundBinaryTag.builder()
            .putString("Id", container.id)
            .putIntArray("Pos", intArrayOf(pos.x, pos.y, pos.z))
            .put(
                "Items",
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND).apply {
                    container.items.forEach { add(itemTag(it)) }
                }.build(),
            )
            .build()

    private fun itemTag(item: ItemStack): CompoundBinaryTag = CompoundBinaryTag.builder()
        .putByte("Slot", item.slot.toByte())
        .putString("id", item.itemId)
        .putByte("Count", item.count.toByte())
        .build()

    private fun signTag(pos: BlockPos, sign: SignBlockEntity): CompoundBinaryTag = CompoundBinaryTag.builder()
        .putString("Id", sign.id)
        .putIntArray("Pos", intArrayOf(pos.x, pos.y, pos.z))
        .put("front_text", signText(sign))
        .put("back_text", signText(sign))
        .putByte("is_waxed", 0)
        .build()

    private fun signText(sign: SignBlockEntity): CompoundBinaryTag = CompoundBinaryTag.builder()
        .putString("color", sign.color)
        .putByte("has_glowing_text", if (sign.glowing) 1 else 0)
        .put(
            "messages",
            ListBinaryTag.builder(BinaryTagTypes.STRING).apply {
                sign.paddedLines.forEach { add(StringBinaryTag.stringBinaryTag(textComponent(it))) }
            }.build(),
        )
        .build()

    private fun textComponent(text: String): String = buildString {
        append("{\"text\":\"")
        text.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append("\"}")
    }

    private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (true) {
            if (remaining and -128 == 0) {
                output.write(remaining)
                return
            }
            output.write(remaining and 127 or 128)
            remaining = remaining ushr 7
        }
    }

    private companion object {
        const val SPONGE_VERSION = 2
        const val DATA_VERSION_1_20_4 = 3700
        const val ROOT_NAME = "Schematic"
    }
}
