package org.kvxd.dust.physical.progress

enum class PhysicalProgressStage {
    SYNTHESIS,
    PLACEMENT,
    ROUTING,
    ELECTRICAL_FINALIZATION,
    EMISSION,
}

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

fun interface PhysicalProgressListener {
    fun onProgress(event: PhysicalProgressEvent)

    companion object {
        val NONE: PhysicalProgressListener = PhysicalProgressListener { }
    }
}
