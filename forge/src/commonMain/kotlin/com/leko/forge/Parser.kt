package com.leko.forge

import kotlin.reflect.KProperty

/**
 * A grammar rule. The body runs top to bottom and consumes input as it goes:
 *
 * ```
 * val plus: Rule<Plus> by rule { val l = expr(); +"+"; Plus(l, atom()) }
 * val classFile by rule { +hex("CAFEBABE"); val minor = u2(); val major = u2(); ... }
 * ```
 *
 * Text:
 * - `+"fun"` matches a literal and returns it; `token(regex)` defines a token rule
 *
 * Binary (when parsing a [ByteArray], big-endian, the position can be any bit):
 * - `+bits("0101")` and `+hex("CAFEBABE")` match exact bit patterns
 * - `uint(n)`, `sint(n)`, `bit()`, `u1()`, `u2()`, `u4()` and `bytes(n)` read values
 * - text literals and tokens work on binary input too, at byte boundaries, with each byte read as one
 *   character (ISO-8859-1). This lets binary and text grammars be mixed
 *
 * Both:
 * - `expr()` applies another rule and returns its value
 * - `optional { }`, `many { }`, `some { }`, `sepBy(rule, ",")` and `oneOf(a, b, ...)`
 *   cover `?`, `*`, `+`, separated lists and alternatives. For count-prefixed lists use
 *   `List(count) { item() }`
 *
 * The parser is a packrat parser: results are memoized per rule and position, so
 * backtracking is cheap. It also supports left recursion (`expr -> expr '+' atom`).
 */
public open class Rule<out T> internal constructor(
    internal var name: String,
    private val body: (ParseScope.() -> T)?,
) : Parselet<T> {

    override fun parseIn(scope: ParseScope): T = scope.apply(this)

    /** Parses this rule once at the current position. [precedence] is only used by [AbstractRule]. */
    internal open fun run(scope: ParseScope, precedence: Int): T = body!!(scope)

    public open operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): Rule<T> {
        name = property.name
        return this
    }

    public open operator fun getValue(thisRef: Any?, property: KProperty<*>): Rule<T> = this

    /**
     * Parses all of [input] with this rule and registers the result as a tree,
     * so inherited attributes can be used on it right away.
     *
     * [skip] matches what is ignored before every literal and token (whitespace by default).
     * [errors] turns a failed parse into the message of the thrown [ParseException].
     * [source] names the input in error messages, e.g. a file name.
     */
    public fun parse(
        input: String,
        skip: Regex = Regex("\\s+"),
        errors: ErrorFormatter = DefaultErrorFormatter,
        source: String? = null,
    ): T = finish(ParseScope(input, null, skip, errors, source).parseAll(this))

    /**
     * Parses all bits of [input] with this rule and registers the result as a tree.
     * By default nothing is skipped. Pass [skip] only if text inside the bytes should skip whitespace.
     * [errors] turns a failed parse into the message of the thrown [ParseException].
     */
    public fun parse(
        input: ByteArray,
        skip: Regex? = null,
        errors: ErrorFormatter = DefaultErrorFormatter,
        source: String? = null,
    ): T = finish(ParseScope(latin1(input), input, skip, errors, source).parseAll(this))

    private fun finish(result: T): T {
        if (result != null) tree(result)
        return result
    }

    override fun toString(): String = name
}

/**
 * Something that parses a [T] at the current position: a [Rule], or a lambda passed to a
 * combinator such as `oneOf(plus, atom, { +"("; expr().also { +")" } })`. Lambdas run in the
 * scope of the enclosing rule, so they can use `+"..."`, rules and combinators directly.
 */
public fun interface Parselet<out T> {
    public fun parseIn(scope: ParseScope): T
}

/** Defines a rule; use it as a delegate (`val x by rule { ... }`) to name it in error messages. */
public fun <T> rule(body: ParseScope.() -> T): Rule<T> = Rule("<rule>", body)

/** A rule that matches the regular expression [pattern] and returns the matched text. */
public fun token(pattern: String): Rule<String> = token(pattern, null)

