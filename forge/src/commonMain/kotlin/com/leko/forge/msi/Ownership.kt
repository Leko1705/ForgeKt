package com.leko.forge.msi

import com.leko.forge.Graph
import com.leko.forge.IdentitySet
import com.leko.forge.TreeNode
import com.leko.forge.childrenOf
import com.leko.forge.identityMap
import com.leko.forge.parentOf
import com.leko.forge.syn
import com.leko.forge.tree

/*
 * The ownership checker: moves, initialization, borrows and lifetimes, for modules that use
 * value or owned types, references or lifetimes. It only rejects programs; accepted programs behave
 * exactly as without it.
 *
 * Every function and lambda is interpreted abstractly over its control flow graph ([msiFlows]):
 * - forward: which variables may be moved (or are not initialized yet), and which loans and lifetimes
 *   ("origins") the references held by each variable may come from
 * - backward: which variables are live. A loan only blocks other accesses while a variable holding it
 *   is live (non-lexical lifetimes); borrows passed directly to a call end with the call
 * - then every statement is replayed in evaluation order to report conflicts
 */

/** Whether the module uses value or owned types, references or lifetimes, which turns the ownership checker on. */
public val Module.usesOwnership: Boolean by syn {
    structs.any { it.kind != MemoryKind.SHARED || it.lifetimes.isNotEmpty() } || unions.any { it.kind != MemoryKind.SHARED } ||
        functions.any { it.lifetimes.isNotEmpty() } || containsOwnershipNodes(this)
}

private fun containsOwnershipNodes(node: Any): Boolean = node is Borrow || node is Deref || node is Copy ||
    (node is Variable && node.type.containsRef()) || (node is Func && node.returnType.containsRef()) ||
    childrenOf(node).any { containsOwnershipNodes(it) }

private fun Type.containsRef(): Boolean = when (this) {
    is Type.Ref -> true
    is Type.Struct -> args.any { it.containsRef() }
    is Type.Union -> args.any { it.containsRef() }
    is Type.Array -> element.containsRef()
    is Type.Function -> params.any { it.containsRef() } || result.containsRef()
    else -> false
}

/** Values of [VALUE][MemoryKind.VALUE] and [OWNED][MemoryKind.OWNED] types move when they are used. */
public fun Module.isLinear(t: Type): Boolean = kindOf(t) != MemoryKind.SHARED

/** Whether using this variable moves its value: a value or owned type used as a value (not as a place). */
public val Local.isMove: Boolean get() = module.isLinear(type) && !inPlaceContext(this)

/** Uses that only read or write a place instead of taking its value. */
private fun inPlaceContext(e: Expr): Boolean = when (val p = parentOf(e)) {
    is Get -> p.target === e
    is Borrow, is Copy, is Is, is Length -> true
    is Index -> p.array === e
    is Match -> p.subject === e
    is Access -> p.target === e
    is Assign -> p.target === e
    is Deref -> true
    else -> false
}

internal val Module.ownershipProblems: List<Problem> by syn { OwnershipChecker(this).check() }

/** Uses of value-type variables that are not their last use, so a Swift-like language copies them. */
internal fun Module.copySites(): IdentitySet<Local> {
    tree(this)
    val checker = OwnershipChecker(this)
    checker.copySites = IdentitySet()
    checker.check()
    return checker.copySites!!
}

/** Whether [variable] is borrowed as a whole (`Borrow(Local)`). Backends with garbage collection keep such variables in a box. */
public fun Module.isBorrowed(variable: Variable): Boolean = variable in borrowedVariables

internal val Module.borrowedVariables: IdentitySet<Variable> by syn {
    val set = IdentitySet<Variable>()
    fun visit(node: Any) {
        if (node is Borrow) (node.place as? Local)?.variable?.let { set.add(it) }
        childrenOf(node).forEach(::visit)
    }
    visit(this)
    set
}

// ==== the checker ================================================================================

