package org.kvxd.dust.physical.progress

data class PhysicalProgressEvent(
    val stage: PhysicalProgressStage,
    val completed: Int? = null,
    val total: Int? = null,
    val candidate: Int? = null,
    val candidateTotal: Int? = null,
    val net: Int? = null,
    val netTotal: Int? = null,
    val negotiationPass: Int? = null,
    val conflicts: Int? = null,
    val detail: String? = null,
    val approximate: Boolean = false,
)
