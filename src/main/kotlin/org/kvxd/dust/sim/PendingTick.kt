package org.kvxd.dust.sim

import org.kvxd.dust.device.TickPriority

internal data class PendingTick(val dueAt: Int, val priority: TickPriority, val index: Int, val sequence: Long)
