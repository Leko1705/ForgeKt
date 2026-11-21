package com.leko.forge.python

import com.leko.forge.parentOf
import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.python.PythonGrammar.expr
import com.leko.forge.python.PythonGrammar.exprList
import com.leko.forge.python.PythonGrammar.name
import com.leko.forge.python.PythonGrammar.simpleLine
import com.leko.forge.python.PythonGrammar.simpleStmt
import com.leko.forge.python.PythonGrammar.stmt
import com.leko.forge.python.PythonGrammar.suite

/** Assignments, expression statements, if, while, for, break, continue, pass, del, global, nonlocal. */
object CoreStatements : PythonFeature("statements") {

    private val augOp by rule {
        oneOf({ +"+=" }, { +"-=" }, { +"*=" }, { +"/=" }, { +"//=" }, { +"%=" }, { +"**=" }, { +"<<=" }, { +">>=" }, { +"&=" }, { +"|=" }, { +"^=" })
    }
    private val elseSuite by rule { optional { +"else"; suite() }.orEmpty() }

    init {
        stmt.on { simpleLine() }
            .on { listOf(ifStmt()) }
            .on { listOf(whileStmt()) }
            .on { listOf(forStmt()) }
        simpleStmt
            .on { +"pass"; Pass() }
            .on { +"break"; Break() }
            .on { +"continue"; Continue() }
            .on { +"del"; Delete(sepBy(expr, ",").also { optional { +"," } }) }
            .on { +"global"; Global(sepBy(name, ",")) }
            .on { +"nonlocal"; Nonlocal(sepBy(name, ",")) }
            .on { val t = expr(); +":"; val a = expr(); AnnAssign(t, a, optional { +"="; exprList() }) }
            .on { val t = exprList(); val op = augOp(); AugAssign(t, op.dropLast(1), exprList()) }
            .on {
                val first = exprList()
                val rest = some { +"="; if (lookahead { +"=" }) fail("an expression"); oneOf({ +"yield"; yieldValue() }, exprList) }
                val all = listOf(first) + rest
                Assign(all.dropLast(1), all.last())
            }
            .on { ExprStmt(oneOf({ +"yield"; yieldValue() }, exprList)) }
    }

    private val ifStmt: com.leko.forge.Rule<PyStmt> by rule { +"if"; val t = expr(); val b = suite(); If(t, b, elifs()) }
    private val whileStmt: com.leko.forge.Rule<PyStmt> by rule { +"while"; val t = expr(); val b = suite(); While(t, b, elseSuite()) }
    private val forStmt: com.leko.forge.Rule<PyStmt> by rule {
        val async = optional { +"async" } != null
        if (async) with(CoreAsync) { requireEnabled() }
        +"for"; val target = targetList(); +"in"; val it = exprList(); val b = suite()
        For(target, it, b, elseSuite(), async)
    }

    private val targetList: com.leko.forge.Rule<PyExpr> by rule {
        val t = CoreTargets.list()
        if (t is TupleExpr && t.elements.isEmpty()) fail("a target")
        t
    }

    private fun com.leko.forge.ParseScope.yieldValue(): PyExpr = oneOf({ +"from"; YieldFrom(expr()) }, { Yield(optional(exprList)) })

    private val elifs: com.leko.forge.Rule<List<PyStmt>> by rule {
        oneOf(
            { +"elif"; val t = expr(); val b = suite(); listOf(If(t, b, elifs())) },
            { +"else"; suite() },
            { emptyList() },
        )
    }

    // ---- binding -----------------------------------------------------------------------------

    private val Assign.boundNames: List<String> by syn { targets.flatMap { targetNames(it) } }
    private val AugAssign.boundNames: List<String> by syn { targetNames(target) }
    private val AnnAssign.boundNames: List<String> by syn { targetNames(target) }
    private val For.boundNames: List<String> by syn { targetNames(target) }
    private val Delete.boundNames: List<String> by syn { targets.flatMap { targetNames(it) } }

    // ---- checks ------------------------------------------------------------------------------

