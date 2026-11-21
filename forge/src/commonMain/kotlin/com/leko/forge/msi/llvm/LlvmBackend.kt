package com.leko.forge.msi.llvm

import com.leko.forge.identityMap
import com.leko.forge.msi.*

/**
 * Translates the module to LLVM IR (text), to be compiled to native code with clang together with
 * [LLVM_RUNTIME_C]:
 *
 * ```
 * clang -O0 module.ll msi_runtime.c host.c -o program
 * ```
 *
 * Every MSI value is one 64 bit word (`i64`): integers (I32 sign extended), booleans (0/1), the bits of
 * doubles, and pointers to heap objects (strings, structs, unions, arrays and closures), whose first word
 * is a type id. This uniform representation makes generics free. Exceptions use `setjmp`/`longjmp`
 * (functions with a `try` are compiled without optimizations so locals survive the jump).
 *
 * Memory: values of [MemoryKind.OWNED] and [MemoryKind.VALUE] types are freed deterministically when
 * their owner goes out of scope, is overwritten or discarded (moves null the source). All other heap data
 * is reclaimed by a conservative mark and sweep collector in the runtime. References ([Borrow]) are plain
 * addresses; the ownership checker guarantees they never outlive their data. Exceptions skip the drops of
 * the frames they leave (that memory is not reclaimed).
 *
 * The host links against these symbols:
 * - `int64_t msi_f_<name>(int64_t...)`: the module's functions
 * - `void msi_init(void)`: initializes the globals; call it first
 * - `int64_t msi_ext_<name>(int64_t...)`: implement one per external function. Strings can be read with
 *   `const char* msi_cstr(int64_t)` and created with `int64_t msi_str(const char*, int64_t length)`.
 *
 * @throws IllegalArgumentException if the module has problems (see [validate]).
 */
public fun Module.toLlvm(): String {
    val problems = validate()
    require(problems.isEmpty()) { "the module has problems:\n" + problems.joinToString("\n") { "  $it" } }
    return LlvmGenerator(this).generate()
}

private fun sanitize(name: String) = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

private class LlvmGenerator(val module: Module) {
    private val globals = StringBuilder()
    private val functions = StringBuilder()
    private var stringCounter = 0
    private var lambdaCounter = 0
    private val functionRefs = HashMap<String, String>()
    private val typeIds = HashMap<String, Int>()

    init {
        var id = 16
        for (s in module.structs) typeIds["struct ${s.name}"] = id++
        for (u in module.unions) typeIds["union ${u.name}"] = id++
    }

    private fun typeId(t: Type): Int = when (t) {
        is Type.Struct -> typeIds.getValue("struct ${t.name}")
        is Type.Union -> typeIds.getValue("union ${t.name}")
        Type.Str -> 1
        else -> error("no type id for $t")
    }

    fun generate(): String {
        for (g in module.globals) globals.append("@msi_g_${sanitize(g.name)} = global i64 0\n")
        for (f in module.functions) function(f)
        val init = FunctionContext("define void @msi_init()", emptyList(), optnone = false)
        for (g in module.globals) init.inst("call void @msi_gc_root(ptr @msi_g_${sanitize(g.name)})")
        for (g in module.globals) {
            val v = g.init?.let { init.expr(it) } ?: init.zero(g.type)
            init.inst("store i64 $v, ptr @msi_g_${sanitize(g.name)}")
        }
        init.finish("ret void")
        functions.append(init.text())
        return HEADER + globals + "\n" + functions
    }

    private fun stringConstant(s: String): Pair<String, Int> {
        val bytes = s.encodeToByteArray()
        val name = "@.str.${stringCounter++}"
        val escaped = buildString {
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c >= 0x20 && c < 0x7f && c != '"'.code && c != '\\'.code) append(c.toChar())
                else append('\\').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
        globals.append("$name = private unnamed_addr constant [${bytes.size + 1} x i8] c\"$escaped\\00\"\n")
        return name to bytes.size
    }

    private fun hasTry(node: Any): Boolean = node is Try || com.leko.forge.childrenOf(node).any { it !is Lambda && hasTry(it) }

    private fun function(f: Func) {
        val params = f.params.mapIndexed { i, _ -> "i64 %a$i" }
        val name = "@msi_f_${sanitize(f.name)}"
        if (f.body == null) {
            val ext = "@msi_ext_${sanitize(f.name)}"
            globals.append("declare i64 $ext(${f.params.joinToString { "i64" }})\n")
            functions.append("define i64 $name(${params.joinToString()}) {\nentry:\n  %r = call i64 $ext(${f.params.indices.joinToString { "i64 %a$it" }})\n  ret i64 %r\n}\n\n")
            return
        }
        val c = FunctionContext("define i64 $name(${params.joinToString()})", f.params, optnone = hasTry(f.body))
        c.beginFunctionScope()
        f.params.forEachIndexed { i, p -> c.declare(p, "%a$i") }
        c.block(f.body)
        c.endFunction("ret i64 0")
        functions.append(c.text())
    }

