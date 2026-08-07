package org.kvxd.dust.device

object Scheduling {
    val TORCH = ScheduledDelay(1, TickPriority.NORMAL)
    val LAMP_OFF = ScheduledDelay(2, TickPriority.NORMAL)
    val COMPARATOR = ScheduledDelay(1, TickPriority.HIGH)

    fun repeater(delay: Int, frontIsDiode: Boolean, shouldBePowered: Boolean): ScheduledDelay {
        require(Properties.DELAY.accepts(delay))
        val priority = when {
            frontIsDiode -> TickPriority.HIGHEST
            !shouldBePowered -> TickPriority.HIGHER
            else -> TickPriority.HIGH
        }
        return ScheduledDelay(delay, priority)
    }

    fun repeaterRecheck(delay: Int): ScheduledDelay = ScheduledDelay(delay, TickPriority.HIGHER)
}
