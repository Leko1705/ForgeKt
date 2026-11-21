package com.leko.forge

import kotlin.concurrent.Volatile
import kotlin.reflect.KClass



/**
 * A named exit of a flow, e.g. `next`, `onTrue`, `onFalse` or `break`. A flow sends control to a port.
 * The flow of the parent decides where the port of a child leads: `onTrue(cond) then body`.
 *
 * If [inherited] is true and the parent does not bind the port, it is looked up further up the
 * tree. That is what `break` and `return` need: they leave many nested statements at once.
 * Ports that are unbound at the root lead to an exit vertex of the graph ([Graph.exit]).
 */
public class Port(public val name: String, public val inherited: Boolean = false) {
    override fun toString(): String = name

    public companion object {
        /** The normal successor. Every flow scope provides it as `next`. */
        public val next: Port = Port("next")
    }
}

public fun port(name: String, inherited: Boolean = false): Port = Port(name, inherited)

/** The port of a child, e.g. `onTrue(cond)`. Created inside flows. */
public class PortRef internal constructor(public val node: Any, public val port: Port) {
    override fun toString(): String = "$port($node)"
}

/** Synthetic vertex for a port that is unbound at the root, e.g. the end of a function. */
public class ExitVertex internal constructor(public val port: Port) {
    override fun toString(): String = "exit($port)"
}


/**
 * Creates a graph definition. Its graphs are built per `Root` node (for example one control flow
 * graph per function) from the flows of the nodes below it.
 *
 * ```
 * val cfg = graph<Function>()
 * val onTrue = port("onTrue"); val onFalse = port("onFalse")
 *
 * cfg.flow<Function> { f -> entry(f.body); f.body then next }
 * cfg.flow<While> { (cond, body) ->
 *     entry(cond)
 *     onTrue(cond) then body
 *     onFalse(cond) then next
 *     body then cond
 * }
 * cfg.flow<Assign> { entry(node); node then next }
 *
 * val Stmt.succ get() = cfg.succ(this)
 * ```
 */
public inline fun <reified Root : Any> graph(): FlowGraph<Root> = FlowGraph(Root::class)


public class FlowGraph<Root : Any> @PublishedApi internal constructor(private val rootType: KClass<Root>) {

    internal class Rule(val type: KClass<*>, val body: (FlowScope<Any>, Any) -> Unit)

    @Volatile private var rules: List<Rule> = emptyList()

    @Volatile private var graphs = weakIdentityMap<Any, Graph>()   // root node -> graph
    @Volatile private var owners = weakIdentityMap<Any, Graph>()   // vertex -> graph

    /**
     * Defines the flow of `Node`. The most specific flow applies to a node. For equally specific
     * flows the one added last wins, so an extension can replace a flow of the base language.
     * Adding a flow discards all graphs built so far.
     *
     * Returns this graph definition, so flows can be chained.
     */
    public inline fun <reified Node : Any> flow(noinline body: FlowScope<Node>.(node: Node) -> Unit): FlowGraph<Root> {
        addFlow(Node::class, body)
        return this
    }

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun addFlow(type: KClass<*>, body: Any) {
        locked(this) {
            rules = rules + Rule(type, body as (FlowScope<Any>, Any) -> Unit)
            graphs = weakIdentityMap()
            owners = weakIdentityMap()
        }
    }

    /** The graph of [root], built on first access. */
    public operator fun get(root: Root): Graph {
        graphs[root]?.let { return it }
        locked(this) {
            graphs[root]?.let { return it }
            val graph = FlowBuild(root, ::ruleFor).build()
            graphs[root] = graph
            for (v in graph.vertices) owners[v] = graph
            return graph
        }
    }

    /**
     * The graph containing [vertex]: the graph it is a vertex of, or else the graph of its nearest
     * enclosing `Root` (built if needed). `null` if there is none.
     */
    public fun of(vertex: Any): Graph? {
        owners[vertex]?.let { return it }
        var current: Any? = vertex
        while (current != null) {
            @Suppress("UNCHECKED_CAST")
            if (rootType.isInstance(current)) return get(current as Root)
            current = Tree.parentOf(current)
        }
        return null
    }