    /** A function calling the module function [f], for function values: `i64 (closure, args...)`. */
    private fun functionRef(f: Func): String = functionRefs.getOrPut(f.name) {
        val name = "@msi_r_${sanitize(f.name)}"
        val args = f.params.indices.joinToString { "i64 %a$it" }
        functions.append("define i64 $name(i64 %self${if (args.isEmpty()) "" else ", $args"}) {\nentry:\n" +
            "  %r = call i64 @msi_f_${sanitize(f.name)}($args)\n  ret i64 %r\n}\n\n")
        name
    }

    private fun lambdaFunction(lambda: Lambda): String {
        val name = "@msi_l${lambdaCounter++}"
        val params = listOf("i64 %self") + lambda.params.indices.map { "i64 %a$it" }
        val c = FunctionContext("define i64 $name(${params.joinToString()})", lambda.params, optnone = hasTry(lambda.body))
        val self = c.fresh()
        c.inst("$self = inttoptr i64 %self to ptr")
        lambda.captures.forEachIndexed { i, v ->
            val slot = c.fresh()
            c.inst("$slot = getelementptr i64, ptr $self, i64 ${2 + i}")
            val box = c.fresh()
            c.inst("$box = load i64, ptr $slot")
            c.bindBox(v, box)
        }
        c.beginFunctionScope()
        lambda.params.forEachIndexed { i, p -> c.declare(p, "%a$i") }
        c.block(lambda.body)
        c.endFunction("ret i64 0")
        functions.append(c.text())
        return name
    }

    private fun linear(t: Type) = module.isLinear(t)

    private val dropFunctions = HashMap<String, String>()
    private val copyFunctions = HashMap<String, String>()

    /** The fields of a value or owned type, per variant for unions (struct: one entry with tag -1). Words start after the header. */
    private fun layouts(t: Type): List<Triple<Int, Int, List<Field>>> = when (t) {
        is Type.Struct -> listOf(Triple(-1, 1, module.structsByName.getValue(t.name).fields))
        is Type.Union -> module.unionsByName.getValue(t.name).variants.mapIndexed { i, v -> Triple(i, 2, v.fields) }
        else -> emptyList()
    }

    private fun typeKey(t: Type) = sanitize(if (t is Type.Struct) t.name else (t as Type.Union).name)

    /** `void @msi_drop_<T>(i64)`: drops the linear fields, then frees the object (null is ignored). */
    private fun dropFunction(t: Type): String = dropFunctions.getOrPut(typeKey(t)) {
        val name = "@msi_drop_${typeKey(t)}"
        val f = StringBuilder("define void $name(i64 %o) {\nentry:\n  %null = icmp eq i64 %o, 0\n  br i1 %null, label %done, label %live\nlive:\n  %p = inttoptr i64 %o to ptr\n")
        var n = 0
        val tagged = t is Type.Union
        if (tagged) f.append("  %tagp = getelementptr i64, ptr %p, i64 1\n  %tag = load i64, ptr %tagp\n")
        for ((tag, offset, fields) in layouts(t)) {
            val linearFields = fields.withIndex().filter { linear(it.value.type) }
            if (linearFields.isEmpty()) continue
            if (tagged) f.append("  %is$n = icmp eq i64 %tag, $tag\n  br i1 %is$n, label %v$n, label %n$n\nv$n:\n")
            for ((i, field) in linearFields) {
                f.append("  %fp$n$i = getelementptr i64, ptr %p, i64 ${offset + i}\n  %f$n$i = load i64, ptr %fp$n$i\n")
                f.append("  call void ${dropFunction(field.type)}(i64 %f$n$i)\n")
            }
            if (tagged) f.append("  br label %n$n\nn$n:\n")
            n++
        }
        f.append("  call void @msi_free_linear(i64 %o)\n  br label %done\ndone:\n  ret void\n}\n\n")
        functions.append(f)
        name
    }

