package com.leko.forge


/** A directed edge. [label] is free to use, e.g. `true`/`false` for the two exits of a branch. */
public class Edge(public val from: Any, public val to: Any, public val label: Any? = null) {
    override fun toString(): String = if (label == null) "$from -> $to" else "$from -[$label]-> $to"
}

/**
 * Creates a graph from [edges]. Use it for graphs that are not built by flows, e.g. call graphs.
 * The vertices are [entry], the edge endpoints and [extraVertices], in that order.
 */
public fun graphOf(entry: Any, edges: List<Edge>, extraVertices: List<Any> = emptyList()): Graph =
    Graph(null, entry, buildList {
        addAll(listOf(entry))
        edges.flatMapTo(this) { listOf(it.from, it.to) }
        addAll(extraVertices)
    }, edges, emptyMap())

/**
 * A directed graph whose vertices can be any objects (AST nodes, synthetic vertices, ...).
 *
 * Vertices are compared by identity, so structurally equal data class nodes stay separate vertices.
 * Every vertex has an [indexOf] in `0 until vertices.size`, which is useful for bit sets and arrays
 * in algorithms.
 */
public class Graph internal constructor(
    /** The node the graph was built from, or `null` for graphs from [graphOf]. */
    public val root: Any?,
    public val entry: Any,
    vertices: List<Any>,
    public val edges: List<Edge>,
    private val exitVertices: Map<Port, Any>,
) {
    private val index = identityMap<Any, Int>()

    /** All vertices, without duplicates, in the order they were found. */
    public val vertices: List<Any>

    private val outgoing: List<MutableList<Edge>>
    private val incoming: List<MutableList<Edge>>

    init {
        val distinct = ArrayList<Any>()
        fun add(v: Any) { if (!index.containsKey(v)) { index[v] = distinct.size; distinct += v } }
        for (v in vertices) add(v)
        for (e in edges) { add(e.from); add(e.to) }
        this.vertices = distinct
        outgoing = List(distinct.size) { ArrayList() }
        incoming = List(distinct.size) { ArrayList() }
        for (e in edges) {
            outgoing[index[e.from]!!] += e
            incoming[index[e.to]!!] += e
        }
    }

    public operator fun contains(vertex: Any): Boolean = index.containsKey(vertex)

    /** The index of [vertex] in [vertices], or -1 if it is not part of this graph. */
    public fun indexOf(vertex: Any): Int = index[vertex] ?: -1

    public fun outEdges(vertex: Any): List<Edge> = index[vertex]?.let { outgoing[it] } ?: emptyList()
    public fun inEdges(vertex: Any): List<Edge> = index[vertex]?.let { incoming[it] } ?: emptyList()

    public fun succ(vertex: Any): List<Any> = outEdges(vertex).map { it.to }
    public fun pred(vertex: Any): List<Any> = inEdges(vertex).map { it.from }

    /** The vertex that an unbound [port] of the root leads to, e.g. the end of a function for `next`. */
    public fun exit(port: Port = Port.next): Any? = exitVertices[port]

    /** All exit vertices, by port. */
    public val exits: Map<Port, Any> get() = exitVertices

    /** Vertices reachable from [from], in depth first preorder. */
    public fun reachable(from: Any = entry): List<Any> {
        val seen = BooleanArray(vertices.size)
        val result = ArrayList<Any>()
        val work = ArrayDeque<Any>()
        work.addLast(from)
        while (work.isNotEmpty()) {
            val v = work.removeLast()
            val i = indexOf(v)
            if (i < 0 || seen[i]) continue
            seen[i] = true
            result += v
            succ(v).asReversed().forEach { work.addLast(it) }
        }
        return result
    }

    /** Vertices reachable from [from] in depth first postorder. */
    public fun postorder(from: Any = entry): List<Any> {
        val seen = BooleanArray(vertices.size)
        val result = ArrayList<Any>()
        val stack = ArrayDeque<Pair<Any, Iterator<Any>>>()
        fun push(v: Any) {
            val i = indexOf(v)
            if (i < 0 || seen[i]) return
            seen[i] = true
            stack.addLast(v to succ(v).iterator())
        }
        push(from)
        while (stack.isNotEmpty()) {
            val (v, children) = stack.last()
            if (children.hasNext()) push(children.next())
            else {
                stack.removeLast()
                result += v
            }
        }
        return result
    }

    /** Reverse postorder: every vertex comes before its successors, except along back edges. */
    public fun reversePostorder(from: Any = entry): List<Any> = postorder(from).asReversed()

    /** The graph in Graphviz DOT format, for debugging. */
    public fun toDot(name: String = "G", label: (Any) -> String = { it.toString() }): String = buildString {
        appendLine("digraph $name {")
        vertices.forEachIndexed { i, v -> appendLine("  n$i [label=\"${label(v).escape()}\"];") }
        for (e in edges) {
            val attrs = e.label?.let { " [label=\"${it.toString().escape()}\"]" } ?: ""
            appendLine("  n${indexOf(e.from)} -> n${indexOf(e.to)}$attrs;")
        }
        append("}")
    }

    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"")

    override fun toString(): String = "Graph(${vertices.size} vertices, ${edges.size} edges)"
}
