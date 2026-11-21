package com.leko.forge.python

import com.leko.forge.AbstractRule
import com.leko.forge.Keywords
import com.leko.forge.ParseScope
import com.leko.forge.Rule
import com.leko.forge.rule
import com.leko.forge.token

/**
 * A part of the language: syntax (alternatives of the [PythonGrammar] rules), name binding
 * (`boundNames`), checks (contributions to [problems]), Python generation (`py`, the main target:
 * extensions lower their constructs to standard Python, with [pythonSupport] code if needed) and
 * optionally JavaScript generation (`js`, with runtime support from [PythonRuntime.add]).
 * The Python language is [PythonCompiler.standardFeatures]; extensions are features too and can
 * come from other modules.
 */
abstract class PythonFeature(val featureName: String) {
    val isEnabled: Boolean get() = this in PythonCompiler.enabledFeatures.get()

    internal var pythonSupportCode: String? = null
        private set

    /**
     * Python code the generated modules need for this feature (helper functions, imports), placed
     * at the top of every module compiled with the feature enabled. Names users may call directly
     * go into [builtins], so name resolution accepts them.
     */
    protected fun pythonSupport(code: String, builtins: List<String> = emptyList()) {
        pythonSupportCode = code.trimIndent()
        PythonBuiltins.add(builtins)
    }

    /** Inside a rule, after the syntax that identifies the feature: stops unless it is enabled. */
    fun ParseScope.requireEnabled() {
        if (!isEnabled) throw FeatureDisabled(this@PythonFeature, offset)
    }

    override fun toString(): String = featureName
}

class FeatureDisabled(val feature: PythonFeature, val offset: Int) :
    RuntimeException("${feature.featureName} are not enabled (add the ${feature::class.simpleName} feature to the compiler)")

object PythonGrammar {
    const val NEWLINE: String = "\n"
    const val INDENT: String = "\u0002"
    const val DEDENT: String = "\u0003"

    /** Spaces and tabs inside a logical line (comments and line breaks are removed by [Layout]). */
    val skip: Regex = Regex("[ \\t\\f]+")

    val keywords: Keywords = Keywords(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
        "not", "or", "pass", "raise", "return", "try", "while", "with", "yield",
    )

    /** Identifiers (not directly followed by a quote: `f"..."` and `r"..."` are string literals). */
    val name: Rule<String> by token("[A-Za-z_][A-Za-z0-9_]*(?![\"'])", except = keywords)

    val expr: AbstractRule<PyExpr> by rule<PyExpr>()
    /** Compound statements and simple statement lines (each ends with its NEWLINE). */
    val stmt: AbstractRule<List<PyStmt>> by rule<List<PyStmt>>()
    /** Statements that fit on a line: `x = 1`, `return`, `pass`, ... */
    val simpleStmt: AbstractRule<PyStmt> by rule<PyStmt>()

    /** `a, b` (a tuple if there is a comma) or a single expression. */
    val exprList: Rule<PyExpr> by rule {
        val first = oneOf({ +"*"; Starred(expr()) }, expr)
        if (!lookahead { +"," }) return@rule first
        val rest = many { +","; oneOf({ +"*"; Starred(expr()) }, expr) }
        optional { +"," }
        TupleExpr(listOf(first) + rest)
    }

    /** `simple; simple NEWLINE` */
    val simpleLine: Rule<List<PyStmt>> by rule {
        val stmts = listOf(simpleStmt()) + many { +";"; simpleStmt() }
        optional { +";" }
        +NEWLINE
        stmts
    }

    /** The body of a compound statement: an indented block, or simple statements on the same line. */
    val suite: Rule<List<PyStmt>> by rule {
        +":"
        oneOf({ +NEWLINE; +INDENT; val s = some(stmt).flatten(); +DEDENT; s }, simpleLine)
    }

    val module: Rule<List<PyStmt>> by rule { many(stmt).flatten() }
}

/** The names resolved as builtins: Python's builtins and those added by features. */
object PythonBuiltins {
    private val names = LinkedHashSet<String>(
        ("ArithmeticError AssertionError AttributeError BaseException BaseExceptionGroup BlockingIOError BrokenPipeError BufferError " +
            "BytesWarning ChildProcessError ConnectionAbortedError ConnectionError ConnectionRefusedError ConnectionResetError " +
            "DeprecationWarning EOFError Ellipsis EncodingWarning EnvironmentError Exception ExceptionGroup FileExistsError " +
            "FileNotFoundError FloatingPointError FutureWarning GeneratorExit IOError ImportError ImportWarning IndentationError " +
            "IndexError InterruptedError IsADirectoryError KeyError KeyboardInterrupt LookupError MemoryError ModuleNotFoundError " +
            "NameError NotADirectoryError NotImplemented NotImplementedError OSError OverflowError PendingDeprecationWarning " +
            "PermissionError ProcessLookupError RecursionError ReferenceError ResourceWarning RuntimeError RuntimeWarning " +
            "StopAsyncIteration StopIteration SyntaxError SyntaxWarning SystemError SystemExit TabError TimeoutError TypeError " +
            "UnboundLocalError UnicodeDecodeError UnicodeEncodeError UnicodeError UnicodeTranslateError UnicodeWarning UserWarning " +
            "ValueError Warning ZeroDivisionError __build_class__ __debug__ __doc__ __import__ __loader__ __name__ __package__ " +
            "__spec__ __file__ __builtins__ __module__ __qualname__ __class__ __annotations__ __path__ __cached__ abs aiter all anext any ascii bin bool breakpoint " +
            "bytearray bytes callable chr classmethod compile complex copyright credits delattr dict dir divmod enumerate eval exec " +
            "exit filter float format frozenset getattr globals hasattr hash help hex id input int isinstance issubclass iter len " +
            "license list locals map max memoryview min next object oct open ord pow print property quit range repr reversed round " +
            "set setattr slice sorted staticmethod str sum super tuple type vars zip").split(" "),
    )

    fun add(builtins: Collection<String>) { names += builtins }
    operator fun contains(name: String): Boolean = name in names || name in PythonRuntime.builtins
}
