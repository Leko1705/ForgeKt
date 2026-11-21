/**
 * The Multi-System-Tree (MSI): a system independent program representation.
 *
 * Languages built with Forge convert their syntax trees into MSI, typically with synthesized
 * attributes (`val Stmt.msi: msi.Stmt by syn()`), and get a common target that can be checked
 * ([validate]), run ([Interpreter]), analysed ([msiFlows]) and translated by the backends to
 * JavaScript (`toJavaScript`), JVM bytecode (`toJvm`, JVM only) and LLVM IR (`toLlvm`).
 *
 * MSI is deliberately small and explicit, so every backend can implement it:
 * - every value has a static [Type]; there are no implicit conversions (use [Convert])
 * - control flow is structured ([Branch], [Loop], [Match], [Try], ...), which maps directly to
 *   JVM bytecode, LLVM basic blocks and JavaScript
 * - data is primitives, strings, [Struct]s (records; backends generate classes), tagged [Union]s
 *   (enums are unions without fields), arrays and function values ([Lambda], closures)
 * - [Struct]s, [Union]s and [Func]tions can be generic ([Type.Param]); backends erase type parameters
 * - anything platform specific is an `external` function ([Func] without a body), implemented by
 *   the backend or host, e.g. `print`
 *
 * All nodes implement [com.leko.forge.TreeNode], so MSI trees work on every platform, also without
 * reflection.
 */
package com.leko.forge.msi

import com.leko.forge.TreeNode

// ==== types ======================================================================================

/** The static type of a value. */
public sealed interface Type {
    /** No value; the result of functions that return nothing. */
    public data object Unit : Type
    public data object Bool : Type
    /** 32 bit signed integer. */
    public data object I32 : Type
    /** 64 bit signed integer. */
    public data object I64 : Type
    /** 64 bit IEEE 754 floating point number. */
    public data object F64 : Type
    /** An immutable string of characters. */
    public data object Str : Type
    /**
     * An instance of the [Struct] called [name], with type arguments if it is generic and lifetime
     * arguments if it holds references. Lifetimes are not part of type identity (`equals` ignores them);
     * the ownership checker handles them.
     */
    public class Struct(
        public val name: String,
        public val args: List<Type> = emptyList(),
        public val lifetimes: List<String> = emptyList(),
    ) : Type {
        override fun equals(other: Any?): Boolean = other is Struct && other.name == name && other.args == args
        override fun hashCode(): Int = name.hashCode() * 31 + args.hashCode()
        override fun toString(): String = "Struct($name, $args, $lifetimes)"
    }
    /** A value of the [Union] called [name], with type arguments if it is generic. */
    public data class Union(public val name: String, public val args: List<Type> = emptyList()) : Type
    /** A reference to a mutable array of fixed length. */
    public data class Array(public val element: Type) : Type
    /** A function value: a [FuncRef] or a [Lambda]. */
    public data class Function(public val params: List<Type>, public val result: Type) : Type
    /**
     * A reference to a place (a variable or a field) holding a [target]. Without a [lifetime] it is a
     * second-class reference (Swift style): a parameter or local that can not escape; it can only be
     * returned by elision (a function with exactly one reference parameter). With a lifetime it is a
     * first-class reference (Rust style) that can be returned and stored in fields, as the lifetime
     * declarations of the function or struct allow. `equals` ignores the lifetime.
     */
    public class Ref(public val target: Type, public val mutable: Boolean, public val lifetime: String? = null) : Type {
        override fun equals(other: Any?): Boolean = other is Ref && other.target == target && other.mutable == mutable
        override fun hashCode(): Int = target.hashCode() * 31 + mutable.hashCode()
        override fun toString(): String = "Ref($target, mutable=$mutable, $lifetime)"
    }
    /** A type parameter of the enclosing generic [Func], [Struct] or [Union]. */
    public data class Param(public val name: String) : Type
    /** Only produced by the checker for expressions that are already wrong. */
    public data object Invalid : Type
}

public val Type.isInteger: Boolean get() = this == Type.I32 || this == Type.I64
public val Type.isNumeric: Boolean get() = isInteger || this == Type.F64
/** Primitive types are compared by value and can be converted to [Type.Str]. */
public val Type.isPrimitive: Boolean get() = this == Type.Bool || this == Type.Str || isNumeric

// ==== declarations ===============================================================================

