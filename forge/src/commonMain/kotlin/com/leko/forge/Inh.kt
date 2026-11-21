package com.leko.forge

import kotlin.concurrent.Volatile
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


/**
 * Creates an inherited attribute. Its equations are added with [Inh.on], so other modules can
 * extend a language with equations for their own node types:
 *
 * ```
 * // language
 * val scopes = inh<Scope>()
 *     .on<Func> { Scope { name -> params.find { it.name == name } } }
 * val Node.scope by scopes
 *
 * // extension module
 * object ForLoops {
 *     init { scopes.on<For> { child -> ... } }
 * }
 * ```
 *
 * Short definitions fit on one line: `val Node.function by inh<Func>().on<Func> { this }`.
 *
 * Equations only count once the code that adds them has run, so make sure extension modules are
 * loaded (for example by referencing `ForLoops`) before evaluating the attribute. Adding an
 * equation later clears all cached values of the attribute.
 */
public fun <Type> inh(cached: Boolean = true): Inh<Type> = Inh(cached)


internal class Equation<Type>(val nodeType: KClass<*>, val provider: (parent: Any, child: Any) -> Type)


public class Inh<Type> internal constructor(private val cached: Boolean) {

    private class Box<T>(val value: T)

    @Volatile private var equations: List<Equation<Type>> = emptyList()
    @Volatile private var default: ((Any) -> Type)? = null

    @Volatile private var cache = weakIdentityMap<Any, Box<Type>>()
    private val inProgress = threadLocal { IdentitySet<Any>() }

    /**
     * Adds an equation: the value for every child of a `Node`. The receiver is the parent and
     * `child` is the child the request came from. Nodes whose parent has no matching equation
     * inherit the value of their parent.
     *
     * The most specific equation applies. For equally specific equations the one added last wins,
     * so an extension can replace an equation of the base language.
     *
     * Returns this attribute, so equations can be chained.
     */
    public inline fun <reified Node : Any> on(noinline provider: Node.(child: Any) -> Type): Inh<Type> {
        addEquation(Node::class, provider)
        return this
    }

    /** Sets the value used when the root of the tree is reached without a matching equation. */
    public fun default(provider: (root: Any) -> Type): Inh<Type> {
        locked(this) {
            default = provider
            cache = weakIdentityMap()
        }
        return this
    }

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <Node : Any> addEquation(nodeType: KClass<Node>, provider: Node.(Any) -> Type) {
        locked(this) {
            equations = equations + Equation(nodeType, provider as (Any, Any) -> Type)
            cache = weakIdentityMap() // values computed without this equation are outdated
        }
    }

    /**
     * Without a receiver (`val x by inh<T>()...`), the attribute applies to the node whose
     * attribute is being evaluated. With a receiver (`val Node.x by ...`), it applies to that node.
     */
    public operator fun getValue(thisRef: Any?, property: KProperty<*>): Type {
        val node = thisRef ?: EvalContext.current()
            ?: error("inherited attribute '${property.name}' was accessed outside of an attribute evaluation; " +
                    "access it from within an attribute equation or declare it with a node receiver")
        return valueFor(node, property)
    }

    private fun valueFor(node: Any, property: KProperty<*>): Type {
        val cache = cache
        if (cached) cache[node]?.let { return it.value }

        val visiting = inProgress.get()
        if (!visiting.add(node)) {
            error("cyclic dependency detected in inherited attribute ${node::class.simpleName}.${property.name}")
        }
        try {
            // Walk up until a parent defines an equation for the child we came from.
            // Every node on the path inherits that same value.
            val path = mutableListOf<Any>()
            var child = node
            var result: Box<Type>? = null
            while (true) {
                path += child
                if (cached && child !== node) {
                    cache[child]?.let { result = it }
                    if (result != null) break
                }
                val parent = Tree.parentOf(child)
                if (parent == null) {
                    val d = default ?: missingEquation(node, child, property)
                    result = Box(EvalContext.withNode(child) { d(child) })
                    break
                }
                val equation = findEquation(parent)
                if (equation != null) {
                    val c = child
                    result = Box(EvalContext.withNode(parent) { equation.provider(parent, c) })
                    break
                }
                child = parent
            }
            val value = result!!
            if (cached && !CircularEval.inCycle()) path.forEach { cache[it] = value }
            return value.value
        } finally {
            visiting.remove(node)
        }
    }

    /** The most specific equation applicable to [parent]. Later additions win ties. */
    private fun findEquation(parent: Any): Equation<Type>? {
        return Tree.mostSpecific(equations, parent, { it.nodeType }) { it.nodeType.isInstance(parent) }
    }

    private fun missingEquation(node: Any, root: Any, property: KProperty<*>): Nothing {
        if (root === node) {
            error("inherited attribute '${property.name}' requested for ${node::class.simpleName}, which has no parent. " +
                    "Register the tree with tree(root) or set a default { }")
        }
        error("no equation for inherited attribute '${property.name}' found above ${node::class.simpleName} " +
                "(reached root ${root::class.simpleName}); add an on<...> equation or a default { }")
    }
}
