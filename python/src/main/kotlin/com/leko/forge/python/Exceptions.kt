package com.leko.forge.python

import com.leko.forge.parentOf
import com.leko.forge.syn
import com.leko.forge.python.PythonGrammar.expr
import com.leko.forge.python.PythonGrammar.name
import com.leko.forge.python.PythonGrammar.simpleStmt
import com.leko.forge.python.PythonGrammar.stmt
import com.leko.forge.python.PythonGrammar.suite

/** `try`/`except`/`else`/`finally`, `raise`, `assert`. */
object CoreExceptions : PythonFeature("exceptions") {
    init {
        stmt.on { listOf(tryStmt()) }
        simpleStmt
            .on { +"raise"; val e = optional(expr); Raise(e, if (e != null) optional { +"from"; expr() } else null) }
            .on { +"assert"; val t = expr(); Assert(t, optional { +","; expr() }) }
        problems.on<Try> { p ->
            val bare = handlers.dropLast(1).firstOrNull { it.type == null }
            if (bare != null) return@on p + bare.problem("SyntaxError: default 'except:' must be last")
            if (handlers.map { it.isGroup }.distinct().size > 1) return@on p + problem("SyntaxError: cannot have both 'except' and 'except*' on the same 'try'")
            p
        }
    }

    private val handler: com.leko.forge.Rule<ExceptHandler> by com.leko.forge.rule {
        +"except"
        val group = optional { +"*" } != null
        val t = if (group) expr() else optional(expr)
        val n = optional { +"as"; name() }
        ExceptHandler(t, n, suite(), group)
    }
    private val tryStmt: com.leko.forge.Rule<Try> by com.leko.forge.rule {
        +"try"; val body = suite()
        val handlers = many(handler)
        val orElse = optional { +"else"; suite() }.orEmpty()
        val fin = optional { +"finally"; suite() }.orEmpty()
        if (handlers.isEmpty() && fin.isEmpty()) fail("except or finally")
        Try(body, handlers, orElse, fin)
    }

    private val ExceptHandler.boundNames: List<String> by syn { listOfNotNull(name) }

    /** The JavaScript variable holding the exception being handled. */
    private val handlerVariables = java.util.IdentityHashMap<Try, String>()

    private val Try.js: String by syn {
        if (handlers.any { it.isGroup }) jsUnsupported(this, "except*")
        val e = temp("e")
        handlerVariables[this] = e
        // else runs if the body completed: no exception (and no return or break)
        val ok = if (orElse.isEmpty()) null else temp("ok")
        var code = "try {\n${indent(block(body) + (ok?.let { "\n$it = true;" } ?: ""))}\n}"
        if (handlers.isNotEmpty()) {
            val dispatch = handlers.foldRight("throw $e;") { h, rest ->
                val bind = h.name?.let { "${jsName(it, resolve(h, it))} = $e;\n" } ?: ""
                val handler = bind + block(h.body)
                if (h.type == null) handler else "if (\$isinstance($e, ${h.type.js})) {\n${indent(handler)}\n} else {\n${indent(rest)}\n}"
            }
            code += " catch ($e) {\n  $e = \$wrap($e);\n${indent(dispatch)}\n}"
        }
        if (ok != null) code = "{\n  let $ok = false;\n${indent(code)}\n  if ($ok) {\n${indent(indent(block(orElse)))}\n  }\n}"
        if (finalBody.isNotEmpty()) code = "try {\n${indent(code)}\n} finally {\n${indent(block(finalBody))}\n}"
        code
    }
    private val Raise.js: String by syn {
        when {
            exception == null -> {
                // re-raise: the exception of the handler we are in
                val tryStmt = enclosingHandler(this)
                if (tryStmt == null) "throw \$error(\"RuntimeError\", \"No active exception to reraise\");" else "throw ${handlerVariables.getValue(tryStmt)};"
            }
            cause != null -> { val x = temp("x"); "{ const $x = \$exception(${exception.js}); \$setattr($x, \"__cause__\", \$exception(${cause.js})); throw $x; }" }
            else -> "throw \$exception(${exception.js});"
        }
    }
    private val Assert.js: String by syn {
        "if (!\$bool(${test.js})) throw \$error(\"AssertionError\"${message?.let { ", ${it.js}" } ?: ""});"
    }

    // ---- Python ------------------------------------------------------------------------------

