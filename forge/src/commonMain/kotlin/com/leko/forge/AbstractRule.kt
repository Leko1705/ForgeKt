package com.leko.forge

import kotlin.concurrent.Volatile
import kotlin.reflect.KProperty

/**
 * Creates an abstract rule: a rule without a body of its own, made of alternatives that can be
 * added anywhere, also from other modules. Like an abstract synthesized attribute (`syn()`), it is
 * implemented by others, and like [ParseScope.oneOf], the first alternative that matches wins.
 *
 * ```
 * val expr: AbstractRule<Expr> by rule<Expr>()
 *     .on { Num(number().toInt()) }
 *     .on { +"("; expr().also { +")" } }
 *     .prefix(30) { +"-"; Neg(operand()) }
 *     .infixLeft(10) { l -> +"+"; Plus(l, operand()) }
 *     .infixLeft(20) { l -> +"*"; Times(l, operand()) }
 *     .infixRight(40) { l -> +"^"; Pow(l, operand()) }
 *
 * // a language extension adds an operator without touching the grammar
 * object Comparisons { init { expr.infixLeft(5) { l -> +"<"; Less(l, operand()) } } }
 * ```
 *
 * Operators have a precedence (higher binds stronger) and an associativity, so no rule per precedence
 * level and no left recursion is needed: `1 + 2 * 3 ^ 4 ^ 5` is `1 + (2 * (3 ^ (4 ^ 5)))`. Inside an
 * operator, [ParseScope.operand] parses the operand with the right binding strength. Operators with
 * a more complex shape work too, e.g. a conditional: `.infixRight(2) { c -> +"?"; val a = expr(); +":"; If(c, a, operand()) }`.
 *
 * Parsing works by precedence climbing: first one plain alternative or prefix operator, then as many
 * infix and postfix operators as bind strongly enough. Within each group, alternatives are tried in
 * the order they were added. Put longer operators first when one is a prefix of another (`<=` before
 * `<`) unless the shorter one fails anyway, which backtracking handles.
 *
 * Adding alternatives affects parses started afterwards. As with other extensions, make sure the module
 * that adds them is loaded before parsing.
 */
public fun <T> rule(): AbstractRule<T> = AbstractRule("<rule>")


public class AbstractRule<T> internal constructor(name: String) : Rule<T>(name, null) {

    internal class Alternative<T>(
        val precedence: Int,
        /** Binding strength of [ParseScope.operand]; `null` for plain alternatives (no operand). */
        val operandPrecedence: Int?,
        val parse: (ParseScope.() -> T)? = null,
        val extend: (ParseScope.(T) -> T)? = null,
    )

    /** Plain alternatives and prefix operators, which start an expression. */
    @Volatile internal var alternatives: List<Alternative<T>> = emptyList()
        private set

    /** Infix and postfix operators, which extend an expression already parsed. */
    @Volatile internal var operators: List<Alternative<T>> = emptyList()
        private set

    /** Adds a plain alternative, e.g. a literal or a parenthesized expression. */
    public fun on(alternative: ParseScope.() -> T): AbstractRule<T> =
        addAlternative(Alternative(Int.MAX_VALUE, null, parse = alternative))

    /**
     * Adds a prefix operator such as `-x`. Its operand binds at least as strongly as [precedence],
     * so `-a * b` is `(-a) * b` if `*` binds weaker than `-`.
     */
    public fun prefix(precedence: Int, operator: ParseScope.() -> T): AbstractRule<T> =
        addAlternative(Alternative(precedence, precedence, parse = operator))

    /** Adds a left associative infix operator: `a - b - c` is `(a - b) - c`. It receives the left operand. */
    public fun infixLeft(precedence: Int, operator: ParseScope.(left: T) -> T): AbstractRule<T> =
        addOperator(Alternative(precedence, precedence + 1, extend = operator))

    /** Adds a right associative infix operator: `a ^ b ^ c` is `a ^ (b ^ c)`. It receives the left operand. */
    public fun infixRight(precedence: Int, operator: ParseScope.(left: T) -> T): AbstractRule<T> =
        addOperator(Alternative(precedence, precedence, extend = operator))

    /** Adds a postfix operator such as `x!` or a call `f(x)`. It receives the operand. */
    public fun postfix(precedence: Int, operator: ParseScope.(operand: T) -> T): AbstractRule<T> =
        addOperator(Alternative(precedence, precedence, extend = operator))

    private fun addAlternative(alternative: Alternative<T>): AbstractRule<T> {
        locked(this) { alternatives = alternatives + alternative }
        return this
    }

    private fun addOperator(operator: Alternative<T>): AbstractRule<T> {
        locked(this) { operators = operators + operator }
        return this
    }

    override fun run(scope: ParseScope, precedence: Int): T = scope.climb(this, precedence)

    override fun provideDelegate(thisRef: Any?, property: KProperty<*>): AbstractRule<T> {
        name = property.name
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): AbstractRule<T> = this
}