/** Where a reference may point to: a local place it borrowed, or a lifetime of the function's parameters. */
private sealed class Origin {
    class Loan(val root: Variable, val path: List<String>, val mutable: Boolean, val node: TreeNode) : Origin()
    data class Lifetime(val name: String) : Origin()
    /** A variable captured by a closure; on the heap, so it never dangles, but it is borrowed while the closure lives. */
    data class Capture(val loan: Loan) : Origin()
}

/** How a place is accessed. */
private enum class Mode { READ, WRITE, MOVE, BORROW, BORROW_MUT }

/** Variables by id (identity based: equal looking variables are different variables). */
private data class State(val moved: Set<Int>, val held: Map<Int, Set<Origin>>) {
    fun join(o: State): State {
        val held = HashMap(held)
        for ((v, s) in o.held) held[v] = (held[v] ?: emptySet()) + s
        return State(moved + o.moved, held)
    }
}

private class OwnershipChecker(val module: Module) {
    /** If set, moves of value types that are not last uses are collected here and treated as copies. */
    var copySites: IdentitySet<Local>? = null
    private val problems = ArrayList<Problem>()
    private val reported = HashSet<String>()

    private fun report(node: Any, message: String) {
        val where = (node as? TreeNode)?.function?.let { "in fun ${it.name}: " } ?: ""
        if (reported.add("${node.hashCode()} $where$message")) problems += Problem(where + message, node)
    }

    fun check(): List<Problem> {
        structural()
        for (f in module.functions) if (f.body != null) Analysis(f, msiFlows[f], f.params, f.returnType, f.lifetimes, f.outlives).run()
        lambdas(module).forEach { l ->
            Analysis(l, lambdaFlows[l], l.params, l.returnType, emptyList(), emptyList()).run()
        }
        return problems
    }

    private fun lambdas(node: Any): List<Lambda> = childrenOf(node).flatMap { (if (it is Lambda) listOf(it) else emptyList()) + lambdas(it) }

    private fun linear(t: Type) = module.isLinear(t)

    /** Types holding references: references, structs with lifetimes, and function values (capturing loans). */
    private fun holdsRefs(t: Type): Boolean = t is Type.Ref || t is Type.Function ||
        (t is Type.Struct && module.structsByName[t.name]?.lifetimes?.isNotEmpty() == true)

    // ---- structural rules ------------------------------------------------------------------

    private fun structural() {
        for (s in module.structs) for (f in s.fields) {
            val where = "field ${f.name} of struct ${s.name}"
            if (s.kind == MemoryKind.SHARED && linear(f.type)) report(s, "$where: a shared struct can not hold ${kindName(f.type)} values")
            if (s.kind == MemoryKind.VALUE && module.kindOf(f.type) == MemoryKind.OWNED) report(s, "$where: a value struct can not hold owned values")
            val ref = f.type as? Type.Ref
            if (ref != null) {
                if (s.kind == MemoryKind.SHARED) report(s, "$where: shared structs can not hold references")
                else if (ref.lifetime == null || ref.lifetime !in s.lifetimes) report(s, "$where: a reference field needs a lifetime declared by the struct")
            }
            typeUses(f.type, s, where, allowRefTop = true)
        }
        for (u in module.unions) for (v in u.variants) for (f in v.fields) {
            val where = "field ${f.name} of ${u.name}.${v.name}"
            if (u.kind == MemoryKind.SHARED && linear(f.type)) report(u, "$where: a shared union can not hold ${kindName(f.type)} values")
            if (u.kind == MemoryKind.VALUE && module.kindOf(f.type) == MemoryKind.OWNED) report(u, "$where: a value union can not hold owned values")
            if (f.type is Type.Ref) report(u, "$where: unions can not hold references")
            typeUses(f.type, u, where)
        }
        for (g in module.globals) {
            if (linear(g.type)) report(g, "global ${g.name} can not hold ${kindName(g.type)} values")
            if (g.type.containsRef()) report(g, "global ${g.name} can not hold references")
        }
        for (f in module.functions) {
            f.params.forEach { typeUses(it.type, f, "parameter ${it.name} of fun ${f.name}", allowRefTop = true) }
            typeUses(f.returnType, f, "the result of fun ${f.name}", allowRefTop = true)
            for (l in declaredLifetimes(f)) if (l !in f.lifetimes) report(f, "fun ${f.name} uses lifetime '$l without declaring it")
            if (holdsRefsOrLinearRefs(f.returnType) && returnLifetimes(f.params, f.returnType) == null)
                report(f, "the reference returned by fun ${f.name} needs a lifetime: it has ${refParams(f.params).size} reference parameters")
        }
        nodes(module)
    }

