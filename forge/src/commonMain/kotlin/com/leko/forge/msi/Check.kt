package com.leko.forge.msi

import com.leko.forge.IdentitySet
import com.leko.forge.TreeNode
import com.leko.forge.childrenOf
import com.leko.forge.coll
import com.leko.forge.inh
import com.leko.forge.parentOf
import com.leko.forge.syn
import com.leko.forge.tree

/** Something wrong in an MSI module, found by [validate]. [node] is where it was found. */
public data class Problem(public val message: String, public val node: Any) {
    override fun toString(): String = message
}

/**
 * Checks the module: names resolve, types match (with generics), control flow is valid (e.g. `break`
 * only in loops, functions with a result always return). Modules that use value or owned types,
 * references or lifetimes ([usesOwnership]) are also checked for ownership: no use after move, no
 * conflicting borrows, no references outliving their data. That is a property of the module, not an
 * option, because backends rely on it (e.g. to free owned values). Returns the problems found; an empty list
 * means backends and the [Interpreter] can process the module.
 *
 * Registers the module as a tree, so the resolution attributes ([type], [variable], [callee],
 * [target], [captures], ...) can be used afterwards.
 */
public fun Module.validate(): List<Problem> {
    tree(this)
    val basic = problemList
    // ownership rules build on well typed code, and only apply to modules that use them
    return if (basic.isEmpty() && usesOwnership) ownershipProblems else basic
}

// ==== resolution (also used by backends) =========================================================

/** The static type of an expression, [Type.Invalid] if it is wrong (and reported by [validate]). */
public val Expr.type: Type by syn { typeOf(this) }

/** The variable this refers to, or `null` if there is none. */
public val Local.variable: Variable? get() = scope.find(name)

/** The function called, or `null` if there is none. */
public val Call.callee: Func? get() = module.functionsByName[function]

/** The function referenced, or `null` if there is none. */
public val FuncRef.callee: Func? get() = module.functionsByName[function]

/** The struct of [New], or `null` if there is none. */
public val New.structDef: Struct? get() = module.structsByName[struct]

/** The union of [Make], or `null` if there is none. */
public val Make.unionDef: Union? get() = module.unionsByName[union]

/** The variant of [Make], or `null` if there is none. */
public val Make.variantDef: Variant? get() = unionDef?.variants?.firstOrNull { it.name == variant }

/** The struct [Get] reads a field of, or `null` if the target is not a struct. */
public val Get.struct: Struct? get() = (target.type as? Type.Struct)?.let { module.structsByName[it.name] }

/** The union matched on, or `null` if the subject is not a union. */
public val Match.union: Union? get() = (subject.type as? Type.Union)?.let { module.unionsByName[it.name] }

/** The union [Is] tests, or `null` if the subject is not a union. */
public val Is.union: Union? get() = (subject.type as? Type.Union)?.let { module.unionsByName[it.name] }

/** The variant handled by this case, or `null` if there is none. */
public val Case.variantDef: Variant? get() = (parentOf(this) as? Match)?.union?.variants?.firstOrNull { it.name == variant }

/** The variables bound by this case: one per binding, typed like the variant's fields. */
public val Case.variables: List<Variable> by syn { bindingsOf(this) }

/** The loop left by this break, or `null` if there is none. */
public val Break.target: Loop? get() = loops.lastOrNull { label == null || it.label == label }

/** The loop continued by this statement, or `null` if there is none. */
public val Continue.target: Loop? get() = loops.lastOrNull { label == null || it.label == label }

/** The function a node belongs to, or `null` outside functions. */
public val TreeNode.function: Func? get() = enclosingFunction

/** The type arguments of a call to a generic function: given or inferred. Empty if there are none. */
public val Call.typeArguments: List<Type> by syn {
    val f = callee ?: return@syn emptyList()
    resolveTypeArgs(f.typeParams, typeArgs, f.params.map { it.type }, args) ?: emptyList()
}

