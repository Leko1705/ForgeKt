package com.leko.forge.msi.jvm

import com.leko.forge.identityMap
import com.leko.forge.msi.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.LabelNode
import java.lang.reflect.InvocationTargetException

/**
 * Translates the module to JVM classes (bytecode, Java 8 class files), as a map from internal class
 * name (`msi/M_demo`) to class file bytes:
 *
 * - the module becomes a class with a static method per function (`f_<name>`) and a static field per
 *   global (`g_<name>`), initialized in the static initializer
 * - structs become classes `S_<name>` with public fields `f_<field>`
 * - unions become an abstract class `U_<name>` with a subclass `U_<name>$<variant>` per variant
 * - lambdas and function references become classes implementing [MsiFunction]; captured variables are
 *   kept in shared one element boxes, so assignments are visible on both sides
 * - exceptions are thrown as [MsiThrowable]; `finally` blocks are inlined on every exit path
 * - generics are erased: type parameters become `Object`, values are boxed where needed, and arrays are
 *   `Object[]` with boxed elements
 *
 * External functions call the host implementations passed to [loadJvm].
 *
 * @throws IllegalArgumentException if the module has problems (see [validate]).
 */
public fun Module.toJvm(packageName: String = "msi"): Map<String, ByteArray> {
    val problems = validate()
    require(problems.isEmpty()) { "the module has problems:\n" + problems.joinToString("\n") { "  $it" } }
    return JvmGenerator(this, packageName.replace('.', '/')).generate()
}

/** Compiles the module with [toJvm] and loads it, so its functions can be called. */
public fun Module.loadJvm(externals: Map<String, (List<Any?>) -> Any?> = emptyMap(), packageName: String = "msi"): JvmProgram =
    JvmProgram(this, toJvm(packageName), packageName.replace('.', '/'), externals)

/** A module compiled to JVM classes and loaded into its own class loader. */
public class JvmProgram internal constructor(
    public val module: Module,
    public val classes: Map<String, ByteArray>,
    packagePath: String,
    externals: Map<String, (List<Any?>) -> Any?>,
) {
    private val loader = object : ClassLoader(JvmProgram::class.java.classLoader) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    /** The class with the module's functions and globals. */
    public val moduleClass: Class<*> = loader.loadClass("$packagePath/${moduleClassName(module)}".replace('/', '.'))

    init {
        moduleClass.getField("\$externals").set(null, externals)
    }

    /**
     * Calls the function [name]. Values are represented as by the [Interpreter], except arrays (`Object[]`),
     * structs and unions (instances of the generated classes) and functions ([MsiFunction]).
     * An uncaught MSI exception ends the call with [MsiThrownException].
     */
    public fun call(name: String, vararg args: Any?): Any? {
        val method = moduleClass.methods.firstOrNull { it.name == "f_${sanitize(name)}" } ?: throw IllegalArgumentException("no function '$name'")
        try {
            val result = method.invoke(null, *args)
            return if (method.returnType == Void.TYPE) Unit else result
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause is MsiThrowable) throw MsiThrownException(cause.value)
            throw cause
        }
    }
}

private fun sanitize(name: String) = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
private fun moduleClassName(module: Module) = "M_" + sanitize(module.name)

private const val OBJECT = "java/lang/Object"
private const val OBJECT_DESC = "Ljava/lang/Object;"
private const val BOX_DESC = "[Ljava/lang/Object;"
private val FUNCTION = MsiFunction::class.java.name.replace('.', '/')
private val THROWABLE = MsiThrowable::class.java.name.replace('.', '/')
private val RUNTIME = MsiRuntime::class.java.name.replace('.', '/')
private val REF = MsiRef::class.java.name.replace('.', '/')
private val BOX_REF = BoxRef::class.java.name.replace('.', '/')
private val FIELD_REF = FieldRef::class.java.name.replace('.', '/')
private val ACCESS = MsiAccess::class.java.name.replace('.', '/')

private class JvmGenerator(val module: Module, val pkg: String) {

    private val classes = LinkedHashMap<String, ByteArray>()
    private val superclasses = HashMap<String, String>()
    private val moduleClass = "$pkg/${moduleClassName(module)}"
    private var lambdaCounter = 0
    private val functionRefs = HashMap<String, String>()

    private fun structClass(name: String) = "$pkg/S_${sanitize(name)}"
    private fun unionClass(name: String) = "$pkg/U_${sanitize(name)}"
    private fun variantClass(union: String, variant: String) = "${unionClass(union)}\$${sanitize(variant)}"

    // ---- types -------------------------------------------------------------------------------

    /** The JVM descriptor of values of [t]; `V` for [Type.Unit]. Type parameters are erased to Object. */
    fun desc(t: Type): String = when (t) {
        Type.Unit -> "V"
        Type.Bool -> "Z"
        Type.I32 -> "I"
        Type.I64 -> "J"
        Type.F64 -> "D"
        Type.Str -> "Ljava/lang/String;"
        is Type.Struct -> "L${structClass(t.name)};"
        is Type.Union -> "L${unionClass(t.name)};"
        is Type.Array -> BOX_DESC
        is Type.Function -> "L$FUNCTION;"
        is Type.Param, Type.Invalid -> OBJECT_DESC
        is Type.Ref -> "L$REF;"
    }

    /** The descriptor for storing values of [t] in variables and fields: never `V`. */
    fun storage(t: Type): String = desc(t).let { if (it == "V") OBJECT_DESC else it }

    fun methodDesc(params: List<Type>, result: Type) = params.joinToString("", "(", ")") { storage(it) } + desc(result)

    // ---- classes -----------------------------------------------------------------------------

    fun generate(): Map<String, ByteArray> {
        for (u in module.unions) {
            superclasses[unionClass(u.name)] = OBJECT
            for (v in u.variants) superclasses[variantClass(u.name, v.name)] = unionClass(u.name)
        }
        for (s in module.structs) generateStruct(s)
        for (u in module.unions) generateUnion(u)
        generateModule()
        return classes
    }