/**
 * A token that never matches one of the [keywords], e.g. identifiers: keywords take precedence, so
 * `return x` is never the variable `return` followed by `x`. Add keywords later (e.g. from a language
 * extension) with [Keywords.add].
 *
 * ```
 * val keywords = Keywords("fun", "var", "while", "return")
 * val ident by token("[A-Za-z_]\\w*", except = keywords)
 * ```
 */
public fun token(pattern: String, except: Keywords?): Rule<String> {
    val regex = Regex(pattern)
    lateinit var self: Rule<String>
    self = Rule("<token>") { match(regex, self.name, except) }
    return self
}

/** Words that tokens defined with `token(pattern, except = keywords)` never match. */
public class Keywords(vararg words: String) {
    @kotlin.concurrent.Volatile private var words: Set<String> = words.toSet()

    /** Adds [words], e.g. the keywords of a language extension. Returns this set. */
    public fun add(vararg words: String): Keywords {
        locked(this) { this.words = this.words + words }
        return this
    }

    public operator fun contains(word: String): Boolean = word in words
    override fun toString(): String = words.toString()
}


/** An exact bit pattern, matched with `+pattern` in a rule. */
public class Bits internal constructor(internal val bits: BooleanArray, private val description: String) {
    public val width: Int get() = bits.size
    override fun toString(): String = description
}

/**
 * Bits given as a string of `0` and `1`, e.g. `bits("0101_0011")`. `_` and spaces are ignored.
 *
 * A string is used instead of a number literal so that leading zeros count: `0b0101 == 0b101`.
 */
public fun bits(pattern: String): Bits {
    val clean = pattern.filterNot { it == '_' || it == ' ' }
    require(clean.isNotEmpty() && clean.all { it == '0' || it == '1' }) { "invalid bit pattern: $pattern" }
    return Bits(BooleanArray(clean.length) { clean[it] == '1' }, "0b$clean")
}

/** The lowest [width] bits of [value], most significant first, e.g. `bits(0b0101, width = 4)`. */
public fun bits(value: Long, width: Int): Bits {
    require(width in 1..64) { "width must be between 1 and 64" }
    val bits = BooleanArray(width) { (value ushr (width - 1 - it)) and 1L == 1L }
    return Bits(bits, "0b" + bits.joinToString("") { if (it) "1" else "0" })
}

/** Bytes given as hex digits, e.g. `hex("CAFEBABE")` or `hex("CA FE BA BE")`. Each digit is 4 bits. */
public fun hex(pattern: String): Bits {
    val clean = pattern.filterNot { it == '_' || it == ' ' }
    require(clean.isNotEmpty()) { "empty hex pattern" }
    val bits = BooleanArray(clean.length * 4)
    clean.forEachIndexed { i, c ->
        val digit = c.digitToIntOrNull(16) ?: throw IllegalArgumentException("invalid hex pattern: $pattern")
        for (b in 0 until 4) bits[i * 4 + b] = (digit shr (3 - b)) and 1 == 1
    }
    return Bits(bits, "0x" + clean.uppercase())
}


