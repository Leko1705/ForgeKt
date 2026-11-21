package com.leko.forge.java

/** A Java type. Open, so features can add types. */
interface JType {
    /** How the type is written in Java. */
    val display: String
}

data class PrimitiveType(override val display: String) : JType {
    override fun toString(): String = display
}

val IntType: PrimitiveType = PrimitiveType("int")
val LongType: PrimitiveType = PrimitiveType("long")
val DoubleType: PrimitiveType = PrimitiveType("double")
val BooleanType: PrimitiveType = PrimitiveType("boolean")
val VoidType: PrimitiveType = PrimitiveType("void")

data class ClassType(val name: String) : JType {
    override val display: String get() = name
    override fun toString(): String = name
}

val StringType: ClassType = ClassType("String")

data class ArrayType(val element: JType) : JType {
    override val display: String get() = "${element.display}[]"
    override fun toString(): String = display
}

/** The type of `null`. */
object NullType : JType { override val display: String = "null" }

/** The type of expressions that are already wrong (and reported). */
object ErrorType : JType { override val display: String = "<error>" }

val JType.isNumeric: Boolean get() = this == IntType || this == LongType || this == DoubleType
val JType.isIntegral: Boolean get() = this == IntType || this == LongType
/** Class, interface and array types (not String, which is a value in this compiler). */
val JType.isReference: Boolean get() = this is ClassType && this != StringType || this is ArrayType || this == NullType

/** The type of `a op b` for numeric operands: the wider of both (binary numeric promotion). */
fun promote(a: JType, b: JType): JType = when {
    a == DoubleType || b == DoubleType -> DoubleType
    a == LongType || b == LongType -> LongType
    else -> IntType
}
