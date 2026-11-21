package com.leko.forge.java

import com.leko.forge.Child

/*
 * The Java syntax tree. The interfaces are open on purpose: features (also from other modules)
 * add their own node types and give them meaning with attributes (see JavaFeature).
 */

interface JNode
interface JStmt : JNode
interface JExpr : JNode
interface JMember : JNode

/** A named value: a parameter, local variable, for-each variable or caught exception. */
interface JVariable : JNode {
    val name: String
    val declaredType: JType?
}

// ---- declarations --------------------------------------------------------------------------------

/** All compilation units compiled together (including the prelude). */
data class Program(@Child val units: List<CompilationUnit>) : JNode

data class CompilationUnit(val source: String?, @Child val types: List<TypeDecl>) : JNode

data class TypeDecl(
    val name: String,
    val isInterface: Boolean,
    val superclass: String?,
    val interfaces: List<String>,
    val modifiers: Set<String>,
    @Child val members: List<JMember>,
) : JNode {
    val isAbstract: Boolean get() = isInterface || "abstract" in modifiers
}

data class FieldDecl(val modifiers: Set<String>, val type: JType, val name: String, @Child val init: JExpr?) : JMember {
    val isStatic: Boolean get() = "static" in modifiers
}

data class ParamDecl(override val declaredType: JType, override val name: String) : JVariable

data class MethodDecl(
    val modifiers: Set<String>,
    val returnType: JType,
    val name: String,
    @Child val params: List<ParamDecl>,
    @Child val body: BlockStmt?,
) : JMember {
    val isStatic: Boolean get() = "static" in modifiers
}

data class ConstructorDecl(val modifiers: Set<String>, val name: String, @Child val params: List<ParamDecl>, @Child val body: BlockStmt) : JMember

// ---- statements ----------------------------------------------------------------------------------

data class BlockStmt(@Child val stmts: List<JStmt>) : JStmt
/** Tree nodes must be distinct objects (each has its own parent), so these are classes, not objects. */
class EmptyStmt : JStmt { override fun toString(): String = "EmptyStmt" }
data class LocalVar(override val declaredType: JType?, override val name: String, @Child val init: JExpr?) : JStmt, JVariable
data class ExprStmt(@Child val expr: JExpr) : JStmt
/** `target op value` with op `=`, `+=`, `-=`, ... */
data class AssignStmt(@Child val target: JExpr, val op: String, @Child val value: JExpr) : JStmt
data class IncDecStmt(@Child val target: JExpr, val delta: Int) : JStmt
data class IfStmt(@Child val condition: JExpr, @Child val then: JStmt, @Child val otherwise: JStmt?) : JStmt

/** Loops; [label] is set for `label: loop`. */
interface LoopStmt : JStmt {
    val label: String?
    val body: JStmt
}
data class WhileStmt(@Child val condition: JExpr, @Child override val body: JStmt, override val label: String? = null) : LoopStmt
data class DoWhileStmt(@Child override val body: JStmt, @Child val condition: JExpr, override val label: String? = null) : LoopStmt
data class ForStmt(@Child val init: List<JStmt>, @Child val condition: JExpr?, @Child val update: List<JStmt>, @Child override val body: JStmt, override val label: String? = null) : LoopStmt
data class ForEachStmt(override val declaredType: JType?, override val name: String, @Child val iterable: JExpr, @Child override val body: JStmt, override val label: String? = null) : LoopStmt, JVariable

data class BreakStmt(val label: String?) : JStmt
data class ContinueStmt(val label: String?) : JStmt
data class ReturnStmt(@Child val value: JExpr?) : JStmt
data class ThrowStmt(@Child val value: JExpr) : JStmt
data class TryStmt(@Child val body: BlockStmt, @Child val catches: List<CatchClause>, @Child val finally: BlockStmt?) : JStmt
data class CatchClause(val types: List<String>, override val name: String, @Child val body: BlockStmt) : JVariable {
    override val declaredType: JType? get() = ClassType(types.first())
}
/** `super(args)` or `this(args)`: the first statement of a constructor. */
data class ConstructorCall(val isSuper: Boolean, @Child val args: List<JExpr>) : JStmt

// ---- expressions ---------------------------------------------------------------------------------

data class Literal(val value: Any, val type: JType) : JExpr
class NullLiteral : JExpr { override fun toString(): String = "null" }
class ThisExpr : JExpr { override fun toString(): String = "this" }
data class NameExpr(val name: String) : JExpr
data class FieldAccess(@Child val target: JExpr, val name: String) : JExpr
/** A call; [target] is `null` for unqualified calls (`foo()`), [isSuper] for `super.foo()`. */
data class MethodCall(@Child val target: JExpr?, val name: String, @Child val args: List<JExpr>, val isSuper: Boolean = false) : JExpr
data class NewObject(val className: String, @Child val args: List<JExpr>) : JExpr
/** `new T[d1][d2]...[]...`: [dimensions] sizes, then [extraDimensions] unsized ones. */
data class NewArrayExpr(val element: JType, @Child val dimensions: List<JExpr>, val extraDimensions: Int) : JExpr
/** `new T[] { ... }` or `{ ... }` in an initializer. */
data class ArrayLiteral(val element: JType?, @Child val elements: List<JExpr>) : JExpr
data class ArrayAccess(@Child val array: JExpr, @Child val index: JExpr) : JExpr
data class BinaryExpr(val op: String, @Child val left: JExpr, @Child val right: JExpr) : JExpr
data class UnaryExpr(val op: String, @Child val operand: JExpr) : JExpr
data class CastExpr(val type: JType, @Child val expr: JExpr) : JExpr
data class InstanceOfExpr(@Child val expr: JExpr, val type: JType) : JExpr
data class ConditionalExpr(@Child val condition: JExpr, @Child val then: JExpr, @Child val otherwise: JExpr) : JExpr