/**
 * How values of a [Struct] or [Union] behave when they are assigned or passed:
 * - [SHARED]: by reference, any number of variables may refer to the same instance (Java, Swift classes)
 * - [VALUE]: value semantics (Swift structs): using a value moves it, [Copy] duplicates it, and
 *   [insertCopies] adds the copies a Swift-like language needs implicitly
 * - [OWNED]: unique ownership (Rust): using a value moves it, there is no copy
 *
 * Values of [VALUE] and [OWNED] types are checked by the ownership checker (see [validate]) and freed
 * deterministically by backends without garbage collection.
 */
public enum class MemoryKind { SHARED, VALUE, OWNED }

/** `'longer: 'shorter`: references with lifetime [longer] live at least as long as those with [shorter]. */
public data class Outlives(public val longer: String, public val shorter: String)

/** A compilation unit. Names of structs, unions, functions and globals are unique in a module. */
public data class Module(
    public val name: String,
    public val structs: List<Struct> = emptyList(),
    public val functions: List<Func> = emptyList(),
    public val globals: List<Global> = emptyList(),
    public val unions: List<Union> = emptyList(),
) : TreeNode {
    override val children: List<Any?> get() = structs + unions + globals + functions
}

/** A record type with named, mutable fields. Backends generate classes from it. */
public data class Struct(
    public val name: String,
    public val fields: List<Field>,
    public val typeParams: List<String> = emptyList(),
    public val kind: MemoryKind = MemoryKind.SHARED,
    /** Lifetime parameters, for fields holding first-class references (`&'a T`). */
    public val lifetimes: List<String> = emptyList(),
) : TreeNode {
    override val children: List<Any?> get() = fields
}

public data class Field(public val name: String, public val type: Type) : TreeNode {
    override val children: List<Any?> get() = emptyList() // types are not nodes
}

/**
 * A tagged union: a value is one of the [variants], each with its own fields. An enum is a union whose
 * variants have no fields. Create values with [Make], inspect them with [Match] and [Is].
 */
public data class Union(
    public val name: String,
    public val variants: List<Variant>,
    public val typeParams: List<String> = emptyList(),
    public val kind: MemoryKind = MemoryKind.SHARED,
) : TreeNode {
    override val children: List<Any?> get() = variants
}

public data class Variant(public val name: String, public val fields: List<Field> = emptyList()) : TreeNode {
    override val children: List<Any?> get() = fields
}

/**
 * A function. Without a [body] it is `external`: implemented by the backend or the host, which makes
 * platform functionality (output, files, time, ...) available in a system independent way.
 */
public data class Func(
    public val name: String,
    public val params: List<Param>,
    public val returnType: Type,
    public val body: Block?,
    public val typeParams: List<String> = emptyList(),
    /** Lifetime parameters of first-class references in the signature. */
    public val lifetimes: List<String> = emptyList(),
    /** Declared relations between the [lifetimes]. */
    public val outlives: List<Outlives> = emptyList(),
) : TreeNode {
    public val isExternal: Boolean get() = body == null
    public val type: Type.Function get() = Type.Function(params.map { it.type }, returnType)
    override val children: List<Any?> get() = params + body
}

/** A named value: a parameter, a local or global variable, a [Match] binding or a caught exception. */
public sealed interface Variable {
    public val name: String
    public val type: Type
}

public data class Param(override val name: String, override val type: Type) : Variable, TreeNode {
    override val children: List<Any?> get() = emptyList()
}

/**
 * A global variable, initialized in declaration order before any function runs. Referenced with
 * [Local] like local variables (locals with the same name hide it).
 */
public data class Global(override val name: String, override val type: Type, public val init: Expr? = null) : Variable, TreeNode {
    override val children: List<Any?> get() = listOf(init)
}

// ==== statements =================================================================================

public sealed interface Stmt : TreeNode

/** Statements run in order. Variables declared in a block are visible until its end. */
public data class Block(public val stmts: List<Stmt>) : Stmt {
    public constructor(vararg stmts: Stmt) : this(stmts.toList())
    override val children: List<Any?> get() = stmts
}

/** Declares a mutable local variable. Without [init] it holds the [zero value][Interpreter.zeroOf] of its type. */
public data class Let(override val name: String, override val type: Type, public val init: Expr? = null) : Stmt, Variable {
    override val children: List<Any?> get() = listOf(init)
}

/** Stores [value] in [target], which is a [Local], a field ([Get]) or an array element ([Index]). */
public data class Assign(public val target: Expr, public val value: Expr) : Stmt {
    override val children: List<Any?> get() = listOf(target, value)
}

/** Runs [then] if [condition] is true, else [alternative]. */
public data class Branch(public val condition: Expr, public val then: Block, public val alternative: Block? = null) : Stmt {
    override val children: List<Any?> get() = listOf(condition, then, alternative)
}