/** The type arguments of [New]: given or inferred. */
public val New.typeArguments: List<Type> by syn {
    val s = structDef ?: return@syn emptyList()
    resolveTypeArgs(s.typeParams, typeArgs, s.fields.map { it.type }, args) ?: emptyList()
}

/** The type arguments of [Make]: given or inferred. */
public val Make.typeArguments: List<Type> by syn {
    val u = unionDef ?: return@syn emptyList()
    val v = variantDef ?: return@syn emptyList()
    resolveTypeArgs(u.typeParams, typeArgs, v.fields.map { it.type }, args) ?: emptyList()
}

/**
 * The variables of enclosing functions and lambdas this lambda uses (also in nested lambdas), in the
 * order they are first used. Globals are not captured.
 */
public val Lambda.captures: List<Variable> by syn { capturesOf(this) }

/** Whether a lambda captures [variable]. Backends keep captured variables in a shared box. */
public fun Module.isCaptured(variable: Variable): Boolean = variable in capturedVariables

/** The function or lambda a variable belongs to, `null` for globals. */
public val Variable.owner: TreeNode? get() = when (this) {
    is Global -> null
    is Param -> parentOf(this) as? TreeNode
    is Let -> callable
    is Catch -> callable
    is CaseBinding -> case.callable
}

// ==== internal attributes ========================================================================

/** A variable bound by a [Case] to the field [index] of the matched variant. */
public class CaseBinding internal constructor(
    override val name: String,
    override val type: Type,
    public val case: Case,
    public val index: Int,
) : Variable {
    override fun toString(): String = "binding $name"
}

internal fun interface Scope { fun find(name: String): Variable? }

private fun Scope.with(variables: List<Variable>): Scope = Scope { name -> variables.lastOrNull { it.name == name } ?: find(name) }

internal val scopes = inh<Scope>()
    .on<Module> { child ->
        val visible = if (child is Global) globals.takeWhile { it !== child } else globals
        Scope { name -> visible.lastOrNull { it.name == name } }
    }
    .on<Func> { scope.with(params) }
    .on<Lambda> { scope.with(params) }
    .on<Block> { child -> scope.with(stmts.takeWhile { it !== child }.filterIsInstance<Let>()) }
    .on<Case> { scope.with(variables) }
    .on<Catch> { scope.with(listOf(this)) }
internal val TreeNode.scope: Scope by scopes

internal val TreeNode.module: Module by inh<Module>().on<Module> { this }

internal val TreeNode.enclosingFunction: Func? by inh<Func?>().on<Module> { null }.on<Func> { this }

/** The nearest enclosing function or lambda. */
internal val TreeNode.callable: TreeNode? by inh<TreeNode?>().on<Module> { null }.on<Func> { this }.on<Lambda> { this }

/** Type parameters in scope. */
internal val TreeNode.typeParamsInScope: Set<String> by inh<Set<String>>()
    .on<Module> { emptySet() }
    .on<Func> { typeParams.toSet() }
    .on<Struct> { typeParams.toSet() }
    .on<Union> { typeParams.toSet() }

/** Enclosing loops, innermost last. Only the body of a loop is inside it, not its condition. */
internal val TreeNode.loops: List<Loop> by inh<List<Loop>>()
    .on<Module> { emptyList() }
    .on<Func> { emptyList() }
    .on<Lambda> { emptyList() }
    .on<Loop> { child -> if (child === body) loops + this else loops }

internal val Module.functionsByName: Map<String, Func> by syn { functions.associateBy { it.name } }
internal val Module.structsByName: Map<String, Struct> by syn { structs.associateBy { it.name } }
internal val Module.unionsByName: Map<String, Union> by syn { unions.associateBy { it.name } }

internal val Module.capturedVariables: IdentitySet<Variable> by syn {
    val set = IdentitySet<Variable>()
    fun visit(node: Any) {
        if (node is Lambda) node.captures.forEach { set.add(it) }
        childrenOf(node).forEach(::visit)
    }
    visit(this)
    set
}

