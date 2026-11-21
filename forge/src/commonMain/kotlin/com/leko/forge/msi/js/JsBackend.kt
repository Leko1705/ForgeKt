package com.leko.forge.msi.js

import com.leko.forge.identityMap
import com.leko.forge.msi.*

/**
 * Translates the module to JavaScript: the source of an expression that evaluates to a factory
 * function. Call it with an object of external function implementations; it returns the functions
 * and globals of the module:
 *
 * ```js
 * const program = (/* toJavaScript() */)({ print: s => console.log(s) })
 * program.functions.main()
 * ```
 *
 * Representation: [Type.I64] is `BigInt` (64 bit wrap around), [Type.I32] and [Type.F64] are numbers,
 * structs are classes, unions are objects of a class per union with `tag` and `values`, arrays are JS
 * arrays, function values are JS functions (closures capture by reference natively), and exceptions are
 * thrown wrapped in `MsiThrown`. External functions receive and return these values.
 *
 * @throws IllegalArgumentException if the module has problems (see [validate]).
 */
public fun Module.toJavaScript(): String {
    val problems = validate()
    require(problems.isEmpty()) { "the module has problems:\n" + problems.joinToString("\n") { "  $it" } }
    return JsGenerator(this).generate()
}

private class JsGenerator(val module: Module) {
    private val out = StringBuilder()
    private var indent = 1
    private var counter = 0
    private val names = identityMap<Any, String>()

    private fun line(text: String) {
        repeat(indent) { out.append("  ") }
        out.append(text).append('\n')
    }

    private inline fun indented(block: () -> Unit) {
        indent++
        block()
        indent--
    }

    private fun fresh(prefix: String) = "$prefix${counter++}"

    private fun nameOf(v: Variable): String =
        if (v is Global) "g_${v.name}" else names[v] ?: "v_${v.name}_${counter++}".also { names[v] = it }

    private fun labelOf(loop: Loop): String = names[loop] ?: fresh("L").also { names[loop] = it }

    fun generate(): String {
        out.append("(function (externals) {\n")
        line("\"use strict\";")
        line(RUNTIME)
        for (s in module.structs) {
            val params = s.fields.joinToString { "f_${it.name}" }
            line("class S_${s.name} { constructor($params) { ${s.fields.joinToString(" ") { "this.f_${it.name} = f_${it.name};" }} } }")
        }
        for (u in module.unions) line("class U_${u.name} { constructor(tag, values) { this.tag = tag; this.values = values; } }")
        // value types are copied deeply by Copy
        for (s in module.structs.filter { it.kind == MemoryKind.VALUE })
            line("S_${s.name}.prototype.\$copy = function () { return new S_${s.name}(${s.fields.joinToString { "\$copy(this.f_${it.name})" }}); };")
        for (u in module.unions.filter { it.kind == MemoryKind.VALUE })
            line("U_${u.name}.prototype.\$copy = function () { return new U_${u.name}(this.tag, this.values.map(\$copy)); };")
        for (f in module.functions) function(f)
        for (g in module.globals) line("let g_${g.name} = ${g.init?.let { expr(it) } ?: zero(g.type)};")
        line("return {")
        indented {
            line("functions: { ${module.functions.joinToString { "${quote(it.name)}: f_${it.name}" }} },")
            line("globals: { ${module.globals.joinToString { "get ${quote(it.name)}() { return g_${it.name}; }" }} },")
        }
        line("};")
        out.append("})")
        return out.toString()
    }

    private fun function(f: Func) {
        val params = f.params.joinToString { nameOf(it) }
        if (f.body == null) {
            line("function f_${f.name}($params) { return externals[${quote(f.name)}]($params); }")
            return
        }
        line("function f_${f.name}($params) {")
        indented { f.body.stmts.forEach(::stmt) }
        line("}")
    }

    // ---- statements --------------------------------------------------------------------------

    private fun block(b: Block) {
        line("{")
        indented { b.stmts.forEach(::stmt) }
        line("}")
    }

    private fun stmt(s: Stmt) {
        when (s) {
            is Block -> block(s)
            is Let -> line("let ${nameOf(s)} = ${s.init?.let { expr(it) } ?: zero(s.type)};")
            is Assign -> if (s.target is Local) line("${target(s.target)} = ${expr(s.value)};") else if (s.target is Deref) {
                val value = fresh("\$v")
                line("{ const $value = ${expr(s.value)}; ${expr(s.target.ref)}.set($value); }")
            } else {
                // MSI evaluates the value before the target, JavaScript the other way round
                val value = fresh("\$v")
                line("{ const $value = ${expr(s.value)}; ${target(s.target)} = $value; }")
            }
            is Branch -> {
                line("if (${expr(s.condition)})")
                block(s.then)
                s.alternative?.let { line("else"); block(it) }
            }
            is Loop -> {
                line("${labelOf(s)}: while (${s.condition?.let { expr(it) } ?: "true"})")
                block(s.body)
            }
            is Break -> line("break ${labelOf(s.target!!)};")
            is Continue -> line("continue ${labelOf(s.target!!)};")
            is Return -> line(if (s.value == null) "return;" else "return ${expr(s.value)};")
            is Eval -> line("${expr(s.expr)};")
            is Match -> {
                val subject = fresh("\$m")
                line("{")
                indented {
                    line("const $subject = ${expr(s.subject)};")
                    s.cases.forEachIndexed { i, c ->
                        line("${if (i == 0) "if" else "else if"} ($subject.tag === ${quote(c.variant)}) {")
                        indented {
                            c.variables.forEachIndexed { j, v -> line("let ${nameOf(v)} = $subject.values[$j];") }
                            c.body.stmts.forEach(::stmt)
                        }
                        line("}")
                    }
                    s.otherwise?.let {
                        if (s.cases.isEmpty()) block(it) else { line("else"); block(it) }
                    }
                }
                line("}")
            }
            is Throw -> line("throw new MsiThrown(${expr(s.value)});")
            is Access -> {
                val o = fresh("\$o")
                line("{")
                indented {
                    line("const $o = ${expr(s.target)}; \$beginAccess($o, ${s.mutable});")
                    line("try")
                    block(s.body)
                    line("finally { \$endAccess($o, ${s.mutable}); }")
                }
                line("}")
            }
            is Try -> {
                line("try")
                block(s.body)
                if (s.catches.isNotEmpty()) {
                    val e = fresh("\$e")
                    line("catch ($e) {")
                    indented {
                        line("if (!($e instanceof MsiThrown)) throw $e;")
                        s.catches.forEachIndexed { i, c ->
                            line("${if (i == 0) "if" else "else if"} (${isInstance("$e.value", c.type)}) {")
                            indented {
                                line("let ${nameOf(c)} = $e.value;")
                                c.body.stmts.forEach(::stmt)
                            }
                            line("}")
                        }
                        line("else throw $e;")
                    }
                    line("}")
                }
                s.finally?.let { line("finally"); block(it) }
            }
        }
    }

    private fun isInstance(value: String, type: Type): String = when (type) {
        is Type.Struct -> "$value instanceof S_${type.name}"
        is Type.Union -> "$value instanceof U_${type.name}"
        else -> "typeof $value === \"string\""
    }

    private fun target(e: Expr): String = when (e) {
        is Local -> nameOf(e.variable!!)
        is Get -> "${expr(e.target)}.f_${e.field}"
        is Index -> indexTarget(e)
        else -> error("not assignable")
    }

    /** `array[index]` with a bounds check; the array expression is evaluated once. */
    private fun indexTarget(e: Index): String = "\$ref(${expr(e.array)}, ${expr(e.index)}).a[\$ref.i]"

    // ---- expressions -------------------------------------------------------------------------

    private fun expr(e: Expr): String = when (e) {
        is IntLit -> if (e.type == Type.I32) "(${e.value})" else "(${e.value}n)"
        is FloatLit -> number(e.value)
        is BoolLit -> "${e.value}"
        is StrLit -> quote(e.value)
        is Local -> nameOf(e.variable!!)
        is Binary -> binary(e)
        is Unary -> {
            val o = expr(e.operand)
            when (e.op) {
                UnaryOp.NOT -> "(!$o)"
                UnaryOp.NEG -> when (e.type) { Type.I32 -> "((-$o) | 0)"; Type.I64 -> "BigInt.asIntN(64, -$o)"; else -> "(-$o)" }
                UnaryOp.BIT_NOT -> if (e.type == Type.I32) "(~$o)" else "(~$o)"
            }
        }
        is Call -> "f_${e.function}(${e.args.joinToString { expr(it) }})"
        is New -> "new S_${e.struct}(${e.args.joinToString { expr(it) }})"
        is Get -> "${expr(e.target)}.f_${e.field}"
        is NewArray -> "\$array(${expr(e.size)}, () => ${zero(e.element)})"
        is Index -> "\$get(${expr(e.array)}, ${expr(e.index)})"
        is Length -> "BigInt(${expr(e.array)}.length)"
        is Convert -> convert(expr(e.value), e.value.type, e.to)
        is Conditional -> "(${expr(e.condition)} ? ${expr(e.then)} : ${expr(e.otherwise)})"
        is Make -> "new U_${e.union}(${quote(e.variant)}, [${e.args.joinToString { expr(it) }}])"
        is Is -> "(${expr(e.subject)}.tag === ${quote(e.variant)})"
        is FuncRef -> "f_${e.function}"
        is Lambda -> lambda(e)
        is Invoke -> "(${expr(e.callee)})(${e.args.joinToString { expr(it) }})"
        is Borrow -> when (val place = e.place) {
            is Local -> { val n = nameOf(place.variable!!); "({ get: () => $n, set: (\$x) => { $n = \$x; } })" }
            is Get -> "((\$o) => ({ get: () => \$o.f_${place.field}, set: (\$x) => { \$o.f_${place.field} = \$x; } }))(${expr(place.target)})"
            else -> expr((place as Deref).ref) // a reborrow is the same reference
        }
        is Deref -> "${expr(e.ref)}.get()"
        is Copy -> "\$copy(${expr(e.value)})"
        is Null -> "null"
    }

    private fun lambda(e: Lambda): String {
        val saved = out.length
        val params = e.params.joinToString { nameOf(it) }
        // generate the body into the buffer, then cut it out again
        out.append("\n")
        indented { indented { e.body.stmts.forEach(::stmt) } }
        val body = out.substring(saved)
        out.setLength(saved)
        return "(($params) => {$body${"  ".repeat(indent + 1)}})"
    }

    private fun binary(e: Binary): String {
        val l = expr(e.left)
        val r = expr(e.right)
        val t = e.left.type
        return when (e.op) {
            BinaryOp.AND -> "($l && $r)"
            BinaryOp.OR -> "($l || $r)"
            BinaryOp.EQ -> "($l === $r)"
            BinaryOp.NE -> "($l !== $r)"
            BinaryOp.LT -> "($l < $r)"
            BinaryOp.LE -> "($l <= $r)"
            BinaryOp.GT -> "($l > $r)"
            BinaryOp.GE -> "($l >= $r)"
            else -> when (t) {
                Type.I32 -> when (e.op) {
                    BinaryOp.ADD -> "(($l + $r) | 0)"
                    BinaryOp.SUB -> "(($l - $r) | 0)"
                    BinaryOp.MUL -> "Math.imul($l, $r)"
                    BinaryOp.DIV -> "\$div32($l, $r)"
                    BinaryOp.REM -> "\$rem32($l, $r)"
                    BinaryOp.BIT_AND -> "($l & $r)"
                    BinaryOp.BIT_OR -> "($l | $r)"
                    BinaryOp.BIT_XOR -> "($l ^ $r)"
                    BinaryOp.SHL -> "($l << $r)"
                    BinaryOp.SHR -> "($l >> $r)"
                    else -> error("unreachable")
                }
                Type.I64 -> when (e.op) {
                    BinaryOp.ADD -> "BigInt.asIntN(64, $l + $r)"
                    BinaryOp.SUB -> "BigInt.asIntN(64, $l - $r)"
                    BinaryOp.MUL -> "BigInt.asIntN(64, $l * $r)"
                    BinaryOp.DIV -> "\$div64($l, $r)"
                    BinaryOp.REM -> "\$rem64($l, $r)"
                    BinaryOp.BIT_AND -> "($l & $r)"
                    BinaryOp.BIT_OR -> "($l | $r)"
                    BinaryOp.BIT_XOR -> "($l ^ $r)"
                    BinaryOp.SHL -> "BigInt.asIntN(64, $l << ($r & 63n))"
                    BinaryOp.SHR -> "($l >> ($r & 63n))"
                    else -> error("unreachable")
                }
                Type.F64 -> "($l ${e.op.symbol} $r)"
                else -> "($l + $r)" // strings
            }
        }
    }

    private fun convert(v: String, from: Type, to: Type): String = when {
        from == to -> v
        to == Type.Str -> if (from == Type.F64) "\$fmt($v)" else "String($v)"
        from == Type.I32 && to == Type.I64 -> "BigInt($v)"
        from == Type.I32 && to == Type.F64 -> v
        from == Type.I64 && to == Type.I32 -> "Number(BigInt.asIntN(32, $v))"
        from == Type.I64 && to == Type.F64 -> "Number($v)"
        from == Type.F64 && to == Type.I32 -> "\$f2i($v)"
        from == Type.F64 && to == Type.I64 -> "\$f2l($v)"
        else -> error("no conversion from $from to $to")
    }

    private fun zero(t: Type): String = when (t) {
        Type.Unit -> "undefined"
        Type.Bool -> "false"
        Type.I32, Type.F64 -> "0"
        Type.I64 -> "0n"
        Type.Str -> "\"\""
        else -> "null"
    }

    private fun number(d: Double): String = when {
        d.isNaN() -> "NaN"
        d == Double.POSITIVE_INFINITY -> "Infinity"
        d == Double.NEGATIVE_INFINITY -> "(-Infinity)"
        else -> "($d)"
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c.code < 0x20 || c.code > 0x7e -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }

    companion object {
        val RUNTIME = """
  class MsiThrown extends Error { constructor(value) { super("uncaught MSI exception"); this.value = value; } }
  function ${'$'}fail(message) { throw new Error(message); }
  function ${'$'}div32(a, b) { if (b === 0) ${'$'}fail("division by zero"); return (a / b) | 0; }
  function ${'$'}rem32(a, b) { if (b === 0) ${'$'}fail("division by zero"); return (a % b) | 0; }
  function ${'$'}div64(a, b) { if (b === 0n) ${'$'}fail("division by zero"); return BigInt.asIntN(64, a / b); }
  function ${'$'}rem64(a, b) { if (b === 0n) ${'$'}fail("division by zero"); return a % b; }
  function ${'$'}f2i(d) { if (Number.isNaN(d)) return 0; return Math.max(-2147483648, Math.min(2147483647, Math.trunc(d))); }
  function ${'$'}f2l(d) { if (Number.isNaN(d)) return 0n; if (d >= 9223372036854775807) return 9223372036854775807n; if (d <= -9223372036854775808) return -9223372036854775808n; return BigInt(Math.trunc(d)); }
  function ${'$'}fmt(d) { const s = String(d); return Number.isFinite(d) && !/[.e]/.test(s) ? s + ".0" : s; }
  function ${'$'}check(a, i) { const n = Number(i); if (n < 0 || n >= a.length) ${'$'}fail("array index " + i + " out of bounds for length " + a.length); return n; }
  function ${'$'}get(a, i) { if (a === null) ${'$'}fail("uninitialized array"); return a[${'$'}check(a, i)]; }
  function ${'$'}ref(a, i) { if (a === null) ${'$'}fail("uninitialized array"); ${'$'}ref.i = ${'$'}check(a, i); ${'$'}ref.a = a; return ${'$'}ref; }
  function ${'$'}copy(v) { return v !== null && typeof v === "object" && typeof v.${'$'}copy === "function" ? v.${'$'}copy() : v; }
  const ${'$'}accesses = new Map();
  function ${'$'}beginAccess(o, mutable) {
    const a = ${'$'}accesses.get(o) || { readers: 0, writing: false };
    if (a.writing || (mutable && a.readers > 0)) ${'$'}fail("simultaneous access: it is already " + (a.writing ? "being modified" : "being read"));
    if (mutable) a.writing = true; else a.readers++;
    ${'$'}accesses.set(o, a);
  }
  function ${'$'}endAccess(o, mutable) { const a = ${'$'}accesses.get(o); if (mutable) a.writing = false; else a.readers--; }
  function ${'$'}array(size, zero) { const n = Number(size); if (n < 0) ${'$'}fail("invalid array size " + size); return Array.from({ length: n }, zero); }""".trimStart('\n')
    }
}