    /** `i64 @msi_copy_<T>(i64)`: a deep copy of a value type (value fields are copied too). */
    private fun copyFunction(t: Type): String = copyFunctions.getOrPut(typeKey(t)) {
        val name = "@msi_copy_${typeKey(t)}"
        val f = StringBuilder("define i64 $name(i64 %o) {\nentry:\n  %null = icmp eq i64 %o, 0\n  br i1 %null, label %none, label %live\nnone:\n  ret i64 0\nlive:\n  %p = inttoptr i64 %o to ptr\n")
        val tagged = t is Type.Union
        if (tagged) f.append("  %tagp = getelementptr i64, ptr %p, i64 1\n  %tag = load i64, ptr %tagp\n")
        var n = 0
        for ((tag, offset, fields) in layouts(t)) {
            if (tagged) f.append("  %is$n = icmp eq i64 %tag, $tag\n  br i1 %is$n, label %v$n, label %n$n\nv$n:\n")
            val words = offset + fields.size
            f.append("  %c$n = call i64 @msi_alloc_linear(i64 $words)\n  %cp$n = inttoptr i64 %c$n to ptr\n")
            for (w in 0 until words) {
                f.append("  %s$n$w = getelementptr i64, ptr %p, i64 $w\n  %x$n$w = load i64, ptr %s$n$w\n")
                val field = if (w >= offset) fields[w - offset] else null
                val value = if (field != null && module.kindOf(field.type) == MemoryKind.VALUE) {
                    f.append("  %y$n$w = call i64 ${copyFunction(field.type)}(i64 %x$n$w)\n"); "%y$n$w"
                } else "%x$n$w"
                f.append("  %d$n$w = getelementptr i64, ptr %cp$n, i64 $w\n  store i64 $value, ptr %d$n$w\n")
            }
            f.append("  ret i64 %c$n\n")
            if (tagged) f.append("n$n:\n")
            n++
        }
        if (tagged) f.append("  ret i64 0\n")
        f.append("}\n\n")
        functions.append(f)
        name
    }

    private class LoopLabels(val again: String, val exit: String, val tryDepth: Int)

    /** Owned and value variables declared in a block: dropped when the block is left. */
    private class Scope { val variables = ArrayList<Variable>() }

    private class TryContext(val finally: (() -> Unit)?) {
        /** Whether a setjmp handler of this try is pushed at the current point. */
        var handlerActive = false
    }