private fun bindingsOf(case: Case): List<Variable> {
    val match = parentOf(case) as? Match
    val union = match?.union
    val variant = case.variantDef
    val args = (match?.subject?.type as? Type.Union)?.args.orEmpty()
    val substitution = union?.typeParams?.zip(args)?.toMap().orEmpty()
    return case.bindings.mapIndexed { i, name ->
        val type = variant?.fields?.getOrNull(i)?.type?.substitute(substitution) ?: Type.Invalid
        CaseBinding(name, type, case, i)
    }
}

private fun isInside(node: Any?, ancestor: Any): Boolean {
    var current = node
    while (current != null) {
        if (current === ancestor) return true
        current = parentOf(current)
    }
    return false
}

private fun capturesOf(lambda: Lambda): List<Variable> {
    val result = ArrayList<Variable>()
    val seen = IdentitySet<Variable>()
    fun visit(node: Any) {
        if (node is Local) {
            val v = node.variable
            if (v != null && v !is Global && !isInside(v.owner, lambda) && seen.add(v)) result += v
        }
        childrenOf(node).forEach(::visit)
    }
    visit(lambda.body)
    return result
}

// ==== types ======================================================================================

/** Replaces type parameters by the types in [substitution]. */
public fun Type.substitute(substitution: Map<String, Type>): Type = if (substitution.isEmpty()) this else when (this) {
    is Type.Param -> substitution[name] ?: this
    is Type.Struct -> Type.Struct(name, args.map { it.substitute(substitution) }, lifetimes)
    is Type.Ref -> Type.Ref(target.substitute(substitution), mutable, lifetime)
    is Type.Union -> Type.Union(name, args.map { it.substitute(substitution) })
    is Type.Array -> Type.Array(element.substitute(substitution))
    is Type.Function -> Type.Function(params.map { it.substitute(substitution) }, result.substitute(substitution))
    else -> this
}

private fun Type.containsInvalid(): Boolean = when (this) {
    Type.Invalid -> true
    is Type.Struct -> args.any { it.containsInvalid() }
    is Type.Union -> args.any { it.containsInvalid() }
    is Type.Array -> element.containsInvalid()
    is Type.Function -> params.any { it.containsInvalid() } || result.containsInvalid()
    is Type.Ref -> target.containsInvalid()
    else -> false
}

/** Binds the type parameters [params] by matching [pattern] against [actual]. Returns false on conflicts. */
private fun unify(pattern: Type, actual: Type, params: Set<String>, bound: MutableMap<String, Type>): Boolean = when {
    pattern is Type.Param && pattern.name in params -> {
        val previous = bound[pattern.name]
        if (previous == null) { bound[pattern.name] = actual; true } else previous == actual
    }
    pattern is Type.Struct && actual is Type.Struct -> pattern.name == actual.name && pattern.args.size == actual.args.size &&
        pattern.args.zip(actual.args).all { (p, a) -> unify(p, a, params, bound) }
    pattern is Type.Union && actual is Type.Union -> pattern.name == actual.name && pattern.args.size == actual.args.size &&
        pattern.args.zip(actual.args).all { (p, a) -> unify(p, a, params, bound) }
    pattern is Type.Array && actual is Type.Array -> unify(pattern.element, actual.element, params, bound)
    pattern is Type.Ref && actual is Type.Ref -> unify(pattern.target, actual.target, params, bound)
    pattern is Type.Function && actual is Type.Function -> pattern.params.size == actual.params.size &&
        pattern.params.zip(actual.params).all { (p, a) -> unify(p, a, params, bound) } && unify(pattern.result, actual.result, params, bound)
    else -> true // mismatches are reported when the argument types are checked
}

