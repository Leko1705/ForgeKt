package com.leko.forge.msi

/** An instance of a [Struct]: the field values in declaration order. */
public class StructValue(public val struct: Struct, public val fields: MutableList<Any?>) {
    public operator fun get(field: String): Any? = fields[struct.fields.indexOfFirst { it.name == field }]
    override fun toString(): String =
        "${struct.name}(${struct.fields.zip(fields).joinToString { (f, v) -> "${f.name}=$v" }})"
}

/** An array: a fixed number of mutable elements. */
public class ArrayValue(public val elements: MutableList<Any?>) {
    override fun toString(): String = elements.toString()
}

/** A value of a [Union]: its [variant] and the field values. */
public class UnionValue(public val union: Union, public val variant: Variant, public val fields: MutableList<Any?>) {
    override fun toString(): String =
        if (fields.isEmpty()) "${union.name}.${variant.name}"
        else "${union.name}.${variant.name}(${variant.fields.zip(fields).joinToString { (f, v) -> "${f.name}=$v" }})"
}

/** A function value: a [FuncRef] or a [Lambda] with its captured variables. */
public class FunctionValue(public val arity: Int, private val body: (List<Any?>) -> Any?) {
    public operator fun invoke(args: List<Any?>): Any? = body(args)
    override fun toString(): String = "<function/$arity>"
}

/** A reference created by [Borrow]: reads and writes the place it points to. */
public class RefValue(private val read: () -> Any?, private val write: (Any?) -> Unit) {
    public fun get(): Any? = read()
    public fun set(value: Any?): Unit = write(value)
    override fun toString(): String = "<ref>"
}

/** A failure while running MSI code, e.g. a division by zero or an array index out of bounds. */
public class MsiRuntimeException(message: String) : RuntimeException(message)

/** An MSI value thrown with [Throw] and not caught. */
public class MsiThrownException(public val value: Any?) : RuntimeException("uncaught MSI exception: $value")

/**
 * Runs MSI modules directly: the reference semantics that every backend must reproduce, and a way to
 * execute MSI on every platform Forge runs on.
 *
 * Values are represented as: [Type.I32] → `Int`, [Type.I64] → `Long`, [Type.F64] → `Double`,
 * [Type.Bool] → `Boolean`, [Type.Str] → `String`, [Type.Unit] → `Unit`, structs → [StructValue],
 * unions → [UnionValue], arrays → [ArrayValue], functions → [FunctionValue]. Struct, union, array
 * and function variables that were never assigned hold `null`.
 *
 * External functions are implemented by [externals], by name:
 *
 * ```
 * val out = StringBuilder()
 * val interpreter = Interpreter(module, mapOf("print" to { args -> out.appendLine(args[0]); Unit }))
 * interpreter.call("main")
 * ```
 *
 * Globals are initialized when the interpreter is created. An uncaught [Throw] ends a call with
 * [MsiThrownException].
 *
 * @throws IllegalArgumentException if the module has problems (see [validate]).
 */
