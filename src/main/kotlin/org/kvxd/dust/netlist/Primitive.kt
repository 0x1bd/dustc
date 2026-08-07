package org.kvxd.dust.netlist

import org.kvxd.dust.cell.BuiltinCells
import org.kvxd.dust.cell.CellType

enum class Primitive(val cellType: CellType) {
    NOT(BuiltinCells.not),
    AND2(BuiltinCells.and2),
    OR2(BuiltinCells.or2),
    XOR2(BuiltinCells.xor2),

    LATCH(BuiltinCells.latch),
}