    private fun writer(): ClassWriter = object : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
        override fun getCommonSuperClass(type1: String, type2: String): String {
            val chain1 = ancestors(type1)
            return ancestors(type2).firstOrNull { it in chain1 } ?: OBJECT
        }
    }

    private fun ancestors(type: String): List<String> {
        val result = ArrayList<String>()
        var current: String? = type
        while (current != null) {
            result += current
            current = superclasses[current] ?: runCatching {
                Class.forName(current.replace('/', '.'), false, JvmGenerator::class.java.classLoader).superclass?.name?.replace('.', '/')
            }.getOrNull()
        }
        if (OBJECT !in result) result += OBJECT
        return result
    }

    private fun simpleClass(name: String, superName: String, fields: List<Field>, access: Int = ACC_PUBLIC or ACC_SUPER, interfaces: Array<String>? = null) {
        val cw = writer()
        cw.visit(V1_8, access, name, null, superName, interfaces)
        for (f in fields) cw.visitField(ACC_PUBLIC, "f_${sanitize(f.name)}", storage(f.type), null, null).visitEnd()
        val ctor = cw.visitMethod(ACC_PUBLIC, "<init>", fields.joinToString("", "(", ")V") { storage(it.type) }, null, null)
        ctor.visitCode()
        ctor.visitVarInsn(ALOAD, 0)
        ctor.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false)
        var slot = 1
        for (f in fields) {
            val d = storage(f.type)
            ctor.visitVarInsn(ALOAD, 0)
            ctor.visitVarInsn(loadOp(d), slot)
            ctor.visitFieldInsn(PUTFIELD, name, "f_${sanitize(f.name)}", d)
            slot += size(d)
        }
        ctor.visitInsn(RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()
        cw.visitEnd()
        classes[name] = cw.toByteArray()
    }

    private fun generateStruct(s: Struct) = simpleClass(structClass(s.name), OBJECT, s.fields)

    private fun generateUnion(u: Union) {
        simpleClass(unionClass(u.name), OBJECT, emptyList(), ACC_PUBLIC or ACC_SUPER or ACC_ABSTRACT)
        for (v in u.variants) simpleClass(variantClass(u.name, v.name), unionClass(u.name), v.fields)
    }

    private fun generateModule() {
        val cw = writer()
        cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER, moduleClass, null, OBJECT, null)
        cw.visitField(ACC_PUBLIC or ACC_STATIC, "\$externals", "Ljava/util/Map;", null, null).visitEnd()
        for (g in module.globals) cw.visitField(ACC_PUBLIC or ACC_STATIC, "g_${sanitize(g.name)}", storage(g.type), null, null).visitEnd()
        for (f in module.functions) {
            val mn = MethodNode(ACC_PUBLIC or ACC_STATIC, "f_${sanitize(f.name)}", methodDesc(f.params.map { it.type }, f.returnType), null, null)
            val m = MethodContext(mn, f.returnType)
            if (f.body == null) external(m, f) else {
                for (p in f.params) m.param(p)
                m.block(f.body)
                m.endOfBody()
            }
            mn.visitMaxs(0, 0)
            mn.accept(cw)
        }
        for (s in module.structs.filter { it.kind == MemoryKind.VALUE }) copyStruct(cw, s)
        for (u in module.unions.filter { it.kind == MemoryKind.VALUE }) copyUnion(cw, u)
        if (module.globals.isNotEmpty()) {
            val mn = MethodNode(ACC_STATIC, "<clinit>", "()V", null, null)
            val m = MethodContext(mn, Type.Unit)
            for (g in module.globals) {
                val d = storage(g.type)
                g.init?.let { m.exprAs(it, d) } ?: m.zero(g.type)
                mn.visitFieldInsn(PUTSTATIC, moduleClass, "g_${sanitize(g.name)}", d)
            }
            mn.visitInsn(RETURN)
            mn.visitMaxs(0, 0)
            mn.accept(cw)
        }
        cw.visitEnd()
        classes[moduleClass] = cw.toByteArray()
    }

    /** Copies a field value: value types deeply, everything else as it is. */
    private fun copyValue(mv: MethodNode, t: Type) {
        val d = storage(t)
        when (module.kindOf(t)) {
            MemoryKind.VALUE -> mv.visitMethodInsn(INVOKESTATIC, moduleClass, if (t is Type.Struct) "copy\$${sanitize(t.name)}" else "copy\$${sanitize((t as Type.Union).name)}", "($d)$d", false)
            else -> {}
        }
    }

    private fun copyStruct(cw: ClassWriter, s: Struct) {
        val owner = structClass(s.name)
        val d = "L$owner;"
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "copy\$${sanitize(s.name)}", "($d)$d", null, null) as org.objectweb.asm.MethodVisitor
        mv.visitCode()
        mv.visitTypeInsn(NEW, owner)
        mv.visitInsn(DUP)
        for (f in s.fields) {
            mv.visitVarInsn(ALOAD, 0)
            mv.visitFieldInsn(GETFIELD, owner, "f_${sanitize(f.name)}", storage(f.type))
            if (module.kindOf(f.type) == MemoryKind.VALUE) {
                val fd = storage(f.type)
                mv.visitMethodInsn(INVOKESTATIC, moduleClass, "copy\$${sanitize((f.type as? Type.Struct)?.name ?: (f.type as Type.Union).name)}", "($fd)$fd", false)
            }
        }
        mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", s.fields.joinToString("", "(", ")V") { storage(it.type) }, false)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun copyUnion(cw: ClassWriter, u: Union) {
        val d = "L${unionClass(u.name)};"
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "copy\$${sanitize(u.name)}", "($d)$d", null, null)
        mv.visitCode()
        for (v in u.variants) {
            val owner = variantClass(u.name, v.name)
            val next = Label()
            mv.visitVarInsn(ALOAD, 0)
            mv.visitTypeInsn(INSTANCEOF, owner)
            mv.visitJumpInsn(IFEQ, next)
            mv.visitTypeInsn(NEW, owner)
            mv.visitInsn(DUP)
            for (f in v.fields) {
                mv.visitVarInsn(ALOAD, 0)
                mv.visitTypeInsn(CHECKCAST, owner)
                mv.visitFieldInsn(GETFIELD, owner, "f_${sanitize(f.name)}", storage(f.type))
                if (module.kindOf(f.type) == MemoryKind.VALUE) {
                    val fd = storage(f.type)
                    mv.visitMethodInsn(INVOKESTATIC, moduleClass, "copy\$${sanitize((f.type as? Type.Struct)?.name ?: (f.type as Type.Union).name)}", "($fd)$fd", false)
                }
            }
            mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", v.fields.joinToString("", "(", ")V") { storage(it.type) }, false)
            mv.visitInsn(ARETURN)
            mv.visitLabel(next)
        }
        mv.visitVarInsn(ALOAD, 0) // null stays null
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun external(m: MethodContext, f: Func) {
        val mv = m.mv
        mv.visitFieldInsn(GETSTATIC, moduleClass, "\$externals", "Ljava/util/Map;")
        mv.visitLdcInsn(f.name)
        pushInt(mv, f.params.size)
        mv.visitTypeInsn(ANEWARRAY, OBJECT)
        var slot = 0
        f.params.forEachIndexed { i, p ->
            val d = storage(p.type)
            mv.visitInsn(DUP)
            pushInt(mv, i)
            mv.visitVarInsn(loadOp(d), slot)
            m.coerce(d, OBJECT_DESC)
            mv.visitInsn(AASTORE)
            slot += size(d)
        }
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME, "external", "(Ljava/util/Map;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false)
        val result = desc(f.returnType)
        m.coerce(OBJECT_DESC, result)
        mv.visitInsn(returnOp(result))
    }

    /** A class implementing [MsiFunction] that calls the module function [f]. */
    private fun functionRef(f: Func): String = functionRefs.getOrPut(f.name) {
        val name = "$pkg/R_${sanitize(module.name)}\$${sanitize(f.name)}"
        val cw = writer()
        cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER, name, null, OBJECT, arrayOf(FUNCTION))
        defaultConstructor(cw)
        val mn = MethodNode(ACC_PUBLIC, "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;", null, null)
        val m = MethodContext(mn, Type.Unit, firstSlot = 2)
        f.params.forEachIndexed { i, p ->
            mn.visitVarInsn(ALOAD, 1)
            pushInt(mn, i)
            mn.visitInsn(AALOAD)
            m.coerce(OBJECT_DESC, storage(p.type))
        }
        mn.visitMethodInsn(INVOKESTATIC, moduleClass, "f_${sanitize(f.name)}", methodDesc(f.params.map { it.type }, f.returnType), false)
        m.coerce(desc(f.returnType), OBJECT_DESC)
        mn.visitInsn(ARETURN)
        mn.visitMaxs(0, 0)
        mn.accept(cw)
        cw.visitEnd()
        classes[name] = cw.toByteArray()
        name
    }

    private fun defaultConstructor(cw: ClassWriter) {
        val ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        ctor.visitCode()
        ctor.visitVarInsn(ALOAD, 0)
        ctor.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false)
        ctor.visitInsn(RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()
    }

    /** A class implementing [MsiFunction] for the lambda: its fields are the boxes of the captured variables. */
    private fun lambdaClass(lambda: Lambda): String {
        val name = "$pkg/L_${sanitize(module.name)}\$${lambdaCounter++}"
        val captures = lambda.captures
        val cw = writer()
        cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER, name, null, OBJECT, arrayOf(FUNCTION))
        captures.indices.forEach { cw.visitField(ACC_PUBLIC, "c$it", BOX_DESC, null, null).visitEnd() }
        val ctor = cw.visitMethod(ACC_PUBLIC, "<init>", captures.joinToString("", "(", ")V") { BOX_DESC }, null, null)
        ctor.visitCode()
        ctor.visitVarInsn(ALOAD, 0)
        ctor.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false)
        captures.indices.forEach {
            ctor.visitVarInsn(ALOAD, 0)
            ctor.visitVarInsn(ALOAD, it + 1)
            ctor.visitFieldInsn(PUTFIELD, name, "c$it", BOX_DESC)
        }
        ctor.visitInsn(RETURN)
        ctor.visitMaxs(0, 0)
        ctor.visitEnd()

        val mn = MethodNode(ACC_PUBLIC, "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;", null, null)
        val m = MethodContext(mn, lambda.returnType, firstSlot = 2, boxedResult = true)
        captures.forEachIndexed { i, v ->
            mn.visitVarInsn(ALOAD, 0)
            mn.visitFieldInsn(GETFIELD, name, "c$i", BOX_DESC)
            m.bindBox(v)
        }
        lambda.params.forEachIndexed { i, p ->
            mn.visitVarInsn(ALOAD, 1)
            pushInt(mn, i)
            mn.visitInsn(AALOAD)
            m.coerce(OBJECT_DESC, storage(p.type))
            m.declare(p)
        }
        m.block(lambda.body)
        m.endOfBody()
        mn.visitMaxs(0, 0)
        mn.accept(cw)
        cw.visitEnd()
        classes[name] = cw.toByteArray()
        return name
    }

    // ---- methods -----------------------------------------------------------------------------

    private class LoopLabels(val again: Label, val exit: Label, val tryDepth: Int)

    /** A try with catches or finally whose protected ranges are being collected. */
    private inner class TryContext(val finally: (() -> Unit)?) {
        val catchRanges = ArrayList<Pair<LabelNode, LabelNode>>()    // the body, for the catches
        val finallyRanges = ArrayList<Pair<LabelNode, LabelNode>>()  // body and catches, for finally
        var catchStart: LabelNode? = null
        var finallyStart: LabelNode? = null
        /** Whether the body is being generated (the catch handler is active), not a catch. */
        var catchPhase = false
    }

    private inner class MethodContext(
        val mv: MethodNode,
        val returnType: Type,
        firstSlot: Int = 0,
        /** Lambdas return their result boxed as Object. */
        val boxedResult: Boolean = false,
    ) {
        private var nextSlot = firstSlot
        private val slots = identityMap<Variable, Int>()
        private val loops = identityMap<Loop, LoopLabels>()
        private val tries = ArrayList<TryContext>()

        fun newSlot(d: String): Int = nextSlot.also { nextSlot += size(d) }

        /** Captured and borrowed variables live in a shared box. */
        private fun captured(v: Variable) = module.isCaptured(v) || module.isBorrowed(v)

        /** Stores the value on the stack (in [storage] form) into a new variable. */
        fun declare(v: Variable) {
            val d = storage(v.type)
            if (captured(v)) {
                val value = newSlot(d)
                mv.visitVarInsn(storeOp(d), value)
                val box = newSlot(BOX_DESC)
                pushInt(mv, 1)
                mv.visitTypeInsn(ANEWARRAY, OBJECT)
                mv.visitVarInsn(ASTORE, box)
                slots[v] = box
                mv.visitVarInsn(ALOAD, box)
                pushInt(mv, 0)
                mv.visitVarInsn(loadOp(d), value)
                coerce(d, OBJECT_DESC)
                mv.visitInsn(AASTORE)
            } else {
                val slot = newSlot(d)
                mv.visitVarInsn(storeOp(d), slot)
                slots[v] = slot
            }
        }

        /** A parameter of a static method: it is already in its slot. */
        fun param(p: Param) {
            val d = storage(p.type)
            val slot = newSlot(d)
            if (captured(p)) {
                mv.visitVarInsn(loadOp(d), slot)
                declare(p)
            } else slots[p] = slot
        }

        /** The box of a captured variable is on the stack. */
        fun bindBox(v: Variable) {
            val box = newSlot(BOX_DESC)
            mv.visitVarInsn(ASTORE, box)
            slots[v] = box
        }

        fun load(v: Variable) {
            val d = storage(v.type)
            if (v is Global) return mv.visitFieldInsn(GETSTATIC, moduleClass, "g_${sanitize(v.name)}", d)
            val slot = slots[v] ?: error("no slot for ${v.name}")
            if (captured(v)) {
                mv.visitVarInsn(ALOAD, slot)
                pushInt(mv, 0)
                mv.visitInsn(AALOAD)
                coerce(OBJECT_DESC, d)
            } else mv.visitVarInsn(loadOp(d), slot)
        }

        /** Stores the value on the stack (in [storage] form). */
        fun store(v: Variable) {
            val d = storage(v.type)
            if (v is Global) return mv.visitFieldInsn(PUTSTATIC, moduleClass, "g_${sanitize(v.name)}", d)
            val slot = slots[v] ?: error("no slot for ${v.name}")
            if (captured(v)) {
                coerce(d, OBJECT_DESC)
                val tmp = newSlot(OBJECT_DESC)
                mv.visitVarInsn(ASTORE, tmp)
                mv.visitVarInsn(ALOAD, slot)
                pushInt(mv, 0)
                mv.visitVarInsn(ALOAD, tmp)
                mv.visitInsn(AASTORE)
            } else mv.visitVarInsn(storeOp(d), slot)
        }

        fun endOfBody() {
            if (returnType == Type.Unit) {
                if (boxedResult) mv.visitInsn(ACONST_NULL).also { mv.visitInsn(ARETURN) } else mv.visitInsn(RETURN)
            } else {
                // unreachable: the checker made sure the body returns
                mv.visitTypeInsn(NEW, "java/lang/IllegalStateException")
                mv.visitInsn(DUP)
                mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false)
                mv.visitInsn(ATHROW)
            }
        }

        // ---- coercions between representations ---------------------------------------------

        fun coerce(from: String, to: String) {
            if (from == to) return
            if (to == "V") {
                if (from != "V") mv.visitInsn(if (size(from) == 2) POP2 else POP)
                return
            }
            if (from == "V") {
                mv.visitInsn(ACONST_NULL)
                return coerce(OBJECT_DESC, to)
            }
            val fromPrimitive = from.length == 1
            val toPrimitive = to.length == 1
            when {
                fromPrimitive && !toPrimitive -> {
                    val (wrapper, _) = wrapperOf(from)
                    mv.visitMethodInsn(INVOKESTATIC, wrapper, "valueOf", "($from)L$wrapper;", false)
                    if (to != OBJECT_DESC) mv.visitTypeInsn(CHECKCAST, internalName(to))
                }
                !fromPrimitive && toPrimitive -> {
                    val (wrapper, getter) = wrapperOf(to)
                    mv.visitTypeInsn(CHECKCAST, wrapper)
                    mv.visitMethodInsn(INVOKEVIRTUAL, wrapper, getter, "()$to", false)
                }
                !fromPrimitive && !toPrimitive -> if (to != OBJECT_DESC) mv.visitTypeInsn(CHECKCAST, internalName(to))
                else -> error("can not convert $from to $to")
            }
        }

        fun zero(t: Type) {
            when (storage(t)) {
                "Z", "I" -> mv.visitInsn(ICONST_0)
                "J" -> mv.visitInsn(LCONST_0)
                "D" -> mv.visitInsn(DCONST_0)
                "Ljava/lang/String;" -> mv.visitLdcInsn("")
                else -> mv.visitInsn(ACONST_NULL)
            }
        }

        // ---- statements --------------------------------------------------------------------

        fun block(b: Block) = b.stmts.forEach(::stmt)

        fun stmt(s: Stmt) {
            when (s) {
                is Block -> block(s)
                is Let -> {
                    s.init?.let { exprAs(it, storage(s.type)) } ?: zero(s.type)
                    declare(s)
                }
                is Assign -> assign(s)
                is Branch -> {
                    val otherwise = Label()
                    val end = Label()
                    condition(s.condition, otherwise)
                    block(s.then)
                    mv.visitJumpInsn(GOTO, end)
                    mv.visitLabel(otherwise)
                    s.alternative?.let(::block)
                    mv.visitLabel(end)
                }
                is Loop -> {
                    val labels = LoopLabels(Label(), Label(), tries.size)
                    loops[s] = labels
                    mv.visitLabel(labels.again)
                    s.condition?.let { condition(it, labels.exit) }
                    block(s.body)
                    mv.visitJumpInsn(GOTO, labels.again)
                    mv.visitLabel(labels.exit)
                }
                is Break -> { val l = loops[s.target!!]!!; leave(l.tryDepth) { mv.visitJumpInsn(GOTO, l.exit) } }
                is Continue -> { val l = loops[s.target!!]!!; leave(l.tryDepth) { mv.visitJumpInsn(GOTO, l.again) } }
                is Return -> returnStmt(s)
                is Eval -> coerce(expr(s.expr), "V")
                is Match -> match(s)
                is Throw -> {
                    mv.visitTypeInsn(NEW, THROWABLE)
                    mv.visitInsn(DUP)
                    exprAs(s.value, OBJECT_DESC)
                    mv.visitMethodInsn(INVOKESPECIAL, THROWABLE, "<init>", "(Ljava/lang/Object;)V", false)
                    mv.visitInsn(ATHROW)
                }
                is Try -> tryStmt(s)
                is Access -> {
                    val target = newSlot(OBJECT_DESC)
                    exprAs(s.target, OBJECT_DESC)
                    mv.visitVarInsn(ASTORE, target)
                    fun call(name: String) {
                        mv.visitVarInsn(ALOAD, target)
                        mv.visitInsn(if (s.mutable) ICONST_1 else ICONST_0)
                        mv.visitMethodInsn(INVOKESTATIC, ACCESS, name, "(Ljava/lang/Object;Z)V", false)
                    }
                    call("begin")
                    tryBlock(s.body, emptyList()) { call("end") }
                }
            }
        }

        private fun returnStmt(s: Return) {
            val result = if (boxedResult) OBJECT_DESC else desc(returnType)
            if (s.value != null) exprAs(s.value, result) else if (boxedResult) mv.visitInsn(ACONST_NULL)
            if (tries.any { it.finally != null }) {
                // finally blocks run after the value is computed
                val tmp = if (result == "V") -1 else newSlot(result)
                if (tmp >= 0) mv.visitVarInsn(storeOp(result), tmp)
                leave(0) {
                    if (tmp >= 0) mv.visitVarInsn(loadOp(result), tmp)
                    mv.visitInsn(returnOp(result))
                }
            } else mv.visitInsn(returnOp(result))
        }

        /** Runs the finally blocks of the tries above [depth] (innermost first), then [jump]. */
        private fun leave(depth: Int, jump: () -> Unit) {
            val exited = tries.subList(depth, tries.size).toList()
            if (exited.none { it.finally != null }) return jump()
            for (t in exited) closeRanges(t)
            val saved = ArrayList(tries)
            for (t in exited.asReversed()) {
                // inside the finally block of t, only the tries outside of t are active
                while (tries.isNotEmpty() && tries.last() !== t) tries.removeAt(tries.size - 1)
                tries.removeAt(tries.size - 1)
                t.finally?.invoke()
            }
            tries.clear()
            tries.addAll(saved)
            jump()
            for (t in exited) openRanges(t)
        }

        private fun here(): LabelNode = LabelNode().also { mv.instructions.add(it) }

        private fun closeRanges(t: TryContext) {
            val end = here()
            t.catchStart?.let { t.catchRanges += it to end; t.catchStart = null }
            t.finallyStart?.let { t.finallyRanges += it to end; t.finallyStart = null }
        }

        private fun openRanges(t: TryContext, catches: Boolean = t.catchPhase) {
            val start = here()
            if (catches) t.catchStart = start
            if (t.finally != null) t.finallyStart = start
        }

        private fun tryStmt(s: Try) = tryBlock(s.body, s.catches, s.finally?.let { f -> { block(f) } })

        /** A try with [catches] and generated [fin] code that runs on every exit (like `finally`). */
        private fun tryBlock(body: Block, catches: List<Catch>, fin: (() -> Unit)?) {
            val t = TryContext(fin)
            t.catchPhase = catches.isNotEmpty()
            val end = Label()
            tries += t
            openRanges(t)
            block(body)
            closeRanges(t)
            tries.removeAt(tries.size - 1)
            fin?.invoke()
            mv.visitJumpInsn(GOTO, end)

            if (catches.isNotEmpty()) {
                // dispatch on the thrown value; the finally handler covers the catches too
                val handler = here()
                tries += t
                t.catchPhase = false
                if (t.finally != null) t.finallyStart = handler
                val exception = newSlot(OBJECT_DESC)
                mv.visitVarInsn(ASTORE, exception)
                for (r in t.catchRanges) if (hasCode(r.first, r.second)) mv.tryCatchBlocks.add(TryCatchBlockNode(r.first, r.second, handler, THROWABLE))
                for (c in catches) {
                    val next = Label()
                    mv.visitVarInsn(ALOAD, exception)
                    mv.visitTypeInsn(CHECKCAST, THROWABLE)
                    mv.visitMethodInsn(INVOKEVIRTUAL, THROWABLE, "getValue", "()Ljava/lang/Object;", false)
                    mv.visitInsn(DUP)
                    mv.visitTypeInsn(INSTANCEOF, internalName(storage(c.type)))
                    val matched = Label()
                    mv.visitJumpInsn(IFNE, matched)
                    mv.visitInsn(POP)
                    mv.visitJumpInsn(GOTO, next)
                    mv.visitLabel(matched)
                    coerce(OBJECT_DESC, storage(c.type))
                    declare(c)
                    block(c.body)
                    closeRanges(t)
                    tries.removeAt(tries.size - 1)
                    fin?.invoke()
                    mv.visitJumpInsn(GOTO, end)
                    tries += t
                    openRanges(t, catches = false)
                    mv.visitLabel(next)
                }
                mv.visitVarInsn(ALOAD, exception)
                mv.visitInsn(ATHROW)
                closeRanges(t)
                tries.removeAt(tries.size - 1)
            }

            if (fin != null) {
                val handler = here()
                val exception = newSlot(OBJECT_DESC)
                mv.visitVarInsn(ASTORE, exception)
                for (r in t.finallyRanges) if (hasCode(r.first, r.second)) mv.tryCatchBlocks.add(TryCatchBlockNode(r.first, r.second, handler, null))
                fin()
                mv.visitVarInsn(ALOAD, exception)
                mv.visitInsn(ATHROW)
            }
            mv.visitLabel(end)
        }

        private fun match(s: Match) {
            val union = s.union!!
            val subject = newSlot(desc(s.subject.type))
            exprAs(s.subject, desc(s.subject.type))
            mv.visitVarInsn(ASTORE, subject)
            val end = Label()
            for (c in s.cases) {
                val next = Label()
                val variantClass = variantClass(union.name, c.variant)
                mv.visitVarInsn(ALOAD, subject)
                mv.visitTypeInsn(INSTANCEOF, variantClass)
                mv.visitJumpInsn(IFEQ, next)
                val variant = c.variantDef!!
                c.variables.forEachIndexed { i, v ->
                    val field = variant.fields[i]
                    mv.visitVarInsn(ALOAD, subject)
                    mv.visitTypeInsn(CHECKCAST, variantClass)
                    mv.visitFieldInsn(GETFIELD, variantClass, "f_${sanitize(field.name)}", storage(field.type))
                    coerce(storage(field.type), storage(v.type))
                    declare(v)
                }
                block(c.body)
                mv.visitJumpInsn(GOTO, end)
                mv.visitLabel(next)
            }
            s.otherwise?.let(::block)
            mv.visitLabel(end)
        }

        private fun assign(s: Assign) {
            when (val target = s.target) {
                is Local -> {
                    val v = target.variable!!
                    exprAs(s.value, storage(v.type))
                    store(v)
                }
                is Get -> {
                    // MSI evaluates the value before the target
                    val struct = target.struct!!
                    val field = struct.fields.first { it.name == target.field }
                    val d = storage(field.type)
                    val tmp = newSlot(d)
                    exprAs(s.value, d)
                    mv.visitVarInsn(storeOp(d), tmp)
                    exprAs(target.target, "L${structClass(struct.name)};")
                    mv.visitVarInsn(loadOp(d), tmp)
                    mv.visitFieldInsn(PUTFIELD, structClass(struct.name), "f_${sanitize(field.name)}", d)
                }
                is Deref -> {
                    val tmp = newSlot(OBJECT_DESC)
                    exprAs(s.value, OBJECT_DESC)
                    mv.visitVarInsn(ASTORE, tmp)
                    exprAs(target.ref, "L$REF;")
                    mv.visitVarInsn(ALOAD, tmp)
                    mv.visitMethodInsn(INVOKEINTERFACE, REF, "set", "(Ljava/lang/Object;)V", true)
                }
                is Index -> {
                    val tmp = newSlot(OBJECT_DESC)
                    exprAs(s.value, OBJECT_DESC)
                    mv.visitVarInsn(ASTORE, tmp)
                    exprAs(target.array, BOX_DESC)
                    index(target.index)
                    mv.visitVarInsn(ALOAD, tmp)
                    mv.visitInsn(AASTORE)
                }
                else -> error("not assignable")
            }
        }

        /** Pushes [e] as an `int` index. */
        private fun index(e: Expr) {
            val d = desc(e.type)
            exprAs(e, d)
            if (d == "J") mv.visitInsn(L2I)
        }

        // ---- expressions -------------------------------------------------------------------

        fun exprAs(e: Expr, to: String) = coerce(expr(e), to)

        /** Jumps to [otherwise] if [e] is false. */
        fun condition(e: Expr, otherwise: Label) {
            exprAs(e, "Z")
            mv.visitJumpInsn(IFEQ, otherwise)
        }

        /** Generates [e] and returns the descriptor of the value it leaves on the stack. */
        fun expr(e: Expr): String {
            when (e) {
                is IntLit -> if (e.type == Type.I32) pushInt(mv, e.value.toInt()) else mv.visitLdcInsn(e.value)
                is FloatLit -> mv.visitLdcInsn(e.value)
                is BoolLit -> mv.visitInsn(if (e.value) ICONST_1 else ICONST_0)
                is StrLit -> mv.visitLdcInsn(e.value)
                is Local -> { val v = e.variable!!; load(v); return storage(v.type) }
                is Binary -> binary(e)
                is Unary -> {
                    val d = expr(e.operand)
                    when (e.op) {
                        UnaryOp.NOT -> { mv.visitInsn(ICONST_1); mv.visitInsn(IXOR) }
                        UnaryOp.NEG -> mv.visitInsn(when (d) { "I" -> INEG; "J" -> LNEG; else -> DNEG })
                        UnaryOp.BIT_NOT -> if (d == "I") { mv.visitInsn(ICONST_M1); mv.visitInsn(IXOR) } else { mv.visitLdcInsn(-1L); mv.visitInsn(LXOR) }
                    }
                }
                is Call -> {
                    val f = e.callee!!
                    f.params.zip(e.args).forEach { (p, a) -> exprAs(a, storage(p.type)) }
                    mv.visitMethodInsn(INVOKESTATIC, moduleClass, "f_${sanitize(f.name)}", methodDesc(f.params.map { it.type }, f.returnType), false)
                    return desc(f.returnType)
                }
                is New -> {
                    val s = e.structDef!!
                    val owner = structClass(s.name)
                    mv.visitTypeInsn(NEW, owner)
                    mv.visitInsn(DUP)
                    s.fields.zip(e.args).forEach { (f, a) -> exprAs(a, storage(f.type)) }
                    mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", s.fields.joinToString("", "(", ")V") { storage(it.type) }, false)
                }
                is Get -> {
                    val s = e.struct!!
                    val field = s.fields.first { it.name == e.field }
                    exprAs(e.target, "L${structClass(s.name)};")
                    mv.visitFieldInsn(GETFIELD, structClass(s.name), "f_${sanitize(field.name)}", storage(field.type))
                    return storage(field.type)
                }
                is NewArray -> {
                    index(e.size)
                    mv.visitTypeInsn(ANEWARRAY, OBJECT)
                    val zero = storage(e.element)
                    if (zero.length == 1 || zero == "Ljava/lang/String;") {
                        mv.visitInsn(DUP)
                        zero(e.element)
                        coerce(zero, OBJECT_DESC)
                        mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "fill", "([Ljava/lang/Object;Ljava/lang/Object;)V", false)
                    }
                }
                is Index -> {
                    exprAs(e.array, BOX_DESC)
                    index(e.index)
                    mv.visitInsn(AALOAD)
                    return OBJECT_DESC
                }
                is Length -> {
                    exprAs(e.array, BOX_DESC)
                    mv.visitInsn(ARRAYLENGTH)
                    mv.visitInsn(I2L)
                }
                is Convert -> convert(e)
                is Conditional -> {
                    val d = desc(e.type).let { if (it == "V") OBJECT_DESC else it }
                    val otherwise = Label()
                    val end = Label()
                    condition(e.condition, otherwise)
                    exprAs(e.then, d)
                    mv.visitJumpInsn(GOTO, end)
                    mv.visitLabel(otherwise)
                    exprAs(e.otherwise, d)
                    mv.visitLabel(end)
                    return d
                }
                is Make -> {
                    val variant = e.variantDef!!
                    val owner = variantClass(e.union, variant.name)
                    mv.visitTypeInsn(NEW, owner)
                    mv.visitInsn(DUP)
                    variant.fields.zip(e.args).forEach { (f, a) -> exprAs(a, storage(f.type)) }
                    mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", variant.fields.joinToString("", "(", ")V") { storage(it.type) }, false)
                    return "L${unionClass(e.union)};"
                }
                is Is -> {
                    exprAs(e.subject, desc(e.subject.type))
                    mv.visitTypeInsn(INSTANCEOF, variantClass(e.union!!.name, e.variant))
                }
                is FuncRef -> {
                    val owner = functionRef(e.callee!!)
                    mv.visitTypeInsn(NEW, owner)
                    mv.visitInsn(DUP)
                    mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", "()V", false)
                }
                is Lambda -> {
                    val owner = lambdaClass(e)
                    mv.visitTypeInsn(NEW, owner)
                    mv.visitInsn(DUP)
                    for (v in e.captures) {
                        mv.visitVarInsn(ALOAD, slots[v] ?: error("${v.name} is not captured here"))
                    }
                    mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", e.captures.joinToString("", "(", ")V") { BOX_DESC }, false)
                }
                is Invoke -> {
                    exprAs(e.callee, "L$FUNCTION;")
                    pushInt(mv, e.args.size)
                    mv.visitTypeInsn(ANEWARRAY, OBJECT)
                    e.args.forEachIndexed { i, a ->
                        mv.visitInsn(DUP)
                        pushInt(mv, i)
                        exprAs(a, OBJECT_DESC)
                        mv.visitInsn(AASTORE)
                    }
                    mv.visitMethodInsn(INVOKEINTERFACE, FUNCTION, "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;", true)
                    return OBJECT_DESC
                }
                is Borrow -> when (val place = e.place) {
                    is Local -> {
                        mv.visitTypeInsn(NEW, BOX_REF)
                        mv.visitInsn(DUP)
                        mv.visitVarInsn(ALOAD, slots[place.variable!!] ?: error("no box for ${place.name}"))
                        mv.visitMethodInsn(INVOKESPECIAL, BOX_REF, "<init>", "([Ljava/lang/Object;)V", false)
                    }
                    is Get -> {
                        mv.visitTypeInsn(NEW, FIELD_REF)
                        mv.visitInsn(DUP)
                        exprAs(place.target, OBJECT_DESC)
                        mv.visitLdcInsn("f_${sanitize(place.field)}")
                        mv.visitMethodInsn(INVOKESPECIAL, FIELD_REF, "<init>", "(Ljava/lang/Object;Ljava/lang/String;)V", false)
                    }
                    else -> exprAs((place as Deref).ref, "L$REF;") // a reborrow is the same reference
                }
                is Deref -> {
                    exprAs(e.ref, "L$REF;")
                    mv.visitMethodInsn(INVOKEINTERFACE, REF, "get", "()Ljava/lang/Object;", true)
                    return OBJECT_DESC
                }
                is Null -> mv.visitInsn(ACONST_NULL)
                is Copy -> {
                    val d = exprAs(e.value, storage(e.type)).let { storage(e.type) }
                    copyValue(mv, e.type)
                    return d
                }
            }
            return desc(e.type)
        }

        private fun convert(e: Convert) {
            val from = expr(e.value)
            when (e.to) {
                Type.Str -> when (from) {
                    "D" -> mv.visitMethodInsn(INVOKESTATIC, RUNTIME, "str", "(D)Ljava/lang/String;", false)
                    "Ljava/lang/String;" -> {}
                    else -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "($from)Ljava/lang/String;", false)
                }
                Type.I32 -> when (from) { "J" -> mv.visitInsn(L2I); "D" -> mv.visitInsn(D2I) }
                Type.I64 -> when (from) { "I" -> mv.visitInsn(I2L); "D" -> mv.visitInsn(D2L) }
                Type.F64 -> when (from) { "I" -> mv.visitInsn(I2D); "J" -> mv.visitInsn(L2D) }
                else -> {}
            }
        }

        private fun binary(e: Binary) {
            when (e.op) {
                BinaryOp.AND, BinaryOp.OR -> {
                    val short = Label()
                    val end = Label()
                    exprAs(e.left, "Z")
                    mv.visitJumpInsn(if (e.op == BinaryOp.AND) IFEQ else IFNE, short)
                    exprAs(e.right, "Z")
                    mv.visitJumpInsn(GOTO, end)
                    mv.visitLabel(short)
                    mv.visitInsn(if (e.op == BinaryOp.AND) ICONST_0 else ICONST_1)
                    mv.visitLabel(end)
                    return
                }
                BinaryOp.EQ, BinaryOp.NE, BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> return comparison(e)
                else -> {}
            }
            val d = desc(e.left.type)
            exprAs(e.left, d)
            if (d == "Ljava/lang/String;") {
                exprAs(e.right, d)
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false)
                return
            }
            val shift = e.op == BinaryOp.SHL || e.op == BinaryOp.SHR
            exprAs(e.right, d)
            if (shift && d == "J") mv.visitInsn(L2I)
            val base = when (e.op) {
                BinaryOp.ADD -> IADD; BinaryOp.SUB -> ISUB; BinaryOp.MUL -> IMUL; BinaryOp.DIV -> IDIV; BinaryOp.REM -> IREM
                BinaryOp.BIT_AND -> IAND; BinaryOp.BIT_OR -> IOR; BinaryOp.BIT_XOR -> IXOR; BinaryOp.SHL -> ISHL; BinaryOp.SHR -> ISHR
                else -> error("unreachable")
            }
            // IADD, LADD, FADD, DADD are consecutive; so are the other arithmetic opcodes
            val offset = when (d) { "I" -> 0; "J" -> 1; else -> 3 }
            mv.visitInsn(if (base >= IAND) base + (if (d == "J") 1 else 0) else base + offset)
        }

        private fun comparison(e: Binary) {
            val t = e.left.type
            val d = desc(t)
            exprAs(e.left, d)
            exprAs(e.right, d)
            val isFalse = Label()
            val end = Label()
            fun jump(opcode: Int) = mv.visitJumpInsn(opcode, isFalse)
            when {
                d == "I" || d == "Z" -> jump(when (e.op) {
                    BinaryOp.EQ -> IF_ICMPNE; BinaryOp.NE -> IF_ICMPEQ; BinaryOp.LT -> IF_ICMPGE
                    BinaryOp.LE -> IF_ICMPGT; BinaryOp.GT -> IF_ICMPLE; else -> IF_ICMPLT
                })
                d == "J" || d == "D" -> {
                    mv.visitInsn(if (d == "J") LCMP else if (e.op == BinaryOp.LT || e.op == BinaryOp.LE) DCMPG else DCMPL)
                    jump(inverse(e.op))
                }
                d == "Ljava/lang/String;" -> if (e.op == BinaryOp.EQ || e.op == BinaryOp.NE) {
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
                    jump(if (e.op == BinaryOp.EQ) IFEQ else IFNE)
                } else {
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "compareTo", "(Ljava/lang/String;)I", false)
                    jump(inverse(e.op))
                }
                else -> jump(if (e.op == BinaryOp.EQ) IF_ACMPNE else IF_ACMPEQ)
            }
            mv.visitInsn(ICONST_1)
            mv.visitJumpInsn(GOTO, end)
            mv.visitLabel(isFalse)
            mv.visitInsn(ICONST_0)
            mv.visitLabel(end)
        }

        /** The jump taken when a comparison result (an int compared to 0) makes [op] false. */
        private fun inverse(op: BinaryOp): Int = when (op) {
            BinaryOp.EQ -> IFNE; BinaryOp.NE -> IFEQ; BinaryOp.LT -> IFGE
            BinaryOp.LE -> IFGT; BinaryOp.GT -> IFLE; else -> IFLT
        }
    }
}

