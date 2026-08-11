package org.kvxd.dust.physical

import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.physical.compilation.PhysicalCompilation
import org.kvxd.dust.physical.design.PhysicalDesign
import org.kvxd.dust.physical.io.PhysicalIo
import org.kvxd.dust.physical.io.PhysicalIoLayout
import org.kvxd.dust.physical.progress.PhysicalProgressListener
import org.kvxd.dust.technology.MinecraftRedstone
import org.kvxd.dust.technology.RedstoneTechnology

class PhysicalCompiler(
    private val technology: RedstoneTechnology = MinecraftRedstone.technology,
) {
    fun compile(
        netlist: BooleanNetlist,
        io: PhysicalIo = PhysicalIo.DEBUG_PADS,
        layout: PhysicalIoLayout? = null,
        progress: PhysicalProgressListener = PhysicalProgressListener.NONE,
    ): PhysicalDesign = PhysicalCompilation(technology).compile(netlist, io, layout, progress)
}
