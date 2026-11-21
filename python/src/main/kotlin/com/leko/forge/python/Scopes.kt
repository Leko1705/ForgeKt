package com.leko.forge.python

import com.leko.forge.Inh
import com.leko.forge.childrenOf
import com.leko.forge.coll
import com.leko.forge.inh
import com.leko.forge.parentOf
import com.leko.forge.spanOf
import com.leko.forge.syn

// ==== problems ===================================================================================

/** A compile error at [node] (positions refer to the source, see [Layout]). */
class PyProblem(val message: String, val node: Any?, private val at: String? = null) {
    val position: String? get() = at ?: (node as? PyNode)?.let { n ->
        // nodes parsed without a rule of their own have no span: the nearest enclosing one counts
        var withSpan: Any? = n
        while (withSpan != null && spanOf(withSpan) == null) withSpan = parentOf(withSpan)
        val span = withSpan?.let { spanOf(it) } ?: return@let null
        val (line, column) = n.module.layout.position(span.start)
        "${n.module.name}:$line:$column"
    }
    override fun toString(): String = "${position?.let { "$it: " } ?: ""}$message"
}

/** Checks of the program. Features add contributions; the owner is the [PyModule]. */
val problems: com.leko.forge.Coll<List<PyProblem>> = coll(emptyList(), owner = PyModule::class)
val PyModule.pyProblems: List<PyProblem> by problems
fun PyNode.problem(message: String): List<PyProblem> = listOf(PyProblem(message, this))

val PyNode.module: PyModule by inh<PyModule>().on<PyModule> { this }

// ==== scopes =====================================================================================

/**
 * The namespace a node's names belong to. Default values, decorators and class bases belong to the
 * enclosing scope; so does the first iterable of a comprehension.
 */
val scopes: Inh<ScopeNode> = inh<ScopeNode>()
    .on<PyModule> { this }
    .on<FunctionDef> { child -> if (child in params || child in body) this else scope }
    .on<Lambda> { child -> if (child in params || child === body) this else scope }
    .on<ClassDef> { child -> if (child in body) this else scope }
    .on<Comprehension> { this }
    .on<Param> { (parentOf(this) as PyNode).scope } // defaults are evaluated where the function is defined
    .on<CompFor> { child ->
        val comp = parentOf(this) as Comprehension
        if (child === iter && this === comp.generators.first()) (parentOf(comp) as PyNode).scope else comp
    }

/** The namespace of a node (for a scope node: the enclosing one). */
val PyNode.scope: ScopeNode by scopes

/** The names a node binds in its scope. Features implement it for their binding constructs. */
val PyNode.boundNames: List<String> by syn { emptyList() }

/**
 * The scope a node's [boundNames] are bound in: its [scope], except for constructs such as `:=`
 * that bind in an enclosing scope.
 */
val PyNode.bindingScope: ScopeNode by syn { scope }

/** The names bound by an assignment target. */
fun targetNames(target: PyExpr): List<String> = when (target) {
    is Name -> listOf(target.id)
    is TupleExpr -> target.elements.flatMap { targetNames(it) }
    is ListExpr -> target.elements.flatMap { targetNames(it) }
    is Starred -> targetNames(target.value)
    else -> emptyList()
}

/** What a scope declares: bound names, `global` and `nonlocal` names. */
class ScopeInfo(val bound: Set<String>, val globals: Set<String>, val nonlocals: Set<String>) {
    /** Local variables: bound here and not declared global or nonlocal. */
    val locals: Set<String> get() = bound - globals - nonlocals
}

val ScopeNode.info: ScopeInfo by syn {
    val bound = LinkedHashSet<String>()
    val globals = LinkedHashSet<String>()
    val nonlocals = LinkedHashSet<String>()
    if (this is FunctionDef) params.forEach { bound += it.name }
    if (this is Lambda) params.forEach { bound += it.name }
    fun visit(n: Any) {
        for (c in childrenOf(n)) {
            if (c !is PyNode) continue
            if (c.bindingScope === this) bound += c.boundNames
            if (c.scope === this) {
                if (c is Global) globals += c.names
                if (c is Nonlocal) nonlocals += c.names
            }
            visit(c)
        }
    }
    visit(this)
    ScopeInfo(bound, globals, nonlocals)
}

sealed class Resolution {
    /** A variable of a function, lambda or comprehension (also of an enclosing one). */
    class Local(val owner: ScopeNode) : Resolution()
    /** A name in a class body. */
    class ClassLocal(val owner: ClassDef) : Resolution()
    object Global : Resolution()
    object Builtin : Resolution()
    object Undefined : Resolution()
}

/**
 * Python's rules: local, enclosing functions (not classes), module, builtins; `global` and `nonlocal`.
 * In a module with `from m import *`, unknown names are globals.
 */
fun resolve(from: PyNode, name: String): Resolution {
    val start = from.scope
    val module = from.module
    fun global() = when {
        name in module.info.bound -> Resolution.Global
        name in PythonBuiltins -> Resolution.Builtin
        module.hasStarImport -> Resolution.Global
        else -> Resolution.Undefined
    }
    if (start is PyModule) return global()
    val info = start.info
    if (name in info.globals) return Resolution.Global
    if (name !in info.nonlocals && name in info.bound) return if (start is ClassDef) Resolution.ClassLocal(start) else Resolution.Local(start)
    var scope: ScopeNode = start
    while (true) {
        scope = scope.scope // the scope a scope node is in: the enclosing one
        if (scope is PyModule) return global()
        if (scope !is ClassDef) {
            val i = scope.info
            if (name in i.globals) return Resolution.Global
            if (name in i.locals) return Resolution.Local(scope)
        }
    }
}

val Name.resolution: Resolution get() = resolve(this, id)

/** Whether a module contains `from m import *` (its globals are not known statically). */
val PyModule.hasStarImport: Boolean by syn {
    fun visit(n: Any): Boolean = childrenOf(n).any { c -> (c is ImportFrom && c.names.any { it.name == "*" }) || visit(c) }
    visit(this)
}

/** The JavaScript name of a Python variable. */
fun jsName(name: String, r: Resolution): String = when (r) {
    is Resolution.Local -> "v_$name"
    is Resolution.ClassLocal -> "c_$name"
    Resolution.Global -> "m_$name"
    Resolution.Builtin -> "\$b.$name"
    Resolution.Undefined -> "\$b.\$undefined(\"$name\")"
}

/** The function a node is in (not crossing classes), or `null` at module level. [FunctionDef]s and [Lambda]s. */
val PyNode.enclosingFunction: ScopeNode? get() {
    var s = scope
    while (s !is PyModule) {
        if (s is FunctionDef || s is Lambda) return s
        s = s.scope
    }
    return null
}

/** The innermost loop around a node in the same function (only the bodies are inside a loop). */
val PyNode.enclosingLoop: LoopStmt? by inh<LoopStmt?>()
    .on<PyModule> { null }.on<FunctionDef> { null }.on<ClassDef> { null }.on<Lambda> { null }
    .on<LoopStmt> { child -> if (child in body) this else enclosingLoop }
