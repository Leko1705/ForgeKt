package com.leko.forge.msi

import com.leko.forge.FlowScope
import com.leko.forge.Graph
import com.leko.forge.Port
import com.leko.forge.graph
import com.leko.forge.identityMap
import com.leko.forge.locked
import com.leko.forge.port

/**
 * The default control flow graph of MSI functions: any language converted to MSI gets control flow
 * graphs, and with [com.leko.forge.circular] attributes data flow analyses, for free.
 *
 * Vertices are statements: [Let], [Assign], [Eval], [Return], [Throw], [Break] and [Continue], and the
 * decisions [Branch], [Loop] and [Match] (which evaluate their condition or subject). Edges from decisions
 * are labelled: `true`/`false` for branches and loops, the variant name (or `"else"`) for matches.
 * [Throw] leads to a [CatchDispatch] vertex of the nearest [Try], from which edges lead to each catch
 * and, if nothing matches, further out. Function exits are the exit vertices of the ports [returnPort]
 * and [throwPort] ([Graph.exit]).
 *
 * Simplifications: calls are not assumed to throw, and `finally` blocks are only on the normal paths
 * (from the end of the body and of each catch). Lambda bodies are not part of the graph.
 *
 * The module must be validated first ([validate]).
 */
public val msiFlows: com.leko.forge.FlowGraph<Func> get() = defineFlows

/** Leaves the function: unbound at the root, it leads to `graph.exit(returnPort)`. */
public val returnPort: Port = port("return", inherited = true)

/** An uncaught exception: unbound at the root, it leads to `graph.exit(throwPort)`. */
public val throwPort: Port = port("throw", inherited = true)

/** The vertex that decides which catch of [tryStmt] handles an exception. */
public class CatchDispatch internal constructor(public val tryStmt: Try) {
    override fun toString(): String = "dispatch"
}

/** The control flow graph of the function. */
public fun Func.controlFlow(): Graph = msiFlows[this]

private val loopPorts = identityMap<Loop, Pair<Port, Port>>()

/** Ports to leave and to continue a specific loop, inherited so nested statements can use them. */
private fun portsOf(loop: Loop): Pair<Port, Port> = locked(loopPorts) {
    loopPorts[loop] ?: (port("break ${loop.label ?: ""}", inherited = true) to port("continue ${loop.label ?: ""}", inherited = true))
        .also { loopPorts[loop] = it }
}

private fun FlowScope<*>.simple(node: Any) {
    entry(node)
    node then next
}

private val defineFlows: com.leko.forge.FlowGraph<Func> = graph<Func>()
    .flow<Func> { f -> f.body?.let { entry(it); it then returnPort } ?: entry(returnPort) }
    .statementFlows()

/** Control flow graphs of lambda bodies, for analyses that also check lambdas (the ownership checker). */
internal val lambdaFlows: com.leko.forge.FlowGraph<Lambda> by lazy {
    graph<Lambda>().flow<Lambda> { l -> entry(l.body); l.body then returnPort }.statementFlows()
}

private fun <R : Any> com.leko.forge.FlowGraph<R>.statementFlows(): com.leko.forge.FlowGraph<R> = this
    .flow<Block> { b -> sequence(b.stmts) }
    .flow<Let> { simple(it) }
    .flow<Assign> { simple(it) }
    .flow<Eval> { simple(it) }
    .flow<Return> { r -> entry(r); r then returnPort }
    .flow<Throw> { t -> entry(t); t then throwPort }
    .flow<Break> { b -> entry(b); b then portsOf(b.target!!).first }
    .flow<Continue> { c -> entry(c); c then portsOf(c.target!!).second }
    .flow<Branch> { b ->
        entry(b)
        b then b.then label true
        b.then then next
        val alternative = b.alternative
        if (alternative != null) {
            b then alternative label false
            alternative then next
        } else b then next label false
    }
    .flow<Loop> { l ->
        val (exit, again) = portsOf(l)
        entry(l)
        l then l.body label true
        if (l.condition != null) l then next label false
        l.body then l
        exit(l.body) then next
        again(l.body) then l
    }
    .flow<Match> { m ->
        entry(m)
        for (c in m.cases) {
            m then c.body label c.variant
            c.body then next
        }
        val otherwise = m.otherwise
        if (otherwise != null) {
            m then otherwise label "else"
            otherwise then next
        } else m then next label "else"
    }
    .flow<Access> { a -> entry(a); a then a.body; a.body then next }
    .flow<Try> { t ->
        val dispatch = vertex(CatchDispatch(t))
        val after = t.finally ?: next
        entry(t.body)
        throwPort(t.body) then dispatch
        t.body then after
        for (c in t.catches) {
            dispatch then c.body label c.type.render()
            c.body then after
        }
        dispatch then throwPort label "uncaught"
        t.finally?.let { it then next }
    }
