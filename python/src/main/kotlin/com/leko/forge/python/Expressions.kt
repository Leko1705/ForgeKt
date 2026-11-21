package com.leko.forge.python

import com.leko.forge.ParseScope
import com.leko.forge.parentOf
import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.token
import com.leko.forge.python.PythonGrammar.expr
import com.leko.forge.python.PythonGrammar.name

/** Literals, names, operators, calls, attributes, subscripts, displays, conditional expressions, lambdas. */
object CoreExpressions : PythonFeature("expressions") {

    val number: com.leko.forge.Rule<String> by token("0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|([0-9][0-9_]*)?\\.[0-9][0-9_]*([eE][+-]?[0-9]+)?|[0-9][0-9_]*\\.([eE][+-]?[0-9]+)?|[0-9][0-9_]*[eE][+-]?[0-9]+|[0-9][0-9_]*")
    /** A string literal with an optional prefix (r, u, b); f-strings are a feature of their own. */
    val stringLiteral: com.leko.forge.Rule<String> by token("(?i:[rub]|br|rb)?(\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(\\\\[\\s\\S]|[^\"\\\\\\n])*\"|'(\\\\[\\s\\S]|[^'\\\\\\n])*')")

    /** Results of parentheses: `(a < b) < c` is not a chained comparison. */
    private val parenthesized = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    private val keywordArg by rule { val n = name(); +"="; if (lookahead { +"=" }) fail("an argument"); Keyword(n, expr()) }
    private val callArgs by rule {
        +"("
        val items = sepBy({ oneOf<Any>({ +"**"; Keyword("**", expr()) }, { +"*"; Starred(expr()) }, keywordArg, { expr() }) }, ",")
        optional { +"," }
        +")"
        items
    }
    private val sliceItem: com.leko.forge.Rule<PyExpr> by rule {
        oneOf({
            val lower = optional(expr); +":"; val upper = optional(expr)
            val step = optional { +":"; optional(expr) }
            Slice(lower, upper, step)
        }, expr)
    }
    /** `[index]` of a subscript: an expression, a slice, or a tuple of them. */
    val subscript: com.leko.forge.Rule<PyExpr> by rule {
        +"["
        val items = sepBy(sliceItem, ",")
        val trailing = optional { +"," } != null
        +"]"
        if (items.size == 1 && !trailing) items.single() else TupleExpr(items)
    }

    /** Lambda parameters (without annotations). */
    val params: com.leko.forge.Rule<List<Param>> by rule { parameterList(annotations = false) }

    /**
     * A parameter list: `a, /, b=1, *args, c, **kwargs` (also with a bare `*`). The kinds follow
     * from the separators `/` and `*`.
     */
    fun ParseScope.parameterList(annotations: Boolean): List<Param> {
        fun ParseScope.annotation(): PyExpr? = if (annotations) optional { +":"; expr() } else null
        val items = sepBy({
            oneOf<Any>(
                { +"**"; val n = name(); Param(n, null, ParamKind.KWARGS, annotation()) },
                { +"*"; val n = name(); Param(n, null, ParamKind.VARARGS, annotation()) },
                { +"*"; "*" },
                { +"/"; "/" },
                { val n = name(); val a = annotation(); Param(n, optional { +"="; expr() }, ParamKind.NORMAL, a) },
            )
        }, ",")
        optional { +"," }
        val slash = items.indexOf("/")
        if (slash == 0 || items.count { it == "/" } > 1) fail("a parameter before /")
        val star = items.indexOfFirst { it == "*" || (it is Param && it.kind == ParamKind.VARARGS) }
        if (slash >= 0 && star in 0 until slash) fail("/ before *")
        if (star >= 0 && items[star] == "*" && items.drop(star + 1).none { it is Param && it.kind == ParamKind.NORMAL }) fail("named arguments after bare *")
        return items.mapIndexedNotNull { i, item ->
            when {
                item !is Param -> null
                item.kind != ParamKind.NORMAL -> item
                i < slash -> item.copy(kind = ParamKind.POSONLY)
                star in 0 until i -> item.copy(kind = ParamKind.KWONLY)
                else -> item
            }
        }
    }

    private fun ParseScope.binary(op: String, l: PyExpr): PyExpr { +op; return BinOp(op, l, operand()) }