/** The given type arguments, or the ones inferred from [args]; `null` if they can not be inferred. */
private fun resolveTypeArgs(typeParams: List<String>, given: List<Type>, paramTypes: List<Type>, args: List<Expr>): List<Type>? {
    if (typeParams.isEmpty()) return emptyList()
    if (given.isNotEmpty()) return given
    val bound = HashMap<String, Type>()
    val names = typeParams.toSet()
    for ((p, a) in paramTypes.zip(args)) {
        val t = a.type
        if (t.containsInvalid()) return null
        if (!unify(p, t, names, bound)) return null
    }
    return if (typeParams.all { it in bound }) typeParams.map { bound.getValue(it) } else null
}

private fun substitution(typeParams: List<String>, args: List<Type>): Map<String, Type> =
    if (typeParams.size == args.size) typeParams.zip(args).toMap() else emptyMap()

/** Type parameters declared by the node itself: checks of a generic declaration see its own parameters. */
private fun TreeNode.ownTypeParams(): List<String> = when (this) {
    is Func -> typeParams
    is Struct -> typeParams
    is Union -> typeParams
    else -> emptyList()
}

private fun TreeNode.isValid(type: Type): Boolean = when (type) {
    is Type.Struct -> module.structsByName[type.name]?.let { it.typeParams.size == type.args.size } == true && type.args.all { isValid(it) }
    is Type.Union -> module.unionsByName[type.name]?.let { it.typeParams.size == type.args.size } == true && type.args.all { isValid(it) }
    is Type.Array -> isValid(type.element)
    is Type.Function -> type.params.all { isValid(it) } && isValid(type.result)
    is Type.Ref -> type.target !is Type.Ref && isValid(type.target)
    is Type.Param -> type.name in typeParamsInScope || type.name in ownTypeParams()
    Type.Invalid -> false
    else -> true
}

private fun typeOf(e: Expr): Type {
    val invalid = Type.Invalid
    return when (e) {
        is IntLit -> if (e.type.isInteger) e.type else invalid
        is FloatLit -> Type.F64
        is BoolLit -> Type.Bool
        is StrLit -> Type.Str
        is Local -> e.variable?.type ?: invalid
        is Binary -> binaryType(e.op, e.left.type, e.right.type)
        is Unary -> {
            val t = e.operand.type
            when (e.op) {
                UnaryOp.NEG -> if (t.isNumeric) t else invalid
                UnaryOp.NOT -> if (t == Type.Bool) t else invalid
                UnaryOp.BIT_NOT -> if (t.isInteger) t else invalid
            }
        }
        is Call -> {
            val f = e.callee ?: return invalid
            if (f.typeParams.size != e.typeArguments.size) return invalid
            f.returnType.substitute(substitution(f.typeParams, e.typeArguments))
        }
        is New -> {
            val s = e.structDef ?: return invalid
            if (s.typeParams.size != e.typeArguments.size) invalid else Type.Struct(e.struct, e.typeArguments)
        }
        is Get -> {
            val t = e.target.type as? Type.Struct ?: return invalid
            val s = e.struct ?: return invalid
            s.fields.firstOrNull { it.name == e.field }?.type?.substitute(substitution(s.typeParams, t.args)) ?: invalid
        }
        is NewArray -> if (e.isValid(e.element)) Type.Array(e.element) else invalid
        is Index -> (e.array.type as? Type.Array)?.element ?: invalid
        is Length -> Type.I64
        is Convert -> if (canConvert(e.value.type, e.to)) e.to else invalid
        is Conditional -> if (e.then.type == e.otherwise.type) e.then.type else invalid
        is Make -> {
            val u = e.unionDef ?: return invalid
            if (e.variantDef == null || u.typeParams.size != e.typeArguments.size) invalid else Type.Union(e.union, e.typeArguments)
        }
        is Is -> Type.Bool
        is FuncRef -> {
            val f = e.callee ?: return invalid
            if (f.typeParams.size != e.typeArgs.size) invalid else f.type.substitute(substitution(f.typeParams, e.typeArgs))
        }
        is Lambda -> Type.Function(e.params.map { it.type }, e.returnType)
        is Invoke -> (e.callee.type as? Type.Function)?.result ?: invalid
        is Borrow -> if (isPlace(e.place) && e.place.type != invalid) Type.Ref(e.place.type, e.mutable) else invalid
        is Deref -> (e.ref.type as? Type.Ref)?.target ?: invalid
        is Copy -> e.value.type
        is Null -> if (isReference(e.type) && e.isValid(e.type)) e.type else invalid
    }
}