    private fun PyNode.target(t: PyExpr, what: String): List<PyProblem> = when (t) {
        is Name, is Attribute, is Subscript -> emptyList()
        is TupleExpr -> t.elements.flatMap { target(it, what) } + if (t.elements.count { it is Starred } > 1) problem("SyntaxError: multiple starred expressions in assignment") else emptyList()
        is ListExpr -> t.elements.flatMap { target(it, what) }
        is Starred -> target(t.value, what)
        else -> problem("SyntaxError: cannot $what ${describe(t)}")
    }

    private fun describe(e: PyExpr) = when (e) {
        is Call -> "function call"; is Constant -> "literal"; is BinOp, is UnaryOp, is BoolOp -> "expression"
        is Compare -> "comparison"; is Lambda -> "lambda"; is IfExp -> "conditional expression"; else -> "expression"
    }

    init {
        problems
            .on<Assign> { p -> p + targets.flatMap { target(it, "assign to") } }
            .on<AugAssign> { p -> if (target !is Name && target !is Attribute && target !is Subscript) p + problem("SyntaxError: '${describe(target)}' is an illegal expression for augmented assignment") else p }
            .on<For> { p -> p + target(target, "assign to") }
            .on<Delete> { p -> p + targets.flatMap { target(it, "delete") } }
            .on<Break> { p -> if (enclosingLoop == null) p + problem("SyntaxError: 'break' outside loop") else p }
            .on<Continue> { p -> if (enclosingLoop == null) p + problem("SyntaxError: 'continue' not properly in loop") else p }
            .on<Nonlocal> { p ->
                val f = enclosingFunction
                if (f == null) return@on p + problem("SyntaxError: nonlocal declaration not allowed at module level")
                p + names.filter { n -> resolveEnclosing(f, n) == null }.flatMap { problem("SyntaxError: no binding for nonlocal '$it' found") }
            }
    }

    /** The enclosing function scope binding [name], for `nonlocal`. */
    private fun resolveEnclosing(f: ScopeNode, name: String): ScopeNode? {
        var s: ScopeNode = f
        while (true) {
            s = s.scope
            if (s is PyModule) return null
            if (s !is ClassDef && name in s.info.locals) return s
        }
    }

    // ---- JavaScript --------------------------------------------------------------------------

    private val ExprStmt.js: String by syn { "${value.js};" }
    private val Pass.js: String by syn { "/* pass */" }
    private val Global.js: String by syn { "/* global ${names.joinToString()} */" }
    private val Nonlocal.js: String by syn { "/* nonlocal ${names.joinToString()} */" }
    private val Assign.js: String by syn {
        if (targets.size == 1 && targets[0] is Name) assignTo(targets[0], value.js) else {
            val t = temp("v")
            "{ const $t = ${value.js};\n" + indent(targets.joinToString("\n") { assignTo(it, t) }) + "\n}"
        }
    }
    private val AnnAssign.js: String by syn { if (value == null) "/* ${annotation.js} */" else assignTo(target, value.js) }
    private val AugAssign.js: String by syn {
        val operator = if (op == "+") "\$iadd" else mapOf("-" to "\$sub", "*" to "\$mul", "/" to "\$truediv", "//" to "\$floordiv", "%" to "\$mod",
            "**" to "\$pow", "<<" to "\$lshift", ">>" to "\$rshift", "&" to "\$bitand", "|" to "\$bitor", "^" to "\$bitxor").getValue(op)
        when (val t = target) {
            is Name -> { val n = jsName(t.id, t.resolution); "$n = $operator($n, ${value.js});" }
            is Attribute -> { val o = temp("o"); "{ const $o = ${t.value.js}; \$setattr($o, ${quote(t.attr)}, $operator(\$getattr($o, ${quote(t.attr)}), ${value.js})); }" }
            is Subscript -> { val o = temp("o"); val k = temp("k"); "{ const $o = ${t.value.js}; const $k = ${t.index.js}; \$setitem($o, $k, $operator(\$getitem($o, $k), ${value.js})); }" }
            else -> error("not assignable")
        }
    }
    private val Delete.js: String by syn {
        targets.joinToString("\n") { t ->
            when (t) {
                is Name -> "${jsName(t.id, t.resolution)} = undefined;"
                is Subscript -> "\$delitem(${t.value.js}, ${t.index.js});"
                is Attribute -> "\$delattr(${t.value.js}, ${quote(t.attr)});"
                else -> ""
            }
        }
    }
    private val If.js: String by syn {
        "if (\$bool(${test.js})) {\n${indent(block(body))}\n}" + if (orElse.isEmpty()) "" else " else {\n${indent(block(orElse))}\n}"
    }