    private fun holdsRefsOrLinearRefs(t: Type) = t is Type.Ref || (t is Type.Struct && module.structsByName[t.name]?.lifetimes?.isNotEmpty() == true)

    private fun kindName(t: Type) = if (module.kindOf(t) == MemoryKind.OWNED) "owned" else "value"

    private fun declaredLifetimes(f: Func): List<String> =
        (f.params.map { it.type } + f.returnType).flatMap { lifetimesIn(it) }.filter { !it.startsWith("_") }

    private fun lifetimesIn(t: Type): List<String> = when (t) {
        is Type.Ref -> listOfNotNull(t.lifetime) + lifetimesIn(t.target)
        is Type.Struct -> t.lifetimes + t.args.flatMap { lifetimesIn(it) }
        else -> emptyList()
    }

    /** Where references and linear values may not appear inside a type. */
    private fun typeUses(t: Type, node: Any, where: String, allowRefTop: Boolean = false) {
        fun inner(t: Type, top: Boolean) {
            when (t) {
                is Type.Ref -> {
                    if (!top || !allowRefTop) report(node, "$where: references can only be parameters, variables, results and fields of value or owned structs")
                    inner(t.target, false)
                }
                is Type.Array -> { if (linear(t.element)) report(node, "$where: arrays can not hold ${kindName(t.element)} values"); inner(t.element, false) }
                is Type.Function -> (t.params + t.result).forEach {
                    if (linear(it) || it.containsRef()) report(node, "$where: function values can not take or return ${if (it.containsRef()) "references" else "${kindName(it)} values"}")
                }
                is Type.Struct -> t.args.forEach { if (linear(it) || it.containsRef()) report(node, "$where: type arguments can not be ${if (it.containsRef()) "references" else "${kindName(it)} types"}") }
                is Type.Union -> t.args.forEach { if (linear(it) || it.containsRef()) report(node, "$where: type arguments can not be ${if (it.containsRef()) "references" else "${kindName(it)} types"}") }
                else -> {}
            }
        }
        if (t is Type.Ref && t.target is Type.Ref) report(node, "$where: references to references are not supported")
        inner(t, true)
    }

    private fun nodes(node: Any) {
        when (node) {
            is Let -> {
                typeUses(node.type, node, "variable ${node.name}", allowRefTop = true)
                if (node.init == null && node.type is Type.Ref) report(node, "reference ${node.name} needs an initial value")
            }
            is Binary -> if ((node.op == BinaryOp.EQ || node.op == BinaryOp.NE) && (linear(node.left.type) || node.left.type is Type.Ref))
                report(node, "can not compare ${if (node.left.type is Type.Ref) "references" else "${kindName(node.left.type)} values"} with ${node.op.symbol}")
            is Copy -> if (module.kindOf(node.value.type) == MemoryKind.OWNED) report(node, "owned values can not be copied")
            is Call -> node.typeArguments.forEach { if (linear(it) || it.containsRef()) report(node, "type arguments can not be references, value or owned types") }
            is New -> node.typeArguments.forEach { if (linear(it) || it.containsRef()) report(node, "type arguments can not be references, value or owned types") }
            is Make -> node.typeArguments.forEach { if (linear(it) || it.containsRef()) report(node, "type arguments can not be references, value or owned types") }
            is Throw -> if (linear(node.value.type)) report(node, "can not throw ${kindName(node.value.type)} values")
            is Case -> node.variables.forEach { if (linear(it.type)) report(node, "can not move ${kindName(it.type)} field ${it.name} out of a union; match on a copy or restructure") }
            is Lambda -> node.captures.forEach { if (linear(it.type) || holdsRefs(it.type) && it.type !is Type.Function) report(node, "lambdas can not capture ${if (linear(it.type)) "${kindName(it.type)} values" else "references"} (${it.name})") }
            is Borrow -> if (throughShared(node.place)) report(node, "can not borrow a field of a shared struct; use Access for shared data")
            is Get -> if (linear(node.type) && !inPlaceContext(node) && parentOf(node) !is Copy) report(node, "can not move ${kindName(node.type)} field ${node.field} out of a struct; borrow or copy it")
            is Deref -> if (linear(node.type) && !inPlaceContext(node)) report(node, "can not move a ${kindName(node.type)} value out of a reference; borrow or copy it")
        }
        childrenOf(node).forEach(::nodes)
    }

