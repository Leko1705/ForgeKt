package com.leko.forge

import kotlin.reflect.KProperty

/**
 * A circular attribute: its equations may depend on themselves, through other nodes or other
 * circular attributes, e.g. on a cyclic graph. The value is computed as a fixpoint. Every value
 * starts at [initial], and all equations involved are evaluated again until nothing changes:
 *
 * ```
 * val Vertex.liveOut: Set<String> by circular(emptySet()) { cfg.succ(this).flatMap { it.liveIn }.toSet() }
 * val Vertex.liveIn: Set<String> by circular(emptySet()) { uses + (liveOut - defs) }
 * ```
 *
 * Values are compared with `==`. The equations must be monotone on a lattice of finite height, as in
 * any data flow analysis, or the evaluation does not terminate. Common cases are growing sets that
 * start empty, or shrinking sets that start "full" (e.g. `null` for "all").
 *
 * Final values are cached per node. While a fixpoint is being computed, other attributes do not
 * cache values, because those values may depend on approximations that are still changing.
 */
public fun <Node, Type> circular(initial: Type, body: Node.() -> Type): CircularAttr<Node, Type> =
    CircularAttr(initial, body)


/** The fixpoint computation currently running on this thread, shared by all circular attributes. */
internal object CircularEval {
    class Cycle {
        var iteration = 0
        var changed = false
        val attributes = IdentitySet<CircularAttr<*, *>>()
        val attributeList = ArrayList<CircularAttr<*, *>>()
        fun involve(attribute: CircularAttr<*, *>) { if (attributes.add(attribute)) attributeList += attribute }
    }

    private val current = threadLocal<Cycle?> { null }

    fun cycle(): Cycle? = current.get()
    fun set(cycle: Cycle?) = current.set(cycle)

    /** Whether a fixpoint is being computed. Other attributes must not cache values then. */
    fun inCycle(): Boolean = current.get() != null
}


public class CircularAttr<Node, Type> internal constructor(
    private val initial: Type,
    private val body: Node.() -> Type,
) {
    private class Box<T>(val value: T)

    private val final = weakIdentityMap<Any, Box<Type>>()

    // only used while a fixpoint is being computed
    private var approx = identityMap<Any, Type>()
    private var visited = identityMap<Any, Int>()

    public operator fun getValue(thisRef: Node, property: KProperty<*>): Type {
        val node: Any = thisRef ?: error("circular attribute '${property.name}' needs a node")
        final[node]?.let { return it.value }

        val cycle = CircularEval.cycle()
        if (cycle != null) {
            cycle.involve(this)
            // already evaluated in this round: use the current approximation
            if (visited[node] == cycle.iteration) return approxOf(node)
            return step(thisRef, node, cycle)
        }

        // start a new fixpoint computation
        val c = CircularEval.Cycle()
        CircularEval.set(c)
        try {
            c.involve(this)
            do {
                c.changed = false
                c.iteration++
                step(thisRef, node, c)
            } while (c.changed)
            // nothing changed in the last round: every value computed in it is final
            for (attribute in c.attributeList) attribute.finish(c.iteration)
            return final[node]!!.value
        } finally {
            CircularEval.set(null)
            for (attribute in c.attributeList) attribute.reset()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun approxOf(node: Any): Type = if (approx.containsKey(node)) approx[node] as Type else initial

    private fun step(thisRef: Node, node: Any, cycle: CircularEval.Cycle): Type {
        visited[node] = cycle.iteration
        val old = approxOf(node)
        val new = EvalContext.withNode(node) { body(thisRef) }
        if (new != old || !approx.containsKey(node)) {
            if (new != old) cycle.changed = true
            approx[node] = new
        }
        return new
    }

    private fun finish(lastIteration: Int) {
        for (node in visited.keys) {
            if (visited[node] == lastIteration) final[node] = Box(approxOf(node))
        }
    }

    private fun reset() {
        approx = identityMap()
        visited = identityMap()
    }
}
