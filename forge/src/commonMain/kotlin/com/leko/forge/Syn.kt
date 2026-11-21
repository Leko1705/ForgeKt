package com.leko.forge

import kotlin.concurrent.Volatile
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


/**
 * Defines a synthesized attribute: a value of a node computed from the node itself, typically from
 * its children (bottom-up), such as the type of an expression or the code generated for it.
 *
 * Declare it as a delegated extension property. [builder] is the equation; the node is its receiver:
 *
 * ```
 * val Num.type: Type by syn { Type.INT }
 * val Plus.type: Type by syn { if (l.type == r.type) l.type else Type.ERROR }
 * ```
 *
 * **Dispatch on interfaces and abstract classes.** Kotlin resolves extension properties statically,
 * so for a value typed as an interface (`val e: Expr`), `e.type` calls the `Expr.type` attribute.
 * Declared without [builder], it dispatches to the implementation for the node's runtime type:
 *
 * ```
 * val Expr.type: Type by syn()                    // dispatches to Num.type, Plus.type, ...
 * val Stmt.isJump: Boolean by syn { false }       // with a default for types without implementation
 * val Return.isJump: Boolean by syn { true }
 * ```
 *
 * Every `syn` with the same property name forms one attribute: a node uses the implementation of the
 * most specific type it is an instance of (see [supertypes] for platforms without reflection). If
 * there is none, the [builder] of the attribute that was accessed is used, and without a builder the
 * access fails with a message naming the missing implementation.
 *
 * **Caching.** With [cached] (the default), the value is computed once per node and cached. Nodes are
 * compared by identity, so structurally equal data class nodes have separate values, and the cache does
 * not keep nodes alive. Values are not cached while a [circular] attribute is being computed, because
 * they may depend on approximations that are still changing. Use `cached = false` for cheap
 * attributes or attributes of trees that change.
 *
 * **Cycles.** A node's attribute that depends on itself fails with "cyclic dependency detected".
 * For intentional cycles (data flow analyses on graphs) use [circular].
 *
 * Inside [builder], receiver-less inherited attributes (`val x by inh<T>()...`) refer to this node.
 *
 * @param Node the node type the attribute is defined for (the receiver of the property).
 * @param Type the type of the value. It must not be nullable; wrap optional values (e.g. in a data class).
 * @param cached whether to compute the value once per node and cache it.
 * @param builder the equation, or `null` for an attribute that only dispatches to implementations of subtypes.
 */
public inline fun <reified Node : Any, Type : Any> syn(
    cached: Boolean = true,
    noinline builder: (Node.() -> Type)? = null,
): SynProvider<Node, Type> = SynProvider(Node::class, cached, builder)


/** Returned by [syn]; creates the attribute when it is bound to a property. */
public class SynProvider<Node : Any, Type> @PublishedApi internal constructor(
    private val nodeType: KClass<Node>,
    private val cached: Boolean,
    private val builder: (Node.() -> Type)?,
) {
    public operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): Syn<Node, Type> {
        val attribute = SynAttribute(nodeType, property.name, cached, builder)
        SynRegistry.add(attribute)
        return attribute
    }
}

/** A synthesized attribute, used as a property delegate. Create it with [syn]. */
public interface Syn<Node, Type> {
    public operator fun getValue(thisRef: Node, property: KProperty<*>): Type
}


/** All synthesized attributes by property name, for dispatch to implementations of subtypes. */
internal object SynRegistry {
    @Volatile private var byName: Map<String, List<SynAttribute<*, *>>> = emptyMap()
    @Volatile var version: Int = 0
        private set

    fun add(attribute: SynAttribute<*, *>) {
        locked(this) {
            byName = byName + (attribute.name to (byName[attribute.name].orEmpty() + attribute))
            version++
        }
    }

    fun implementationsOf(name: String): List<SynAttribute<*, *>> = byName[name].orEmpty()
}


internal class SynAttribute<Node : Any, Type>(
    val nodeType: KClass<Node>,
    val name: String,
    private val cached: Boolean,
    private val builder: (Node.() -> Type)?,
) : Syn<Node, Type> {

    private class Box<T>(val value: T)

    /** One value per node, by identity: equal data class nodes are still separate nodes. */
    private val cache = weakIdentityMap<Any, Box<Type>>()

    /** Nodes whose value is currently being computed (per thread), so only real cycles are reported. */
    private val inProgress = threadLocal { IdentitySet<Any>() }

    /** Implementation per runtime class, valid for [dispatchVersion] of the registry. */
    private var dispatch = HashMap<KClass<*>, SynAttribute<*, *>>()
    private var dispatchVersion = -1

    @Suppress("UNCHECKED_CAST")
    override operator fun getValue(thisRef: Node, property: KProperty<*>): Type {
        val runtimeClass = thisRef::class
        if (runtimeClass != nodeType) {
            val implementation = implementationFor(thisRef, runtimeClass)
            if (implementation !== this) return (implementation as SynAttribute<Node, Type>).compute(thisRef, property)
        }
        return compute(thisRef, property)
    }

    private fun implementationFor(node: Any, runtimeClass: KClass<*>): SynAttribute<*, *> = locked(this) {
        if (dispatchVersion != SynRegistry.version) {
            dispatch = HashMap()
            dispatchVersion = SynRegistry.version
        }
        dispatch.getOrPut(runtimeClass) {
            val candidates = SynRegistry.implementationsOf(name).filter { it.builder != null || it === this }
            Tree.mostSpecific(candidates, node, { it.nodeType }) { it.nodeType.isInstance(node) } ?: this
        }
    }

    private fun compute(thisRef: Node, property: KProperty<*>): Type {
        val node: Any = thisRef
        if (cached) cache[node]?.let { return it.value }
        val body = builder ?: error(
            "no implementation of synthesized attribute '$name' for ${node::class.simpleName}; " +
                    "add `val ${node::class.simpleName}.$name by syn { ... }` or a default to `${nodeType.simpleName}.$name`"
        )
        val visiting = inProgress.get()
        if (!visiting.add(node)) error("cyclic dependency detected in ${node::class.simpleName}.${property.name}")
        try {
            val value = EvalContext.withNode(node) { body(thisRef) }
            if (cached && !CircularEval.inCycle()) cache[node] = Box(value) // may depend on unfinished approximations
            return value
        } finally {
            visiting.remove(node)
        }
    }
}
