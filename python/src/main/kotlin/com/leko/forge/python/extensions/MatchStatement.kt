package com.leko.forge.python.extensions

import com.leko.forge.Child
import com.leko.forge.rule
import com.leko.forge.syn
import com.leko.forge.python.*
import com.leko.forge.python.PythonGrammar.DEDENT
import com.leko.forge.python.PythonGrammar.INDENT
import com.leko.forge.python.PythonGrammar.NEWLINE
import com.leko.forge.python.PythonGrammar.expr
import com.leko.forge.python.PythonGrammar.exprList
import com.leko.forge.python.PythonGrammar.name
import com.leko.forge.python.PythonGrammar.stmt
import com.leko.forge.python.PythonGrammar.suite

interface Pattern : PyNode
data class LiteralPattern(@Child val value: PyExpr) : Pattern
data class CapturePattern(val name: String) : Pattern
class WildcardPattern : Pattern { override fun toString(): String = "_" }
data class ValuePattern(@Child val value: PyExpr) : Pattern
data class SequencePattern(@Child val patterns: List<Pattern>) : Pattern
data class StarPattern(val name: String?) : Pattern
data class MappingPattern(@Child val keys: List<PyExpr>, @Child val patterns: List<Pattern>) : Pattern
data class ClassPattern(@Child val cls: PyExpr, @Child val positional: List<Pattern>, val keywords: List<String>, @Child val keywordPatterns: List<Pattern>) : Pattern
data class OrPattern(@Child val alternatives: List<Pattern>) : Pattern
data class AsPattern(@Child val pattern: Pattern, val name: String) : Pattern
data class MatchCase(@Child val pattern: Pattern, @Child val guard: PyExpr?, @Child val body: List<PyStmt>) : PyNode
data class Match(@Child val subject: PyExpr, @Child val cases: List<MatchCase>) : PyStmt

/** Structural pattern matching (Python 3.10): `match`/`case` with all kinds of patterns and guards. */
object MatchStatement : PythonFeature("match statements") {

    /** Numbers (also negative), strings, None, True, False: not expressions (`0 | 1` is an or-pattern). */
    private val literal by rule {
        oneOf(
            { +"-"; LiteralPattern(UnaryOp("-", CoreExpressions.constant(CoreExpressions.number()))) },
            { LiteralPattern(CoreExpressions.constant(CoreExpressions.number())) },
            { LiteralPattern(Constant(some(CoreExpressions.stringLiteral).joinToString("") { CoreExpressions.unescape(it) }, ConstantKind.STR)) },
            { +"None"; LiteralPattern(Constant(null, ConstantKind.NONE)) },
            { +"True"; LiteralPattern(Constant(true, ConstantKind.BOOL)) },
            { +"False"; LiteralPattern(Constant(false, ConstantKind.BOOL)) },
        )
    }
    private val matchCase: com.leko.forge.Rule<MatchCase> by rule { +"case"; val p = topPattern(); val g = optional { +"if"; expr() }; MatchCase(p, g, suite()) }
    private val matchStmt: com.leko.forge.Rule<Match> by rule {
        +"match"; requireEnabled()
        val subject = exprList(); +":"; +NEWLINE; +INDENT
        val cases = some(matchCase)
        +DEDENT
        Match(subject, cases)
    }
    private val closed: com.leko.forge.Rule<Pattern> by rule {
        oneOf(
            { +"_"; if (lookahead { name() }) fail("a pattern"); WildcardPattern() },
            { val e = dotted(); +"("; val (pos, kws) = classArgs(); +")"; ClassPattern(e, pos.map { it }, kws.map { it.first }, kws.map { it.second }) },
            { val e = dotted(); if (e !is Attribute) fail("a value pattern"); ValuePattern(e) },
            { val n = name(); if (lookahead { oneOf({ +"." }, { +"(" }) }) fail("a pattern"); CapturePattern(n) },
            { literal() },
            { +"["; val ps = sepBy(patternOrStar, ","); optional { +"," }; +"]"; SequencePattern(ps) },
            { +"("; +")"; SequencePattern(emptyList()) },
            { +"("; val p = pattern(); if (lookahead { +"," }) fail("a group"); +")"; p },
            { +"("; val ps = sepBy(patternOrStar, ","); optional { +"," }; +")"; SequencePattern(ps) },
            { +"{"; val entries = sepBy({ val k = expr(); +":"; k to pattern() }, ","); optional { +"," }; +"}"; MappingPattern(entries.map { it.first }, entries.map { it.second }) },
        )
    }
    private val dotted: com.leko.forge.Rule<PyExpr> by rule { var e: PyExpr = Name(name()); many { +"."; e = Attribute(e, name()) }; e }
    private val classArgs by rule {
        val items = sepBy({ oneOf<Any>({ val n = name(); +"="; n to pattern() }, { pattern() }) }, ",")
        optional { +"," }
        items.filterIsInstance<Pattern>() to items.filterIsInstance<Pair<String, Pattern>>()
    }
    private val patternOrStar: com.leko.forge.Rule<Pattern> by rule {
        oneOf({ +"*"; val n = name(); StarPattern(if (n == "_") null else n) }, { pattern() })
    }
    private val orPattern by rule { val first = closed(); val rest = many { +"|"; closed() }; if (rest.isEmpty()) first else OrPattern(listOf(first) + rest) }
    val pattern: com.leko.forge.Rule<Pattern> by rule { val p = orPattern(); optional { +"as"; AsPattern(p, name()) } ?: p }
    /** At the top a sequence can be written without brackets: `case a, b:` */
    private val topPattern by rule {
        val first = patternOrStar()
        if (!lookahead { +"," }) return@rule first
        val rest = many { +","; patternOrStar() }
        optional { +"," }
        SequencePattern(listOf(first) + rest)
    }