/** Types whose values can be null: shared structs, unions, arrays and functions. */
internal fun isReference(t: Type): Boolean = t is Type.Struct || t is Type.Union || t is Type.Array || t is Type.Function

/** A place: something that can be borrowed and assigned. */
internal fun isPlace(e: Expr): Boolean = when (e) {
    is Local -> true
    is Get -> isPlace(e.target)
    is Deref -> true
    else -> false
}

/** Whether the target of an assignment or mutable borrow may be written. Shared data is always writable. */
internal fun writable(e: Expr): Boolean = when (e) {
    is Deref -> (e.ref.type as? Type.Ref)?.mutable == true
    is Get -> e.target !is Deref && e.target !is Get || writable(e.target)
    else -> true
}

/** The memory kind of values of [t]: [MemoryKind.SHARED] for everything but value and owned structs and unions. */
public fun Module.kindOf(t: Type): MemoryKind = when (t) {
    is Type.Struct -> structsByName[t.name]?.kind ?: MemoryKind.SHARED
    is Type.Union -> unionsByName[t.name]?.kind ?: MemoryKind.SHARED
    else -> MemoryKind.SHARED
}

private fun binaryType(op: BinaryOp, l: Type, r: Type): Type {
    if (l == Type.Invalid || r == Type.Invalid || l != r) return Type.Invalid
    return when (op) {
        BinaryOp.ADD -> if (l.isNumeric || l == Type.Str) l else Type.Invalid
        BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.REM -> if (l.isNumeric) l else Type.Invalid
        BinaryOp.EQ, BinaryOp.NE -> if (l is Type.Param || l == Type.Unit) Type.Invalid else Type.Bool
        BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> if (l.isNumeric || l == Type.Str) Type.Bool else Type.Invalid
        BinaryOp.AND, BinaryOp.OR -> if (l == Type.Bool) l else Type.Invalid
        BinaryOp.BIT_AND, BinaryOp.BIT_OR, BinaryOp.BIT_XOR, BinaryOp.SHL, BinaryOp.SHR ->
            if (l.isInteger) l else Type.Invalid
    }
}

private fun canConvert(from: Type, to: Type): Boolean =
    from != Type.Invalid && (from == to || (from.isNumeric && to.isNumeric) || (to == Type.Str && from.isPrimitive))

private fun isThrowable(t: Type) = t is Type.Struct || t is Type.Union || t == Type.Str

// ==== control flow ===============================================================================

/** Whether running the statement never reaches its end normally (it always returns, throws or loops forever). */
internal fun Stmt.terminates(): Boolean = when (this) {
    is Return, is Throw -> true
    is Block -> stmts.any { it.terminates() }
    is Branch -> alternative != null && then.terminates() && alternative.terminates()
    is Loop -> condition == null && breaksOf(body).none { it.target === this }
    is Match -> otherwise != null && otherwise.terminates() && cases.all { it.body.terminates() }
    is Access -> body.terminates()
    is Try -> (finally?.terminates() == true) || (body.terminates() && catches.all { it.body.terminates() })
    else -> false
}

private fun breaksOf(node: Any): List<Break> =
    childrenOf(node).flatMap { if (it is Break) listOf(it) else if (it is Lambda) emptyList() else breaksOf(it) }

// ==== problems ===================================================================================

private fun duplicates(names: List<String>): Set<String> = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