    private fun throughShared(place: Expr): Boolean = place is Get &&
        ((place.target.type as? Type.Struct)?.let { module.kindOf(it) == MemoryKind.SHARED } == true || throughShared(place.target))

    // ---- lifetimes of signatures -----------------------------------------------------------

    /** The parameters holding references, with the lifetimes of their types (elided ones named `_p<i>`). */
    private fun refParams(params: List<Param>): List<Pair<Int, List<String>>> = params.mapIndexedNotNull { i, p ->
        val t = p.type
        when {
            t is Type.Ref -> i to (listOf(t.lifetime ?: "_p$i") + lifetimesIn(t.target))
            t is Type.Struct && module.structsByName[t.name]?.lifetimes?.isNotEmpty() == true -> i to t.lifetimes.ifEmpty { listOf("_p$i") }
            else -> null
        }
    }

    /** The lifetimes of the result, `null` if they are elided but can not be inferred. */
    private fun returnLifetimes(params: List<Param>, result: Type): List<String>? {
        if (!holdsRefsOrLinearRefs(result)) return emptyList()
        val explicit = lifetimesIn(result)
        val elided = result is Type.Ref && result.lifetime == null || result is Type.Struct && result.lifetimes.isEmpty()
        if (!elided) return explicit
        val refs = refParams(params)
        return if (refs.size == 1) refs.single().second.take(1) + explicit else null
    }

    private fun outlives(longer: String, shorter: String, relations: List<Outlives>): Boolean {
        if (longer == shorter) return true
        val seen = HashSet<String>()
        val work = ArrayDeque(listOf(longer))
        while (work.isNotEmpty()) {
            val l = work.removeLast()
            if (l == shorter) return true
            if (seen.add(l)) relations.filter { it.longer == l }.forEach { work += it.shorter }
        }
        return false
    }

    // ---- flow analysis of one function or lambda -------------------------------------------

