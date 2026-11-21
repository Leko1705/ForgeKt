package com.leko.forge.python

import com.leko.forge.childrenOf
import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.python.PythonGrammar.expr
import com.leko.forge.python.PythonGrammar.exprList
import com.leko.forge.python.PythonGrammar.name
import com.leko.forge.python.PythonGrammar.simpleStmt
import com.leko.forge.python.PythonGrammar.stmt
import com.leko.forge.python.PythonGrammar.suite

/** `def`, decorators, `return`, `yield`, and the scopes of functions. */
object CoreFunctions : PythonFeature("functions") {

    /** Parameters with annotations: `a: int = 1, /, b, *args, c, **kwargs`. */
    private val parameters by rule {
        +"("
        val ps = with(CoreExpressions) { parameterList(annotations = true) }
        +")"
        ps
    }
    private val functionDef: com.leko.forge.Rule<FunctionDef> by rule {
        val decorators = decorators()
        val async = optional { +"async" } != null
        if (async) with(CoreAsync) { requireEnabled() }
        +"def"; val n = name(); val ps = parameters(); val returns = optional { +"->"; expr() }
        FunctionDef(n, ps, suite(), decorators, returns, async)
    }
    val decorators: com.leko.forge.Rule<List<PyExpr>> by rule { many { +"@"; val d = expr(); +PythonGrammar.NEWLINE; d } }

    init {
        stmt.on { listOf(functionDef()) }
        simpleStmt.on { +"return"; Return(optional(exprList)) }
    }

    /** Whether a function is a generator: it contains `yield` (not in nested functions). */
    val FunctionDef.isGenerator: Boolean get() = containsYield(this)

    private fun containsYield(n: Any): Boolean = childrenOf(n).any { c ->
        c is Yield || c is YieldFrom || (c !is FunctionDef && c !is Lambda && c !is ClassDef && c !is Comprehension && containsYield(c))
    }

    /**
     * The JavaScript signature: parameter names (positional-only ones first), defaults (evaluated
     * now), *args, keyword-only parameters with their defaults, and **kwargs.
     */
    fun signature(params: List<Param>): String {
        val positional = params.filter { it.kind == ParamKind.POSONLY || it.kind == ParamKind.NORMAL }
        val posonly = params.count { it.kind == ParamKind.POSONLY }
        val kwonly = params.filter { it.kind == ParamKind.KWONLY }
        val varargs = params.firstOrNull { it.kind == ParamKind.VARARGS }?.let { quote(it.name) } ?: "null"
        val kwargs = params.firstOrNull { it.kind == ParamKind.KWARGS }?.let { quote(it.name) } ?: "null"
        val kwdefaults = kwonly.filter { it.default != null }.joinToString { "${quote(it.name)}: ${it.default!!.js}" }
        return "{ params: [${positional.joinToString { quote(it.name) }}], defaults: [${positional.mapNotNull { it.default?.js }.joinToString()}], " +
            "posonly: $posonly, varargs: $varargs, kwonly: [${kwonly.joinToString { quote(it.name) }}], kwdefaults: { $kwdefaults }, kwargs: $kwargs }"
    }

    /** JavaScript parameters, in the order of the signature: positional, *args, keyword-only, **kwargs. */
    fun jsParams(params: List<Param>): String =
        listOf(listOf(ParamKind.POSONLY, ParamKind.NORMAL), listOf(ParamKind.VARARGS), listOf(ParamKind.KWONLY), listOf(ParamKind.KWARGS))
            .flatMap { kinds -> params.filter { it.kind in kinds } }.joinToString { "v_${it.name}" }

    /** Applies decorators (the last one first) to the JavaScript value [value]. */
    fun decorate(decorators: List<PyExpr>, value: String): String = decorators.foldRight(value) { d, v -> "\$call(${d.js}, [$v])" }

    // ---- binding and checks ------------------------------------------------------------------

    private val FunctionDef.boundNames: List<String> by syn { listOf(name) }

    init {
        problems
            .on<FunctionDef> { p -> p + paramProblems(this, params) }
            .on<Lambda> { p -> p + paramProblems(this, params) }
            .on<Return> { p -> if (enclosingFunction !is FunctionDef) p + problem("SyntaxError: 'return' outside function") else p }
            .on<Yield> { p -> if (enclosingFunction == null) p + problem("SyntaxError: 'yield' outside function") else p }
            .on<YieldFrom> { p -> if (enclosingFunction == null) p + problem("SyntaxError: 'yield' outside function") else p }
    }