/** Reports `actual` unless it is `expected` or already wrong. */
private fun mismatch(what: String, expected: Type, actual: Type, node: Any): List<Problem> =
    if (actual == expected || actual.containsInvalid() || expected.containsInvalid()) emptyList()
    else listOf(Problem("$what must be ${expected.render()}, not ${actual.render()}", node))

private fun TreeNode.where(): String = function?.let { "in fun ${it.name}: " } ?: ""

private fun TreeNode.problem(message: String) = Problem(where() + message, this)

private fun TreeNode.checkType(type: Type, what: String): List<Problem> =
    if (isValid(type)) emptyList() else listOf(problem("unknown type ${type.render()} of $what"))

private fun TreeNode.checkArgs(what: String, params: List<Type>, args: List<Expr>): List<Problem> {
    if (params.size != args.size) return listOf(problem("$what expects ${params.size} arguments, not ${args.size}"))
    return params.zip(args).flatMap { (p, a) -> mismatch(where() + "argument of $what", p, a.type, a) }
}

private fun TreeNode.checkTypeArgs(what: String, typeParams: List<String>, given: List<Type>, resolved: List<Type>): List<Problem> = when {
    typeParams.isEmpty() && given.isNotEmpty() -> listOf(problem("$what is not generic"))
    given.isNotEmpty() && given.size != typeParams.size -> listOf(problem("$what expects ${typeParams.size} type arguments, not ${given.size}"))
    typeParams.isNotEmpty() && resolved.size != typeParams.size -> listOf(problem("can not infer the type arguments of $what"))
    else -> given.flatMap { checkType(it, "a type argument of $what") }
}

private fun TreeNode.returns(returnType: Type, body: Block, what: String): List<Problem> =
    if (returnType != Type.Unit && !body.terminates()) listOf(problem("$what may end without returning a value")) else emptyList()

