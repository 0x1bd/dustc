package org.kvxd.dust.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Spec
import picocli.CommandLine.Model.CommandSpec

@Command(
    name = "dustc",
    description = ["Compile dust logic-gate circuits to Minecraft schematics."],
    mixinStandardHelpOptions = true,
    version = ["dustc 0.1.0"],
    subcommands = [BuildCommand::class],
)
class DustcCommand : Runnable {
    @Spec
    private lateinit var spec: CommandSpec

    override fun run() {
        spec.commandLine().usage(spec.commandLine().out)
    }
}