    private fun ParseScope.compare(op: String, l: PyExpr): PyExpr {
        when (op) {
            "not in" -> { +"not"; +"in" }
            "is not" -> { +"is"; +"not" }
            else -> { +op; if (op == "<" || op == ">") { if (lookahead { +"=" }) fail(op) } }
        }
        val r: PyExpr = operand()
        return if (l is Compare && l !in parenthesized) Compare(l.left, l.ops + op, l.comparators + r) else Compare(l, listOf(op), listOf(r))
    }

    init {
        expr
            .on { +"lambda"; val p = params(); +":"; Lambda(p, expr()) }
            .on { val n = name(); +":="; NamedExpr(Name(n), expr()) }
            .on { constant(number()) }
            .on {
                val parts = some(stringLiteral)
                val bytes = parts.map { isBytes(it) }
                if (bytes.distinct().size > 1) fail("only bytes or only strings")
                Constant(parts.joinToString("") { unescape(it) }, if (bytes[0]) ConstantKind.BYTES else ConstantKind.STR)
            }
            .on { +"True"; Constant(true, ConstantKind.BOOL) }
            .on { +"False"; Constant(false, ConstantKind.BOOL) }
            .on { +"None"; Constant(null, ConstantKind.NONE) }
            .on { +"..."; Constant(null, ConstantKind.ELLIPSIS) }
            .on { Name(name()) }
            .on { +"("; +")"; TupleExpr(emptyList()) }
            .on { +"("; val e = oneOf({ +"yield"; yieldExpr() }, PythonGrammar.exprList); +")"; parenthesized.add(e); e }
            .on { +"["; val items = sepBy({ oneOf({ +"*"; Starred(expr()) }, expr) }, ","); optional { +"," }; +"]"; ListExpr(items) }
            .on { +"{"; +"}"; DictExpr(emptyList(), emptyList()) }
            .on {
                +"{"
                val pairs = sepBy({ oneOf({ +"**"; DictUnpack() as PyExpr to expr() }, { val k = expr(); +":"; k to expr() }) }, ",")
                if (pairs.isEmpty()) fail("a dictionary entry")
                optional { +","}; +"}"
                DictExpr(pairs.map { it.first }, pairs.map { it.second })
            }
            .on { +"{"; val items = sepBy({ oneOf({ +"*"; Starred(expr()) }, expr) }, ","); if (items.isEmpty()) fail("an element"); optional { +"," }; +"}"; SetExpr(items) }
            // postfix: calls, attributes, subscripts
            .postfix(16) { f ->
                val items = callArgs()
                Call(f, items.filter { it is PyExpr }.map { it as PyExpr }, items.filterIsInstance<Keyword>())
            }
            .postfix(16) { e -> +"."; Attribute(e, name()) }
            .postfix(16) { e -> Subscript(e, subscript()) }
            // ** binds stronger than a unary operator on its left, weaker than one on its right
            .infixRight(15) { l -> binary("**", l) }
            .prefix(14) { +"-"; UnaryOp("-", operand()) }
            .prefix(14) { +"+"; UnaryOp("+", operand()) }
            .prefix(14) { +"~"; UnaryOp("~", operand()) }
            .infixLeft(13) { l -> binary("*", l) }.infixLeft(13) { l -> binary("//", l) }.infixLeft(13) { l -> binary("/", l) }
            .infixLeft(13) { l -> binary("%", l) }.infixLeft(13) { l -> binary("@", l) }
            .infixLeft(12) { l -> binary("+", l) }.infixLeft(12) { l -> binary("-", l) }
            .infixLeft(11) { l -> binary("<<", l) }.infixLeft(11) { l -> binary(">>", l) }
            .infixLeft(10) { l -> binary("&", l) }
            .infixLeft(9) { l -> binary("^", l) }
            .infixLeft(8) { l -> binary("|", l) }
            .infixLeft(7) { l -> compare("==", l) }.infixLeft(7) { l -> compare("!=", l) }
            .infixLeft(7) { l -> compare("<=", l) }.infixLeft(7) { l -> compare(">=", l) }
            .infixLeft(7) { l -> compare("<", l) }.infixLeft(7) { l -> compare(">", l) }
            .infixLeft(7) { l -> compare("not in", l) }.infixLeft(7) { l -> compare("in", l) }
            .infixLeft(7) { l -> compare("is not", l) }.infixLeft(7) { l -> compare("is", l) }
            .prefix(6) { +"not"; UnaryOp("not", operand()) }
            .infixLeft(5) { l -> +"and"; BoolOp("and", l, operand()) }
            .infixLeft(4) { l -> +"or"; BoolOp("or", l, operand()) }
            .infixRight(3) { body -> +"if"; val test = expr(); +"else"; IfExp(test, body, operand()) }
    }