    private inner class Analysis(
        val owner: TreeNode,
        val graph: Graph,
        val params: List<Param>,
        val returnType: Type,
        val lifetimes: List<String>,
        val relations: List<Outlives>,
    ) {
        private val liveOut = identityMap<Any, Set<Int>>()
        private val stateIn = identityMap<Any, State>()
        private val loans = identityMap<Any, Origin.Loan>()
        private val captureLoans = identityMap<Lambda, com.leko.forge.IdentityMap<Variable, Origin.Loan>>()
        private val ids = identityMap<Variable, Int>()
        private var nextId = 0
        private fun id(v: Variable): Int = ids[v] ?: nextId++.also { ids[v] = it }
        private var reporting = false
        private var live: Set<Int> = emptySet()
        private val temps = ArrayList<Set<Origin>>()

        fun run() {
            liveness()
            val entry = State(emptySet(), params.mapIndexedNotNull { i, p ->
                refParams(params).firstOrNull { it.first == i }?.let { (_, ls) -> id(p) to ls.map<String, Origin> { Origin.Lifetime(it) }.toSet() }
            }.toMap())
            // forward fixpoint
            val work = ArrayDeque<Any>(graph.reversePostorder())
            stateIn[graph.entry] = entry
            val queued = IdentitySet<Any>()
            work.forEach { queued.add(it) }
            while (work.isNotEmpty()) {
                val v = work.removeFirst()
                queued.remove(v)
                val input = stateIn[v] ?: continue
                val output = transfer(v, input)
                for (s in graph.succ(v)) {
                    val old = stateIn[s]
                    val new = old?.join(output) ?: output
                    if (old == null || new != old) {
                        stateIn[s] = new
                        if (queued.add(s)) work.addLast(s)
                    }
                }
            }
            // report
            reporting = true
            for (v in graph.vertices) stateIn[v]?.let { transfer(v, it) }
        }

        // ---- liveness ----------------------------------------------------------------------

        private fun expressionsOf(v: Any): List<Expr> = when (v) {
            is Let -> listOfNotNull(v.init)
            is Assign -> listOf(v.value, v.target)
            is Eval -> listOf(v.expr)
            is Return -> listOfNotNull(v.value)
            is Throw -> listOf(v.value)
            is Branch -> listOf(v.condition)
            is Loop -> listOfNotNull(v.condition)
            is Match -> listOf(v.subject)
            is Access -> listOf(v.target)
            else -> emptyList()
        }

        private fun usesOf(v: Any): Set<Int> {
            val out = HashSet<Int>()
            fun visit(n: Any, isTarget: Boolean) {
                if (n is Local && !isTarget) n.variable?.let { out += id(it) }
                childrenOf(n).forEach { visit(it, false) }
            }
            for (e in expressionsOf(v)) visit(e, v is Assign && e === v.target && e is Local)
            return out
        }

        private fun defOf(v: Any): Int? = when (v) {
            is Let -> id(v)
            is Assign -> (v.target as? Local)?.variable?.let { id(it) }
            else -> null
        }

        private fun liveness() {
            val liveIn = identityMap<Any, Set<Int>>()
            var changed = true
            val order = graph.postorder()
            while (changed) {
                changed = false
                for (v in order) {
                    val out = graph.succ(v).flatMap { liveIn[it] ?: emptySet() }.toSet()
                    val inn = usesOf(v) + (out - setOfNotNull(defOf(v)))
                    if (liveOut[v] != out || liveIn[v] != inn) changed = true
                    liveOut[v] = out
                    liveIn[v] = inn
                }
            }
        }

        // ---- transfer: abstract interpretation of one vertex -------------------------------

        private lateinit var moved: MutableSet<Int>
        private lateinit var held: MutableMap<Int, Set<Origin>>

        private var vertex: Any? = null
        private val occurrences = identityMap<Any, List<Local>>()

        /** The variable uses of a vertex, in evaluation order. */
        private fun occurrencesOf(v: Any): List<Local> = occurrences[v] ?: ArrayList<Local>().also { list ->
            fun visit(n: Any) { if (n is Local) list += n; if (n !is Lambda) childrenOf(n).forEach(::visit) }
            expressionsOf(v).forEach(::visit)
            occurrences[v] = list
        }

        /** Whether the variable of [use] is used again after it. */
        private fun usedLater(use: Local, variable: Variable): Boolean {
            val v = vertex ?: return true
            if (id(variable) in (liveOut[v] ?: emptySet())) return true
            val list = occurrencesOf(v)
            val i = list.indexOfFirst { it === use }
            return list.drop(i + 1).any { it.variable === variable }
        }

        private fun transfer(v: Any, input: State): State {
            vertex = v
            moved = input.moved.toMutableSet()
            held = input.held.toMutableMap()
            live = (liveOut[v] ?: emptySet()) + usesOf(v)
            temps.clear()
            when (v) {
                is Let -> {
                    val origins = v.init?.let { value(it) } ?: emptySet()
                    if (v.init == null && linear(v.type)) moved += id(v) else moved -= id(v)
                    if (holdsRefs(v.type)) held[id(v)] = origins else held.remove(id(v))
                }
                is Assign -> {
                    val origins = value(v.value)
                    temps += origins
                    assign(v.target, origins, v)
                }
                is Eval -> value(v.expr)
                is Return -> {
                    val origins = v.value?.let { value(it) } ?: emptySet()
                    if (reporting && holdsRefsOrLinearRefs(returnType)) checkReturn(origins, v)
                }
                is Throw -> value(v.value)
                is Branch -> value(v.condition)
                is Loop -> v.condition?.let { value(it) }
                is Match -> place(v.subject, Mode.READ, v)
                is Access -> value(v.target)
            }
            return State(moved.toSet(), held.toMap())
        }

        private fun checkReturn(origins: Set<Origin>, node: TreeNode) {
            val allowed = returnLifetimes(params, returnType) ?: return
            for (o in origins) when (o) {
                // a reborrow through a reference (`&(*p).x`) points to what p points to: its origins are inherited
                is Origin.Loan -> if (o.path.firstOrNull() != "*") report(node, "returns a reference to local data (${describe(o)})")
                is Origin.Lifetime -> if (allowed.none { outlives(o.name, it, relations) })
                    report(node, "returns a reference with lifetime ${show(o.name)} where ${allowed.joinToString { show(it) }} is required; declare ${show(o.name)}: ${show(allowed.first())}")
                is Origin.Capture -> {}
            }
        }

        private fun show(lifetime: String) = if (lifetime.startsWith("_p")) "of parameter ${params.getOrNull(lifetime.drop(2).toIntOrNull() ?: -1)?.name}" else "'$lifetime"

        private fun describe(loan: Origin.Loan) = (listOf(loan.root.name) + loan.path).joinToString(".")

        // ---- accesses and conflicts ----------------------------------------------------------


        /** Loans that are in use: held by live variables or by temporaries of the current statement. */
        private fun activeLoans(): List<Origin.Loan> {
            val result = ArrayList<Origin.Loan>()
            for (v in live) held[v]?.forEach { o -> loanOf(o)?.let { result += it } }
            for (t in temps) t.forEach { o -> loanOf(o)?.let { result += it } }
            return result
        }

        private fun loanOf(o: Origin): Origin.Loan? = when (o) { is Origin.Loan -> o; is Origin.Capture -> o.loan; else -> null }

        private fun overlaps(l: Origin.Loan, root: Variable, path: List<String>): Boolean {
            if (l.root !== root) return false
            val n = minOf(l.path.size, path.size)
            return l.path.subList(0, n) == path.subList(0, n)
        }

        private fun access(root: Variable, path: List<String>, kind: Mode, node: TreeNode, through: Origin.Loan? = null) {
            if (!reporting) return
            val name = (listOf(root.name) + path).joinToString(".")
            val reinitializes = kind == Mode.WRITE && path.isEmpty() // assigning a whole new value is fine after a move
            if (!reinitializes && id(root) in moved && (linear(root.type) || root.type is Type.Ref)) {
                report(node, if (root is Let && root.init == null) "'${root.name}' may be used before it is initialized" else "use of moved value '${root.name}'")
                return
            }
            for (l in activeLoans()) {
                if (l === through || !overlaps(l, root, path)) continue
                val conflict = when (kind) {
                    Mode.READ, Mode.BORROW -> l.mutable
                    Mode.WRITE, Mode.MOVE, Mode.BORROW_MUT -> true
                }
                if (!conflict) continue
                val verb = when (kind) {
                    Mode.READ -> "use"; Mode.WRITE -> "assign to"; Mode.MOVE -> "move"
                    Mode.BORROW -> "borrow"; Mode.BORROW_MUT -> "borrow mutably"
                }
                report(node, "can not $verb '$name' while it is borrowed${if (l.mutable) " mutably" else ""} (by ${describe(l)} at ${(l.node as? Borrow)?.render() ?: "a closure"})")
                return
            }
        }

        // ---- places --------------------------------------------------------------------------

        /** The variable and field path of a place rooted in a local variable, if any. */
        private fun pathOf(e: Expr): Pair<Variable, List<String>>? = when (e) {
            is Local -> e.variable?.let { it to emptyList() }
            is Get -> pathOf(e.target)?.let { (v, p) -> v to p + e.field }
            is Deref -> (e.ref as? Local)?.variable?.let { it to listOf("*") }
            else -> null
        }

        /** Evaluates a place for [kind] without taking its value. Returns the origins of what it holds. */
        private fun place(e: Expr, kind: Mode, node: TreeNode): Set<Origin> {
            val path = pathOf(e)
            if (path == null) return when (e) { // a temporary, e.g. a call result, or a reference that is not a variable
                is Deref -> value(e.ref)
                is Get -> place(e.target, Mode.READ, node)
                else -> value(e)
            }
            val (root, fields) = path
            if (e is Deref || (e is Get && containsDeref(e))) {
                // through a reference: the reference itself is read
                val ref = refRoot(e)!!
                access(ref, emptyList(), Mode.READ, node)
                access(ref, fields, kind, node)
                return held[id(ref)] ?: emptySet()
            }
            access(root, fields, kind, node)
            if (kind == Mode.MOVE && fields.isEmpty()) moved += id(root)
            return held[id(root)] ?: emptySet()
        }

        private fun containsDeref(e: Expr): Boolean = e is Deref || e is Get && containsDeref(e.target)
        private fun refRoot(e: Expr): Variable? = when (e) {
            is Deref -> (e.ref as? Local)?.variable
            is Get -> refRoot(e.target)
            else -> null
        }

        private fun assign(target: Expr, origins: Set<Origin>, node: Assign) {
            when (target) {
                is Local -> {
                    val v = target.variable ?: return
                    access(v, emptyList(), Mode.WRITE, node)
                    moved -= id(v)
                    if (holdsRefs(v.type)) held[id(v)] = origins
                }
                is Index -> { value(target.array); value(target.index) }
                else -> {
                    val path = pathOf(target)
                    if (path == null) { value(target); return }
                    if (containsDeref(target)) {
                        val ref = refRoot(target)!!
                        access(ref, emptyList(), Mode.READ, node)
                        access(ref, path.second, Mode.WRITE, node)
                        if (origins.isNotEmpty()) storeThrough(ref, origins, node)
                    } else {
                        access(path.first, path.second, Mode.WRITE, node)
                        if (origins.isNotEmpty()) held[id(path.first)] = (held[id(path.first)] ?: emptySet()) + origins
                    }
                }
            }
        }

        /** References stored where [ref] points must live as long as the data there. */
        private fun storeThrough(ref: Variable, origins: Set<Origin>, node: TreeNode) {
            for (target in held[id(ref)] ?: emptySet()) {
                when (target) {
                    is Origin.Loan -> held[id(target.root)] = (held[id(target.root)] ?: emptySet()) + origins
                    is Origin.Lifetime -> if (reporting) {
                        val required = lifetimesIn((ref.type as Type.Ref).target)
                        for (o in origins) when (o) {
                            is Origin.Loan -> if (o.path.firstOrNull() != "*") report(node, "stores a reference to local data (${describe(o)}) where it may outlive it")
                            is Origin.Lifetime -> if (required.any { !outlives(o.name, it, relations) })
                                report(node, "stores a reference with lifetime ${show(o.name)} where ${required.joinToString { show(it) }} is required")
                            is Origin.Capture -> {}
                        }
                    }
                    is Origin.Capture -> {}
                }
            }
        }

        // ---- values --------------------------------------------------------------------------

        /** Evaluates [e] as a value, in evaluation order. Returns where references in its value may come from. */
        private fun value(e: Expr): Set<Origin> = when (e) {
            is Local -> {
                val v = e.variable
                when {
                    v == null -> emptySet()
                    linear(v.type) && !inPlaceContext(e) && copySites != null && module.kindOf(v.type) == MemoryKind.VALUE && usedLater(e, v) -> {
                        copySites?.add(e) // a copy instead of a move
                        place(e, Mode.READ, e)
                    }
                    linear(v.type) && !inPlaceContext(e) -> place(e, Mode.MOVE, e)
                    v.type is Type.Ref && (v.type as Type.Ref).mutable && !isCallArgument(e) && !inPlaceContext(e) -> place(e, Mode.MOVE, e)
                    else -> place(e, Mode.READ, e)
                }
            }
            is Get -> place(e, Mode.READ, e)
            is Deref -> place(e, Mode.READ, e)
            is Borrow -> borrow(e)
            is Copy -> if (isPlace(e.value)) place(e.value, Mode.READ, e) else value(e.value)
            is Call -> call(e.args, e.callee?.let { refDependencies(it) } ?: emptySet())
            is Invoke -> { value(e.callee); call(e.args, emptySet()) }
            is New -> call(e.args, if (holdsRefs(e.type)) e.args.indices.toSet() else emptySet())
            is Make -> call(e.args, emptySet())
            is Lambda -> lambda(e)
            is Conditional -> { value(e.condition); value(e.then) + value(e.otherwise) }
            is Index -> { value(e.array); value(e.index); emptySet() }
            is Is -> { if (isPlace(e.subject)) place(e.subject, Mode.READ, e) else value(e.subject); emptySet() }
            is Length -> { value(e.array); emptySet() }
            else -> { childrenOf(e).forEach { if (it is Expr) value(it) }; emptySet() }
        }

        private fun isCallArgument(e: Expr) = parentOf(e).let { it is Call || it is Invoke && it.callee !== e || it is New || it is Make }

        /** Arguments are evaluated in order; borrows among them are temporaries until the call ends. */
        private fun call(args: List<Expr>, resultFrom: Set<Int>): Set<Origin> {
            val start = temps.size
            val result = HashSet<Origin>()
            args.forEachIndexed { i, a ->
                val o = value(a)
                temps += o
                if (i in resultFrom) result += o
            }
            while (temps.size > start) temps.removeAt(temps.size - 1)
            return result
        }

        /** The parameters whose references the result of a call to [f] may come from. */
        private fun refDependencies(f: Func): Set<Int> {
            val result = returnLifetimes(f.params, f.returnType) ?: return emptySet()
            if (result.isEmpty()) return emptySet()
            return refParams(f.params).filter { (_, ls) -> ls.any { l -> result.any { r -> outlives(l, r, f.outlives) } } }.map { it.first }.toSet()
        }

        private fun borrow(e: Borrow): Set<Origin> {
            val path = pathOf(e.place)
            if (path == null) { value(e.place); return emptySet() }
            val kind = if (e.mutable) Mode.BORROW_MUT else Mode.BORROW
            val inherited = place(e.place, kind, e)
            val loan = loans[e] ?: run {
                val (root, fields) = if (containsDeref(e.place)) refRoot(e.place)!! to path.second else path
                Origin.Loan(root, fields, e.mutable, e)
            }.also { loans[e] = it }
            // a reborrow through a reference also depends on what that reference borrowed
            return setOf(loan) + if (containsDeref(e.place)) inherited else emptySet()
        }

        private fun lambda(e: Lambda): Set<Origin> {
            val result = HashSet<Origin>()
            for (v in e.captures) {
                val mutable = assigns(e.body, v)
                access(v, emptyList(), if (mutable) Mode.BORROW_MUT else Mode.BORROW, e)
                val perLambda = captureLoans[e] ?: identityMap<Variable, Origin.Loan>().also { captureLoans[e] = it }
                val loan = perLambda[v] ?: Origin.Loan(v, emptyList(), mutable, e).also { perLambda[v] = it }
                result += Origin.Capture(loan)
            }
            return result
        }

        private fun assigns(node: Any, v: Variable): Boolean =
            (node is Assign && (node.target as? Local)?.variable === v) || childrenOf(node).any { assigns(it, v) }
    }
}