internal val problems = coll<List<Problem>>(emptyList(), owner = Module::class)
    .on<Module> { p ->
        p + duplicates(structs.map { it.name } + unions.map { it.name }).map { Problem("type '$it' is defined more than once", this) } +
            duplicates(functions.map { it.name }).map { Problem("fun '$it' is defined more than once", this) } +
            duplicates(globals.map { it.name }).map { Problem("global '$it' is defined more than once", this) }
    }
    .on<Struct> { p ->
        p + duplicates(fields.map { it.name }).map { Problem("field '$it' of struct $name is defined more than once", this) } +
            fields.flatMap { checkType(it.type, "field ${it.name} of struct $name") }
    }
    .on<Union> { p ->
        p + duplicates(variants.map { it.name }).map { Problem("variant '$it' of union $name is defined more than once", this) } +
            variants.flatMap { v ->
                duplicates(v.fields.map { it.name }).map { Problem("field '$it' of $name.${v.name} is defined more than once", this) } +
                    v.fields.flatMap { checkType(it.type, "field ${it.name} of $name.${v.name}") }
            }
    }
    .on<Global> { p -> p + checkType(type, "global $name") + (init?.let { mismatch("value of global '$name'", type, it.type, it) } ?: emptyList()) }
    .on<Func> { p ->
        p + duplicates(params.map { it.name }).map { Problem("parameter '$it' of fun $name is defined more than once", this) } +
            params.flatMap { checkType(it.type, "parameter ${it.name} of fun $name") } +
            checkType(returnType, "the result of fun $name") +
            (body?.let { returns(returnType, it, "fun $name") } ?: emptyList())
    }
    .on<Lambda> { p -> p + params.flatMap { checkType(it.type, "lambda parameter ${it.name}") } + checkType(returnType, "a lambda result") + returns(returnType, body, "lambda") }
    .on<Let> { p ->
        p + checkType(type, "variable $name") + (init?.let { mismatch(where() + "value of '$name'", type, it.type, it) } ?: emptyList())
    }
    .on<Assign> { p ->
        if (target !is Local && target !is Get && target !is Index && target !is Deref) p + problem("can only assign to variables, fields, array elements and references")
        else if (!writable(target)) p + problem("can not assign through a reference that is not mutable")
        else p + mismatch(where() + "assigned value", target.type, value.type, value)
    }
    .on<Borrow> { p ->
        when {
            !isPlace(place) -> p + problem("can only borrow variables, fields and references")
            mutable && !writable(place) -> p + problem("can not borrow mutably through a reference that is not mutable")
            place.type is Type.Ref -> p + problem("can not borrow a reference; borrow what it points to instead")
            else -> p
        }
    }
    .on<Null> { p -> if (!isReference(type)) p + problem("null is only a value of struct, union, array and function types, not ${type.render()}") else p }
    .on<Deref> { p -> val t = ref.type; if (t != Type.Invalid && t !is Type.Ref) p + problem("${t.render()} is not a reference") else p }
    .on<Access> { p ->
        val t = target.type
        val shared = (t is Type.Struct || t is Type.Union) && module.kindOf(t) == MemoryKind.SHARED || t is Type.Array
        if (t != Type.Invalid && !shared) p + problem("can only check access to shared structs, unions and arrays, not ${t.render()}") else p
    }
    .on<Branch> { p -> p + mismatch(where() + "condition", Type.Bool, condition.type, condition) }
    .on<Loop> { p -> p + (condition?.let { mismatch(where() + "loop condition", Type.Bool, it.type, it) } ?: emptyList()) }
    .on<Break> { p -> if (target == null) p + problem(if (label == null) "break outside of a loop" else "no loop with label '$label'") else p }
    .on<Continue> { p -> if (target == null) p + problem(if (label == null) "continue outside of a loop" else "no loop with label '$label'") else p }
    .on<Return> { p ->
        val expected = when (val c = callable) {
            is Func -> c.returnType
            is Lambda -> c.returnType
            else -> return@on p + Problem("return outside of a function", this)
        }
        when {
            value == null && expected != Type.Unit -> p + problem("return needs a value of type ${expected.render()}")
            value != null && expected == Type.Unit -> p + problem("return can not have a value here")
            value != null -> p + mismatch(where() + "return value", expected, value.type, value)
            else -> p
        }
    }
    .on<Match> { p ->
        val t = subject.type
        val u = union
        when {
            t == Type.Invalid -> p
            u == null -> p + problem("can only match on unions, not ${t.render()}")
            else -> p + duplicates(cases.map { it.variant }).map { problem("variant '$it' is matched more than once") } +
                cases.flatMap { c ->
                    val v = u.variants.firstOrNull { it.name == c.variant }
                    when {
                        v == null -> listOf(problem("union ${u.name} has no variant '${c.variant}'"))
                        c.bindings.size > v.fields.size -> listOf(problem("${u.name}.${v.name} has ${v.fields.size} fields, not ${c.bindings.size}"))
                        else -> emptyList()
                    }
                }
        }
    }
    .on<Throw> { p -> val t = value.type; if (t != Type.Invalid && !isThrowable(t)) p + problem("can only throw structs, unions and strings, not ${t.render()}") else p }
    .on<Catch> { p -> p + checkType(type, "catch $name") + (if (isValid(type) && !isThrowable(type)) listOf(problem("can only catch structs, unions and strings, not ${type.render()}")) else emptyList()) }
    .on<IntLit> { p -> if (type.isInteger) p else p + problem("integer literal must have an integer type, not ${type.render()}") }
    .on<Local> { p -> if (variable == null) p + problem("unknown variable '$name'") else p }
    .on<Binary> { p ->
        val bad = type == Type.Invalid && left.type != Type.Invalid && right.type != Type.Invalid
        if (bad) p + problem("operator ${op.symbol} can not be applied to ${left.type.render()} and ${right.type.render()}") else p
    }
    .on<Unary> { p ->
        if (type == Type.Invalid && operand.type != Type.Invalid) p + problem("operator ${op.symbol} can not be applied to ${operand.type.render()}") else p
    }
    .on<Call> { p ->
        val f = callee ?: return@on p + problem("unknown function '$function'")
        val typeArgProblems = checkTypeArgs("fun $function", f.typeParams, typeArgs, typeArguments)
        if (typeArgProblems.isNotEmpty()) return@on p + typeArgProblems
        val s = substitution(f.typeParams, typeArguments)
        p + checkArgs("fun $function", f.params.map { it.type.substitute(s) }, args)
    }
    .on<New> { p ->
        val st = structDef ?: return@on p + problem("unknown struct '$struct'")
        val typeArgProblems = checkTypeArgs("struct $struct", st.typeParams, typeArgs, typeArguments)
        if (typeArgProblems.isNotEmpty()) return@on p + typeArgProblems
        val s = substitution(st.typeParams, typeArguments)
        p + checkArgs("struct $struct", st.fields.map { it.type.substitute(s) }, args)
    }
    .on<Make> { p ->
        val u = unionDef ?: return@on p + problem("unknown union '$union'")
        val v = variantDef ?: return@on p + problem("union $union has no variant '$variant'")
        val typeArgProblems = checkTypeArgs("$union.$variant", u.typeParams, typeArgs, typeArguments)
        if (typeArgProblems.isNotEmpty()) return@on p + typeArgProblems
        val s = substitution(u.typeParams, typeArguments)
        p + checkArgs("$union.$variant", v.fields.map { it.type.substitute(s) }, args)
    }
    .on<Is> { p ->
        val t = subject.type
        val u = union
        when {
            t == Type.Invalid -> p
            u == null -> p + problem("can only test the variant of unions, not ${t.render()}")
            u.variants.none { it.name == variant } -> p + problem("union ${u.name} has no variant '$variant'")
            else -> p
        }
    }
    .on<FuncRef> { p ->
        val f = callee ?: return@on p + problem("unknown function '$function'")
        when {
            f.typeParams.isEmpty() && typeArgs.isNotEmpty() -> p + problem("fun $function is not generic")
            f.typeParams.size != typeArgs.size -> p + problem("a reference to fun $function needs ${f.typeParams.size} type arguments")
            else -> p + typeArgs.flatMap { checkType(it, "a type argument of fun $function") }
        }
    }
    .on<Invoke> { p ->
        val t = callee.type
        when {
            t == Type.Invalid -> p
            t !is Type.Function -> p + problem("${t.render()} is not a function")
            else -> p + checkArgs("the function value", t.params, args)
        }
    }
    .on<Get> { p ->
        val t = target.type
        when {
            t == Type.Invalid -> p
            t !is Type.Struct -> p + problem("${t.render()} has no fields")
            type == Type.Invalid -> p + problem("struct ${t.name} has no field '$field'")
            else -> p
        }
    }
    .on<NewArray> { p -> p + checkType(element, "array elements") + integer(size, "array size") }
    .on<Index> { p ->
        val t = array.type
        p + (if (t != Type.Invalid && t !is Type.Array) listOf(problem("${t.render()} is not an array")) else emptyList()) +
            integer(index, "array index")
    }
    .on<Length> { p -> val t = array.type; if (t != Type.Invalid && t !is Type.Array) p + problem("${t.render()} is not an array") else p }
    .on<Convert> { p ->
        if (type == Type.Invalid && value.type != Type.Invalid) p + problem("can not convert ${value.type.render()} to ${to.render()}") else p
    }
    .on<Conditional> { p ->
        p + mismatch(where() + "condition", Type.Bool, condition.type, condition) +
            mismatch(where() + "alternative of the conditional", then.type, otherwise.type, otherwise)
    }

private fun TreeNode.integer(e: Expr, what: String): List<Problem> {
    val t = e.type
    return if (t == Type.Invalid || t.isInteger) emptyList() else listOf(problem("$what must be an integer, not ${t.render()}"))
}

internal val Module.problemList: List<Problem> by problems
