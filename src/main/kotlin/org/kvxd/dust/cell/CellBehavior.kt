package org.kvxd.dust.cell

sealed interface CellBehavior {
    val stateBits: Int

    enum class StateMode { TRANSPARENT, EDGE_TRIGGERED }

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
        val mode: StateMode,
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
