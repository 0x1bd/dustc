package org.kvxd.dust.device

data class SignBlockEntity(
    val lines: List<String>,
    val color: String = "black",
    val glowing: Boolean = false,
    override val id: String = "minecraft:sign",
) : BlockEntity {
    init {
        require(lines.size <= 4) { "a sign has at most four lines" }
        require(color.matches(Regex("[a-z_]+"))) { "invalid sign color '$color'" }
    }

    val paddedLines: List<String> = List(4) { lines.getOrElse(it) { "" } }
}