    /** The flag set by `break` in loops with `else`. */
    private val loopFlags = java.util.IdentityHashMap<LoopStmt, String>()
    private fun flag(loop: LoopStmt): String = loopFlags.getOrPut(loop) { temp("broke") }

    private val Break.js: String by syn { val l = enclosingLoop!!; if (l.orElse.isEmpty()) "break;" else "{ ${flag(l)} = true; break; }" }
    private val Continue.js: String by syn { "continue;" }

    private fun loop(l: LoopStmt, head: String, prologue: String = ""): String {
        val body = "$head {\n${indent(prologue + block(l.body))}\n}"
        if (l.orElse.isEmpty()) return body
        val f = flag(l)
        return "{ let $f = false;\n${indent(body)}\n  if (!$f) {\n${indent(indent(block(l.orElse)))}\n  }\n}"
    }
    private val While.js: String by syn { loop(this, "while (\$bool(${test.js}))") }
    private val For.js: String by syn {
        if (isAsync) jsUnsupported(this, "async for")
        val v = temp("x")
        loop(this, "for (const $v of \$iter(${iter.js}))", assignTo(target, v) + "\n")
    }

    // ---- Python ------------------------------------------------------------------------------

    private val ExprStmt.py: String by syn { pyTuple(value) }
    private val Pass.py: String by syn { "pass" }
    private val Break.py: String by syn { "break" }
    private val Continue.py: String by syn { "continue" }
    private val Global.py: String by syn { "global ${names.joinToString(", ")}" }
    private val Nonlocal.py: String by syn { "nonlocal ${names.joinToString(", ")}" }
    private val Assign.py: String by syn { (targets + value).joinToString(" = ") { pyTuple(it) } }
    private val AugAssign.py: String by syn { "${target.py} $op= ${pyTuple(value)}" }
    private val AnnAssign.py: String by syn { "${target.py}: ${annotation.py}" + (value?.let { " = ${it.py}" } ?: "") }
    private val Delete.py: String by syn { "del " + targets.joinToString(", ") { pyItem(it) } }
    private val If.py: String by syn {
        var code = "if ${test.py}:\n${pySuite(body)}"
        var rest = orElse
        while (rest.size == 1 && rest[0] is If) {
            val elif = rest[0] as If
            code += "\nelif ${elif.test.py}:\n${pySuite(elif.body)}"
            rest = elif.orElse
        }
        if (rest.isNotEmpty()) code += "\nelse:\n${pySuite(rest)}"
        code
    }
    private fun loopElse(l: LoopStmt): String = if (l.orElse.isEmpty()) "" else "\nelse:\n${pySuite(l.orElse)}"
    private val While.py: String by syn { "while ${test.py}:\n${pySuite(body)}" + loopElse(this) }
    private val For.py: String by syn { "${if (isAsync) "async " else ""}for ${pyTuple(target)} in ${pyTuple(iter)}:\n${pySuite(body)}" + loopElse(this) }

}

/** Assignment targets: names, attributes, subscripts, and parenthesized or bracketed lists of them. */
object CoreTargets {
    /** A target; unlike an expression it stops before `in` (`for x in ...`). */
    val target: com.leko.forge.Rule<PyExpr> by rule {
        var e: PyExpr = oneOf(
            { +"("; val t = list(); +")"; t },
            { +"["; val t = list(); +"]"; if (t is TupleExpr) ListExpr(t.elements) else ListExpr(listOf(t)) },
            { Name(name()) },
        )
        many {
            oneOf(
                { +"."; e = Attribute(e, name()) },
                { e = Subscript(e, CoreExpressions.subscript()) },
            )
        }
        e
    }

    val list: com.leko.forge.Rule<PyExpr> by rule {
        val items = sepBy({ oneOf({ +"*"; Starred(target()) }, target) }, ",")
        val trailing = optional { +"," } != null
        if (items.size == 1 && !trailing) items.single() else TupleExpr(items)
    }
}

