package com.leko.forge.msi

/**
 * Adds the copies of [MemoryKind.VALUE] values a language with value semantics (like Swift) does
 * implicitly: wherever a value-type variable is used but still needed afterwards, the use becomes a
 * [Copy]. The last use of a variable moves it, so no copy is made there. Front-ends can lower
 * `b = a` naively and call this before [validate].
 *
 * Returns a new module; the receiver is not changed.
 */
public fun Module.insertCopies(): Module {
    val sites = copySites()
    return rewrite { e -> if (e is Local && sites.contains(e)) Copy(e) else null }
}

/**
 * A copy of the module in which every expression for which [replace] returns a value is replaced
 * by it. [replace] gets the original expression; children of replaced expressions are not visited.
 */
public fun Module.rewrite(replace: (Expr) -> Expr?): Module = Rewriter(replace).module(this)

private class Rewriter(val replace: (Expr) -> Expr?) {
    fun module(m: Module): Module = Module(
        m.name, m.structs, m.functions.map(::function), m.globals.map { Global(it.name, it.type, it.init?.let(::expr)) }, m.unions,
    )

    fun function(f: Func) = Func(f.name, f.params, f.returnType, f.body?.let(::block), f.typeParams, f.lifetimes, f.outlives)

    fun block(b: Block): Block = Block(b.stmts.map(::stmt))

    fun stmt(s: Stmt): Stmt = when (s) {
        is Block -> block(s)
        is Let -> Let(s.name, s.type, s.init?.let(::expr))
        is Assign -> Assign(expr(s.target), expr(s.value))
        is Branch -> Branch(expr(s.condition), block(s.then), s.alternative?.let(::block))
        is Loop -> Loop(s.condition?.let(::expr), block(s.body), s.label)
        is Break -> Break(s.label)
        is Continue -> Continue(s.label)
        is Return -> Return(s.value?.let(::expr))
        is Eval -> Eval(expr(s.expr))
        is Match -> Match(expr(s.subject), s.cases.map { Case(it.variant, it.bindings, block(it.body)) }, s.otherwise?.let(::block))
        is Throw -> Throw(expr(s.value))
        is Try -> Try(block(s.body), s.catches.map { Catch(it.name, it.type, block(it.body)) }, s.finally?.let(::block))
        is Access -> Access(expr(s.target), s.mutable, block(s.body))
    }

    fun expr(e: Expr): Expr = replace(e) ?: when (e) {
        is IntLit -> IntLit(e.value, e.type)
        is FloatLit -> FloatLit(e.value)
        is BoolLit -> BoolLit(e.value)
        is StrLit -> StrLit(e.value)
        is Local -> Local(e.name)
        is Binary -> Binary(e.op, expr(e.left), expr(e.right))
        is Unary -> Unary(e.op, expr(e.operand))
        is Call -> Call(e.function, e.args.map(::expr), e.typeArgs)
        is New -> New(e.struct, e.args.map(::expr), e.typeArgs)
        is Get -> Get(expr(e.target), e.field)
        is NewArray -> NewArray(e.element, expr(e.size))
        is Index -> Index(expr(e.array), expr(e.index))
        is Length -> Length(expr(e.array))
        is Convert -> Convert(expr(e.value), e.to)
        is Conditional -> Conditional(expr(e.condition), expr(e.then), expr(e.otherwise))
        is Make -> Make(e.union, e.variant, e.args.map(::expr), e.typeArgs)
        is Is -> Is(expr(e.subject), e.variant)
        is FuncRef -> FuncRef(e.function, e.typeArgs)
        is Lambda -> Lambda(e.params, e.returnType, block(e.body))
        is Invoke -> Invoke(expr(e.callee), e.args.map(::expr))
        is Borrow -> Borrow(expr(e.place), e.mutable)
        is Deref -> Deref(expr(e.ref))
        is Copy -> Copy(expr(e.value))
        is Null -> Null(e.type)
    }
}