    private val Try.py: String by syn {
        var code = "try:\n${pySuite(body)}"
        for (h in handlers) {
            val star = if (h.isGroup) "*" else ""
            val type = h.type?.let { " ${it.py}" } ?: ""
            code += "\nexcept$star$type${h.name?.let { " as $it" } ?: ""}:\n${pySuite(h.body)}"
        }
        if (orElse.isNotEmpty()) code += "\nelse:\n${pySuite(orElse)}"
        if (finalBody.isNotEmpty()) code += "\nfinally:\n${pySuite(finalBody)}"
        code
    }
    private val Raise.py: String by syn {
        when {
            exception == null -> "raise"
            cause == null -> "raise ${exception.py}"
            else -> "raise ${exception.py} from ${cause.py}"
        }
    }
    private val Assert.py: String by syn { "assert ${test.py}" + (message?.let { ", ${it.py}" } ?: "") }

    private fun enclosingHandler(n: PyNode): Try? {
        var c: Any? = parentOf(n)
        var child: Any? = n
        while (c != null && c !is FunctionDef && c !is ClassDef) {
            if (c is ExceptHandler) return parentOf(c) as Try
            child = c
            c = parentOf(c)
        }
        return null
    }
}

/** List, set and dict comprehensions and generator expressions. */
object CoreComprehensions : PythonFeature("comprehensions") {
    private val generators by com.leko.forge.rule {
        some {
            val async = optional { +"async" } != null
            if (async) with(CoreAsync) { requireEnabled() }
            +"for"; val t = CoreStatementsTargetsAccess.targets(); +"in"
            val it = expr()
            CompFor(t, it, many { +"if"; expr() }, async)
        }
    }

    init {
        expr
            .on { +"["; val e = expr(); val g = generators(); +"]"; Comprehension(ComprehensionKind.LIST, e, null, g) }
            .on { +"{"; val k = expr(); +":"; val v = expr(); val g = generators(); +"}"; Comprehension(ComprehensionKind.DICT, k, v, g) }
            .on { +"{"; val e = expr(); val g = generators(); +"}"; Comprehension(ComprehensionKind.SET, e, null, g) }
            .on { +"("; val e = expr(); val g = generators(); +")"; Comprehension(ComprehensionKind.GENERATOR, e, null, g) }
            // f(x for x in xs): a generator expression as the only argument
            .postfix(16) { f -> +"("; val e = expr(); val g = generators(); +")"; Call(f, listOf(Comprehension(ComprehensionKind.GENERATOR, e, null, g)), emptyList()) }
    }

    private val CompFor.boundNames: List<String> by syn { targetNames(target) }

    private val Comprehension.js: String by syn {
        if (generators.any { it.isAsync }) jsUnsupported(this, "async comprehensions")
        val r = temp("r")
        val add = when (kind) {
            ComprehensionKind.LIST -> "$r.push(${element.js});"
            ComprehensionKind.SET -> "$r.add(${element.js});"
            ComprehensionKind.DICT -> "$r.set(${element.js}, ${value!!.js});"
            ComprehensionKind.GENERATOR -> "yield ${element.js};"
        }
        val loops = generators.foldRight(add) { g, inner ->
            val v = temp("x")
            val conditions = g.conditions.joinToString("") { "if (!\$bool(${it.js})) continue;\n" }
            "for (const $v of \$iter(${g.iter.js})) {\n${indent(assignTo(g.target, v) + "\n" + conditions + inner)}\n}"
        }
        val locals = declarations(this, "v_")
        val init = when (kind) { ComprehensionKind.LIST -> "const $r = [];"; ComprehensionKind.SET -> "const $r = new PySet();"; ComprehensionKind.DICT -> "const $r = new Dict();"; else -> "" }
        if (kind == ComprehensionKind.GENERATOR) "(function* () { $locals\n${indent(loops)}\n})()"
        else "(() => { $locals $init\n${indent(loops)}\nreturn $r; })()"
    }

    // ---- Python ------------------------------------------------------------------------------

    private val Comprehension.py: String by syn {
        val loops = generators.joinToString(" ") { g ->
            (if (g.isAsync) "async " else "") + "for ${pyTuple(g.target)} in ${pyOperand(g.iter, PyPrecedence.OR)}" +
                g.conditions.joinToString("") { " if ${pyOperand(it, PyPrecedence.OR)}" }
        }
        when (kind) {
            ComprehensionKind.LIST -> "[${pyItem(element)} $loops]"
            ComprehensionKind.SET -> "{${pyItem(element)} $loops}"
            ComprehensionKind.DICT -> "{${pyItem(element)}: ${pyItem(value!!)} $loops}"
            ComprehensionKind.GENERATOR -> "(${pyItem(element)} $loops)"
        }
    }
}

internal object CoreStatementsTargetsAccess {
    val targets: com.leko.forge.Rule<PyExpr> by com.leko.forge.rule {
        val items = sepBy(CoreTargets.target, ",")
        val trailing = optional { +"," } != null
        if (items.size == 1 && !trailing) items.single() else TupleExpr(items)
    }
}