    private inner class FunctionContext(val header: String, params: List<Param>, val optnone: Boolean) {
        private val allocas = StringBuilder()
        private val body = StringBuilder()
        private var counter = 0
        private var terminated = false
        private val slots = identityMap<Variable, String>()
        private val loops = identityMap<Loop, LoopLabels>()
        /** Scopes and tries, innermost last: leaving them drops values and runs finally code. */
        private val exits = ArrayList<Any>()

        fun beginFunctionScope() { exits += Scope() }

        fun endFunction(defaultReturn: String) {
            if (!terminated) (exits.firstOrNull() as? Scope)?.let(::dropAll)
            finish(defaultReturn)
        }

        private fun dropAll(scope: Scope) { for (v in scope.variables.asReversed()) drop(v.type, load(v)) }

        fun drop(t: Type, value: String) { if (linear(t)) inst("call void ${dropFunction(t)}(i64 $value)") }

        fun fresh(): String = "%t${counter++}"
        fun freshLabel(): String = "L${counter++}"

        fun inst(text: String) {
            if (terminated) label(freshLabel()) // dead code after a jump still needs a block
            body.append("  ").append(text).append('\n')
        }

        fun terminate(text: String) {
            inst(text)
            terminated = true
        }

        fun label(name: String) {
            if (!terminated) body.append("  br label %$name\n")
            body.append("$name:\n")
            terminated = false
        }

        fun finish(defaultReturn: String) {
            if (!terminated) body.append("  $defaultReturn\n")
        }

        fun text(): String = "$header ${if (optnone) "#0 " else ""}{\nentry:\n$allocas  br label %start\nstart:\n$body}\n\n"

        private fun alloca(): String = "%v${counter++}".also { allocas.append("  $it = alloca i64\n") }

        private fun captured(v: Variable) = module.isCaptured(v)

        /** A new variable with the initial word [value]. */
        fun declare(v: Variable, value: String) {
            val slot = alloca()
            slots[v] = slot
            if (linear(v.type)) (exits.lastOrNull { it is Scope } as? Scope)?.variables?.add(v)
            if (captured(v)) {
                val box = fresh()
                inst("$box = call i64 @msi_alloc(i64 1)")
                inst("store i64 $box, ptr $slot")
                storeInBox(box, value)
            } else inst("store i64 $value, ptr $slot")
        }

        /** A captured variable of a lambda: [box] is the address of its cell. */
        fun bindBox(v: Variable, box: String) {
            val slot = alloca()
            slots[v] = slot
            inst("store i64 $box, ptr $slot")
        }

        private fun storeInBox(box: String, value: String) {
            val p = fresh()
            inst("$p = inttoptr i64 $box to ptr")
            inst("store i64 $value, ptr $p")
        }

        fun load(v: Variable): String {
            if (v is Global) return fresh().also { inst("$it = load i64, ptr @msi_g_${sanitize(v.name)}") }
            val slot = slots[v] ?: error("no slot for ${v.name}")
            val value = fresh()
            inst("$value = load i64, ptr $slot")
            if (!captured(v)) return value
            val p = fresh()
            inst("$p = inttoptr i64 $value to ptr")
            return fresh().also { inst("$it = load i64, ptr $p") }
        }

        fun store(v: Variable, value: String) {
            if (v is Global) return inst("store i64 $value, ptr @msi_g_${sanitize(v.name)}")
            val slot = slots[v] ?: error("no slot for ${v.name}")
            if (captured(v)) {
                val box = fresh()
                inst("$box = load i64, ptr $slot")
                storeInBox(box, value)
            } else inst("store i64 $value, ptr $slot")
        }

        fun zero(t: Type): String = if (t == Type.Str) string("") else "0"

        private fun string(s: String): String {
            val (name, length) = stringConstant(s)
            return fresh().also { inst("$it = call i64 @msi_str(ptr $name, i64 $length)") }
        }

        private fun word(obj: String, index: Int): String {
            val p = fresh()
            inst("$p = inttoptr i64 $obj to ptr")
            val slot = fresh()
            inst("$slot = getelementptr i64, ptr $p, i64 $index")
            return slot
        }

        private fun loadWord(obj: String, index: Int): String = fresh().also { inst("$it = load i64, ptr ${word(obj, index)}") }

        private fun storeWord(obj: String, index: Int, value: String) = inst("store i64 $value, ptr ${word(obj, index)}")

        private fun allocObject(typeId: Int, words: List<String>, linear: Boolean = false): String {
            val obj = fresh()
            inst("$obj = call i64 @msi_alloc${if (linear) "_linear" else ""}(i64 ${1 + words.size})")
            storeWord(obj, 0, "$typeId")
            words.forEachIndexed { i, v -> storeWord(obj, i + 1, v) }
            return obj
        }

        // ---- statements --------------------------------------------------------------------

        fun block(b: Block) {
            val scope = Scope()
            exits += scope
            b.stmts.forEach(::stmt)
            if (!terminated) dropAll(scope)
            exits.removeAt(exits.size - 1)
        }

        fun stmt(s: Stmt) {
            when (s) {
                is Block -> block(s)
                is Let -> declare(s, s.init?.let { expr(it) } ?: zero(s.type))
                is Assign -> when (val target = s.target) {
                    is Local -> {
                        val v = target.variable!!
                        val value = expr(s.value)
                        drop(v.type, load(v)) // the old value
                        store(v, value)
                    }
                    is Get -> {
                        val value = expr(s.value)
                        val obj = checked(expr(target.target))
                        val index = 1 + target.struct!!.fields.indexOfFirst { it.name == target.field }
                        if (linear(target.type)) drop(target.type, loadWord(obj, index))
                        storeWord(obj, index, value)
                    }
                    is Deref -> {
                        val value = expr(s.value)
                        val p = fresh()
                        inst("$p = inttoptr i64 ${expr(target.ref)} to ptr")
                        inst("store i64 $value, ptr $p")
                    }
                    is Index -> {
                        val value = expr(s.value)
                        val slot = arraySlot(target)
                        inst("store i64 $value, ptr $slot")
                    }
                    else -> error("not assignable")
                }
                is Branch -> {
                    val then = freshLabel(); val otherwise = freshLabel(); val end = freshLabel()
                    condition(s.condition, then, otherwise)
                    label(then); block(s.then); terminate("br label %$end")
                    label(otherwise); s.alternative?.let(::block); terminate("br label %$end")
                    label(end)
                }
                is Loop -> {
                    val labels = LoopLabels(freshLabel(), freshLabel(), exits.size)
                    loops[s] = labels
                    val body = freshLabel()
                    label(labels.again)
                    if (s.condition != null) condition(s.condition, body, labels.exit) else terminate("br label %$body")
                    label(body); block(s.body); terminate("br label %${labels.again}")
                    label(labels.exit)
                }
                is Break -> { val l = loops[s.target!!]!!; leave(l.tryDepth); terminate("br label %${l.exit}") }
                is Continue -> { val l = loops[s.target!!]!!; leave(l.tryDepth); terminate("br label %${l.again}") }
                is Return -> {
                    val value = s.value?.let { expr(it) } ?: "0"
                    leave(0)
                    terminate("ret i64 $value")
                }
                is Eval -> drop(s.expr.type, expr(s.expr)) // a discarded owned result is dropped
                is Match -> match(s)
                is Throw -> {
                    inst("call void @msi_throw(i64 ${expr(s.value)})")
                    terminate("unreachable")
                }
                is Try -> tryStmt(s)
                is Access -> {
                    val saved = alloca()
                    inst("store i64 ${checked(expr(s.target))}, ptr $saved")
                    fun call(function: String) {
                        val o = fresh()
                        inst("$o = load i64, ptr $saved")
                        inst("call void $function(i64 $o, i64 ${if (s.mutable) 1 else 0})")
                    }
                    call("@msi_access_begin")
                    tryBlock(s.body, emptyList()) { call("@msi_access_end") }
                }
            }
        }

        /** Leaves the scopes and tries above [depth] (innermost first): drops owned values, pops handlers, runs finally code. */
        private fun leave(depth: Int) {
            val exited = exits.subList(depth, exits.size).toList()
            val saved = ArrayList(exits)
            val states = exited.filterIsInstance<TryContext>().map { it to it.handlerActive }
            for (e in exited.asReversed()) {
                while (exits.isNotEmpty() && exits.last() !== e) exits.removeAt(exits.size - 1)
                exits.removeAt(exits.size - 1)
                when (e) {
                    is Scope -> dropAll(e)
                    is TryContext -> {
                        if (e.handlerActive) inst("call void @msi_try_pop()")
                        e.handlerActive = false
                        e.finally?.invoke()
                    }
                }
            }
            exits.clear(); exits.addAll(saved)
            states.forEach { (t, active) -> t.handlerActive = active }
        }

        /** Pushes a setjmp handler; continues in [normal] or, after a throw, in [caught]. */
        private fun protect(normal: String, caught: String) {
            val buffer = fresh()
            inst("$buffer = call ptr @msi_try_push()")
            val r = fresh()
            inst("$r = call i32 @_setjmp(ptr $buffer) #1")
            val zero = fresh()
            inst("$zero = icmp eq i32 $r, 0")
            terminate("br i1 $zero, label %$normal, label %$caught")
        }

        private fun tryStmt(s: Try) = tryBlock(s.body, s.catches, s.finally?.let { f -> { block(f) } })

        /** A try with [catches] and generated [fin] code that runs on every exit (like `finally`). */
        private fun tryBlock(body: Block, catches: List<Catch>, fin: (() -> Unit)?) {
            val t = TryContext(fin)
            val bodyLabel = freshLabel(); val caught = freshLabel(); val end = freshLabel()
            protect(bodyLabel, caught)

            label(bodyLabel)
            exits += t
            t.handlerActive = true
            block(body)
            if (!terminated) inst("call void @msi_try_pop()")
            t.handlerActive = false
            exits.removeAt(exits.size - 1)
            fin?.invoke()
            terminate("br label %$end")

            // a throw popped the handler already
            label(caught)
            val value = fresh()
            inst("$value = call i64 @msi_thrown()")
            if (catches.isNotEmpty()) {
                val handled = freshLabel(); val unhandled = freshLabel()
                val inCatch = freshLabel()
                val saved = alloca()
                inst("store i64 $value, ptr $saved")
                if (fin != null) protect(inCatch, unhandled) else terminate("br label %$inCatch")
                label(inCatch)
                exits += t
                t.handlerActive = fin != null
                val thrown = fresh()
                inst("$thrown = load i64, ptr $saved")
                val type = fresh()
                inst("$type = call i64 @msi_type_of(i64 $thrown)")
                for (c in catches) {
                    val matched = freshLabel(); val next = freshLabel()
                    val is_ = fresh()
                    inst("$is_ = icmp eq i64 $type, ${typeId(c.type)}")
                    terminate("br i1 $is_, label %$matched, label %$next")
                    label(matched)
                    declare(c, thrown)
                    block(c.body)
                    if (!terminated) {
                        if (t.handlerActive) inst("call void @msi_try_pop()")
                        t.handlerActive = false
                        exits.removeAt(exits.size - 1)
                        fin?.invoke()
                        terminate("br label %$end")
                        exits += t
                        t.handlerActive = fin != null
                    }
                    label(next)
                }
                // no catch matched: run finally (below) and throw again
                if (t.handlerActive) inst("call void @msi_try_pop()")
                exits.removeAt(exits.size - 1)
                t.handlerActive = false
                terminate("br label %$handled")
                label(unhandled) // a catch threw: its handler is used up
                terminate("br label %$handled")
                label(handled)
                // the exception to throw again: the original one, or the one a catch threw.
                // Read it before finally runs, which may throw and catch exceptions itself.
                val pending = alloca()
                inst("store i64 ${call("@msi_thrown_now")}, ptr $pending")
                fin?.invoke()
                val again = fresh()
                inst("$again = load i64, ptr $pending")
                inst("call void @msi_throw(i64 $again)")
                terminate("unreachable")
            } else {
                fin?.invoke()
                inst("call void @msi_throw(i64 $value)")
                terminate("unreachable")
            }
            label(end)
        }

        private fun match(s: Match) {
            val subject = checked(expr(s.subject))
            val tag = loadWord(subject, 1)
            val union = s.union!!
            val end = freshLabel()
            for (c in s.cases) {
                val matched = freshLabel(); val next = freshLabel()
                val is_ = fresh()
                inst("$is_ = icmp eq i64 $tag, ${union.variants.indexOfFirst { it.name == c.variant }}")
                terminate("br i1 $is_, label %$matched, label %$next")
                label(matched)
                c.variables.forEachIndexed { i, v -> declare(v, loadWord(subject, 2 + i)) }
                block(c.body)
                terminate("br label %$end")
                label(next)
            }
            s.otherwise?.let(::block)
            terminate("br label %$end")
            label(end)
        }

        private fun checked(obj: String): String = fresh().also { inst("$it = call i64 @msi_check(i64 $obj)") }

        private fun arraySlot(e: Index): String {
            val array = expr(e.array)
            val index = expr(e.index)
            return fresh().also { inst("$it = call ptr @msi_array_slot(i64 $array, i64 $index)") }
        }

        // ---- expressions -------------------------------------------------------------------

        fun condition(e: Expr, then: String, otherwise: String) {
            val v = expr(e)
            val b = fresh()
            inst("$b = icmp ne i64 $v, 0")
            terminate("br i1 $b, label %$then, label %$otherwise")
        }

        private fun bool(i1: String): String = fresh().also { inst("$it = zext i1 $i1 to i64") }

        private fun toDouble(v: String): String = fresh().also { inst("$it = bitcast i64 $v to double") }

        private fun fromDouble(v: String): String = fresh().also { inst("$it = bitcast double $v to i64") }

        private fun trunc32(v: String): String = fresh().also { inst("$it = trunc i64 $v to i32") }

        private fun sext32(v: String): String = fresh().also { inst("$it = sext i32 $v to i64") }

        private fun call(function: String, vararg args: String): String =
            fresh().also { inst("$it = call i64 $function(${args.joinToString { "i64 $it" }})") }

        fun expr(e: Expr): String = when (e) {
            is IntLit -> if (e.type == Type.I32) "${e.value.toInt()}" else "${e.value}"
            is FloatLit -> "${e.value.toRawBits()}"
            is BoolLit -> if (e.value) "1" else "0"
            is StrLit -> string(e.value)
            is Local -> {
                val v = e.variable!!
                val value = load(v)
                if (e.isMove) store(v, "0") // moved out: nothing left to drop
                value
            }
            is Binary -> binary(e)
            is Unary -> {
                val v = expr(e.operand)
                when (e.op) {
                    UnaryOp.NOT -> fresh().also { inst("$it = xor i64 $v, 1") }
                    UnaryOp.BIT_NOT -> fresh().also { inst("$it = xor i64 $v, -1") }
                    UnaryOp.NEG -> when (e.type) {
                        Type.I32 -> sext32(fresh().also { inst("$it = sub i32 0, ${trunc32(v)}") })
                        Type.I64 -> fresh().also { inst("$it = sub i64 0, $v") }
                        else -> fromDouble(fresh().also { inst("$it = fneg double ${toDouble(v)}") })
                    }
                }
            }
            is Call -> {
                val args = e.args.map { expr(it) }
                call("@msi_f_${sanitize(e.function)}", *args.toTypedArray())
            }
            is New -> {
                val args = e.args.map { expr(it) }
                allocObject(typeId(e.type), args, linear(e.type))
            }
            is Get -> {
                val obj = checked(expr(e.target))
                loadWord(obj, 1 + e.struct!!.fields.indexOfFirst { it.name == e.field })
            }
            is NewArray -> {
                val size = expr(e.size)
                val zero = zero(e.element)
                call("@msi_array_new", size, zero)
            }
            is Index -> fresh().also { inst("$it = load i64, ptr ${arraySlot(e)}") }
            is Length -> call("@msi_array_length", expr(e.array))
            is Convert -> convert(expr(e.value), e.value.type, e.to)
            is Conditional -> {
                val result = alloca()
                val then = freshLabel(); val otherwise = freshLabel(); val end = freshLabel()
                condition(e.condition, then, otherwise)
                label(then); inst("store i64 ${expr(e.then)}, ptr $result"); terminate("br label %$end")
                label(otherwise); inst("store i64 ${expr(e.otherwise)}, ptr $result"); terminate("br label %$end")
                label(end)
                fresh().also { inst("$it = load i64, ptr $result") }
            }
            is Make -> {
                val args = e.args.map { expr(it) }
                val union = e.unionDef!!
                allocObject(typeId(e.type), listOf("${union.variants.indexOfFirst { it.name == e.variant }}") + args, linear(e.type))
            }
            is Is -> {
                val tag = loadWord(checked(expr(e.subject)), 1)
                bool(fresh().also { inst("$it = icmp eq i64 $tag, ${e.union!!.variants.indexOfFirst { v -> v.name == e.variant }}") })
            }
            is FuncRef -> {
                val fn = fresh()
                inst("$fn = ptrtoint ptr ${functionRef(e.callee!!)} to i64")
                allocObject(3, listOf(fn))
            }
            is Lambda -> {
                val fn = fresh()
                inst("$fn = ptrtoint ptr ${lambdaFunction(e)} to i64")
                val boxes = e.captures.map { v -> fresh().also { inst("$it = load i64, ptr ${slots[v] ?: error("${v.name} is not captured here")}") } }
                allocObject(3, listOf(fn) + boxes)
            }
            is Invoke -> {
                val closure = checked(expr(e.callee))
                val args = e.args.map { expr(it) }
                val fn = fresh()
                inst("$fn = inttoptr i64 ${loadWord(closure, 1)} to ptr")
                fresh().also { inst("$it = call i64 $fn(${(listOf(closure) + args).joinToString { a -> "i64 $a" }})") }
            }
            is Borrow -> when (val place = e.place) {
                is Local -> {
                    val v = place.variable!!
                    val slot = slots[v] ?: error("no slot for ${v.name}")
                    if (captured(v)) fresh().also { inst("$it = load i64, ptr $slot") } // the box
                    else fresh().also { inst("$it = ptrtoint ptr $slot to i64") }
                }
                is Get -> {
                    val obj = checked(expr(place.target))
                    val index = 1 + (place.struct ?: error("no struct")).fields.indexOfFirst { it.name == place.field }
                    fresh().also { inst("$it = ptrtoint ptr ${word(obj, index)} to i64") }
                }
                else -> expr((place as Deref).ref) // a reborrow is the same address
            }
            is Deref -> {
                val p = fresh()
                inst("$p = inttoptr i64 ${expr(e.ref)} to ptr")
                fresh().also { inst("$it = load i64, ptr $p") }
            }
            is Null -> "0"
            is Copy -> {
                val value = expr(e.value)
                if (module.kindOf(e.type) == MemoryKind.VALUE) call(copyFunction(e.type), value) else value
            }
        }

        private fun convert(v: String, from: Type, to: Type): String = when {
            from == to -> v
            to == Type.Str -> when (from) {
                Type.F64 -> call("@msi_f64_str", v)
                Type.Bool -> call("@msi_bool_str", v)
                else -> call("@msi_i64_str", v)
            }
            from == Type.I32 && to == Type.I64 -> v
            from == Type.I64 && to == Type.I32 -> sext32(trunc32(v))
            to == Type.F64 -> fromDouble(fresh().also { inst("$it = sitofp i64 $v to double") })
            from == Type.F64 && to == Type.I64 -> call("@msi_f2l", v)
            from == Type.F64 && to == Type.I32 -> call("@msi_f2i", v)
            else -> error("no conversion from $from to $to")
        }

        private fun binary(e: Binary): String {
            if (e.op == BinaryOp.AND || e.op == BinaryOp.OR) {
                val result = alloca()
                val right = freshLabel(); val end = freshLabel()
                val l = expr(e.left)
                inst("store i64 $l, ptr $result")
                val b = fresh()
                inst("$b = icmp ne i64 $l, 0")
                terminate(if (e.op == BinaryOp.AND) "br i1 $b, label %$right, label %$end" else "br i1 $b, label %$end, label %$right")
                label(right)
                inst("store i64 ${expr(e.right)}, ptr $result")
                terminate("br label %$end")
                label(end)
                return fresh().also { inst("$it = load i64, ptr $result") }
            }
            val t = e.left.type
            val l = expr(e.left)
            val r = expr(e.right)
            return when (e.op) {
                BinaryOp.EQ, BinaryOp.NE, BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> comparison(e.op, t, l, r)
                else -> when (t) {
                    Type.Str -> call("@msi_concat", l, r)
                    Type.F64 -> {
                        val op = when (e.op) { BinaryOp.ADD -> "fadd"; BinaryOp.SUB -> "fsub"; BinaryOp.MUL -> "fmul"; BinaryOp.DIV -> "fdiv"; else -> "frem" }
                        fromDouble(fresh().also { inst("$it = $op double ${toDouble(l)}, ${toDouble(r)}") })
                    }
                    Type.I32 -> when (e.op) {
                        BinaryOp.DIV -> call("@msi_div32", l, r)
                        BinaryOp.REM -> call("@msi_rem32", l, r)
                        else -> {
                            val a = trunc32(l)
                            var b = trunc32(r)
                            if (e.op == BinaryOp.SHL || e.op == BinaryOp.SHR) b = fresh().also { inst("$it = and i32 $b, 31") }
                            sext32(fresh().also { inst("$it = ${intOp(e.op)} i32 $a, $b") })
                        }
                    }
                    else -> when (e.op) {
                        BinaryOp.DIV -> call("@msi_div64", l, r)
                        BinaryOp.REM -> call("@msi_rem64", l, r)
                        BinaryOp.SHL, BinaryOp.SHR -> {
                            val b = fresh().also { inst("$it = and i64 $r, 63") }
                            fresh().also { inst("$it = ${intOp(e.op)} i64 $l, $b") }
                        }
                        else -> fresh().also { inst("$it = ${intOp(e.op)} i64 $l, $r") }
                    }
                }
            }
        }

        private fun intOp(op: BinaryOp) = when (op) {
            BinaryOp.ADD -> "add"; BinaryOp.SUB -> "sub"; BinaryOp.MUL -> "mul"
            BinaryOp.BIT_AND -> "and"; BinaryOp.BIT_OR -> "or"; BinaryOp.BIT_XOR -> "xor"
            BinaryOp.SHL -> "shl"; BinaryOp.SHR -> "ashr"
            else -> error("unreachable")
        }

        private fun comparison(op: BinaryOp, t: Type, l: String, r: String): String {
            val c = fresh()
            when (t) {
                Type.F64 -> {
                    val cond = when (op) { BinaryOp.EQ -> "oeq"; BinaryOp.NE -> "une"; BinaryOp.LT -> "olt"; BinaryOp.LE -> "ole"; BinaryOp.GT -> "ogt"; else -> "oge" }
                    inst("$c = fcmp $cond double ${toDouble(l)}, ${toDouble(r)}")
                }
                Type.Str -> if (op == BinaryOp.EQ || op == BinaryOp.NE) {
                    val eq = call("@msi_str_eq", l, r)
                    inst("$c = icmp ${if (op == BinaryOp.EQ) "ne" else "eq"} i64 $eq, 0")
                } else {
                    val cmp = call("@msi_str_cmp", l, r)
                    inst("$c = icmp ${signed(op)} i64 $cmp, 0")
                }
                else -> inst("$c = icmp ${signed(op)} i64 $l, $r")
            }
            return bool(c)
        }

        private fun signed(op: BinaryOp) = when (op) {
            BinaryOp.EQ -> "eq"; BinaryOp.NE -> "ne"; BinaryOp.LT -> "slt"; BinaryOp.LE -> "sle"; BinaryOp.GT -> "sgt"; else -> "sge"
        }
    }

