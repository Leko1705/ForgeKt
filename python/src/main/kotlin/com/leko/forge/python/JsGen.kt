package com.leko.forge.python

import com.leko.forge.syn

/** The JavaScript code of an expression. Every feature implements it for its expressions. */
val PyExpr.js: String by syn()

/** The JavaScript code of a statement (one or more lines). Every feature implements it for its statements. */
val PyStmt.js: String by syn()

/**
 * A construct the JavaScript backend does not implement (the Python target supports everything).
 * Thrown while generating JavaScript; [PyCompilation.toJavaScript] reports it with its position.
 */
class JsUnsupported(val node: PyNode?, val what: String) : RuntimeException("$what is not supported by the JavaScript backend")

/** Stops JavaScript generation: [what] (at [node]) is not supported by the JavaScript backend. */
fun jsUnsupported(node: PyNode?, what: String): Nothing = throw JsUnsupported(node, what)

/** Runtime support: the JavaScript library generated code calls, and the Python builtins. */
object PythonRuntime {
    private val parts = LinkedHashMap<String, String>()
    private val builtinNames = LinkedHashSet<String>()

    /** The core runtime (`runtime.js`) and the builtins it defines. */
    init {
        val core = PythonRuntime::class.java.getResource("/com/leko/forge/python/runtime.js")!!.readText()
        parts["core"] = core
        Regex("""\b(?:builtin|nb|nativeClass|exceptionClass)\("([A-Za-z_][A-Za-z0-9_]*)"""").findAll(core).forEach { builtinNames += it.groupValues[1] }
    }

    /** Adds JavaScript to the runtime (once per [name]); it can define builtins with `builtin("name", value)`. */
    fun add(name: String, code: String, builtins: List<String> = emptyList()) {
        if (name in parts) return
        parts[name] = code
        builtinNames += builtins
    }

    val builtins: Set<String> get() = builtinNames
    val code: String get() = parts.values.joinToString("\n")
}

/** Indents every line of [code] by two spaces. */
fun indent(code: String): String = code.lines().joinToString("\n") { if (it.isEmpty()) it else "  $it" }

/** The code of a block of statements. */
fun block(stmts: List<PyStmt>): String = stmts.joinToString("\n") { it.js }.ifEmpty { "/* pass */" }

private var temps = 0

/** A fresh JavaScript temporary name. */
fun temp(prefix: String = "t"): String = "\$$prefix${temps++}"

/** `let v_a, v_b;` for the local variables of a scope (except [except], e.g. parameters). */
fun declarations(scope: ScopeNode, prefix: String, except: Set<String> = emptySet()): String {
    val names = scope.info.locals - except
    return if (names.isEmpty()) "" else "let ${names.joinToString { "$prefix$it" }};"
}

/** Assigns the JavaScript value [value] (an expression without side effects, e.g. a temporary) to a target. */
fun assignTo(target: PyExpr, value: String): String = when (target) {
    is Name -> "${jsName(target.id, target.resolution)} = $value;"
    is Attribute -> "\$setattr(${target.value.js}, ${quote(target.attr)}, $value);"
    is Subscript -> "\$setitem(${target.value.js}, ${target.index.js}, $value);"
    is TupleExpr, is ListExpr -> {
        val elements = if (target is TupleExpr) target.elements else (target as ListExpr).elements
        val star = elements.indexOfFirst { it is Starred }
        val t = temp("u")
        "{ const $t = \$unpack($value, ${elements.size}, $star);\n" +
            indent(elements.mapIndexed { i, e -> assignTo(if (e is Starred) e.value else e, "$t[$i]") }.joinToString("\n")) + "\n}"
    }
    is Starred -> assignTo(target.value, value)
    else -> error("cannot assign to $target")
}

/** A JavaScript string literal. */
fun quote(s: String): String = buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c.code < 0x20 || c.code in 0x7f..0x9f || c == ' ' || c == ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> append(c)
    }
    append('"')
}
