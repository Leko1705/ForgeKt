package com.leko.forge.java

import com.leko.forge.ParseScope
import com.leko.forge.msi.*
import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.token
import com.leko.forge.java.JavaGrammar.expr
import com.leko.forge.java.JavaGrammar.ident
import com.leko.forge.java.JavaGrammar.type

/** Primitive, String, class and array types. */
object CoreTypes : JavaFeature("types") {
    init {
        type.on { +"int"; IntType }
            .on { +"long"; LongType }
            .on { +"double"; DoubleType }
            .on { +"boolean"; BooleanType }
            .on { +"void"; VoidType }
            .on { val n = ident(); if (n == "String") StringType else ClassType(n) }
            .postfix(10) { t -> +"["; +"]"; ArrayType(t) }
    }
}

/** What a call refers to: a method, or a built-in operation (`System.out.println`, `String.equals`). */
class CallTarget(val method: MethodInfo?, val intrinsic: String? = null, val problem: String? = null)

/** The method a call refers to (overloads are resolved by the argument types, the most specific wins). */
val MethodCall.resolvedCall: CallTarget by syn { CoreExpressions.resolveCall(this) }

/** The constructor `new C(args)` calls, `null` if there is no applicable one. */
val NewObject.resolvedConstructor: ConstructorInfo? get() {
    val c = classes[className] ?: return null
    val types = args.map { it.jtype }
    val applicable = c.constructors.filter { k -> k.params.size == types.size && types.zip(k.params).all { (a, p) -> classes.assignable(a, p) } }
    return applicable.firstOrNull { k -> applicable.all { o -> k.params.zip(o.params).all { (a, b) -> classes.assignable(a, b) } } }
}

/** Literals, names, field access, calls, object and array creation, operators, casts. */
object CoreExpressions : JavaFeature("expressions") {

    private val doubleLiteral by token("[0-9]+\\.[0-9]+([eE][+-]?[0-9]+)?|[0-9]+[eE][+-]?[0-9]+")
    private val longLiteral by token("[0-9]+[lL]")
    private val intLiteral by token("[0-9]+")
    private val stringLiteral by token("\"(\\\\.|[^\"\\\\])*\"")
    private val args by rule { +"("; val a = sepBy(expr, ","); +")"; a }

    private fun ParseScope.binary(op: String, l: JExpr): JExpr { +op; return BinaryExpr(op, l, operand()) }

    init {
        expr
            // primary expressions
            .on { Literal(doubleLiteral().toDouble(), DoubleType) }
            .on { Literal(longLiteral().dropLast(1).toLong(), LongType) }
            .on { Literal(intLiteral().toLong(), IntType) }
            .on { Literal(unescape(stringLiteral()), StringType) }
            .on { +"true"; Literal(true, BooleanType) }
            .on { +"false"; Literal(false, BooleanType) }
            .on { +"null"; NullLiteral() }
            .on { +"this"; ThisExpr() }
            .on { +"super"; +"."; val n = ident(); MethodCall(null, n, args(), isSuper = true) }
            .on { +"new"; val e = arrayElement(); +"["; +"]"; +"{"; val items = sepBy(expr, ","); optional { +"," }; +"}"; ArrayLiteral(e, items) }
            .on {
                +"new"; val e = arrayElement()
                val dims = some { +"["; expr().also { +"]" } }
                val extra = many { +"["; +"]" }.size
                NewArrayExpr(e, dims, extra)
            }
            .on { +"new"; val n = ident(); NewObject(n, args()) }
            .on { val n = ident(); MethodCall(null, n, args()) }
            .on { NameExpr(ident()) }
            // casts come before parentheses; a reference cast is not followed by + or - (that is an operator)
            .prefix(13) {
                +"("; val t = type(); +")"
                if (t !is PrimitiveType && lookahead { oneOf({ +"+" }, { +"-" }) }) fail("an expression")
                CastExpr(t, operand())
            }
            .on { +"("; expr().also { +")" } }
            // postfix: member access, calls, indexing
            .postfix(15) { e -> +"."; val n = ident(); val a = optional(args); if (a != null) MethodCall(e, n, a) else FieldAccess(e, n) }
            .postfix(15) { e -> +"["; val i = expr(); +"]"; ArrayAccess(e, i) }
            // unary
            .prefix(13) { +"-"; UnaryExpr("-", operand()) }
            .prefix(13) { +"+"; UnaryExpr("+", operand()) }
            .prefix(13) { +"!"; UnaryExpr("!", operand()) }
            .prefix(13) { +"~"; UnaryExpr("~", operand()) }
            // binary, from strong to weak
            .infixLeft(12) { l -> binary("*", l) }.infixLeft(12) { l -> binary("/", l) }.infixLeft(12) { l -> binary("%", l) }
            .infixLeft(11) { l -> binary("+", l) }.infixLeft(11) { l -> binary("-", l) }
            .infixLeft(10) { l -> binary("<<", l) }.infixLeft(10) { l -> binary(">>", l) }
            .infixLeft(9) { l -> binary("<=", l) }.infixLeft(9) { l -> binary(">=", l) }
            .infixLeft(9) { l -> binary("<", l) }.infixLeft(9) { l -> binary(">", l) }
            .postfix(9) { e -> +"instanceof"; InstanceOfExpr(e, type()) }
            .infixLeft(8) { l -> binary("==", l) }.infixLeft(8) { l -> binary("!=", l) }
            .infixLeft(7) { l -> if (lookahead { +"&&" }) fail("&&"); binary("&", l) }
            .infixLeft(6) { l -> binary("^", l) }
            .infixLeft(5) { l -> if (lookahead { +"||" }) fail("||"); binary("|", l) }
            .infixLeft(4) { l -> binary("&&", l) }
            .infixLeft(3) { l -> binary("||", l) }
            .infixRight(2) { c -> +"?"; val a = expr(); +":"; ConditionalExpr(c, a, operand()) }
    }