    companion object {
        val HEADER = """
; generated by Forge from an MSI module
declare i64 @msi_alloc(i64)
declare i64 @msi_alloc_linear(i64)
declare void @msi_free_linear(i64)
declare void @msi_gc_root(ptr)
declare void @msi_access_begin(i64, i64)
declare void @msi_access_end(i64, i64)
declare i64 @msi_str(ptr, i64)
declare i64 @msi_concat(i64, i64)
declare i64 @msi_str_eq(i64, i64)
declare i64 @msi_str_cmp(i64, i64)
declare i64 @msi_i64_str(i64)
declare i64 @msi_f64_str(i64)
declare i64 @msi_bool_str(i64)
declare i64 @msi_array_new(i64, i64)
declare ptr @msi_array_slot(i64, i64)
declare i64 @msi_array_length(i64)
declare i64 @msi_check(i64)
declare i64 @msi_div64(i64, i64)
declare i64 @msi_rem64(i64, i64)
declare i64 @msi_div32(i64, i64)
declare i64 @msi_rem32(i64, i64)
declare i64 @msi_f2l(i64)
declare i64 @msi_f2i(i64)
declare ptr @msi_try_push()
declare void @msi_try_pop()
declare void @msi_throw(i64) noreturn
declare i64 @msi_thrown()
declare i64 @msi_type_of(i64)
declare i32 @_setjmp(ptr) returns_twice

define i64 @msi_thrown_now() {
entry:
  %v = call i64 @msi_thrown()
  ret i64 %v
}

attributes #0 = { noinline optnone }
attributes #1 = { returns_twice }

""".trimStart()
    }
}
