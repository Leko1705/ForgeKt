package com.leko.forge.java

import com.leko.forge.childrenOf
import com.leko.forge.msi.*
import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.java.JavaGrammar.block
import com.leko.forge.java.JavaGrammar.expr
import com.leko.forge.java.JavaGrammar.ident
import com.leko.forge.java.JavaGrammar.stmt
import com.leko.forge.java.JavaGrammar.type

/** Several statements parsed from one (`int a = 0, b = 1;`); blocks and `for` headers flatten them. */
data class StatementList(@com.leko.forge.Child val stmts: List<JStmt>) : JStmt

/** Flattens [StatementList]s. */
fun flatten(stmts: List<JStmt>): List<JStmt> = stmts.flatMap { if (it is StatementList) flatten(it.stmts) else listOf(it) }

/**
 * Whether a statement can complete normally (Java's reachability rules, simplified). Features
 * implement it for statements that never complete, like `return` or `throw`; the default is `true`.
 */
val JStmt.completesNormally: Boolean by syn { true }

/** Blocks, variables, assignments, if, loops, break, continue, return. */
object CoreStatements : JavaFeature("statements") {

    private val assignOp by rule {
        oneOf({ +"=" }, { +"+=" }, { +"-=" }, { +"*=" }, { +"/=" }, { +"%=" }, { +"&=" }, { +"|=" }, { +"^=" }, { +"<<=" }, { +">>=" })
    }
    /** The value of a variable or field: an expression or an array literal `{ ... }`. */
    val initializer: com.leko.forge.Rule<JExpr> by rule { oneOf({ arrayLiteral() }, { expr() }) }
    private val arrayLiteral: com.leko.forge.Rule<JExpr> by rule { +"{"; val items = sepBy(initializer, ","); optional { +"," }; +"}"; ArrayLiteral(null, items) }

    /** `type a = 1, b;` or `var a = 1;` */
    val localVars: com.leko.forge.Rule<JStmt> by rule {
        val t: JType? = oneOf({ +"var"; null }, { type() })
        val vars = sepBy({ val n = ident(); LocalVar(t, n, optional { +"="; initializer() }) }, ",")
        if (vars.isEmpty()) fail("a variable name")
        if (vars.size == 1) vars.single() else StatementList(vars)
    }
    private val simpleStmt: com.leko.forge.Rule<JStmt> by rule {
        oneOf(
            { val t = expr(); val op = assignOp(); AssignStmt(t, op, expr()) },
            { val t = expr(); +"++"; IncDecStmt(t, 1) },
            { val t = expr(); +"--"; IncDecStmt(t, -1) },
            { +"++"; IncDecStmt(expr(), 1) },
            { +"--"; IncDecStmt(expr(), -1) },
            { ExprStmt(expr()) },
        )
    }
    private val forInit by rule { oneOf({ listOf(localVars()) }, { sepBy(simpleStmt, ",") }) }

    init {
        stmt.on { block() }
            .on { +";"; EmptyStmt() }
            .on { localVars().also { +";" } }
            .on { simpleStmt().also { +";" } }
            .on { +"if"; +"("; val c = expr(); +")"; val t = stmt(); IfStmt(c, t, optional { +"else"; stmt() }) }
            .on { +"while"; +"("; val c = expr(); +")"; WhileStmt(c, stmt()) }
            .on { +"do"; val b = stmt(); +"while"; +"("; val c = expr(); +")"; +";"; DoWhileStmt(b, c) }
            .on {
                +"for"; +"("; val t: JType? = oneOf({ +"var"; null }, { type() }); val n = ident(); +":"; val it = expr(); +")"
                ForEachStmt(t, n, it, stmt())
            }
            .on {
                +"for"; +"("; val i = flatten(forInit()); +";"; val c = optional(expr); +";"; val u = sepBy(simpleStmt, ","); +")"
                ForStmt(i, c, u, stmt())
            }
            .on {
                val label = ident(); +":"
                when (val s = stmt()) {
                    is WhileStmt -> s.copy(label = label)
                    is DoWhileStmt -> s.copy(label = label)
                    is ForStmt -> s.copy(label = label)
                    is ForEachStmt -> s.copy(label = label)
                    else -> fail("a loop after the label")
                }
            }
            .on { +"break"; val l = optional(ident); +";"; BreakStmt(l) }
            .on { +"continue"; val l = optional(ident); +";"; ContinueStmt(l) }
            .on { +"return"; val v = optional(expr); +";"; ReturnStmt(v) }
            .on { +"super"; val a = arguments(); +";"; ConstructorCall(true, a) }
            .on { +"this"; val a = arguments(); +";"; ConstructorCall(false, a) }
    }