/**
 * Runs [body] as long as [condition] is true, checked before each iteration. Without a condition
 * it loops until a [Break] or [Return]. [label] lets [Break] and [Continue] address an outer loop.
 * Other loops (do-while, for, for-each) are expressed with it.
 */
public data class Loop(public val condition: Expr?, public val body: Block, public val label: String? = null) : Stmt {
    override val children: List<Any?> get() = listOf(condition, body)
}

/** Leaves the innermost loop, or the loop with [label]. */
public data class Break(public val label: String? = null) : Stmt {
    override val children: List<Any?> get() = emptyList()
}

/** Starts the next iteration of the innermost loop, or of the loop with [label]. */
public data class Continue(public val label: String? = null) : Stmt {
    override val children: List<Any?> get() = emptyList()
}

/** Leaves the function (or lambda), with [value] unless it returns [Type.Unit]. */
public data class Return(public val value: Expr? = null) : Stmt {
    override val children: List<Any?> get() = listOf(value)
}

/** Evaluates [expr] for its effects, e.g. a call. */
public data class Eval(public val expr: Expr) : Stmt {
    override val children: List<Any?> get() = listOf(expr)
}

/**
 * Runs the [Case] for the variant of the union value [subject], or [otherwise] if no case matches
 * (nothing happens if there is neither).
 */
public data class Match(public val subject: Expr, public val cases: List<Case>, public val otherwise: Block? = null) : Stmt {
    override val children: List<Any?> get() = listOf(subject) + cases + otherwise
}

/** One case of a [Match]: the fields of [variant] are bound to [bindings], in order (fewer are allowed). */
public data class Case(public val variant: String, public val bindings: List<String>, public val body: Block) : TreeNode {
    override val children: List<Any?> get() = listOf(body)
}

/** Throws [value], a struct, union or string, to the nearest [Try] with a matching [Catch]. */
public data class Throw(public val value: Expr) : Stmt {
    override val children: List<Any?> get() = listOf(value)
}

/**
 * Runs [body]. If it throws, the first [Catch] whose type matches the thrown value runs. [finally]
 * runs in every case, also when [body] or a catch leaves with `return`, `break` or `continue`.
 */
public data class Try(public val body: Block, public val catches: List<Catch>, public val finally: Block? = null) : Stmt {
    override val children: List<Any?> get() = listOf(body) + catches + finally
}

/**
 * Checks at runtime that no conflicting access to the shared object [target] (a [MemoryKind.SHARED]
 * struct, union or array) happens while [body] runs: a [mutable] access excludes all other accesses,
 * a reading access excludes mutable ones. This covers what the ownership checker can not prove
 * statically for shared objects (like Swift's dynamic exclusivity or Rust's `RefCell`).
 */
public data class Access(public val target: Expr, public val mutable: Boolean, public val body: Block) : Stmt {
    override val children: List<Any?> get() = listOf(target, body)
}

/**
 * Catches values of [type] (a struct, union or [Type.Str]; type arguments are not checked at runtime)
 * and binds them to [name].
 */
public data class Catch(override val name: String, override val type: Type, public val body: Block) : Variable, TreeNode {
    override val children: List<Any?> get() = listOf(body)
}

// ==== expressions ================================================================================

public sealed interface Expr : TreeNode

public data class IntLit(public val value: Long, public val type: Type = Type.I64) : Expr {
    override val children: List<Any?> get() = emptyList()
}

public data class FloatLit(public val value: Double) : Expr {
    override val children: List<Any?> get() = emptyList()
}

public data class BoolLit(public val value: Boolean) : Expr {
    override val children: List<Any?> get() = emptyList()
}

public data class StrLit(public val value: String) : Expr {
    override val children: List<Any?> get() = emptyList()
}

/** The value of a variable: a parameter, a local or global variable, a match binding or a caught value. */
public data class Local(public val name: String) : Expr {
    override val children: List<Any?> get() = emptyList()
}

public enum class BinaryOp(public val symbol: String) {
    /** Also concatenates strings. */
    ADD("+"), SUB("-"), MUL("*"),
    /** Integer division truncates towards zero. */
    DIV("/"), REM("%"),
    /** Compares primitives by value and everything else by identity. */
    EQ("=="), NE("!="),
    LT("<"), LE("<="), GT(">"), GE(">="),
    /** Short circuit: the right side is only evaluated if needed. */
    AND("&&"), OR("||"),
    BIT_AND("&"), BIT_OR("|"), BIT_XOR("^"), SHL("<<"), SHR(">>"),
}