    private fun ParseScope.yieldExpr(): PyExpr = oneOf({ +"from"; YieldFrom(expr()) }, { Yield(optional(PythonGrammar.exprList)) })

    private fun isBytes(literal: String): Boolean = literal.substring(0, literal.indexOfFirst { it == '"' || it == '\'' }).contains('b', ignoreCase = true)

    fun constant(text: String): Constant {
        val t = text.replace("_", "")
        return when {
            t.startsWith("0x") || t.startsWith("0X") -> Constant(java.math.BigInteger(t.drop(2), 16).toString(), ConstantKind.INT)
            t.startsWith("0b") || t.startsWith("0B") -> Constant(java.math.BigInteger(t.drop(2), 2).toString(), ConstantKind.INT)
            t.startsWith("0o") || t.startsWith("0O") -> Constant(java.math.BigInteger(t.drop(2), 8).toString(), ConstantKind.INT)
            t.contains('.') || t.contains('e') || t.contains('E') -> Constant(t.toDouble(), ConstantKind.FLOAT)
            else -> Constant(java.math.BigInteger(t).toString(), ConstantKind.INT)
        }
    }

    /** The value of a string literal (prefix and quotes removed, escapes resolved unless raw). */
    fun unescape(literal: String): String {
        val prefixEnd = literal.indexOfFirst { it == '"' || it == '\'' }
        val raw = literal.substring(0, prefixEnd).contains('r', ignoreCase = true)
        val q = if (literal.startsWith("\"\"\"", prefixEnd) || literal.startsWith("'''", prefixEnd)) 3 else 1
        val body = literal.substring(prefixEnd + q, literal.length - q)
        if (raw) return body
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i++]
            if (c != '\\' || i >= body.length) { out.append(c); continue }
            when (val e = body[i++]) {
                'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r'); '0' -> out.append('\u0000')
                'a' -> out.append('\u0007'); 'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'v' -> out.append('\u000b')
                '\n' -> {}
                'x' -> { out.append(body.substring(i, i + 2).toInt(16).toChar()); i += 2 }
                'u' -> { out.append(body.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                'U' -> { out.appendCodePoint(body.substring(i, i + 8).toInt(16)); i += 8 }
                '\\', '\'', '"' -> out.append(e)
                else -> out.append('\\').append(e)
            }
        }
        return out.toString()
    }

    // ---- checks ------------------------------------------------------------------------------

    init {
        problems
            .on<Name> { p -> if (resolution == Resolution.Undefined && !isStored(this)) p + problem("NameError: name '$id' is not defined") else p }
            .on<Call> { p ->
                val names = keywords.map { it.name }.filter { it != "**" }
                val duplicate = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                if (duplicate.isNotEmpty()) p + problem("SyntaxError: keyword argument repeated: ${duplicate.first()}") else p
            }
    }

    /** Whether a name is an assignment target (stored, not read). */
    fun isStored(n: Name): Boolean {
        var child: Any = n
        var parent = parentOf(n)
        while (parent is TupleExpr || parent is ListExpr || parent is Starred) { child = parent; parent = parentOf(parent) }
        return when (parent) {
            is Assign -> child in parent.targets
            is AugAssign -> false
            is AnnAssign -> parent.target === child
            is For -> parent.target === child
            is CompFor -> parent.target === child
            is Delete -> true
            is NamedExpr -> parent.target === child
            is WithItem -> parent.target === child
            else -> false
        }
    }

    // ---- JavaScript --------------------------------------------------------------------------

    private val binaryOps = mapOf("+" to "\$add", "-" to "\$sub", "*" to "\$mul", "/" to "\$truediv", "//" to "\$floordiv", "%" to "\$mod",
        "**" to "\$pow", "<<" to "\$lshift", ">>" to "\$rshift", "&" to "\$bitand", "|" to "\$bitor", "^" to "\$bitxor")
    private val compareOps = mapOf("==" to "\$eq", "!=" to "\$ne", "<" to "\$lt", "<=" to "\$le", ">" to "\$gt", ">=" to "\$ge", "in" to "\$in")

    /** One comparison of a chain. */
    private fun compare(op: String, a: String, b: String): String = when (op) {
        "is" -> "($a === $b)"
        "is not" -> "($a !== $b)"
        "not in" -> "!\$in($a, $b)"
        "in" -> "\$in($a, $b)"
        else -> "${compareOps.getValue(op)}($a, $b)"
    }

    /** JavaScript arguments of a call: `[positional], {keywords}` (with `*args` and `**kwargs` spread). */
    fun callArguments(args: List<PyExpr>, keywords: List<Keyword>): String {
        val positional = "[" + args.joinToString { if (it is Starred) "...\$list(${it.value.js})" else it.js } + "]"
        if (keywords.isEmpty()) return positional
        val named = keywords.joinToString(", ") { if (it.name == "**") "...\$kwargs(${it.value.js})" else "${quote(it.name)}: ${it.value.js}" }
        return "$positional, { $named }"
    }

    private val Name.js: String by syn {
        val r = resolution
        if (r == Resolution.Builtin && id !in PythonRuntime.builtins) jsUnsupported(this, "the builtin '$id'")
        jsName(id, r)
    }
    private val Constant.js: String by syn {
        when (kind) {
            ConstantKind.INT -> "${value}n"
            ConstantKind.FLOAT -> (value as Double).let { if (it.isInfinite()) (if (it > 0) "Infinity" else "-Infinity") else it.toString() }
            ConstantKind.STR -> quote(value as String)
            ConstantKind.BOOL -> "$value"
            ConstantKind.NONE, ConstantKind.ELLIPSIS -> "null"
            ConstantKind.BYTES -> jsUnsupported(this, "bytes")
        }
    }
    private val BinOp.js: String by syn { "${binaryOps[op] ?: error("no operator $op")}(${left.js}, ${right.js})" }
    private val UnaryOp.js: String by syn {
        when (op) { "-" -> "\$neg(${operand.js})"; "+" -> "\$pos(${operand.js})"; "~" -> "\$invert(${operand.js})"; else -> "\$not(${operand.js})" }
    }
    private val BoolOp.js: String by syn { "\$${op}(${left.js}, () => ${right.js})" }
    private val Compare.js: String by syn {
        if (ops.size == 1) compare(ops[0], left.js, comparators[0].js) else {
            // a < b < c: every operand is evaluated once, and only while the comparisons hold
            val names = (0..ops.size).map { temp("c") }
            val values = listOf(left) + comparators
            val steps = StringBuilder("const ${names[0]} = ${values[0].js};")
            for (i in 1..ops.size) steps.append(" const ${names[i]} = ${values[i].js}; if (!${compare(ops[i - 1], names[i - 1], names[i])}) return false;")
            "(() => { $steps return true; })()"
        }
    }
    private val IfExp.js: String by syn { "(\$bool(${test.js}) ? ${body.js} : ${orElse.js})" }
    private val Call.js: String by syn {
        val f = func
        when {
            // super() in a method: the class being defined and the first parameter
            f is Name && f.id == "super" && args.isEmpty() && f.resolution == Resolution.Builtin -> superCall(this)
            f is Attribute -> "\$callMethod(${f.value.js}, ${quote(f.attr)}, ${callArguments(args, keywords)})"
            else -> "\$call(${f.js}, ${callArguments(args, keywords)})"
        }
    }
    private val Attribute.js: String by syn { "\$getattr(${value.js}, ${quote(attr)})" }
    private val Subscript.js: String by syn { "\$getitem(${value.js}, ${index.js})" }
    private val Slice.js: String by syn { "\$slice(${lower?.js ?: "null"}, ${upper?.js ?: "null"}, ${step?.js ?: "null"})" }
    private val Starred.js: String by syn { value.js }
    private val ListExpr.js: String by syn { "[" + elements.joinToString { if (it is Starred) "...\$list(${it.value.js})" else it.js } + "]" }
    private val TupleExpr.js: String by syn { "new Tuple([" + elements.joinToString { if (it is Starred) "...\$list(${it.value.js})" else it.js } + "])" }
    private val SetExpr.js: String by syn { "new PySet([" + elements.joinToString { if (it is Starred) "...\$list(${it.value.js})" else it.js } + "])" }
    private val DictExpr.js: String by syn {
        val d = temp("d")
        val entries = keys.indices.joinToString(" ") {
            if (keys[it] is DictUnpack) "for (const [, [k, v]] of \$dict(${values[it].js}).map) $d.set(k, v);" else "$d.set(${keys[it].js}, ${values[it].js});"
        }
        "((() => { const $d = new Dict(); $entries return $d; })())"
    }
    private val NamedExpr.js: String by syn { "(${jsName(target.id, target.resolution)} = ${value.js})" }
    private val Lambda.js: String by syn {
        val ps = params.joinToString { "v_${it.name}" }
        val locals = declarations(this, "v_", params.map { it.name }.toSet())
        "\$def(\"<lambda>\", ($ps) => { $locals return ${body.js}; }, ${CoreFunctions.signature(params)})"
    }

    private fun superCall(call: Call): String {
        val function = call.enclosingFunction as? FunctionDef
        val cls = function?.scope as? ClassDef
        if (function == null || cls == null || function.params.isEmpty()) return "\$call(\$b.super, [])"
        return "\$super(${jsName(cls.name, resolve(cls, cls.name))}, v_${function.params.first().name})"
    }

    init {
        PythonRuntime.add("kwargs", """
            function ${'$'}kwargs(d) { const o = {}; for (const [, [k, v]] of ${'$'}dict(d).map) o[k] = v; return o; }
            function ${'$'}dict(d) { if (d instanceof Dict) return d; return ${'$'}call(${'$'}b.dict, [d]); }
        """.trimIndent())
    }

    // ---- binding -----------------------------------------------------------------------------

    private val NamedExpr.boundNames: List<String> by syn { listOf(target.id) }
    /** `:=` in a comprehension binds in the enclosing function or module. */
    private val NamedExpr.bindingScope: ScopeNode by syn { var s = scope; while (s is Comprehension) s = s.scope; s }

    // ---- Python ------------------------------------------------------------------------------

    private val binaryPrecedence = mapOf("|" to PyPrecedence.BIT_OR, "^" to PyPrecedence.BIT_XOR, "&" to PyPrecedence.BIT_AND,
        "<<" to PyPrecedence.SHIFT, ">>" to PyPrecedence.SHIFT, "+" to PyPrecedence.SUM, "-" to PyPrecedence.SUM,
        "*" to PyPrecedence.PRODUCT, "/" to PyPrecedence.PRODUCT, "//" to PyPrecedence.PRODUCT, "%" to PyPrecedence.PRODUCT,
        "@" to PyPrecedence.PRODUCT, "**" to PyPrecedence.POWER)

    private val BinOp.pyPrecedence: Int by syn { binaryPrecedence.getValue(op) }
    private val UnaryOp.pyPrecedence: Int by syn { if (op == "not") PyPrecedence.NOT else PyPrecedence.UNARY }
    private val BoolOp.pyPrecedence: Int by syn { if (op == "and") PyPrecedence.AND else PyPrecedence.OR }
    private val Compare.pyPrecedence: Int by syn { PyPrecedence.COMPARE }
    private val IfExp.pyPrecedence: Int by syn { PyPrecedence.IF }
    private val Lambda.pyPrecedence: Int by syn { PyPrecedence.LAMBDA }

    /** Python arguments of a call (or of class bases). */
    fun pyArguments(args: List<PyExpr>, keywords: List<Keyword>): String =
        (args.map { pyItem(it) } + keywords.map { if (it.name == "**") "**${pyOperand(it.value, PyPrecedence.BIT_OR)}" else "${it.name}=${pyItem(it.value)}" }).joinToString(", ")

    /** A Python parameter list, with `/` and a bare `*` where needed. */
    fun pyParams(params: List<Param>): String {
        val out = ArrayList<String>()
        fun param(p: Param): String {
            val star = when (p.kind) { ParamKind.VARARGS -> "*"; ParamKind.KWARGS -> "**"; else -> "" }
            val annotation = p.annotation?.let { ": ${pyItem(it)}" } ?: ""
            val default = p.default?.let { if (annotation.isEmpty()) "=${pyItem(it)}" else " = ${pyItem(it)}" } ?: ""
            return star + p.name + annotation + default
        }
        for ((i, p) in params.withIndex()) {
            val previous = params.getOrNull(i - 1)?.kind
            if (previous == ParamKind.POSONLY && p.kind != ParamKind.POSONLY) out += "/"
            if (p.kind == ParamKind.KWONLY && previous != ParamKind.KWONLY && previous != ParamKind.VARARGS) out += "*"
            out += param(p)
        }
        if (params.lastOrNull()?.kind == ParamKind.POSONLY) out += "/"
        return out.joinToString(", ")
    }

    private fun pyFloat(d: Double): String = when {
        d.isInfinite() -> "1e999"
        else -> d.toString()
    }

    private val Name.py: String by syn { id }
    private val Constant.py: String by syn {
        when (kind) {
            ConstantKind.INT -> value as String
            ConstantKind.FLOAT -> pyFloat(value as Double)
            ConstantKind.STR -> pyString(value as String)
            ConstantKind.BYTES -> pyBytes(value as String)
            ConstantKind.BOOL -> if (value == true) "True" else "False"
            ConstantKind.NONE -> "None"
            ConstantKind.ELLIPSIS -> "..."
        }
    }
    private val BinOp.py: String by syn {
        val p = binaryPrecedence.getValue(op)
        if (op == "**") "${pyOperand(left, PyPrecedence.AWAIT)} ** ${pyOperand(right, PyPrecedence.UNARY)}"
        else "${pyOperand(left, p)} $op ${pyOperand(right, p + 1)}"
    }
    private val UnaryOp.py: String by syn { if (op == "not") "not ${pyOperand(operand, PyPrecedence.NOT)}" else "$op${pyOperand(operand, PyPrecedence.UNARY)}" }
    private val BoolOp.py: String by syn {
        val p = if (op == "and") PyPrecedence.AND else PyPrecedence.OR
        "${pyOperand(left, p)} $op ${pyOperand(right, p + 1)}"
    }
    private val Compare.py: String by syn {
        pyOperand(left, PyPrecedence.BIT_OR) + ops.indices.joinToString("") { " ${ops[it]} ${pyOperand(comparators[it], PyPrecedence.BIT_OR)}" }
    }
    private val IfExp.py: String by syn { "${pyOperand(body, PyPrecedence.OR)} if ${pyOperand(test, PyPrecedence.OR)} else ${pyOperand(orElse, PyPrecedence.IF)}" }
    private val Lambda.py: String by syn { if (params.isEmpty()) "lambda: ${body.py}" else "lambda ${pyParams(params)}: ${body.py}" }
    private val Call.py: String by syn {
        val only = args.singleOrNull()
        // f(x for x in xs): a sole generator argument needs no parentheses of its own
        if (only is Comprehension && only.kind == ComprehensionKind.GENERATOR && keywords.isEmpty()) "${pyOperand(func, PyPrecedence.ATOM)}${only.py}"
        else "${pyOperand(func, PyPrecedence.ATOM)}(${pyArguments(args, keywords)})"
    }
    private val Attribute.py: String by syn {
        val v = value
        (if (v is Constant && v.kind == ConstantKind.INT) "(${v.py})" else pyOperand(v, PyPrecedence.ATOM)) + ".$attr"
    }
    private fun pyIndex(e: PyExpr): String = if (e is Slice) e.py else pyItem(e)
    private val Subscript.py: String by syn {
        val i = index
        val inside = if (i is TupleExpr && i.elements.isNotEmpty()) i.elements.joinToString(", ") { pyIndex(it) } + (if (i.elements.size == 1) "," else "") else pyIndex(i)
        "${pyOperand(value, PyPrecedence.ATOM)}[$inside]"
    }
    private val Slice.py: String by syn {
        (lower?.let { pyItem(it) } ?: "") + ":" + (upper?.let { pyItem(it) } ?: "") + (step?.let { ":" + pyItem(it) } ?: "")
    }
    private val Starred.py: String by syn { "*${pyOperand(value, PyPrecedence.BIT_OR)}" }
    private val ListExpr.py: String by syn { "[" + elements.joinToString(", ") { pyItem(it) } + "]" }
    private val TupleExpr.py: String by syn { if (elements.size == 1) "(${pyItem(elements[0])},)" else "(" + elements.joinToString(", ") { pyItem(it) } + ")" }
    private val SetExpr.py: String by syn { "{" + elements.joinToString(", ") { pyItem(it) } + "}" }
    private val DictExpr.py: String by syn {
        "{" + keys.indices.joinToString(", ") { if (keys[it] is DictUnpack) "**${pyOperand(values[it], PyPrecedence.BIT_OR)}" else "${pyItem(keys[it])}: ${pyItem(values[it])}" } + "}"
    }
    private val NamedExpr.py: String by syn { "(${target.id} := ${pyItem(value)})" }
}