    private val arguments by rule { +"("; val a = sepBy(expr, ","); +")"; a }

    // ---- reachability ------------------------------------------------------------------------

    private fun breaksOut(loop: LoopStmt): Boolean {
        fun visit(n: Any): Boolean = (n is BreakStmt && n.loopTarget(n.label) === loop) || childrenOf(n).any { visit(it) }
        return visit(loop.body)
    }

    private val BlockStmt.completesNormally: Boolean by syn { flatten(stmts).all { it.completesNormally } }
    private val ReturnStmt.completesNormally: Boolean by syn { false }
    private val BreakStmt.completesNormally: Boolean by syn { false }
    private val ContinueStmt.completesNormally: Boolean by syn { false }
    private val IfStmt.completesNormally: Boolean by syn { otherwise == null || then.completesNormally || otherwise.completesNormally }
    private val WhileStmt.completesNormally: Boolean by syn { !(condition == Literal(true, BooleanType)) || breaksOut(this) }
    private val ForStmt.completesNormally: Boolean by syn { !(condition == null || condition == Literal(true, BooleanType)) || breaksOut(this) }
    private val DoWhileStmt.completesNormally: Boolean by syn { (body.completesNormally && condition != Literal(true, BooleanType)) || breaksOut(this) }

    // ---- checks ------------------------------------------------------------------------------

    private fun JNode.mismatch(value: JExpr, target: JType): List<JavaProblem> =
        if (value.jtype == ErrorType || target == ErrorType || classes.assignable(value.jtype, target)) emptyList()
        else value.problem("incompatible types: ${value.jtype.display} cannot be converted to ${target.display}")

    private fun JNode.condition(e: JExpr): List<JavaProblem> =
        if (e.jtype == BooleanType || e.jtype == ErrorType) emptyList() else e.problem("incompatible types: ${e.jtype.display} cannot be converted to boolean")

    private fun returnTypeOf(n: JNode): JType = when (val m = n.enclosingMember) {
        is MethodDecl -> m.returnType
        else -> VoidType
    }

