package com.leko.forge.java

import com.leko.forge.msi.*
import com.leko.forge.syn

/*
 * Lowering to MSI. Every Java object is one shared MSI struct `$Object`: its class id, its virtual
 * method table (one global `$VTable` per class, with a function value per method signature of the
 * program) and the instance fields of all classes (prefixed with the class). Virtual calls go through
 * a dispatcher function per signature, so the receiver is evaluated once. Constructors become an
 * init function (on an existing object) and a factory function (allocate, init, return).
 */

object Names {
    const val OBJECT_STRUCT: String = "\$Object"
    const val VTABLE_STRUCT: String = "\$VTable"
    const val CLASS_FIELD: String = "\$class"
    const val VTABLE_FIELD: String = "\$vt"
    fun field(f: FieldInfo): String = "${f.owner.name}__${f.name}"
    fun method(m: MethodInfo): String = "${m.owner.name}__${if (m.isStatic) "s" else "m"}_${m.name}_${m.index}"
    fun init(c: ConstructorInfo): String = "${c.owner.name}__init_${c.index}"
    fun factory(c: ConstructorInfo): String = "${c.owner.name}__new_${c.index}"
    fun vtable(c: ClassInfo): String = "\$vt__${c.name}"
    fun slot(i: Int): String = "slot_$i"
    fun dispatch(i: Int): String = "\$call__$i"
    fun stub(i: Int): String = "\$abstract__$i"
}

val OBJECT_TYPE: Type.Struct = Type.Struct(Names.OBJECT_STRUCT)
val VTABLE_TYPE: Type.Struct = Type.Struct(Names.VTABLE_STRUCT)

/** The MSI type of values of a Java type. */
val JType.msi: Type get() = when (this) {
    IntType -> Type.I32
    LongType -> Type.I64
    DoubleType -> Type.F64
    BooleanType -> Type.Bool
    VoidType -> Type.Unit
    StringType -> Type.Str
    is ClassType, NullType -> OBJECT_TYPE
    is ArrayType -> Type.Array(element.msi)
    else -> Type.Invalid
}

/** The value of a variable or field that was not assigned. */
fun zeroOf(t: JType): Expr = when (t) {
    IntType -> IntLit(0, Type.I32)
    LongType -> IntLit(0)
    DoubleType -> FloatLit(0.0)
    BooleanType -> BoolLit(false)
    StringType -> StrLit("")
    else -> Null(t.msi)
}

/** The lowering of an expression. Every feature implements it for its expressions. */
val JExpr.msi: Expr by syn()

/** The lowering of a statement. Every feature implements it for its statements. */
val JStmt.msi: Stmt by syn()

/** The lowering of this expression as a value of [target]: with numeric widening, `null` of the right type. */
fun JExpr.msiAs(target: JType): Expr {
    if (this is NullLiteral || jtype == NullType) return Null(target.msi.takeIf { it != Type.Str } ?: OBJECT_TYPE)
    return convert(msi, jtype, target)
}

/** Converts an MSI value between Java types (numeric widening and narrowing). */
fun convert(e: Expr, from: JType, to: JType): Expr =
    if (from == to || !from.isNumeric || !to.isNumeric) e else Convert(e, to.msi)

/** The text of a value, as string concatenation and `println` produce it. */
fun JExpr.msiString(): Expr = when {
    this is NullLiteral -> StrLit("null")
    jtype == StringType -> msi
    jtype == BooleanType || jtype.isNumeric -> Convert(msi, Type.Str)
    else -> Call("\$toString", msi)
}

/** Helper functions generated during lowering (casts, array literals, ...), by name. */
class Helpers {
    val functions: LinkedHashMap<String, Func> = LinkedHashMap()
    private var counter = 0
    fun add(name: String, build: () -> Func): String { if (name !in functions) functions[name] = build(); return name }
    fun fresh(prefix: String): String = "\$$prefix${counter++}"
}

val Program.helpers: Helpers by syn { Helpers() }
fun JNode.helper(name: String, build: () -> Func): String = program.helpers.add(name, build)
fun JNode.fresh(prefix: String): String = program.helpers.fresh(prefix)

