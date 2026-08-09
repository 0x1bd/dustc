package org.kvxd.dust.cli

import java.nio.file.Path
import java.util.concurrent.Callable
import org.kvxd.dust.compile
import org.kvxd.dust.physical.PhysicalIo
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec

@Command(
    name = "build",
    description = ["Synthesize, place, and route a hardware module."],
    mixinStandardHelpOptions = true,
)
class BuildCommand : Callable<Int> {
    @Spec
    private lateinit var spec: CommandSpec

    @Parameters(index = "0", paramLabel = "<design.dust>", description = ["Module source file."])
    private lateinit var source: Path

    @Option(names = ["-o", "--output"], paramLabel = "<output.schem>", description = ["Schematic output path."])
    private var destination: Path? = null

    @Option(names = ["--module"], paramLabel = "<name>", description = ["Module to build if the file declares several."])
    private var moduleName: String? = null

    @Option(names = ["--terminals"], description = ["Use bare terminals instead of demo levers and lamps."])
    private var terminals: Boolean = false

    override fun call(): Int {
        val schematic = destination ?: Path.of(source.fileName.toString().removeSuffix(".dust") + ".schem")
        require(schematic.fileName.toString().endsWith(".schem")) { "output must end in .schem" }

        val module = CircuitSourceLoader().load(source, moduleName)
        val progress = CliProgressRenderer(spec.commandLine().out)
        val io = if (terminals) PhysicalIo.TERMINALS else PhysicalIo.DEBUG_PADS
        val compiled = try {
            module.compile(io = io, progress = progress).also { it.writeSchematic(schematic, progress) }
        } finally {
            progress.finish()
        }
        val physical = compiled.physical
        val matrix = physical.matrix
        spec.commandLine().out.println(
            "dustc: wrote $schematic; ${matrix.width} x ${matrix.height} x ${matrix.length}, " +
                "${matrix.blockCount()} blocks, ${physical.cells.size} standard cells, " +
                "${physical.routes.size} routed nets",
        )
        return 0
    }
}
