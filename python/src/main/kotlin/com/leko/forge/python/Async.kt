package com.leko.forge.python

import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.python.PythonGrammar.expr
import com.leko.forge.python.PythonGrammar.stmt
import com.leko.forge.python.PythonGrammar.suite

/**
 * Coroutines: `async def`, `await`, `async for`, `async with` and asynchronous comprehensions. The
 * `async` forms are parsed by the features of the statements (they call `requireEnabled()` of this
 * feature); this feature adds `await` and the checks. Not supported by the JavaScript backend.
 */
object CoreAsync : PythonFeature("coroutines") {
    init {
        expr.prefix(PyPrecedence.ATOM) { +"await"; requireEnabled(); Await(operand()) }
    }

    /** The function a node is in if it is an `async def` (lambdas and plain functions are not). */
    private val PyNode.inAsyncFunction: Boolean get() = (enclosingFunction as? FunctionDef)?.isAsync == true

    init {
        problems
            .on<Await> { p ->
                when {
                    enclosingFunction == null -> p + problem("SyntaxError: 'await' outside function")
                    !inAsyncFunction -> p + problem("SyntaxError: 'await' outside async function")
                    else -> p
                }
            }
            .on<For> { p -> if (isAsync && !inAsyncFunction) p + problem("SyntaxError: 'async for' outside async function") else p }
            .on<With> { p -> if (isAsync && !inAsyncFunction) p + problem("SyntaxError: 'async with' outside async function") else p }
            .on<Comprehension> { p ->
                if (kind != ComprehensionKind.GENERATOR && generators.any { it.isAsync } && !inAsyncFunction)
                    p + problem("SyntaxError: asynchronous comprehension outside of an asynchronous function") else p
            }
            .on<YieldFrom> { p -> if (inAsyncFunction) p + problem("SyntaxError: 'yield from' inside async function") else p }
            .on<Return> { p ->
                val f = enclosingFunction as? FunctionDef
                if (value != null && f != null && f.isAsync && with(CoreFunctions) { f.isGenerator }) p + problem("SyntaxError: 'return' with value in async generator") else p
            }
    }

    private val Await.pyPrecedence: Int by syn { PyPrecedence.AWAIT }
    private val Await.py: String by syn { "await ${pyOperand(value, PyPrecedence.ATOM)}" }
    private val Await.js: String by syn { jsUnsupported(this, "await") }
}

/** `with a as x, b:` and the parenthesized form `with (a as x, b):` (also `async with`). */
object CoreWith : PythonFeature("with statements") {
    private val item by rule { val e = expr(); WithItem(e, optional { +"as"; CoreTargets.target() }) }
    private val items by rule {
        oneOf(
            { +"("; val i = sepBy(item, ","); optional { +"," }; +")"; if (!lookahead { +":" }) fail("':'"); i },
            { sepBy(item, ",") },
        ).also { if (it.isEmpty()) fail("a context manager") }
    }
    private val withStmt: com.leko.forge.Rule<PyStmt> by rule {
        val async = optional { +"async" } != null
        if (async) with(CoreAsync) { requireEnabled() }
        +"with"
        val i = items()
        With(i, suite(), async)
    }

    init {
        stmt.on { listOf(withStmt()) }
        problems.on<WithItem> { p ->
            when (val t = target) {
                null, is Name, is Attribute, is Subscript, is TupleExpr, is ListExpr -> p
                else -> p + problem("SyntaxError: cannot assign to ${t::class.simpleName}")
            }
        }
    }

    private val WithItem.boundNames: List<String> by syn { target?.let { targetNames(it) }.orEmpty() }

    private val With.py: String by syn {
        val list = items.joinToString(", ") { i -> pyItem(i.context) + (i.target?.let { " as ${it.py}" } ?: "") }
        "${if (isAsync) "async " else ""}with $list:\n${pySuite(body)}"
    }

    /** Every item is a `try` around the rest: `__exit__` gets the exception, and can suppress it. */
    private val With.js: String by syn {
        if (isAsync) jsUnsupported(this, "async with")
        items.foldRight(block(body)) { i, inner ->
            val m = temp("m"); val exit = temp("exit"); val v = temp("v"); val e = temp("e"); val ok = temp("ok")
            val bind = i.target?.let { assignTo(it, v) + "\n" } ?: ""
            "{\n" + indent(
                "const $m = ${i.context.js};\nconst $exit = \$getattr($m, \"__exit__\");\nconst $v = \$callMethod($m, \"__enter__\", []);\nlet $ok = true;\n" +
                    "try {\n${indent(bind + inner)}\n} catch ($e) {\n  $ok = false;\n  $e = \$wrap($e);\n  if (!\$bool(\$call($exit, [\$type($e), $e, null]))) throw $e;\n" +
                    "} finally {\n  if ($ok) \$call($exit, [null, null, null]);\n}",
            ) + "\n}"
        }
    }
}