/** `o != null && o.$class in classes`: whether an object is an instance of [type]. */
fun JNode.instanceTest(o: Expr, type: ClassInfo): Expr {
    val ids = classes.classes.values.filter { c -> classes.ancestors(c).any { it === type } }.map { it.id }
    val classId = Get(o, Names.CLASS_FIELD)
    val anyOf = ids.map { Binary(BinaryOp.EQ, classId, IntLit(it.toLong(), Type.I32)) }.reduceOrNull { a, b -> Binary(BinaryOp.OR, a, b) } ?: BoolLit(false)
    return Binary(BinaryOp.AND, Binary(BinaryOp.NE, o, Null(OBJECT_TYPE)), anyOf)
}

/** Creates an exception of the prelude class [name] with [message] (for casts and other runtime checks). */
fun JNode.newException(name: String, message: Expr): Expr {
    val c = classes[name] ?: error("no class $name")
    val ctor = c.constructors.first { it.params == listOf(StringType) }
    return Call(Names.factory(ctor), message)
}

// ==== the module =================================================================================

/** The program as an MSI module, with `$main()` calling the `main` method. */
val Program.msiModule: Module by syn { ModuleBuilder(this).build() }

private class ModuleBuilder(val program: Program) {
    val table = program.classTable
    val functions = ArrayList<Func>()
    val globals = ArrayList<Global>()

    fun build(): Module {
        val slots = table.slots
        val objectFields = listOf(Field(Names.CLASS_FIELD, Type.I32), Field(Names.VTABLE_FIELD, VTABLE_TYPE)) +
            table.classes.values.flatMap { c -> c.fields.values.filter { !it.isStatic }.map { Field(Names.field(it), it.type.msi) } }
        val slotType = { m: MethodInfo -> Type.Function(listOf(OBJECT_TYPE) + m.params.map { it.msi }, m.returnType.msi) }
        val structs = listOf(
            Struct(Names.OBJECT_STRUCT, objectFields),
            Struct(Names.VTABLE_STRUCT, slots.mapIndexed { i, m -> Field(Names.slot(i), slotType(m)) }),
        )
        functions += Func("println", listOf(Param("s", Type.Str)), Type.Unit, null)
        functions += Func("print", listOf(Param("s", Type.Str)), Type.Unit, null)

        // virtual dispatch: one dispatcher and one stub (for abstract methods) per signature
        slots.forEachIndexed { i, m ->
            val params = listOf(Param("this", OBJECT_TYPE)) + m.params.mapIndexed { j, t -> Param("p$j", t.msi) }
            val call = Invoke(Get(Get(Local("this"), Names.VTABLE_FIELD), Names.slot(i)), params.map { Local(it.name) })
            functions += Func(Names.dispatch(i), params, m.returnType.msi, Block(if (m.returnType == VoidType) Eval(call) else Return(call)))
            functions += Func(Names.stub(i), params, m.returnType.msi, Block(Throw(StrLit("AbstractMethodError: ${m.key}"))))
        }

        for (c in table.classes.values) if (!c.isAbstract && c.name != OBJECT || c.name == OBJECT) {
            if (c.isInterface || c.isAbstract) continue
            val entries = slots.mapIndexed { i, m -> table.implementation(c, m.key)?.let { FuncRef(Names.method(it)) } ?: FuncRef(Names.stub(i)) }
            globals += Global(Names.vtable(c), VTABLE_TYPE, New(Names.VTABLE_STRUCT, entries))
        }
        for (c in table.classes.values) classMembers(c)
        builtins()

        // main
        val main = table.classes.values.flatMap { it.methods }.firstOrNull { it.name == "main" && it.isStatic && it.params == listOf(ArrayType(StringType)) }
        if (main != null) functions += Func("\$main", emptyList(), Type.Unit, Block(Eval(Call(Names.method(main), NewArray(Type.Str, IntLit(0))))))

        functions += program.helpers.functions.values
        return Module("java", structs, functions, globals)
    }