    private fun paramProblems(node: PyNode, params: List<Param>): List<PyProblem> {
        val result = ArrayList<PyProblem>()
        val names = params.map { it.name }
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { result += node.problem("SyntaxError: duplicate argument '$it' in function definition") }
        val positional = params.filter { it.kind == ParamKind.POSONLY || it.kind == ParamKind.NORMAL }
        val firstDefault = positional.indexOfFirst { it.default != null }
        if (firstDefault >= 0 && positional.drop(firstDefault).any { it.default == null })
            result += node.problem("SyntaxError: parameter without a default follows parameter with a default")
        if (params.count { it.kind == ParamKind.VARARGS } > 1) result += node.problem("SyntaxError: * argument may appear only once")
        if (params.dropLast(1).any { it.kind == ParamKind.KWARGS })
            result += node.problem("SyntaxError: arguments cannot follow var-keyword argument")
        else if (params.zipWithNext().any { (a, b) -> b.kind < a.kind && b.kind != ParamKind.KWONLY })
            result += node.problem("SyntaxError: invalid parameter order")
        return result
    }

    // ---- JavaScript --------------------------------------------------------------------------

    private val FunctionDef.js: String by syn {
        if (isAsync) jsUnsupported(this, "async def")
        val locals = declarations(this, "v_", params.map { it.name }.toSet())
        val keyword = if (isGenerator) "function*" else "function"
        val body = (if (locals.isEmpty()) "" else "$locals\n") + block(body) + "\nreturn null;"
        val f = "\$def(${quote(name)}, $keyword (${jsParams(params)}) {\n${indent(body)}\n}, ${signature(params)})"
        "${jsName(name, resolve(this, name))} = ${decorate(decorators, f)};"
    }
    private val Return.js: String by syn { "return ${value?.js ?: "null"};" }
    private val Yield.js: String by syn {
        if (enclosingFunction is Lambda) jsUnsupported(this, "yield in a lambda")
        "(yield ${value?.js ?: "null"})"
    }
    private val YieldFrom.js: String by syn { "(yield* \$iter(${value.js}))" }

    // ---- Python ------------------------------------------------------------------------------

    /** `@decorator` lines. */
    fun pyDecorators(decorators: List<PyExpr>): String = decorators.joinToString("") { "@${it.py}\n" }

    private val FunctionDef.py: String by syn {
        val returns = returns?.let { " -> ${it.py}" } ?: ""
        "${pyDecorators(decorators)}${if (isAsync) "async " else ""}def $name(${CoreExpressions.pyParams(params)})$returns:\n${pySuite(body)}"
    }
    private val Return.py: String by syn { if (value == null) "return" else "return ${pyTuple(value)}" }
    private val Yield.py: String by syn { if (value == null) "(yield)" else "(yield ${value.py})" }
    private val YieldFrom.py: String by syn { "(yield from ${value.py})" }
}

/** `class`, with bases, decorators and methods. */
object CoreClasses : PythonFeature("classes") {
    init {
        stmt.on { listOf(classDef()) }
    }

    private val classDef: com.leko.forge.Rule<ClassDef> by rule {
        val decorators = CoreFunctions.decorators()
        +"class"; val n = name()
        val arguments = optional {
            +"("
            val items = sepBy({ oneOf<Any>({ +"**"; Keyword("**", expr()) }, { +"*"; Starred(expr()) }, { val k = name(); +"="; Keyword(k, expr()) }, { expr() }) }, ",")
            optional { +"," }
            +")"
            items
        }.orEmpty()
        ClassDef(n, arguments.filterIsInstance<PyExpr>(), suite(), decorators, arguments.filterIsInstance<Keyword>())
    }

    private val ClassDef.boundNames: List<String> by syn { listOf(name) }

    private val ClassDef.js: String by syn {
        if (keywords.isNotEmpty()) jsUnsupported(this, "class keywords (metaclass=...)")
        if (bases.any { it is Starred }) jsUnsupported(this, "*bases")
        val names = info.locals
        val locals = if (names.isEmpty()) "" else "let ${names.joinToString { "c_$it" }};\n"
        val namespace = "return { ${names.joinToString { "${quote(it)}: c_$it" }} };"
        val cls = "\$class(${quote(name)}, [${bases.joinToString { it.js }}], function () {\n${indent(locals + block(body) + "\n" + namespace)}\n})"
        "${jsName(name, resolve(this, name))} = ${CoreFunctions.decorate(decorators, cls)};"
    }

    private val ClassDef.py: String by syn {
        val arguments = CoreExpressions.pyArguments(bases, keywords)
        "${CoreFunctions.pyDecorators(decorators)}class $name${if (arguments.isEmpty()) "" else "($arguments)"}:\n${pySuite(body)}"
    }
}