public data class Binary(public val op: BinaryOp, public val left: Expr, public val right: Expr) : Expr {
    override val children: List<Any?> get() = listOf(left, right)
}

public enum class UnaryOp(public val symbol: String) { NEG("-"), NOT("!"), BIT_NOT("~") }

public data class Unary(public val op: UnaryOp, public val operand: Expr) : Expr {
    override val children: List<Any?> get() = listOf(operand)
}

/**
 * Calls the function called [function] in the same module. For a generic function, [typeArgs] are
 * inferred from the arguments if they are not given.
 */
public data class Call(public val function: String, public val args: List<Expr>, public val typeArgs: List<Type> = emptyList()) : Expr {
    public constructor(function: String, vararg args: Expr) : this(function, args.toList())
    override val children: List<Any?> get() = args
}

/** Creates an instance of the struct called [struct], with one argument per field, in order. */
public data class New(public val struct: String, public val args: List<Expr>, public val typeArgs: List<Type> = emptyList()) : Expr {
    public constructor(struct: String, vararg args: Expr) : this(struct, args.toList())
    override val children: List<Any?> get() = args
}

/** The field called [field] of a struct instance. */
public data class Get(public val target: Expr, public val field: String) : Expr {
    override val children: List<Any?> get() = listOf(target)
}

/** Creates an array of [size] elements, each holding the zero value of [element]. */
public data class NewArray(public val element: Type, public val size: Expr) : Expr {
    override val children: List<Any?> get() = listOf(size)
}

/** The element at [index] (an [Type.I32] or [Type.I64]) of an array. */
public data class Index(public val array: Expr, public val index: Expr) : Expr {
    override val children: List<Any?> get() = listOf(array, index)
}

/** The number of elements of an array, as [Type.I64]. */
public data class Length(public val array: Expr) : Expr {
    override val children: List<Any?> get() = listOf(array)
}

/** Converts between numeric types (truncating towards zero from floats), and primitives to [Type.Str]. */
public data class Convert(public val value: Expr, public val to: Type) : Expr {
    override val children: List<Any?> get() = listOf(value)
}

/** [then] if [condition] is true, else [otherwise]; only the chosen side is evaluated. */
public data class Conditional(public val condition: Expr, public val then: Expr, public val otherwise: Expr) : Expr {
    override val children: List<Any?> get() = listOf(condition, then, otherwise)
}

/** Creates a value of [variant] of the union called [union]. [typeArgs] are inferred from [args] if not given. */
public data class Make(
    public val union: String,
    public val variant: String,
    public val args: List<Expr> = emptyList(),
    public val typeArgs: List<Type> = emptyList(),
) : Expr {
    override val children: List<Any?> get() = args
}

/** Whether the union value [subject] is [variant]. */
public data class Is(public val subject: Expr, public val variant: String) : Expr {
    override val children: List<Any?> get() = listOf(subject)
}

/** The function called [function] as a value. A generic function needs its [typeArgs]. */
public data class FuncRef(public val function: String, public val typeArgs: List<Type> = emptyList()) : Expr {
    override val children: List<Any?> get() = emptyList()
}

/**
 * An anonymous function. It captures the variables it uses from enclosing functions by reference:
 * assignments on either side are visible to the other.
 */
public data class Lambda(public val params: List<Param>, public val returnType: Type, public val body: Block) : Expr {
    override val children: List<Any?> get() = params + body
}

/** Calls the function value [callee]. */
public data class Invoke(public val callee: Expr, public val args: List<Expr>) : Expr {
    public constructor(callee: Expr, vararg args: Expr) : this(callee, args.toList())
    override val children: List<Any?> get() = listOf(callee) + args
}

/**
 * A reference to [place]: a variable ([Local]), a field of a value or owned struct ([Get]), or what
 * another reference points to ([Deref]). The ownership checker makes sure no conflicting access happens
 * while the reference is in use.
 */
public data class Borrow(public val place: Expr, public val mutable: Boolean = false) : Expr {
    override val children: List<Any?> get() = listOf(place)
}

/** The place a reference points to: reads it, or, as an [Assign] target, writes it (mutable references). */
public data class Deref(public val ref: Expr) : Expr {
    override val children: List<Any?> get() = listOf(ref)
}

/** No object: the null reference of a struct, union, array or function type [type]. */
public data class Null(public val type: Type) : Expr {
    override val children: List<Any?> get() = emptyList()
}

/** A copy of [value]: a deep copy for [MemoryKind.VALUE] structs and unions, the value itself otherwise. */
public data class Copy(public val value: Expr) : Expr {
    override val children: List<Any?> get() = listOf(value)
}
