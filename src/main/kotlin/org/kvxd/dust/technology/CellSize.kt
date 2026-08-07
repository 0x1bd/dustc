package org.kvxd.dust.technology

data class CellSize(val x: Int, val y: Int, val z: Int) {
    init {
        require(x > 0 && y > 0 && z > 0)
    }
}