public class ParseScope internal constructor(
    /** The text, or the bytes read as ISO-8859-1 characters for binary input. */
    private val text: String,
    private val bytes: ByteArray?,
    private val skip: Regex?,
    private val errors: ErrorFormatter,
    private val source: String?,
) {
    /** Thrown on mismatch. It is preallocated and has no stack trace, so backtracking stays cheap. */
    // a single instance: its stack trace is created once, so backtracking stays cheap
    private object Mismatch : RuntimeException("mismatch")

    private class Frame
    /** An abstract rule gives different results at different minimum precedences, so they are memoized separately. */
    private class Key(val rule: Rule<*>, val pos: Int, val precedence: Int) {
        override fun hashCode() = (rule.hashCode() * 31 + pos) * 31 + precedence // Rule keeps identity hashCode
        override fun equals(other: Any?) =
            other is Key && other.rule === rule && other.pos == pos && other.precedence == precedence
    }
    /** [head] is set while the rule is running at this position. It is used to detect left recursion. */
    private class Memo(var ok: Boolean, var value: Any?, var end: Int, var head: Frame?)

    /** How many position units one character takes: 1 for text, 8 bits for binary input. */
    private val unit = if (bytes == null) 1 else 8
    private val size = text.length * unit

    /** Position in characters (text input) or bits (binary input). */
    private var pos = 0
    private val memo = HashMap<Key, Memo>()
    private val stack = ArrayList<Frame>()
    private val leftRecursive = HashSet<Frame>()
    private val involved = HashSet<Frame>()

    private var furthest = -1
    private val expected = LinkedHashSet<String>()

    // ---- text primitives ---------------------------------------------------------------------

    /** Matches the literal. A literal ending in a letter or digit must not be followed by one (`fun` ≠ `funny`). */
    public operator fun String.unaryPlus(): String {
        skipIgnored()
        if (pos % unit != 0) fail("'$this'")
        val start = pos / unit
        val end = start + length
        if (!text.startsWith(this, start) ||
            (isNotEmpty() && last().isWordChar() && end < text.length && text[end].isWordChar())
        ) fail("'$this'")
        pos = end * unit
        return this
    }

    internal fun match(regex: Regex, name: String, except: Keywords? = null): String {
        skipIgnored()
        if (pos % unit != 0) fail(name)
        val m = regex.matchAt(text, pos / unit) ?: fail(name)
        if (except != null && m.value in except) fail(name)
        pos = (m.range.last + 1) * unit
        return m.value
    }

    private fun skipIgnored() {
        if (skip == null || pos % unit != 0) return
        while (true) {
            val m = skip.matchAt(text, pos / unit) ?: return
            if (m.value.isEmpty()) return
            pos = (m.range.last + 1) * unit
        }
    }

    // ---- binary primitives -------------------------------------------------------------------

    /** Matches the exact bit pattern, e.g. `+bits("1010")` or `+hex("CAFEBABE")`. */
    public operator fun Bits.unaryPlus(): Bits {
        requireBinary()
        if (pos + width > size) fail(toString())
        for (i in 0 until width) if (bitAt(pos + i) != bits[i]) fail(toString())
        pos += width
        return this
    }

    /** Reads [width] bits (0..64) as an unsigned number, most significant bit first. */
    public fun uint(width: Int): Long {
        requireBinary()
        require(width in 0..64) { "width must be between 0 and 64" }
        if (pos + width > size) fail("$width more bits")
        var value = 0L
        if (pos % 8 == 0 && width % 8 == 0) {
            for (i in 0 until width / 8) value = (value shl 8) or (bytes!![pos / 8 + i].toLong() and 0xFF)
        } else {
            for (i in 0 until width) value = (value shl 1) or (if (bitAt(pos + i)) 1L else 0L)
        }
        pos += width
        return value
    }

    /** Reads [width] bits (1..64) as a two's complement number. */
    public fun sint(width: Int): Long {
        require(width in 1..64) { "width must be between 1 and 64" }
        val value = uint(width)
        return if (width < 64 && (value ushr (width - 1)) and 1L == 1L) value - (1L shl width) else value
    }

    public fun bit(): Boolean = uint(1) == 1L

    /** Unsigned 8 bit number (`u1` in the JVM class file format). */
    public fun u1(): Int = uint(8).toInt()

    /** Unsigned 16 bit number (`u2` in the JVM class file format). */
    public fun u2(): Int = uint(16).toInt()

    /** Unsigned 32 bit number (`u4` in the JVM class file format). */
    public fun u4(): Long = uint(32)

    /** Reads [count] raw bytes. They need not start at a byte boundary. */
    public fun bytes(count: Int): ByteArray {
        requireBinary()
        if (pos + count * 8 > size) fail("$count more bytes")
        if (pos % 8 == 0) {
            val result = bytes!!.copyOfRange(pos / 8, pos / 8 + count)
            pos += count * 8
            return result
        }
        return ByteArray(count) { uint(8).toByte() }
    }

    private fun bitAt(bitPos: Int): Boolean =
        (bytes!![bitPos ushr 3].toInt() shr (7 - (bitPos and 7))) and 1 == 1

    private fun requireBinary() {
        check(bytes != null) { "bit and byte primitives need binary input: parse a ByteArray" }
    }

    // ---- rules and failure -------------------------------------------------------------------

    public operator fun <T> Rule<T>.invoke(): T = apply(this)

    /** Fails at the current position, reporting [expected] in the error message. */
    public fun fail(expected: String): Nothing {
        if (pos > furthest) {
            furthest = pos
            this.expected.clear()
        }
        if (pos == furthest) this.expected += expected
        throw Mismatch
    }

    // ---- combinators -------------------------------------------------------------------------

    /** `x?`: the value of [body], or `null` (consuming nothing) if it does not match. */
    public fun <T> optional(body: Parselet<T>): T? {
        val start = pos
        return try {
            body.parseIn(this)
        } catch (_: Mismatch) {
            pos = start
            null
        }
    }

    /** `x*` */
    public fun <T> many(body: Parselet<T>): List<T> {
        val result = ArrayList<T>()
        while (true) {
            val start = pos
            try {
                result += body.parseIn(this)
            } catch (_: Mismatch) {
                pos = start
                return result
            }
            if (pos == start) return result // matched nothing: stop, or this would loop forever
        }
    }

    /** `x+` */
    public fun <T> some(body: Parselet<T>): List<T> = listOf(body.parseIn(this)) + many(body)

    /** Zero or more [item]s separated by [separator]. */
    public fun <T> sepBy(item: Parselet<T>, separator: String): List<T> {
        val first = optional(item) ?: return emptyList()
        return listOf(first) + many { +separator; item.parseIn(this) }
    }

    /** Alternatives. Tries them in order and returns the first one that matches (PEG ordered choice). */
    /** The current position: a character offset (a byte offset for binary input). */
    public val offset: Int get() = pos / unit

    /** The input text (bytes as ISO-8859-1 characters for binary input). */
    public val input: String get() = text

    /** Whether [body] matches at the current position. Consumes nothing (a PEG `&` / `!` predicate). */
    public fun lookahead(body: Parselet<*>): Boolean {
        val start = pos
        return try {
            body.parseIn(this)
            true
        } catch (_: Mismatch) {
            false
        } finally {
            pos = start
        }
    }

    public fun <T> oneOf(vararg alternatives: Parselet<T>): T {
        val start = pos
        for (alternative in alternatives) {
            try {
                return alternative.parseIn(this)
            } catch (_: Mismatch) {
                pos = start
            }
        }
        throw Mismatch
    }

    // ---- abstract rules ----------------------------------------------------------------------

    private class OperatorContext(val rule: AbstractRule<*>, val operandPrecedence: Int)

    /** The operator whose body is running, or `null` inside a rule (a barrier). */
    private val operators = ArrayList<OperatorContext?>()

    /**
     * Inside an operator of an [AbstractRule]: parses its operand with the binding strength the
     * operator's precedence and associativity require. For `infixLeft(10) { l -> +"+"; Plus(l, operand()) }`,
     * the operand only contains operators with a precedence above 10, so `1 + 2 + 3` is `(1 + 2) + 3`.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> operand(): T {
        val context = operators.lastOrNull()
            ?: error("operand() can only be called directly in prefix, infix or postfix operators of an abstract rule")
        return apply(context.rule, context.operandPrecedence) as T
    }

    /** Runs [block] as the body of an operator, or of a plain alternative if [operandPrecedence] is `null`. */
    private inline fun <R> inOperator(rule: AbstractRule<*>, operandPrecedence: Int?, block: () -> R): R {
        operators.add(operandPrecedence?.let { OperatorContext(rule, it) })
        try {
            return block()
        } finally {
            operators.removeAt(operators.size - 1)
        }
    }

    /**
     * Precedence climbing: parses one alternative (a plain one or a prefix operator), then keeps
     * extending it with infix and postfix operators that bind at least as strongly as [minPrecedence].
     */
    internal fun <T> climb(rule: AbstractRule<T>, minPrecedence: Int): T {
        val start = pos
        var left: T = run {
            for (alternative in rule.alternatives) {
                try {
                    return@run inOperator(rule, alternative.operandPrecedence) { alternative.parse!!(this) }.also { it?.let { v -> record(v, start, pos) } }
                } catch (_: Mismatch) {
                    pos = start
                }
            }
            if (rule.alternatives.isEmpty()) error("abstract rule '${rule.name}' has no alternatives")
            throw Mismatch
        }
        extend@ while (true) {
            for (operator in rule.operators) {
                if (operator.precedence < minPrecedence) continue
                val before = pos
                try {
                    val extended = inOperator(rule, operator.operandPrecedence) { operator.extend!!(this, left) }
                    if (pos == before) { pos = before; continue } // consumed nothing: would loop forever
                    left = extended
                    extended?.let { record(it, start, pos) }
                    continue@extend
                } catch (_: Mismatch) {
                    pos = before
                }
            }
            return left
        }
    }

    // ---- engine ------------------------------------------------------------------------------

    /**
     * Applies [rule] at the current position with memoization and left recursion support
     * (seed growing, see Warth et al., "Packrat Parsers Can Support Left Recursion").
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T> apply(rule: Rule<T>, precedence: Int = Int.MIN_VALUE): T {
        val start = pos
        val key = Key(rule, start, precedence)
        memo[key]?.let { m ->
            m.head?.let { head -> // the rule calls itself at the same position: left recursion
                leftRecursive += head
                // rules between the head and this call depend on the seed: never memoize them
                for (i in stack.lastIndexOf(head) + 1 until stack.size) involved += stack[i]
            }
            if (!m.ok) throw Mismatch
            pos = m.end
            return m.value as T
        }

        val frame = Frame()
        val m = Memo(false, null, start, frame) // a recursive call fails at first (the seed)
        memo[key] = m
        stack += frame
        operators.add(null) // operand() is only valid directly inside an operator, not in rules it calls
        try {
            attempt(rule, precedence, start, m)
            if (frame in leftRecursive) {
                // grow the seed as long as each new attempt consumes more input
                while (m.ok) {
                    val (value, end) = m.value to m.end
                    if (!attempt(rule, precedence, start, m) || m.end <= end) {
                        m.ok = true; m.value = value; m.end = end
                        break
                    }
                }
            }
        } finally {
            operators.removeAt(operators.size - 1)
            stack.removeAt(stack.size - 1)
            m.head = null
            if (frame in involved) memo.remove(key)
            leftRecursive -= frame
            involved -= frame
        }
        if (!m.ok) {
            pos = start
            throw Mismatch
        }
        pos = m.end
        m.value?.let { record(it, start, m.end) }
        return m.value as T
    }

    /** Remembers where a parsed node came from (the innermost rule that produced it wins). */
    private fun record(value: Any, start: Int, end: Int) {
        if (value is CharSequence || value is Number || value is Boolean || value is Unit || value is Iterable<*> || value is Enum<*>) return
        Spans.record(value, SourceSpan(source, text, skippedFrom(start) / unit, (end + unit - 1) / unit))
    }

    /** The position after the ignored text (whitespace, comments) at [from]: where a node really starts. */
    private fun skippedFrom(from: Int): Int {
        if (skip == null || from % unit != 0) return from
        var p = from
        while (true) {
            val m = skip.matchAt(text, p / unit) ?: return p
            if (m.value.isEmpty()) return p
            p = (m.range.last + 1) * unit
        }
    }

    private fun attempt(rule: Rule<*>, precedence: Int, start: Int, into: Memo): Boolean {
        pos = start
        try {
            into.value = rule.run(this, precedence)
            into.end = pos
            into.ok = true
        } catch (_: Mismatch) {
            into.ok = false
        }
        return into.ok
    }

    internal fun <T> parseAll(rule: Rule<T>): T {
        try {
            val result = apply(rule)
            skipIgnored()
            if (pos == size) return result
            if (pos > furthest) {
                furthest = pos
                expected.clear()
            }
            if (pos == furthest) expected += "end of input"
        } catch (_: Mismatch) {
        }
        throw error()
    }

    private fun error(): ParseException {
        val at = furthest.coerceAtLeast(0)
        val expected = expected.toSet()
        return when (bytes) {
            null -> TextParseError(at, expected, text, source).let { ParseException(it, errors.format(it)) }
            else -> BinaryParseError(at, expected, bytes, source).let { ParseException(it, errors.format(it)) }
        }
    }

    private fun Char.isWordChar() = isLetterOrDigit() || this == '_'
}


/** Every byte as one character (ISO-8859-1), so text rules can run on binary input. */
internal fun latin1(bytes: ByteArray): String =
    CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