    init {
        stmt.on { listOf(matchStmt()) }
        problems.on<MatchCase> { p ->
            val names = captures(pattern)
            val duplicate = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            p + duplicate.flatMap { problem("SyntaxError: multiple assignments to name '$it' in pattern") }
        }
    }

    /** The names a pattern binds. */
    fun captures(p: Pattern): List<String> = when (p) {
        is CapturePattern -> listOf(p.name)
        is StarPattern -> listOfNotNull(p.name)
        is SequencePattern -> p.patterns.flatMap { captures(it) }
        is MappingPattern -> p.patterns.flatMap { captures(it) }
        is ClassPattern -> (p.positional + p.keywordPatterns).flatMap { captures(it) }
        is OrPattern -> captures(p.alternatives.first())
        is AsPattern -> captures(p.pattern) + p.name
        else -> emptyList()
    }

    /** Captures are variables of the enclosing scope (like assignments). */
    private val MatchCase.boundNames: List<String> by syn { captures(pattern) }

    // ---- JavaScript --------------------------------------------------------------------------

    /** A JavaScript condition testing [subject] (a variable) against the pattern; captures are assigned on the way. */
    private fun condition(p: Pattern, subject: String, at: PyNode, temps: MutableList<String>): String = when (p) {
        is WildcardPattern -> "true"
        is CapturePattern -> "((${jsName(p.name, resolve(at, p.name))} = $subject), true)"
        is LiteralPattern -> { val v = p.value; if (v is Constant && (v.kind == ConstantKind.NONE || v.kind == ConstantKind.BOOL)) "($subject === ${v.js})" else "\$eq($subject, ${v.js})" }
        is ValuePattern -> "\$eq($subject, ${p.value.js})"
        is AsPattern -> "(${condition(p.pattern, subject, at, temps)} && ((${jsName(p.name, resolve(at, p.name))} = $subject), true))"
        is OrPattern -> p.alternatives.joinToString(" || ", "(", ")") { condition(it, subject, at, temps) }
        is SequencePattern -> {
            val star = p.patterns.indexOfFirst { it is StarPattern }
            val items = "\$m${temps.size}".also { temps += it }
            val checks = p.patterns.mapIndexed { i, sub -> if (sub is StarPattern) (sub.name?.let { "((${jsName(it, resolve(at, it))} = $items[$i]), true)" } ?: "true") else condition(sub, "$items[$i]", at, temps) }
            "(($items = \$matchSequence($subject, ${p.patterns.size}, $star)) !== null" + checks.joinToString("") { " && $it" } + ")"
        }
        is MappingPattern -> {
            val checks = p.keys.indices.map { i ->
                val v = "\$m${temps.size}".also { temps += it }
                "(\$in(${p.keys[i].js}, $subject) && (($v = \$getitem($subject, ${p.keys[i].js})), true) && ${condition(p.patterns[i], v, at, temps)})"
            }
            "($subject instanceof Dict" + checks.joinToString("") { " && $it" } + ")"
        }
        is ClassPattern -> {
            val positional = p.positional.mapIndexed { i, sub ->
                if (i == 0) "(\$matchSelf(${p.cls.js}) ? ${condition(sub, subject, at, temps)} : false)" else "false"
            }
            val keywords = p.keywords.indices.map { i ->
                val v = "\$m${temps.size}".also { temps += it }
                "(\$hasattr($subject, ${quote(p.keywords[i])}) && (($v = \$getattr($subject, ${quote(p.keywords[i])})), true) && ${condition(p.keywordPatterns[i], v, at, temps)})"
            }
            "\$isinstance($subject, ${p.cls.js})" + (positional + keywords).joinToString("") { " && $it" }
        }
        else -> error("unknown pattern $p")
    }