    init {
        problems
            .on<LocalVar> { p ->
                val t = declaredType
                when {
                    t == null && init == null -> p + problem("cannot infer the type of $name without an initializer")
                    t != null && !isKnown(t) -> p + problem("cannot find class ${t.display}")
                    scope.find(name).let { it != null && it !== this } -> p + problem("variable $name is already defined")
                    init != null && t != null -> p + mismatch(init, t)
                    else -> p
                }
            }
            .on<AssignStmt> { p ->
                val t = target.jtype
                when {
                    target !is NameExpr && target !is FieldAccess && target !is ArrayAccess -> p + problem("not assignable")
                    target is FieldAccess && t == IntType && (target.target.jtype is ArrayType) -> p + problem("cannot assign a value to the length of an array")
                    op == "=" -> p + mismatch(value, t)
                    else -> {
                        val result = CoreExpressions.binaryType(op.dropLast(1), t, value.jtype)
                        if (result == ErrorType && t != ErrorType && value.jtype != ErrorType)
                            p + problem("bad operand types for $op: ${t.display} and ${value.jtype.display}") else p
                    }
                }
            }
            .on<IncDecStmt> { p -> if (!target.jtype.isNumeric && target.jtype != ErrorType) p + problem("bad operand type ${target.jtype.display} for ${if (delta > 0) "++" else "--"}") else p }
            .on<ExprStmt> { p -> if (expr !is MethodCall && expr !is NewObject) p + problem("not a statement") else p }
            .on<IfStmt> { p -> p + condition(condition) }
            .on<WhileStmt> { p -> p + condition(condition) }
            .on<DoWhileStmt> { p -> p + condition(condition) }
            .on<ForStmt> { p -> p + (condition?.let { condition(it) } ?: emptyList()) }
            .on<ForEachStmt> { p ->
                val t = iterable.jtype
                when {
                    t == ErrorType -> p
                    t !is ArrayType -> p + problem("for-each is only supported for arrays, not ${t.display}")
                    declaredType != null && !classes.assignable(t.element, declaredType) -> p + problem("incompatible types: ${t.element.display} cannot be converted to ${declaredType.display}")
                    else -> p
                }
            }
            .on<BreakStmt> { p -> if (loopTarget(label) == null) p + problem(if (label == null) "break outside of a loop" else "undefined label: $label") else p }
            .on<ContinueStmt> { p -> if (loopTarget(label) == null) p + problem(if (label == null) "continue outside of a loop" else "undefined label: $label") else p }
            .on<ReturnStmt> { p ->
                val expected = returnTypeOf(this)
                when {
                    value == null && expected != VoidType -> p + problem("missing return value")
                    value != null && expected == VoidType -> p + problem("incompatible types: unexpected return value")
                    value != null -> p + mismatch(value, expected)
                    else -> p
                }
            }
            .on<ConstructorCall> { p ->
                val ctor = enclosingMember as? ConstructorDecl
                if (ctor == null || ctor.body.stmts.firstOrNull() !== this) p + problem("call to ${if (isSuper) "super" else "this"} must be the first statement in a constructor")
                else p + constructorProblems(this)
            }
    }

    /** The constructor a `super(...)`/`this(...)` call refers to. */
    fun targetConstructor(call: ConstructorCall): ConstructorInfo? {
        val c = call.enclosingClass ?: return null
        val owner = if (call.isSuper) call.classes.superclass(c) else c
        if (owner == null || owner.name == OBJECT) return null
        val types = call.args.map { it.jtype }
        return owner.constructors.firstOrNull { k -> k.params.size == types.size && types.zip(k.params).all { (a, p) -> call.classes.assignable(a, p) } }
    }

    private fun constructorProblems(call: ConstructorCall): List<JavaProblem> {
        val c = call.enclosingClass ?: return emptyList()
        val owner = if (call.isSuper) call.classes.superclass(c) else c
        if (owner == null || owner.name == OBJECT) return if (call.args.isEmpty()) emptyList() else call.problem("Object has no constructor with arguments")
        return if (targetConstructor(call) == null) call.problem("no constructor ${owner.name}(${call.args.joinToString { it.jtype.display }})") else emptyList()
    }

    // ---- lowering ----------------------------------------------------------------------------

    /** MSI labels of Java loops: where `break` and `continue` go. */
    private class Labels(val breakTo: String, val continueTo: String, val continueIsBreak: Boolean)

    private val loopLabels = java.util.IdentityHashMap<LoopStmt, Labels>()

    private fun labels(loop: LoopStmt): Labels = loopLabels[loop] ?: run {
        val name = loop.fresh("loop")
        when (loop) {
            is ForStmt, is ForEachStmt -> Labels(name, "${name}_body", continueIsBreak = true)
            else -> Labels(name, name, continueIsBreak = false)
        }
    }.also { loopLabels[loop] = it }

    private fun lowerBody(s: JStmt): Block = when (val m = s.msi) { is Block -> m; else -> Block(m) }

