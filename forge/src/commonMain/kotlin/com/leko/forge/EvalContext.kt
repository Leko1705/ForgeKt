package com.leko.forge

/**
 * Tracks the node whose attribute is currently being evaluated (per thread).
 *
 * This lets receiver-less inherited attributes such as
 * `val typeEnv: Type by inh<Type>().on<...> { ... }` know which node they are requested for.
 */
internal object EvalContext {

    private val stack = threadLocal { ArrayDeque<Any>() }

    fun current(): Any? = stack.get().lastOrNull()

    inline fun <R> withNode(node: Any?, block: () -> R): R {
        if (node == null) return block()
        val s = stack.get()
        s.addLast(node)
        try {
            return block()
        } finally {
            s.removeLast()
        }
    }
}