    /** The element type of `new T[...]`: without its own `[]`. */
    private val arrayElement by rule {
        oneOf({ +"int"; IntType }, { +"long"; LongType }, { +"double"; DoubleType }, { +"boolean"; BooleanType },
            { val n = ident(); if (n == "String") StringType else ClassType(n) })
    }

    private fun unescape(literal: String): String {
        val s = literal.substring(1, literal.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') { out.append(c); continue }
            when (val e = s[i++]) {
                'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r'); '0' -> out.append('\u0000')
                else -> out.append(e)
            }
        }
        return out.toString()
    }

    // ---- resolution --------------------------------------------------------------------------

    internal fun resolveCall(call: MethodCall): CallTarget {
        val t = call.target
        if (t is FieldAccess && t.name == "out" && (t.target as? NameExpr)?.name == "System" && call.name in setOf("println", "print"))
            return if (call.args.size <= 1) CallTarget(null, call.name) else CallTarget(null, problem = "System.out.${call.name} takes at most one argument")
        val table = call.classes
        val candidates: List<MethodInfo> = when {
            call.isSuper -> call.enclosingClass?.let { table.superclass(it) }?.let { table.methods(it, call.name) }.orEmpty()
            t == null -> call.enclosingClass?.let { table.methods(it, call.name) }.orEmpty()
            t is NameExpr && t.resolution is Resolution.Class -> table.methods((t.resolution as Resolution.Class).info, call.name).filter { it.isStatic }
            t.jtype == StringType -> return when {
                call.name == "equals" && call.args.size == 1 -> CallTarget(null, "string.equals")
                else -> CallTarget(null, problem = "String.${call.name} is not supported")
            }
            t.jtype is ClassType -> table[(t.jtype as ClassType).name]?.let { table.methods(it, call.name) }.orEmpty()
            else -> return CallTarget(null, problem = if (t.jtype == ErrorType) "" else "${t.jtype.display} has no methods")
        }
        if (candidates.isEmpty()) return CallTarget(null, problem = "cannot find method ${call.name}")
        val types = call.args.map { it.jtype }
        if (ErrorType in types) return CallTarget(null, problem = "")
        val applicable = candidates.filter { m -> m.params.size == types.size && types.zip(m.params).all { (a, p) -> table.assignable(a, p) } }
        val best = applicable.filter { m -> applicable.all { n -> m.params.zip(n.params).all { (a, b) -> table.assignable(a, b) } } }
        return when {
            applicable.isEmpty() -> CallTarget(null, problem = "no method ${call.name}(${types.joinToString { it.display }}) found; candidates: ${candidates.joinToString { it.key }}")
            best.size != 1 -> CallTarget(null, problem = "call of ${call.name} is ambiguous: ${applicable.joinToString { it.key }}")
            else -> CallTarget(best.single())
        }
    }

    // ---- types -------------------------------------------------------------------------------

    private val Literal.jtype: JType by syn { type }
    private val NullLiteral.jtype: JType by syn { NullType }
    private val ThisExpr.jtype: JType by syn { enclosingClass?.let { ClassType(it.name) } ?: ErrorType }
    private val NameExpr.jtype: JType by syn {
        when (val r = resolution) {
            is Resolution.Variable -> r.variable.type
            is Resolution.Field -> r.field.type
            else -> ErrorType
        }
    }
    private val FieldAccess.jtype: JType by syn { fieldOf(this)?.type ?: if (name == "length" && target.jtype is ArrayType) IntType else ErrorType }
    private val MethodCall.jtype: JType by syn {
        val t = resolvedCall
        when {
            t.intrinsic == "string.equals" -> BooleanType
            t.intrinsic != null -> VoidType
            else -> t.method?.returnType ?: ErrorType
        }
    }
    private val NewObject.jtype: JType by syn { if (classes[className] != null) ClassType(className) else ErrorType }
    private val NewArrayExpr.jtype: JType by syn { (1..dimensions.size + extraDimensions).fold(element) { t, _ -> ArrayType(t) } }
    private val ArrayLiteral.jtype: JType by syn { element?.let { ArrayType(it) } ?: contextArrayType(this) ?: ErrorType }
    private val ArrayAccess.jtype: JType by syn { (array.jtype as? ArrayType)?.element ?: ErrorType }
    private val UnaryExpr.jtype: JType by syn {
        val t = operand.jtype
        when {
            op == "!" -> if (t == BooleanType) BooleanType else ErrorType
            op == "~" -> if (t.isIntegral) promote(t, IntType) else ErrorType
            else -> if (t.isNumeric) promote(t, IntType) else ErrorType
        }
    }
    private val BinaryExpr.jtype: JType by syn { binaryType(op, left.jtype, right.jtype) }
    private val CastExpr.jtype: JType by syn { type }
    private val InstanceOfExpr.jtype: JType by syn { BooleanType }
    private val ConditionalExpr.jtype: JType by syn {
        val a = then.jtype
        val b = otherwise.jtype
        when {
            a == b -> a
            a.isNumeric && b.isNumeric -> promote(a, b)
            a == NullType && b.isReference -> b
            b == NullType && a.isReference -> a
            classes.assignable(a, b) -> b
            classes.assignable(b, a) -> a
            else -> ErrorType
        }
    }

    /** `System` in `System.out.println`. */
    private fun isSystem(e: NameExpr) = e.name == "System" && (com.leko.forge.parentOf(e) as? FieldAccess)?.name == "out"

    /** The declared array type an array literal initializes (`int[] a = { ... }`). */
    private fun contextArrayType(e: ArrayLiteral): JType? = when (val p = com.leko.forge.parentOf(e)) {
        is LocalVar -> p.declaredType
        is FieldDecl -> p.type
        is ArrayLiteral -> (p.jtype as? ArrayType)?.element
        else -> null
    } as? ArrayType

    fun binaryType(op: String, l: JType, r: JType): JType {
        if (l == ErrorType || r == ErrorType) return ErrorType
        return when (op) {
            "+" -> if (l == StringType || r == StringType) StringType else if (l.isNumeric && r.isNumeric) promote(l, r) else ErrorType
            "-", "*", "/", "%" -> if (l.isNumeric && r.isNumeric) promote(l, r) else ErrorType
            "<<", ">>" -> if (l.isIntegral && r.isIntegral) promote(l, IntType) else ErrorType
            "<", ">", "<=", ">=" -> if (l.isNumeric && r.isNumeric) BooleanType else ErrorType
            "==", "!=" -> if (l.isNumeric && r.isNumeric || l == r || l.isReference && r.isReference || l == NullType || r == NullType) BooleanType else ErrorType
            "&&", "||" -> if (l == BooleanType && r == BooleanType) BooleanType else ErrorType
            "&", "|", "^" -> if (l == BooleanType && r == BooleanType) BooleanType else if (l.isIntegral && r.isIntegral) promote(l, r) else ErrorType
            else -> ErrorType
        }
    }

    /** The field a field access refers to: static (`C.f`) or of an object. */
    fun fieldOf(e: FieldAccess): FieldInfo? {
        val t = e.target
        if (t is NameExpr && t.resolution is Resolution.Class) return e.classes.field((t.resolution as Resolution.Class).info, e.name)?.takeIf { it.isStatic }
        val type = t.jtype as? ClassType ?: return null
        if (type == StringType) return null
        return e.classes[type.name]?.let { e.classes.field(it, e.name) }
    }

    // ---- checks ------------------------------------------------------------------------------

    init {
        problems
            .on<NameExpr> { p -> if (resolution == Resolution.None && !isSystem(this)) p + problem("cannot find symbol $name") else p }
            .on<NameExpr> { p ->
                val r = resolution
                if (r is Resolution.Field && !r.field.isStatic && isStaticContext) p + problem("non-static field $name cannot be referenced from a static context") else p
            }
            .on<FieldAccess> { p -> if (jtype == ErrorType && target.jtype != ErrorType) p + problem("cannot find field $name in ${target.jtype.display}") else p }
            .on<MethodCall> { p ->
                val t = resolvedCall
                when {
                    t.problem != null -> if (t.problem.isEmpty()) p else p + problem(t.problem)
                    t.method != null && !t.method.isStatic && this.target == null && !isSuper && isStaticContext ->
                        p + problem("non-static method ${t.method.key} cannot be referenced from a static context")
                    else -> p
                }
            }
            .on<NewObject> { p ->
                val c = classes[className]
                when {
                    c == null -> p + problem("cannot find class $className")
                    c.isAbstract -> p + problem("$className is abstract; cannot be instantiated")
                    resolvedConstructor == null && args.none { it.jtype == ErrorType } -> p + problem("no constructor $className(${args.joinToString { it.jtype.display }})")
                    else -> p
                }
            }
            .on<NewArrayExpr> { p -> p + dimensions.flatMap { if (it.jtype != IntType && it.jtype != ErrorType) it.problem("array size must be int, not ${it.jtype.display}") else emptyList() } }
            .on<ArrayLiteral> { p ->
                val element = (jtype as? ArrayType)?.element ?: return@on p + problem("cannot infer the type of the array literal")
                p + elements.flatMap { e -> if (!classes.assignable(e.jtype, element)) e.problem("incompatible types: ${e.jtype.display} cannot be converted to ${element.display}") else emptyList() }
            }
            .on<ArrayAccess> { p ->
                when {
                    array.jtype != ErrorType && array.jtype !is ArrayType -> p + problem("array required, but ${array.jtype.display} found")
                    index.jtype != IntType && index.jtype != ErrorType -> p + problem("array index must be int, not ${index.jtype.display}")
                    else -> p
                }
            }
            .on<UnaryExpr> { p -> if (jtype == ErrorType && operand.jtype != ErrorType) p + problem("bad operand type ${operand.jtype.display} for unary operator $op") else p }
            .on<BinaryExpr> { p ->
                if (jtype == ErrorType && left.jtype != ErrorType && right.jtype != ErrorType)
                    p + problem("bad operand types for binary operator $op: ${left.jtype.display} and ${right.jtype.display}") else p
            }
            .on<CastExpr> { p ->
                val from = expr.jtype
                val ok = from == ErrorType || from.isNumeric && type.isNumeric || classes.assignable(from, type) || classes.assignable(type, from) || (from.isReference && type.isReference)
                if (!ok) p + problem("incompatible types: ${from.display} cannot be converted to ${type.display}") else p
            }
            .on<ConditionalExpr> { p ->
                when {
                    condition.jtype != BooleanType && condition.jtype != ErrorType -> p + problem("incompatible types: ${condition.jtype.display} cannot be converted to boolean")
                    jtype == ErrorType && then.jtype != ErrorType && otherwise.jtype != ErrorType -> p + problem("incompatible types in conditional: ${then.jtype.display} and ${otherwise.jtype.display}")
                    else -> p
                }
            }
    }

    // ---- lowering ----------------------------------------------------------------------------

    private val Literal.msi: Expr by syn {
        when (type) {
            IntType -> IntLit(value as Long, Type.I32)
            LongType -> IntLit(value as Long)
            DoubleType -> FloatLit(value as Double)
            BooleanType -> BoolLit(value as Boolean)
            else -> StrLit(value as String)
        }
    }
    private val NullLiteral.msi: Expr by syn { Null(OBJECT_TYPE) }
    private val ThisExpr.msi: Expr by syn { Local("this") }
    private val NameExpr.msi: Expr by syn {
        when (val r = resolution) {
            is Resolution.Variable -> Local(name)
            is Resolution.Field -> if (r.field.isStatic) Local(Names.field(r.field)) else Get(Local("this"), Names.field(r.field))
            else -> error("unresolved $name")
        }
    }
    private val FieldAccess.msi: Expr by syn {
        val f = fieldOf(this)
        when {
            f == null -> Convert(Length(target.msi), Type.I32) // array.length
            f.isStatic -> Local(Names.field(f))
            else -> Get(target.msi, Names.field(f))
        }
    }
    private val MethodCall.msi: Expr by syn {
        val t = resolvedCall
        val receiver = this.target
        when (t.intrinsic) {
            "println" -> Call("println", args.firstOrNull()?.msiString() ?: StrLit(""))
            "print" -> Call("print", args.firstOrNull()?.msiString() ?: StrLit(""))
            "string.equals" -> Binary(BinaryOp.EQ, receiver!!.msi, args[0].msiAs(StringType))
            else -> {
                val m = t.method!!
                val arguments = args.zip(m.params).map { (a, p) -> a.msiAs(p) }
                when {
                    m.isStatic -> Call(Names.method(m), arguments)
                    isSuper -> Call(Names.method(m), listOf(Local("this")) + arguments)
                    else -> Call(Names.dispatch(classes.slots.indexOfFirst { it.key == m.key }), listOf(receiver?.msi ?: Local("this")) + arguments)
                }
            }
        }
    }
    private val NewObject.msi: Expr by syn {
        val k = resolvedConstructor!!
        Call(Names.factory(k), args.zip(k.params).map { (a, p) -> a.msiAs(p) })
    }
    private val NewArrayExpr.msi: Expr by syn {
        val shape = (jtype as ArrayType)
        Call(newArrayHelper(this, shape, dimensions.size), dimensions.map { it.msiAs(IntType) })
    }

    /** A function creating nested arrays: `new T[n0][n1]...`. */
    private fun newArrayHelper(node: JNode, type: ArrayType, sizes: Int): String {
        val name = "\$newArray_${sanitize(type.display)}_$sizes"
        return node.helper(name) {
            val params = (0 until sizes).map { Param("n$it", Type.I32) }
            val body = if (sizes == 1) Block(Return(NewArray(type.element.msi, Local("n0")))) else Block(
                Let("a", type.msi, NewArray(type.element.msi, Local("n0"))),
                Let("i", Type.I32, IntLit(0, Type.I32)),
                Loop(Binary(BinaryOp.LT, Local("i"), Local("n0")), Block(
                    Assign(Index(Local("a"), Local("i")), Call(newArrayHelper(node, type.element as ArrayType, sizes - 1), (1 until sizes).map { Local("n$it") })),
                    Assign(Local("i"), Binary(BinaryOp.ADD, Local("i"), IntLit(1, Type.I32))),
                )),
                Return(Local("a")),
            )
            Func(name, params, type.msi, body)
        }
    }

    private fun sanitize(s: String) = s.replace("[]", "A").replace(Regex("[^A-Za-z0-9_]"), "_")

    private val ArrayLiteral.msi: Expr by syn {
        val type = jtype as ArrayType
        val name = fresh("arrayLiteral")
        helper(name) {
            val params = elements.indices.map { Param("e$it", type.element.msi) }
            Func(name, params, type.msi, Block(
                listOf(Let("a", type.msi, NewArray(type.element.msi, IntLit(elements.size.toLong(), Type.I32)))) +
                    elements.indices.map { Assign(Index(Local("a"), IntLit(it.toLong(), Type.I32)), Local("e$it")) } +
                    Return(Local("a"))
            ))
        }
        Call(name, elements.map { it.msiAs(type.element) })
    }
    private val ArrayAccess.msi: Expr by syn { Index(array.msi, index.msiAs(IntType)) }
    private val UnaryExpr.msi: Expr by syn {
        val t = jtype
        when (op) {
            "-" -> Unary(UnaryOp.NEG, operand.msiAs(t))
            "~" -> Unary(UnaryOp.BIT_NOT, operand.msiAs(t))
            "!" -> Unary(UnaryOp.NOT, operand.msi)
            else -> operand.msiAs(t)
        }
    }
    private val BinaryExpr.msi: Expr by syn { lowerBinary(this) }
    private val CastExpr.msi: Expr by syn {
        val from = expr.jtype
        when {
            from.isNumeric && type.isNumeric -> convert(expr.msi, from, type)
            type is ClassType && type != StringType && !classes.assignable(from, type) -> {
                val c = classes[type.name]!!
                val name = helper("\$cast_${c.name}") {
                    Func("\$cast_${c.name}", listOf(Param("o", OBJECT_TYPE)), OBJECT_TYPE, Block(
                        Branch(Binary(BinaryOp.OR, Binary(BinaryOp.EQ, Local("o"), Null(OBJECT_TYPE)), instanceTest(Local("o"), c)), Block(Return(Local("o")))),
                        Throw(newException("ClassCastException", Binary(BinaryOp.ADD, Call("\$className", Local("o")), StrLit(" cannot be cast to ${c.name}")))),
                    ))
                }
                Call(name, expr.msi)
            }
            else -> expr.msiAs(type)
        }
    }
    private val InstanceOfExpr.msi: Expr by syn {
        val c = (type as? ClassType)?.let { classes[it.name] }
        if (c == null) BoolLit(expr.jtype != NullType) else {
            val name = helper("\$is_${c.name}") { Func("\$is_${c.name}", listOf(Param("o", OBJECT_TYPE)), Type.Bool, Block(Return(instanceTest(Local("o"), c)))) }
            Call(name, expr.msi)
        }
    }
    private val ConditionalExpr.msi: Expr by syn { Conditional(condition.msi, then.msiAs(jtype), otherwise.msiAs(jtype)) }

    /** `&`, `|` and `^` on booleans: both sides are evaluated (a helper function takes both values). */
    fun booleanOperator(node: JNode, op: String, a: Expr, b: Expr): Expr {
        val name = "\$bool_" + mapOf("&" to "and", "|" to "or", "^" to "xor").getValue(op)
        node.helper(name) {
            val body = when (op) {
                "&" -> Binary(BinaryOp.AND, Local("a"), Local("b"))
                "|" -> Binary(BinaryOp.OR, Local("a"), Local("b"))
                else -> Binary(BinaryOp.NE, Local("a"), Local("b"))
            }
            Func(name, listOf(Param("a", Type.Bool), Param("b", Type.Bool)), Type.Bool, Block(Return(body)))
        }
        return Call(name, a, b)
    }

    private fun lowerBinary(e: BinaryExpr): Expr {
        val l = e.left.jtype
        val r = e.right.jtype
        val ops = mapOf("+" to BinaryOp.ADD, "-" to BinaryOp.SUB, "*" to BinaryOp.MUL, "/" to BinaryOp.DIV, "%" to BinaryOp.REM,
            "<<" to BinaryOp.SHL, ">>" to BinaryOp.SHR, "<" to BinaryOp.LT, ">" to BinaryOp.GT, "<=" to BinaryOp.LE, ">=" to BinaryOp.GE,
            "==" to BinaryOp.EQ, "!=" to BinaryOp.NE, "&&" to BinaryOp.AND, "||" to BinaryOp.OR, "&" to BinaryOp.BIT_AND, "|" to BinaryOp.BIT_OR, "^" to BinaryOp.BIT_XOR)
        val op = ops.getValue(e.op)
        return when {
            e.op == "+" && e.jtype == StringType -> Binary(BinaryOp.ADD, e.left.msiString(), e.right.msiString())
            e.op == "<<" || e.op == ">>" -> Binary(op, e.left.msiAs(e.jtype), e.right.msiAs(e.jtype))
            l.isNumeric && r.isNumeric -> { val t = promote(l, r); Binary(op, e.left.msiAs(t), e.right.msiAs(t)) }
            e.op in setOf("&", "|", "^") -> booleanOperator(e, e.op, e.left.msi, e.right.msi)
            e.op == "==" || e.op == "!=" -> {
                val t = if (l == NullType) r else l
                Binary(op, e.left.msiAs(t), e.right.msiAs(t))
            }
            else -> Binary(op, e.left.msi, e.right.msi)
        }
    }
}
