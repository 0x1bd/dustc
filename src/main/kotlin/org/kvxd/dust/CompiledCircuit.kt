package org.kvxd.dust

import java.nio.file.Files
import java.nio.file.Path
import org.kvxd.dust.emit.SchematicWriter
import org.kvxd.dust.physical.PhysicalCompiler
import org.kvxd.dust.physical.design.PhysicalDesign
import org.kvxd.dust.physical.io.PhysicalIo
import org.kvxd.dust.physical.io.PhysicalIoDirection
import org.kvxd.dust.physical.io.PhysicalIoGroup
import org.kvxd.dust.physical.io.PhysicalIoLayout
import org.kvxd.dust.physical.progress.PhysicalProgressEvent
import org.kvxd.dust.physical.progress.PhysicalProgressListener
import org.kvxd.dust.physical.progress.PhysicalProgressStage
import org.kvxd.dust.technology.MinecraftRedstone
import org.kvxd.dust.technology.RedstoneTechnology

data class CompiledCircuit(
    val circuit: Circuit,
    val physical: PhysicalDesign,
) {
    fun writeSchematic(
        output: Path,
        progress: PhysicalProgressListener = PhysicalProgressListener.NONE,
    ): Path {
        require(output.fileName.toString().endsWith(".schem")) { "output must end in .schem" }
        progress.onProgress(PhysicalProgressEvent(PhysicalProgressStage.EMISSION, completed = 0, total = 1))
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        Files.write(output, SchematicWriter().write(physical.matrix, circuit.name))
        progress.onProgress(PhysicalProgressEvent(PhysicalProgressStage.EMISSION, completed = 1, total = 1))
        return output
    }
}

fun Circuit.compile(
    technology: RedstoneTechnology = MinecraftRedstone.technology,
    io: PhysicalIo = PhysicalIo.DEBUG_PADS,
    progress: PhysicalProgressListener = PhysicalProgressListener.NONE,
): CompiledCircuit {
    progress.onProgress(PhysicalProgressEvent(PhysicalProgressStage.SYNTHESIS, completed = 0, total = 1))
    val netlist = lowerToBooleanNetlist()
    progress.onProgress(PhysicalProgressEvent(PhysicalProgressStage.SYNTHESIS, completed = 1, total = 1))
    return CompiledCircuit(
        this,
        PhysicalCompiler(technology).compile(
            netlist,
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
                        group.edge,
                        group.panel,
                    )
                },
            ),
            progress,
        ),
    )
}