    private fun classMembers(c: ClassInfo) {
        val decl = c.decl ?: return
        for (f in c.fields.values) if (f.isStatic) globals += Global(Names.field(f), f.type.msi, f.decl?.init?.msiAs(f.type))
        for (m in c.methods) {
            val d = m.decl ?: continue
            val params = (if (m.isStatic) emptyList() else listOf(Param("this", OBJECT_TYPE))) + d.params.map { Param(it.name, it.declaredType.msi) }
            val body = d.body?.let { it.msi as Block }
            functions += Func(Names.method(m), params, m.returnType.msi, body ?: Block(Throw(StrLit("AbstractMethodError: ${m.key}"))))
        }
        if (c.isInterface) return
        for (ctor in c.constructors) {
            val params = ctor.decl?.params.orEmpty().map { Param(it.name, it.declaredType.msi) }
            val stmts = ctor.decl?.body?.stmts.orEmpty()
            val explicit = stmts.firstOrNull() as? ConstructorCall
            val body = ArrayList<Stmt>()
            if (explicit != null) body += explicit.msi
            else superInit(c)?.let { body += Eval(Call(it, Local("this"))) }
            if (explicit?.isSuper != false) for (f in c.fields.values) if (!f.isStatic && f.decl?.init != null)
                body += Assign(Get(Local("this"), Names.field(f)), f.decl.init.msiAs(f.type))
            for (s in stmts.drop(if (explicit != null) 1 else 0)) body += s.msi
            functions += Func(Names.init(ctor), listOf(Param("this", OBJECT_TYPE)) + params, Type.Unit, Block(body))
            if (!c.isAbstract) {
                val objectFields = table.classes.values.flatMap { x -> x.fields.values.filter { !it.isStatic } }
                val create = New(Names.OBJECT_STRUCT, listOf(IntLit(c.id.toLong(), Type.I32), Local(Names.vtable(c))) + objectFields.map { zeroOf(it.type) })
                functions += Func(Names.factory(ctor), params, OBJECT_TYPE, Block(
                    Let("\$o", OBJECT_TYPE, create),
                    Eval(Call(Names.init(ctor), listOf(Local("\$o")) + params.map { Local(it.name) })),
                    Return(Local("\$o")),
                ))
            }
        }
    }

    /** The init function of the superclass constructor without parameters, `null` for Object. */
    private fun superInit(c: ClassInfo): String? {
        val sup = table.superclass(c) ?: return null
        if (sup.name == OBJECT) return null
        return sup.constructors.firstOrNull { it.params.isEmpty() }?.let { Names.init(it) }
    }

    private fun builtins() {
        val names = table.classes.values.fold(StrLit("Object") as Expr) { rest, c ->
            Conditional(Binary(BinaryOp.EQ, Get(Local("o"), Names.CLASS_FIELD), IntLit(c.id.toLong(), Type.I32)), StrLit(c.name), rest)
        }
        functions += Func("\$className", listOf(Param("o", OBJECT_TYPE)), Type.Str, Block(Return(names)))
        val toString = table.slots.indexOfFirst { it.key == "toString()" }
        functions += Func("\$toString", listOf(Param("o", OBJECT_TYPE)), Type.Str, Block(Return(
            Conditional(Binary(BinaryOp.EQ, Local("o"), Null(OBJECT_TYPE)), StrLit("null"), Call(Names.dispatch(toString), Local("o")))
        )))
        val obj = table[OBJECT]!!
        val self = Param("this", OBJECT_TYPE)
        for (m in obj.methods) functions += when (m.builtin) {
            "toString" -> Func(Names.method(m), listOf(self), Type.Str, Block(Return(Binary(BinaryOp.ADD, Call("\$className", Local("this")), StrLit("@object")))))
            "equals" -> Func(Names.method(m), listOf(self, Param("other", OBJECT_TYPE)), Type.Bool, Block(Return(Binary(BinaryOp.EQ, Local("this"), Local("other")))))
            else -> Func(Names.method(m), listOf(self), Type.I32, Block(Return(Get(Local("this"), Names.CLASS_FIELD))))
        }
    }
}