    public fun succ(vertex: Any): List<Any> = of(vertex)?.succ(vertex) ?: emptyList()
    public fun pred(vertex: Any): List<Any> = of(vertex)?.pred(vertex) ?: emptyList()

    private fun ruleFor(node: Any): Rule? {
        return Tree.mostSpecific(rules, node, { it.type }) { it.type.isInstance(node) }
    }
}


/**
 * Where the parts of a flow lead: a vertex, the entry of a child flow, or a port of the node.
 * Each one is a separate object, so cycles can be detected by identity.
 */
internal sealed class FlowTarget {
    class Vertex(val vertex: Any) : FlowTarget()
    class Child(val node: Any) : FlowTarget()
    class OwnPort(val node: Any, val port: Port) : FlowTarget()
}

internal class EdgeSpec(val from: Any, val to: FlowTarget, var label: Any?)


/**
 * The receiver of a flow. Inside it:
 *
 * - `node` (or the lambda parameter) is the node whose flow is defined. Used in a flow, it is a
 *   vertex of the graph. So are objects created with [vertex]. Every other object is a child, and
 *   stands for the child's own flow
 * - [entry] sets where control enters this flow
 * - `a then b` connects: if `a` is a vertex, it adds an edge from it; if `a` is a child, its `next`
 *   leads to `b`; if `a` is `port(child)`, that port of the child leads to `b`. `b` can be a vertex,
 *   a child (its entry) or a port of this node, e.g. `next`
 * - `a then b label x` labels the edge
 * - [bound] tells whether the parent connected a port of this node. A flow can use it to act
 *   differently in different contexts, e.g. as a condition (`onTrue` bound) or as a value
 */
public class FlowScope<N : Any> internal constructor(
    public val node: N,
    private val build: FlowBuild,
) {
    internal var entrySpec: FlowTarget? = null
    private val ownVertices = IdentitySet<Any>()

    /** The normal successor port. */
    public val next: Port get() = Port.next

    /** Control enters this flow at [target]. */
    public fun entry(target: Any) {
        check(entrySpec == null) { "the flow of $node already has an entry" }
        entrySpec = toTarget(target)
    }

    public infix fun Any.then(target: Any): Connection {
        val source = this
        val to = toTarget(target)
        when {
            source is Port -> error("a port of the node itself can not be connected; only ports of children: $source($node)")
            source is PortRef -> {
                build.reference(source.node, node)
                build.bind(source.node, source.port, to)
                return Connection(null)
            }
            isVertex(source) -> {
                build.addVertex(source)
                val spec = EdgeSpec(source, to, null)
                build.edges += spec
                return Connection(spec)
            }
            else -> {
                build.reference(source, node)
                build.bind(source, Port.next, to)
                return Connection(null)
            }
        }
    }

    /** The [port] of [child], e.g. `onTrue(cond) then body`. */
    public operator fun Port.invoke(child: Any): PortRef = PortRef(child, this)

    /** Makes [vertex] a vertex of the graph, e.g. `val t = vertex(PushTrue())`. Returns it. */
    public fun <V : Any> vertex(vertex: V): V {
        ownVertices.add(vertex)
        build.addVertex(vertex)
        return vertex
    }

    /** Whether the parent connected [port] of this node. Inherited ports also look further up. */
    public fun bound(port: Port): Boolean = build.isBound(node, port)

    /** Runs [items] one after another: the entry is the first, each one's `next` is the following, the last leads to `next`. */
    public fun sequence(items: List<Any>) {
        if (items.isEmpty()) {
            entry(next)
            return
        }
        entry(items.first())
        for (i in 0 until items.size - 1) items[i] then items[i + 1]
        items.last() then next
    }

    private fun isVertex(x: Any) = x === node || x in ownVertices

    private fun toTarget(x: Any): FlowTarget = when {
        x is Port -> FlowTarget.OwnPort(node, x)
        x is PortRef -> error("a port of a child can not be a target: $x. Connect it instead: $x then ...")
        isVertex(x) -> {
            build.addVertex(x)
            FlowTarget.Vertex(x)
        }
        else -> {
            build.reference(x, node)
            FlowTarget.Child(x)
        }
    }

    /** Result of `a then b`. */
    public class Connection internal constructor(private val spec: EdgeSpec?) {
        /** Sets the label of the edge. Only edges from vertices have labels. */
        public infix fun label(label: Any?) {
            checkNotNull(spec) { "only edges from vertices can be labeled" }.label = label
        }
    }
}