    private val Match.js: String by syn {
        val s = temp("subject")
        val temps = ArrayList<String>()
        val branches = cases.map { c ->
            val test = condition(c.pattern, s, c, temps) + (c.guard?.let { " && \$bool(${it.js})" } ?: "")
            "if ($test) {\n${indent(block(c.body))}\n}"
        }
        val declarations = if (temps.isEmpty()) "" else "let ${temps.joinToString()};\n"
        "{\n${indent("const $s = ${subject.js};\n" + declarations + branches.joinToString(" else "))}\n}"
    }

    // ---- Python ------------------------------------------------------------------------------

    /** The Python code of a pattern (`match` needs Python 3.10). */
    val Pattern.py: String by syn()

    private val LiteralPattern.py: String by syn { value.py }
    private val CapturePattern.py: String by syn { name }
    private val WildcardPattern.py: String by syn { "_" }
    private val ValuePattern.py: String by syn { value.py }
    private val SequencePattern.py: String by syn { "[" + patterns.joinToString(", ") { it.py } + "]" }
    private val StarPattern.py: String by syn { "*${name ?: "_"}" }
    private val MappingPattern.py: String by syn { "{" + keys.indices.joinToString(", ") { "${keys[it].py}: ${patterns[it].py}" } + "}" }
    private val ClassPattern.py: String by syn {
        "${cls.py}(" + (positional.map { it.py } + keywords.indices.map { "${keywords[it]}=${keywordPatterns[it].py}" }).joinToString(", ") + ")"
    }
    private val OrPattern.py: String by syn { alternatives.joinToString(" | ") { if (it is AsPattern) "(${it.py})" else it.py } }
    private val AsPattern.py: String by syn { (if (pattern is OrPattern) "(${pattern.py})" else pattern.py) + " as $name" }

    private val Match.py: String by syn {
        "match ${subject.py}:\n" + pyIndent(cases.joinToString("\n") { c ->
            "case ${c.pattern.py}${c.guard?.let { " if ${it.py}" } ?: ""}:\n${pySuite(c.body)}"
        })
    }

    init {
        PythonRuntime.add("match", """
            /** The items of a sequence (not a string) for a pattern of [count] items, or null. */
            function ${'$'}matchSequence(x, count, star) {
              if (!(Array.isArray(x) || x instanceof Tuple)) return null;
              const items = Array.isArray(x) ? x : x.items;
              if (star < 0) return items.length === count ? items : null;
              if (items.length < count - 1) return null;
              const after = count - star - 1;
              return [...items.slice(0, star), items.slice(star, items.length - after), ...items.slice(items.length - after)];
            }
            /** Builtin types match their positional sub-pattern against the subject itself: `case int(x)`. */
            function ${'$'}matchSelf(cls) { return [int, float, str, bool, list, dict, tuple, set].includes(cls); }
        """.trimIndent())
    }
}
