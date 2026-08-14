package org.kvxd.dust.cli

import java.nio.file.Path
import java.util.concurrent.Callable
import org.kvxd.dust.compile
import org.kvxd.dust.physical.io.PhysicalIo
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

    @Option(
        names = ["--module"],
        paramLabel = "<name>",
        description = ["Build a top-level module instead of main."],
    )
    private var moduleName: String? = null

    @Option(
        names = ["--param"],
        paramLabel = "<NAME=VALUE>",
        description = ["Set a top-level integer parameter. may be repeated."],
    )
    private var parameterOptions: List<String> = emptyList()

    @Option(names = ["--terminals"], description = ["Use bare terminals instead of demo levers and lamps."])
    private var terminals: Boolean = false

    override fun call(): Int {
        val schematic = destination ?: Path.of(source.fileName.toString().removeSuffix(".dust") + ".schem")
        require(schematic.fileName.toString().endsWith(".schem")) { "output must end in .schem" }

        val module = CircuitSourceLoader().load(source, moduleName, parseParameters())
        val progress = CliProgressRenderer(spec.commandLine().out)
        val io = if (terminals) PhysicalIo.TERMINALS else PhysicalIo.DEBUG_PADS
        val compiled = try {
            module.compile(io = io, progress = progress).also { it.writeSchematic(schematic, progress) }
        } finally {
            progress.finish()
        }
        val physical = compiled.physical
        val matrix = physical.matrix
        if (compiled.timing.minimumSafeStepTicks > 0) {
            spec.commandLine().out.println(
                "dustc: timing: minimum safe clock interval ${compiled.timing.minimumSafeStepTicks} ticks; " +
                    "maximum clock skew ${compiled.timing.maximumClockSkewTicks} ticks",
            )
        }
        spec.commandLine().out.println(
            "dustc: wrote $schematic; ${matrix.width} x ${matrix.height} x ${matrix.length}, " +
                "${matrix.blockCount()} blocks, ${physical.cells.size} standard cells, " +
                "${physical.routes.size} routed nets",
        )
        return 0
    }

    private fun parseParameters(): Map<String, Int> {
        val parameters = linkedMapOf<String, Int>()
        parameterOptions.forEach { option ->
            val match = PARAMETER.matchEntire(option)
                ?: error("invalid --param '$option'; expected NAME=VALUE")
            val name = match.groupValues[1]
            val text = match.groupValues[2]
            require(name !in parameters) { "duplicate --param '$name'" }
            val negative = text.startsWith('-')
            val unsigned = (if (negative) text.drop(1) else text).replace("_", "")
            val parsed = when {
                unsigned.startsWith("0x", ignoreCase = true) -> unsigned.drop(2).toIntOrNull(16)
                unsigned.startsWith("0b", ignoreCase = true) -> unsigned.drop(2).toIntOrNull(2)
                else -> unsigned.toIntOrNull()
            } ?: error("--param '$name' value '$text' does not fit in 32 bits")
            parameters[name] = if (negative) {
                try {
                    Math.negateExact(parsed)
                } catch (_: ArithmeticException) {
                    error("--param '$name' value '$text' does not fit in 32 bits")
                }
            } else parsed
        }
        return parameters
    }

    private companion object {
        val PARAMETER = Regex(
            "([A-Za-z_][A-Za-z0-9_]*)=" +
                    "(-?(?:[0-9][0-9_]*|0[xX][0-9A-Fa-f][0-9A-Fa-f_]*|0[bB][01][01_]*))",
        )
    }
}