/** Runs the flows of all nodes reachable from [root] and resolves them into a [Graph]. */
internal class FlowBuild(
    private val root: Any,
    private val ruleFor: (Any) -> FlowGraph.Rule?,
) {
    private val scopes = identityMap<Any, FlowScope<Any>>()
    private val flowParent = identityMap<Any, Any>()
    private val bindings = identityMap<Any, MutableMap<Port, FlowTarget>>()
    private val queue = ArrayDeque<Any>()
    private val vertices = ArrayList<Any>()
    private val vertexSet = IdentitySet<Any>()
    private val exits = LinkedHashMap<Port, Any>()
    internal val edges = ArrayList<EdgeSpec>()

    fun build(): Graph {
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (scopes.containsKey(node)) continue
            val rule = ruleFor(node) ?: error(
                "no flow defined for ${node::class.simpleName}" +
                        (flowParent[node]?.let { ", used in the flow of ${it::class.simpleName}" } ?: "")
            )
            val scope = FlowScope(node, this)
            scopes[node] = scope
            EvalContext.withNode(node) { rule.body(scope, node) }
        }
        val entry = resolve(FlowTarget.Child(root))
        val resolved = edges.map { Edge(it.from, resolve(it.to), it.label) }
        return Graph(root, entry, listOf(entry) + vertices, resolved, exits)
    }

    fun reference(child: Any, by: Any) {
        if (child === root || flowParent.containsKey(child)) return
        flowParent[child] = by
        queue.addLast(child)
    }

    fun bind(child: Any, port: Port, target: FlowTarget) {
        val ports = bindings[child] ?: LinkedHashMap<Port, FlowTarget>().also { bindings[child] = it }
        check(port !in ports) { "port $port of $child is connected twice" }
        ports[port] = target
    }

    fun addVertex(v: Any) {
        if (vertexSet.add(v)) vertices += v
    }

    fun isBound(node: Any, port: Port): Boolean {
        if (bindings[node]?.containsKey(port) == true) return true
        if (!port.inherited) return false
        return flowParent[node]?.let { isBound(it, port) } ?: false
    }

    // ---- resolution ------------------------------------------------------------------------

    private val resolved = identityMap<FlowTarget, Any>()
    private val resolving = IdentitySet<FlowTarget>()

    private fun resolve(target: FlowTarget): Any {
        resolved[target]?.let { return it }
        if (!resolving.add(target)) error("flow cycle without a vertex at ${describe(target)}")
        try {
            val vertex = when (target) {
                is FlowTarget.Vertex -> target.vertex
                is FlowTarget.Child -> {
                    val scope = scopes[target.node] ?: error("no flow for ${target.node}")
                    resolve(scope.entrySpec ?: error("the flow of ${target.node} has no entry"))
                }
                is FlowTarget.OwnPort -> resolvePort(target.node, target.port)
            }
            resolved[target] = vertex
            return vertex
        } finally {
            resolving.remove(target)
        }
    }

    private fun resolvePort(node: Any, port: Port): Any {
        bindings[node]?.get(port)?.let { return resolve(it) }
        if (node === root) return exits.getOrPut(port) { ExitVertex(port).also(::addVertex) }
        val parent = flowParent[node]!!
        if (port.inherited) return resolvePort(parent, port)
        error("port $port of $node is not connected; connect it in the flow of $parent, e.g. `${if (port == Port.next) "child" else "$port(child)"} then ...`")
    }

    private fun describe(target: FlowTarget) = when (target) {
        is FlowTarget.Vertex -> target.vertex.toString()
        is FlowTarget.Child -> "entry of ${target.node}"
        is FlowTarget.OwnPort -> "${target.port}(${target.node})"
    }
}