    private val BlockStmt.msi: Stmt by syn { Block(flatten(stmts).map { it.msi }) }
    private val StatementList.msi: Stmt by syn { Block(flatten(stmts).map { it.msi }) }
    private val EmptyStmt.msi: Stmt by syn { Block() }
    private val LocalVar.msi: Stmt by syn { Let(name, type.msi, init?.msiAs(type)) }
    private val ExprStmt.msi: Stmt by syn { Eval(expr.msi) }
    private val AssignStmt.msi: Stmt by syn {
        val t = target.jtype
        val lowered = if (op == "=") value.msiAs(t) else {
            // compound assignment: target = (T) (target op value)
            val o = op.dropLast(1)
            when (CoreExpressions.binaryType(o, t, value.jtype)) {
                StringType -> Binary(BinaryOp.ADD, target.msiString(), value.msiString())
                BooleanType -> CoreExpressions.booleanOperator(this, o, target.msi, value.msi)
                else -> {
                    val p = if (o == "<<" || o == ">>") t else promote(t, value.jtype)
                    convert(lowerOp(o, target.msiAs(p), value.msiAs(p)), p, t)
                }
            }
        }
        Assign(target.msi, lowered)
    }
    private val IncDecStmt.msi: Stmt by syn {
        val t = target.jtype
        val one = when (t) { LongType -> IntLit(1); DoubleType -> FloatLit(1.0); else -> IntLit(1, Type.I32) }
        Assign(target.msi, Binary(if (delta > 0) BinaryOp.ADD else BinaryOp.SUB, target.msi, one))
    }
    private val IfStmt.msi: Stmt by syn { Branch(condition.msi, lowerBody(then), otherwise?.let { lowerBody(it) }) }
    private val WhileStmt.msi: Stmt by syn { Loop(condition.msi, lowerBody(body), labels(this).breakTo) }
    private val DoWhileStmt.msi: Stmt by syn {
        // the condition is checked after the body: `first || condition`, so `continue` checks it too
        val first = fresh("first")
        Block(
            Let(first, Type.Bool, BoolLit(true)),
            Loop(Binary(BinaryOp.OR, Local(first), condition.msi), Block(Assign(Local(first), BoolLit(false)), lowerBody(body)), labels(this).breakTo),
        )
    }
    private val ForStmt.msi: Stmt by syn {
        val l = labels(this)
        // the body runs in an inner loop of one iteration: `continue` leaves it, so the update still runs
        val inner = Loop(null, Block(lowerBody(body), Break(l.continueTo)), l.continueTo)
        Block(flatten(init).map { it.msi } + Loop(condition?.msi ?: BoolLit(true), Block(listOf(inner) + update.map { it.msi }), l.breakTo))
    }
    private val ForEachStmt.msi: Stmt by syn {
        val l = labels(this)
        val array = fresh("array")
        val index = fresh("index")
        val element = (iterable.jtype as ArrayType).element
        val inner = Loop(null, Block(Let(name, type.msi, convert(Index(Local(array), Local(index)), element, type)), lowerBody(body), Break(l.continueTo)), l.continueTo)
        Block(
            Let(array, iterable.jtype.msi, iterable.msi),
            Let(index, Type.I32, IntLit(0, Type.I32)),
            Loop(Binary(BinaryOp.LT, Local(index), Convert(Length(Local(array)), Type.I32)), Block(
                inner,
                Assign(Local(index), Binary(BinaryOp.ADD, Local(index), IntLit(1, Type.I32))),
            ), l.breakTo),
        )
    }
    private val BreakStmt.msi: Stmt by syn { Break(labels(loopTarget(label)!!).breakTo) }
    private val ContinueStmt.msi: Stmt by syn {
        val l = labels(loopTarget(label)!!)
        if (l.continueIsBreak) Break(l.continueTo) else Continue(l.continueTo)
    }
    private val ReturnStmt.msi: Stmt by syn { Return(value?.msiAs(returnTypeOf(this))) }
    private val ConstructorCall.msi: Stmt by syn {
        val k = targetConstructor(this)
        if (k == null) Block() else Eval(Call(Names.init(k), listOf(Local("this")) + args.zip(k.params).map { (a, p) -> a.msiAs(p) }))
    }

    private fun lowerOp(op: String, l: Expr, r: Expr): Expr = Binary(when (op) {
        "+" -> BinaryOp.ADD; "-" -> BinaryOp.SUB; "*" -> BinaryOp.MUL; "/" -> BinaryOp.DIV; "%" -> BinaryOp.REM
        "&" -> BinaryOp.BIT_AND; "|" -> BinaryOp.BIT_OR; "^" -> BinaryOp.BIT_XOR; "<<" -> BinaryOp.SHL; else -> BinaryOp.SHR
    }, l, r)
}
