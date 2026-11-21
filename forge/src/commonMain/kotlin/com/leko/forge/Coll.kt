package com.leko.forge

import kotlin.concurrent.Volatile
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


/**
 * Creates a collection attribute: [initial] folded with every contribution aimed at a node.
 *
 * The collection and its contributions are declared separately, so other modules can extend a language
 * by adding contributions to an existing collection:
 *
 * ```
 * // language
 * val diagnostics = coll<List<Diagnostic>>(emptyList())
 * val Root.diags by diagnostics
 *
 * // anywhere else, e.g. an extension module
 * object TypeChecks {
 *     init {
 *         diagnostics.on<Plus> { diags -> diags + ... }
 *         diagnostics.on<This>(to = { root }) { diags -> ... }
 *     }
 * }
 * ```
 *
 * Contributions only count once the code that adds them has run. Kotlin initializes a top-level
 * property or an `object` the first time it is accessed, so make sure extension modules are loaded
 * (for example by referencing `TypeChecks`) before evaluating the collection. Adding a contribution
 * later clears all cached values of the collection.
 *
 * [initial] is shared by every target, so use an immutable value such as `emptyList()`.
 */
public fun <Type> coll(initial: Type, owner: KClass<*>? = null): Coll<Type> = Coll(initial, owner)


internal class Contribution<Type>(
    val source: KClass<*>,
    val target: ((Any) -> Any?)?,
    val contribute: (node: Any, acc: Type) -> Type,
)


public class Coll<Type> internal constructor(private val initial: Type, owner: KClass<*>?) {

    private var name = "<coll>"
    private var owner: KClass<*>? = owner
    @Volatile private var contributions: List<Contribution<Type>> = emptyList()

    private class Box<T>(val value: T)

    @Volatile
    private var cache = weakIdentityMap<Any, Box<Type>>()
    private val inProgress = IdentitySet<Any>()

    /**
     * Adds a contribution. It runs for every `Node` in the tree, with the node as the receiver.
     * It receives the value collected so far and returns the new value.
     *
     * By default a node contributes to its nearest ancestor (or itself) of the collection's owner type.
     * Pass `to = { someNode }` to choose the target explicitly. If it returns `null`, nothing is contributed.
     *
     * Returns this collection, so contributions can be chained: `coll(0).on<A> { ... }.on<B> { ... }`.
     */
    public inline fun <reified Node : Any> on(
        noinline to: (Node.() -> Any?)? = null,
        noinline contribute: Node.(acc: Type) -> Type,
    ): Coll<Type> {
        addContribution(Node::class, to, contribute)
        return this
    }

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <Node : Any> addContribution(
        source: KClass<Node>,
        to: (Node.() -> Any?)?,
        contribute: Node.(Type) -> Type,
    ) {
        locked(this) {
            contributions = contributions + Contribution(source, to as ((Any) -> Any?)?, contribute as (Any, Type) -> Type)
            cache = weakIdentityMap() // values computed without this contribution are outdated
        }
    }

    /** Binds the collection to a property: `val Root.diags by diagnostics`. */
    public operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): Coll<Type> {
        // The owner type is the extension receiver (`val Root.diags`), or the class for member properties.
        val owner = thisRef?.let { it::class } ?: reflectedReceiver(property)
        locked(this) {
            val previous = this.owner
            check(previous == null || owner == null || previous == owner) {
                "collection attribute '$name' is already bound to ${previous!!.simpleName}; " +
                        "create a separate coll() for ${property.name}"
            }
            if (owner != null) this.owner = owner
            name = property.name
        }
        return this
    }

    public operator fun getValue(thisRef: Any, property: KProperty<*>): Type {
        cache[thisRef]?.let { return it.value }

        var root = thisRef
        while (true) root = Tree.parentOf(root) ?: break

        locked(this) {
            // during a fixpoint, contributions may depend on unfinished approximations: don't cache
            val values = if (CircularEval.inCycle()) weakIdentityMap() else cache
            values[thisRef]?.let { return it.value }
            if (!inProgress.add(root)) {
                error("cyclic dependency detected in collection attribute ${thisRef::class.simpleName}.$name")
            }
            try {
                for ((target, value) in collect(root)) values[target] = Box(value)
            } finally {
                inProgress.remove(root)
            }
            return (values[thisRef] ?: Box(initial).also { values[thisRef] = it }).value
        }
    }

    /** Visits the tree under [root] (pre-order, children in field order) and folds every contribution into its target. */
    private fun collect(root: Any): List<Pair<Any, Type>> {
        val values = identityMap<Any, Type>()
        val visited = IdentitySet<Any>()
        val work = ArrayDeque<Any>()
        work.addLast(root)
        while (work.isNotEmpty()) {
            val node = work.removeLast()
            if (!visited.add(node)) continue
            for (c in contributions) {
                if (!c.source.isInstance(node)) continue
                EvalContext.withNode(node) {
                    val target = if (c.target != null) c.target(node) else defaultTarget(node)
                    if (target != null) {
                        @Suppress("UNCHECKED_CAST")
                        val acc = if (values.containsKey(target)) values[target] as Type else initial
                        values[target] = c.contribute(node, acc)
                    }
                }
            }
            Tree.childrenOf(node).asReversed().forEach { work.addLast(it) } // keep children in order
        }
        @Suppress("UNCHECKED_CAST")
        return values.keys.map { it to (values[it] as Type) }
    }

    private fun defaultTarget(node: Any): Any {
        val owner = owner ?: error(
            "can not determine the owner type of collection attribute '$name'; " +
                    "pass coll(initial, owner = Owner::class) or to = { ... } to its contributions"
        )
        var current: Any? = node
        while (current != null) {
            if (owner.isInstance(current)) return current
            current = Tree.parentOf(current)
        }
        error("${node::class.simpleName} contributes to collection attribute '$name', but has no enclosing " +
                "${owner.simpleName}; register the tree with tree(root) or pass to = { ... }")
    }
}
