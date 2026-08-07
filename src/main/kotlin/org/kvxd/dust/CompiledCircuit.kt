package org.kvxd.dust

import java.nio.file.Files
import java.nio.file.Path
import org.kvxd.dust.emit.SchematicWriter
import org.kvxd.dust.physical.PhysicalCompiler
import org.kvxd.dust.physical.PhysicalDesign
import org.kvxd.dust.physical.PhysicalIo
import org.kvxd.dust.physical.PhysicalIoDirection
import org.kvxd.dust.physical.PhysicalIoGroup
import org.kvxd.dust.physical.PhysicalIoLayout
import org.kvxd.dust.technology.MinecraftRedstone
import org.kvxd.dust.technology.RedstoneTechnology

data class CompiledCircuit(
    val circuit: Circuit,
    val physical: PhysicalDesign,
) {
    fun writeSchematic(output: Path): Path {
        require(output.fileName.toString().endsWith(".schem")) { "output must end in .schem" }
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.write(output, SchematicWriter().write(physical.matrix, circuit.name))
        return output
    }
}

fun Circuit.compile(
    technology: RedstoneTechnology = MinecraftRedstone.technology,
    io: PhysicalIo = PhysicalIo.DEBUG_PADS,
): CompiledCircuit = CompiledCircuit(
    this,
    PhysicalCompiler(technology).compile(
        lowerToBooleanNetlist(),
        io,
        PhysicalIoLayout(
            ioGroups.map { group ->
                PhysicalIoGroup(
                    group.name,
                    if (group.direction == CircuitPortDirection.INPUT) {
                        PhysicalIoDirection.INPUT
                    } else {
                        PhysicalIoDirection.OUTPUT
                    },
                    group.ports.flatMap { port -> List(port.width) { bit -> port.bitName(bit) } },
                )
            },
        ),
    ),
)