public class Interpreter(
    public val module: Module,
    private val externals: Map<String, (List<Any?>) -> Any?> = emptyMap(),
) {
    private class Cell(var value: Any?)

    private val globals = HashMap<String, Cell>()

    /** Running [Access] checks per object. */
    private class AccessCount { var readers = 0; var writing = false }
    private val accesses = com.leko.forge.identityMap<Any, AccessCount>()

    init {
        val problems = module.validate()
        require(problems.isEmpty()) { "the module has problems:\n" + problems.joinToString("\n") { "  $it" } }
        val frame = Frame(emptyList())
        for (g in module.globals) globals[g.name] = Cell(g.init?.let { eval(it, frame) } ?: zeroOf(g.type))
    }

    /** Calls the function [name] with [args] and returns its result. */
    public fun call(name: String, vararg args: Any?): Any? {
        val f = module.functionsByName[name] ?: throw IllegalArgumentException("no function '$name'")
        require(args.size == f.params.size) { "fun $name expects ${f.params.size} arguments, not ${args.size}" }
        return invoke(f, args.toList())
    }

    /** The current value of the global [name]. */
    public fun global(name: String): Any? = (globals[name] ?: throw IllegalArgumentException("no global '$name'")).value

    // ---- functions and statements ----------------------------------------------------------

    /** The variables of a running function: nested scopes, innermost last. Closures share the outer ones. */
    private inner class Frame(outer: List<HashMap<String, Cell>>) {
        val scopes = ArrayList(outer)
        fun cell(name: String): Cell {
            for (i in scopes.indices.reversed()) scopes[i][name]?.let { return it }
            return globals[name] ?: throw MsiRuntimeException("unknown variable '$name'")
        }
        fun declare(name: String, value: Any?) { scopes.last()[name] = Cell(value) }
        inline fun <R> scoped(block: () -> R): R {
            scopes += HashMap()
            try { return block() } finally { scopes.removeAt(scopes.size - 1) }
        }
    }

    /** How a statement ended, if not normally. */
    private sealed class Jump {
        class Out(val loop: Loop) : Jump()
        class Next(val loop: Loop) : Jump()
        class Result(val value: Any?) : Jump()
    }

    private fun invoke(f: Func, args: List<Any?>): Any? {
        val body = f.body ?: run {
            val external = externals[f.name] ?: throw MsiRuntimeException("no implementation for external fun ${f.name}")
            return external(args)
        }
        return run(f.params, args, body, emptyList())
    }

    private fun run(params: List<Param>, args: List<Any?>, body: Block, outer: List<HashMap<String, Cell>>): Any? {
        val frame = Frame(outer)
        frame.scopes += HashMap<String, Cell>().also { scope -> params.zip(args).forEach { (p, a) -> scope[p.name] = Cell(a) } }
        return when (val jump = exec(body, frame)) {
            is Jump.Result -> jump.value
            else -> Unit
        }
    }

    private fun exec(s: Stmt, frame: Frame): Jump? {
        when (s) {
            is Block -> return frame.scoped {
                for (stmt in s.stmts) exec(stmt, frame)?.let { return@scoped it }
                null
            }
            is Let -> frame.declare(s.name, s.init?.let { eval(it, frame) } ?: zeroOf(s.type))
            is Assign -> assign(s.target, eval(s.value, frame), frame)
            is Branch -> {
                if (eval(s.condition, frame) as Boolean) return exec(s.then, frame)
                s.alternative?.let { return exec(it, frame) }
            }
            is Loop -> while (s.condition == null || eval(s.condition, frame) as Boolean) {
                when (val jump = exec(s.body, frame)) {
                    null -> {}
                    is Jump.Out -> if (jump.loop === s) break else return jump
                    is Jump.Next -> if (jump.loop === s) continue else return jump
                    is Jump.Result -> return jump
                }
            }
            is Break -> return Jump.Out(s.target!!)
            is Continue -> return Jump.Next(s.target!!)
            is Return -> return Jump.Result(s.value?.let { eval(it, frame) } ?: Unit)
            is Eval -> eval(s.expr, frame)
            is Match -> {
                val value = eval(s.subject, frame) as UnionValue? ?: throw MsiRuntimeException("match on an uninitialized union")
                val case = s.cases.firstOrNull { it.variant == value.variant.name }
                if (case != null) return frame.scoped {
                    case.bindings.forEachIndexed { i, name -> frame.declare(name, value.fields[i]) }
                    exec(case.body, frame)
                }
                s.otherwise?.let { return exec(it, frame) }
            }
            is Throw -> throw MsiThrownException(eval(s.value, frame))
            is Access -> {
                val obj = eval(s.target, frame) ?: throw MsiRuntimeException("access to an uninitialized value")
                val count = accesses[obj] ?: AccessCount().also { accesses[obj] = it }
                if (count.writing || s.mutable && count.readers > 0)
                    throw MsiRuntimeException("simultaneous access to ${s.target.render()}: it is already ${if (count.writing) "being modified" else "being read"}")
                if (s.mutable) count.writing = true else count.readers++
                try {
                    return exec(s.body, frame)
                } finally {
                    if (s.mutable) count.writing = false else count.readers--
                }
            }
            is Try -> return execTry(s, frame)
        }
        return null
    }

    private fun execTry(s: Try, frame: Frame): Jump? {
        var jump: Jump? = null
        var pending: Throwable? = null
        val depth = frame.scopes.size
        try {
            jump = exec(s.body, frame)
        } catch (thrown: MsiThrownException) {
            while (frame.scopes.size > depth) frame.scopes.removeAt(frame.scopes.size - 1)
            val catch = s.catches.firstOrNull { matches(it.type, thrown.value) }
            if (catch == null) pending = thrown
            else try {
                jump = frame.scoped {
                    frame.declare(catch.name, thrown.value)
                    exec(catch.body, frame)
                }
            } catch (t: Throwable) {
                pending = t
            }
        } catch (t: Throwable) {
            pending = t
        }
        while (frame.scopes.size > depth) frame.scopes.removeAt(frame.scopes.size - 1)
        s.finally?.let { f -> exec(f, frame)?.let { return it } } // a jump out of finally discards everything else
        pending?.let { throw it }
        return jump
    }

    private fun matches(type: Type, value: Any?): Boolean = when (type) {
        is Type.Struct -> value is StructValue && value.struct.name == type.name
        is Type.Union -> value is UnionValue && value.union.name == type.name
        Type.Str -> value is String
        else -> false
    }

    private fun assign(target: Expr, value: Any?, frame: Frame) {
        when (target) {
            is Local -> frame.cell(target.name).value = value
            is Deref -> (eval(target.ref, frame) as RefValue? ?: throw MsiRuntimeException("uninitialized reference")).set(value)
            is Get -> {
                val obj = eval(target.target, frame) as StructValue? ?: throw MsiRuntimeException("field ${target.field} of an uninitialized struct")
                obj.fields[obj.struct.fields.indexOfFirst { it.name == target.field }] = value
            }
            is Index -> {
                val array = arrayOf(target.array, frame)
                array.elements[checkedIndex(array, eval(target.index, frame))] = value
            }
            else -> throw MsiRuntimeException("can not assign to ${target.render()}")
        }
    }

    // ---- expressions -------------------------------------------------------------------------

    private fun eval(e: Expr, frame: Frame): Any? = when (e) {
        is IntLit -> if (e.type == Type.I32) e.value.toInt() else e.value
        is FloatLit -> e.value
        is BoolLit -> e.value
        is StrLit -> e.value
        is Local -> frame.cell(e.name).value
        is Binary -> binary(e, frame)
        is Unary -> {
            val v = eval(e.operand, frame)
            when (e.op) {
                UnaryOp.NOT -> !(v as Boolean)
                UnaryOp.NEG -> when (e.type) { Type.I32 -> -(v as Int); Type.I64 -> -(v as Long); else -> -(v as Double) }
                UnaryOp.BIT_NOT -> if (e.type == Type.I32) (v as Int).inv() else (v as Long).inv()
            }
        }
        is Call -> invoke(e.callee!!, e.args.map { eval(it, frame) })
        is New -> StructValue(e.structDef!!, e.args.map { eval(it, frame) }.toMutableList())
        is Get -> {
            val obj = eval(e.target, frame) as StructValue? ?: throw MsiRuntimeException("field ${e.field} of an uninitialized struct")
            obj[e.field]
        }
        is NewArray -> {
            val size = toLong(eval(e.size, frame))
            if (size < 0 || size > Int.MAX_VALUE) throw MsiRuntimeException("invalid array size $size")
            ArrayValue(MutableList(size.toInt()) { zeroOf(e.element) })
        }
        is Index -> {
            val array = arrayOf(e.array, frame)
            array.elements[checkedIndex(array, eval(e.index, frame))]
        }
        is Length -> arrayOf(e.array, frame).elements.size.toLong()
        is Convert -> convert(eval(e.value, frame), e.value.type, e.to)
        is Conditional -> if (eval(e.condition, frame) as Boolean) eval(e.then, frame) else eval(e.otherwise, frame)
        is Make -> UnionValue(e.unionDef!!, e.variantDef!!, e.args.map { eval(it, frame) }.toMutableList())
        is Is -> (eval(e.subject, frame) as UnionValue? ?: throw MsiRuntimeException("uninitialized union")).variant.name == e.variant
        is FuncRef -> { val f = e.callee!!; FunctionValue(f.params.size) { args -> invoke(f, args) } }
        is Lambda -> {
            val captured = frame.scopes.toList() // the scopes themselves are shared, so assignments are visible
            FunctionValue(e.params.size) { args -> run(e.params, args, e.body, captured) }
        }
        is Invoke -> {
            val f = eval(e.callee, frame) as FunctionValue? ?: throw MsiRuntimeException("call of an uninitialized function value")
            f(e.args.map { eval(it, frame) })
        }
        is Borrow -> borrow(e.place, frame)
        is Deref -> (eval(e.ref, frame) as RefValue? ?: throw MsiRuntimeException("uninitialized reference")).get()
        is Copy -> copy(eval(e.value, frame))
        is Null -> null
    }

    private fun borrow(place: Expr, frame: Frame): RefValue = when (place) {
        is Local -> { val cell = frame.cell(place.name); RefValue({ cell.value }, { cell.value = it }) }
        is Get -> {
            val obj = eval(place.target, frame) as StructValue? ?: throw MsiRuntimeException("borrow of a field of an uninitialized struct")
            val i = obj.struct.fields.indexOfFirst { it.name == place.field }
            RefValue({ obj.fields[i] }, { obj.fields[i] = it })
        }
        is Deref -> eval(place.ref, frame) as RefValue? ?: throw MsiRuntimeException("uninitialized reference")
        else -> throw MsiRuntimeException("can not borrow ${place.render()}")
    }

    /** A deep copy of value structs and unions; everything else is shared or immutable. */
    private fun copy(v: Any?): Any? = when {
        v is StructValue && v.struct.kind == MemoryKind.VALUE -> StructValue(v.struct, v.fields.map { copy(it) }.toMutableList())
        v is UnionValue && v.union.kind == MemoryKind.VALUE -> UnionValue(v.union, v.variant, v.fields.map { copy(it) }.toMutableList())
        else -> v
    }

    private fun arrayOf(e: Expr, frame: Frame): ArrayValue =
        eval(e, frame) as ArrayValue? ?: throw MsiRuntimeException("uninitialized array ${e.render()}")

    private fun checkedIndex(array: ArrayValue, index: Any?): Int {
        val i = toLong(index)
        if (i < 0 || i >= array.elements.size) throw MsiRuntimeException("array index $i out of bounds for length ${array.elements.size}")
        return i.toInt()
    }

    private fun toLong(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        else -> throw MsiRuntimeException("not an integer: $v")
    }

    private fun binary(e: Binary, frame: Frame): Any? {
        // short circuit
        if (e.op == BinaryOp.AND) return eval(e.left, frame) as Boolean && eval(e.right, frame) as Boolean
        if (e.op == BinaryOp.OR) return eval(e.left, frame) as Boolean || eval(e.right, frame) as Boolean
        val type = e.left.type
        val l = eval(e.left, frame)
        val r = eval(e.right, frame)
        return when (e.op) {
            BinaryOp.EQ -> same(type, l, r)
            BinaryOp.NE -> !same(type, l, r)
            BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> {
                val c = compare(type, l, r)
                when (e.op) { BinaryOp.LT -> c < 0; BinaryOp.LE -> c <= 0; BinaryOp.GT -> c > 0; else -> c >= 0 }
            }
            else -> when (type) {
                Type.I32 -> int(e.op, l as Int, r as Int)
                Type.I64 -> long(e.op, l as Long, r as Long)
                Type.F64 -> double(e.op, l as Double, r as Double)
                Type.Str -> (l as String) + (r as String)
                else -> throw MsiRuntimeException("invalid operands of ${e.op.symbol}")
            }
        }
    }

    private fun same(type: Type, l: Any?, r: Any?): Boolean = if (type.isPrimitive) l == r else l === r

    private fun compare(type: Type, l: Any?, r: Any?): Int = when (type) {
        Type.I32 -> (l as Int).compareTo(r as Int)
        Type.I64 -> (l as Long).compareTo(r as Long)
        Type.F64 -> (l as Double).compareTo(r as Double)
        else -> (l as String).compareTo(r as String)
    }

    private fun int(op: BinaryOp, l: Int, r: Int): Int = when (op) {
        BinaryOp.ADD -> l + r
        BinaryOp.SUB -> l - r
        BinaryOp.MUL -> l * r
        BinaryOp.DIV -> { if (r == 0) throw MsiRuntimeException("division by zero"); l / r }
        BinaryOp.REM -> { if (r == 0) throw MsiRuntimeException("division by zero"); l % r }
        BinaryOp.BIT_AND -> l and r
        BinaryOp.BIT_OR -> l or r
        BinaryOp.BIT_XOR -> l xor r
        BinaryOp.SHL -> l shl r
        BinaryOp.SHR -> l shr r
        else -> throw MsiRuntimeException("invalid operator ${op.symbol}")
    }

    private fun long(op: BinaryOp, l: Long, r: Long): Long = when (op) {
        BinaryOp.ADD -> l + r
        BinaryOp.SUB -> l - r
        BinaryOp.MUL -> l * r
        BinaryOp.DIV -> { if (r == 0L) throw MsiRuntimeException("division by zero"); l / r }
        BinaryOp.REM -> { if (r == 0L) throw MsiRuntimeException("division by zero"); l % r }
        BinaryOp.BIT_AND -> l and r
        BinaryOp.BIT_OR -> l or r
        BinaryOp.BIT_XOR -> l xor r
        BinaryOp.SHL -> l shl r.toInt()
        BinaryOp.SHR -> l shr r.toInt()
        else -> throw MsiRuntimeException("invalid operator ${op.symbol}")
    }

    private fun double(op: BinaryOp, l: Double, r: Double): Double = when (op) {
        BinaryOp.ADD -> l + r
        BinaryOp.SUB -> l - r
        BinaryOp.MUL -> l * r
        BinaryOp.DIV -> l / r
        BinaryOp.REM -> l % r
        else -> throw MsiRuntimeException("invalid operator ${op.symbol}")
    }

    private fun convert(v: Any?, from: Type, to: Type): Any? {
        if (from == to) return v
        if (to == Type.Str) return when (from) {
            Type.F64 -> formatDouble(v as Double)
            else -> v.toString()
        }
        val asDouble = when (from) { Type.I32 -> (v as Int).toDouble(); Type.I64 -> (v as Long).toDouble(); else -> v as Double }
        return when (to) {
            Type.I32 -> if (from == Type.I64) (v as Long).toInt() else asDouble.toInt()
            Type.I64 -> if (from == Type.I32) (v as Int).toLong() else asDouble.toLong()
            else -> asDouble
        }
    }

    public companion object {
        /** The value of a variable that was declared without an initial value. */
        public fun zeroOf(type: Type): Any? = when (type) {
            Type.Unit -> Unit
            Type.Bool -> false
            Type.I32 -> 0
            Type.I64 -> 0L
            Type.F64 -> 0.0
            Type.Str -> ""
            is Type.Struct, is Type.Union, is Type.Array, is Type.Function, is Type.Param, is Type.Ref, Type.Invalid -> null
        }

        /** Doubles as text, the same on every platform: always with a fraction (`2.0`, `0.5`). */
        public fun formatDouble(d: Double): String {
            if (d.isNaN() || d.isInfinite()) return d.toString()
            val s = d.toString()
            return if ('.' in s || 'e' in s || 'E' in s) s else "$s.0"
        }
    }
}