// ---- small helpers -------------------------------------------------------------------------------

private fun size(desc: String) = if (desc == "J" || desc == "D") 2 else 1

/** Whether real instructions lie between two labels: the JVM rejects empty exception table ranges. */
private fun hasCode(start: LabelNode, end: LabelNode): Boolean {
    var n = start.next
    while (n != null && n !== end) {
        if (n.opcode >= 0) return true
        n = n.next
    }
    return false
}

private fun loadOp(desc: String) = when (desc) { "Z", "I" -> ILOAD; "J" -> LLOAD; "D" -> DLOAD; else -> ALOAD }
private fun storeOp(desc: String) = when (desc) { "Z", "I" -> ISTORE; "J" -> LSTORE; "D" -> DSTORE; else -> ASTORE }
private fun returnOp(desc: String) = when (desc) { "V" -> RETURN; "Z", "I" -> IRETURN; "J" -> LRETURN; "D" -> DRETURN; else -> ARETURN }

private fun internalName(desc: String) = if (desc.startsWith("L")) desc.substring(1, desc.length - 1) else desc

private fun wrapperOf(primitive: String): Pair<String, String> = when (primitive) {
    "Z" -> "java/lang/Boolean" to "booleanValue"
    "I" -> "java/lang/Integer" to "intValue"
    "J" -> "java/lang/Long" to "longValue"
    "D" -> "java/lang/Double" to "doubleValue"
    else -> error("not a primitive: $primitive")
}

private fun pushInt(mv: MethodNode, value: Int) {
    when (value) {
        in -1..5 -> mv.visitInsn(ICONST_0 + value)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, value)
        in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, value)
        else -> mv.visitLdcInsn(value)
    }
}
