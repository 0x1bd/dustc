package org.kvxd.dust.physical.progress

fun interface PhysicalProgressListener {
    fun onProgress(event: PhysicalProgressEvent)

    companion object {
        val NONE: PhysicalProgressListener = PhysicalProgressListener { }
    }
}
