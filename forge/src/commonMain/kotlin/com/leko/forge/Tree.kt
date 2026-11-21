package com.leko.forge

import kotlin.concurrent.Volatile
import kotlin.reflect.KClass

/**
 * Marks a field as a child in the tree.
 *
 * On the JVM, children are found by reflection: by default every field is a child (except values
 * of JDK and Kotlin library types). If any field of a class is annotated with `@Child`, only the
 * annotated fields are children. Use it when a node stores references to other nodes that are not
 * its children, e.g. a resolved declaration:
 *
 * ```
 * class Call(@Child val args: List<Expr>, val target: Func)  // target is not a child
 * ```
 *
 * On platforms without reflection, implement [TreeNode] or register [children] instead. A code
 * generator (e.g. a KSP processor) can produce those registrations from `@Child`, see README.md.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class Child

/**
 * A node that lists its own children. Works on every platform and takes precedence over
 * registrations and reflection.
 *
 * ```
 * data class Plus(val l: Expr, val r: Expr) : Expr, TreeNode {
 *     override val children get() = listOf(l, r)
 * }
 * ```
 *
 * `null` entries are ignored. Lists, maps (their values) and arrays in [children] are flattened.
 */
public interface TreeNode {
    public val children: List<Any?>
}

/**
 * Registers how to find the children of `Node` (and its subtypes). Works on every platform.
 * Registrations replace reflection for the registered types. For a node matching several
 * registrations, the most specific type wins, and among equally specific ones the last added.
 *
 * ```
 * children<While> { listOf(cond, body) }
 * children<Block> { stmts }
 * ```
 */
public inline fun <reified Node : Any> children(noinline of: Node.() -> Iterable<Any?>) {
    @Suppress("UNCHECKED_CAST")
    Tree.registerChildren(Node::class, of as Any.() -> Iterable<Any?>)
}

/**
 * Registers the supertypes of `Node`, for platforms without reflection (on the JVM, subtypes are
 * known anyway). Forge uses them to pick the most specific equation, contribution or flow: with
 * `supertypes<Assign>(Stmt::class)`, an `on<Assign>` equation is preferred over an `on<Stmt>` one.
 * Without it, the equation for the exact class of the node wins, otherwise the last added one.
 */
public inline fun <reified Node : Any> supertypes(vararg types: KClass<*>) {
    Tree.registerSupertypes(Node::class, types.toList())
}

/**
 * Registers [root] and all nodes reachable from it, so that inherited attributes can find the
 * parents of nodes. Rules return trees that are already registered; call this for trees built in
 * code, and again after modifying a tree.
 *
 * Parents are held weakly, so keep a reference to the root while you evaluate attributes.
 */
public fun <T : Any> tree(root: T): T {
    Tree.register(root)
    return root
}

/** The parent of [node] in a registered tree, or `null` for roots and unknown nodes. */
public fun parentOf(node: Any): Any? = Tree.parentOf(node)

/** The children of [node], in order. See [TreeNode], [children] and [Child]. */
public fun childrenOf(node: Any): List<Any> = Tree.childrenOf(node)


@PublishedApi
internal object Tree {

    private class Registration(val type: KClass<*>, val of: Any.() -> Iterable<Any?>)

    @Volatile private var registrations: List<Registration> = emptyList()
    @Volatile private var supertypes: Map<KClass<*>, List<KClass<*>>> = emptyMap()

    private val parents = weakIdentityMap<Any, Ref<Any>>()

    fun registerChildren(type: KClass<*>, of: Any.() -> Iterable<Any?>) {
        locked(this) { registrations = registrations + Registration(type, of) }
    }

    fun registerSupertypes(type: KClass<*>, types: List<KClass<*>>) {
        locked(this) { supertypes = supertypes + (type to (supertypes[type].orEmpty() + types)) }
    }

    fun parentOf(node: Any): Any? = parents[node]?.get()

    fun register(root: Any) {
        val visited = IdentitySet<Any>()
        val work = ArrayDeque<Any>()
        visited.add(root)
        work.addLast(root)
        while (work.isNotEmpty()) {
            val node = work.removeLast()
            for (child in childrenOf(node)) {
                if (visited.add(child)) {
                    parents[child] = weakRef(node)
                    work.addLast(child)
                }
            }
        }
    }

    fun childrenOf(node: Any): List<Any> {
        if (node is TreeNode) return flatten(node.children)
        mostSpecific(registrations, node, { it.type }) { it.type.isInstance(node) }?.let { return flatten(it.of(node)) }
        return reflectedChildren(node) ?: emptyList()
    }

    private fun flatten(values: Iterable<Any?>): List<Any> {
        val result = ArrayList<Any>()
        for (v in values) flattenChildren(v, result)
        return result
    }

    /** Whether [sub] is [sup] or a subtype of it, by reflection or the registered [supertypes]. */
    fun isSubtype(sub: KClass<*>, sup: KClass<*>): Boolean {
        if (sub == sup) return true
        reflectedIsSubtype(sub, sup)?.let { return it }
        val seen = HashSet<KClass<*>>()
        val work = ArrayDeque(supertypes[sub].orEmpty())
        while (work.isNotEmpty()) {
            val t = work.removeLast()
            if (t == sup) return true
            if (seen.add(t)) work.addAll(supertypes[t].orEmpty())
        }
        return false
    }

    /**
     * Picks the most specific of [candidates] that [matches] [value]: a candidate replaces the
     * current best one if its type is a subtype of (or equal to) the best type, so among equally
     * specific candidates the last one wins. If subtypes are unknown, the exact class of the value wins.
     */
    inline fun <T> mostSpecific(candidates: List<T>, value: Any, type: (T) -> KClass<*>, matches: (T) -> Boolean): T? {
        val exact = value::class
        var best: T? = null
        for (c in candidates) {
            if (!matches(c)) continue
            val b = best
            if (b == null || isSubtype(type(c), type(b)) || (type(c) == exact && type(b) != exact)) best = c
        }
        return best
    }
}

/** Adds the nodes in [value] to [into]: lists, maps (values) and arrays are flattened, simple values are skipped. */
internal fun flattenChildren(value: Any?, into: MutableList<Any>) {
    when (value) {
        null -> {}
        is Iterable<*> -> value.forEach { flattenChildren(it, into) }
        is Map<*, *> -> value.values.forEach { flattenChildren(it, into) }
        is Array<*> -> value.forEach { flattenChildren(it, into) }
        else -> if (!isLeafValue(value)) into += value
    }
}

private fun isLeafValue(value: Any): Boolean =
    value is Number || value is Boolean || value is Char || value is CharSequence || value is Enum<*> ||
            value is KClass<*> || value is Function<*> || value is Unit ||
            value is BooleanArray || value is ByteArray || value is CharArray || value is ShortArray ||
            value is IntArray || value is LongArray || value is FloatArray || value is DoubleArray ||
            value is Syn<*, *> || value is SynProvider<*, *> || value is Inh<*> || value is Coll<*> ||
            value is CircularAttr<*, *> || value is Rule<*>
