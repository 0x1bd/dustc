package org.kvxd.dust.cell.behavior

import org.kvxd.dust.cell.timing.Edge

sealed interface CellBehavior {
    val stateBits: Int

    sealed interface Trigger {
        data object Transparent : Trigger

        data class EdgeTriggered(val clockPort: String, val edge: Edge) : Trigger {
            init {
                require(clockPort.isNotBlank())
            }
        }
    }

    fun evaluate(
        inputs: Map<String, BooleanArray>,
        previousState: BooleanArray,
    ): CellEvaluation

    class Combinational(
        private val evaluator: (Map<String, BooleanArray>) -> Map<String, BooleanArray>,
    ) : CellBehavior {
        override val stateBits: Int = 0

        override fun evaluate(
            inputs: Map<String, BooleanArray>,
            previousState: BooleanArray,
        ): CellEvaluation {
            require(previousState.isEmpty())
            return CellEvaluation(evaluator(inputs))
        }
    }

    class Stateful(
        override val stateBits: Int,
        val trigger: Trigger,
        private val evaluator: (
            Map<String, BooleanArray>,
            BooleanArray,
        ) -> CellEvaluation,
    ) : CellBehavior {
        init {
            require(stateBits > 0)
        }

        override fun evaluate(
            inputs: Map<String, BooleanArray>,
            previousState: BooleanArray,
        ): CellEvaluation {
            require(previousState.size == stateBits)
            return evaluator(inputs, previousState).also {
                require(it.nextState.size == stateBits)
            }
        }
    }
}
