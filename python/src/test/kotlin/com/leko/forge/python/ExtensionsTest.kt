package com.leko.forge.python

import com.leko.forge.Child
import com.leko.forge.python.extensions.MatchStatement
import com.leko.forge.python.extensions.PipeOperator
import com.leko.forge.syn
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A statement defined outside of the compiler module: `repeat n:` runs its body n times. */
data class Repeat(@Child val count: PyExpr, @Child val body: List<PyStmt>) : PyStmt

object RepeatStatement : PythonFeature("repeat statements") {
    init {
        PythonGrammar.stmt.on { +"repeat"; requireEnabled(); val n = PythonGrammar.expr(); listOf(Repeat(n, PythonGrammar.suite())) }
    }

    /** Lowered to a loop over a support function (to show [pythonSupport]). */
    private val Repeat.py: String by syn { "for ${pyTemp("i")} in _forge_repeat(${count.py}):\n${pySuite(body)}" }

    init {
        pythonSupport("""
            def _forge_repeat(n):
                return range(n)
        """)
    }

    private val Repeat.js: String by syn {
        val i = temp("i")
        "for (let $i = 0n; $i < ${count.js}; $i++) {\n${indent(block(body))}\n}"
    }
}

class ExtensionsTest {

    /** Runs the program on CPython and on Node.js; both must print the same. */
    private fun run(compiler: PythonCompiler, source: String): String {
        val result = compiler.compile(source, "test.py")
        assertTrue(result.succeeded, result.problems.joinToString("\n"))
        val out = StringBuilder()
        assertEquals(0, result.run({ out.append(it) }, { System.err.print(it) }), result.toPython())
        val js = StringBuilder()
        assertEquals(0, result.runJavaScript({ js.append(it) }, { System.err.print(it) }))
        assertEquals(out.toString(), js.toString(), "JavaScript output")
        return out.toString()
    }

    @Test
    fun matchStatementsBehaveLikeCPython() {
        val source = javaClass.getResource("/matching.py")!!.readText()
        val file = File.createTempFile("match", ".py").also { it.writeText(source) }
        val process = ProcessBuilder("python3", file.path).start()
        val expected = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        file.delete()
        assertEquals(expected, run(PythonCompiler(PythonCompiler.standardFeatures + MatchStatement), source))
    }

    @Test
    fun pipeOperatorAndAFeatureFromOutside() {
        val source = """
            numbers = [5, 3, 8, 1]
            print(numbers |> sorted |> list)
            print(3 |> pow(2), "a-b-c" |> str.split("-") |> len)
            repeat 2:
                print("again")
        """.trimIndent()
        val compiler = PythonCompiler(PythonCompiler.standardFeatures + PipeOperator + RepeatStatement)
        assertEquals("[1, 3, 5, 8]\n9 3\nagain\nagain\n", run(compiler, source))
    }

    @Test
    fun extensionsAreLoweredToStandardPython() {
        val source = "xs = [3, 1, 2]\nprint(xs |> sorted |> len)\nrepeat 2:\n    print(xs |> max)\n"
        val python = PythonCompiler(PythonCompiler.standardFeatures + PipeOperator + RepeatStatement).compile(source, "test.py").toPython()
        assertTrue("|>" !in python && "repeat" !in python.replace("_forge_repeat", ""), python)
        assertTrue("print(len(sorted(xs)))" in python, python)
        assertTrue("def _forge_repeat(n):" in python, python)
    }

    @Test
    fun disabledFeaturesAreRejected() {
        PipeOperator.hashCode() // loaded, but not enabled
        val result = PythonCompiler().compile("x = 1\nprint(x |> str)\n", "test.py")
        val message = result.problems.single().toString()
        assertTrue(message.startsWith("test.py:2:11: SyntaxError: pipe operators are not enabled (add the PipeOperator feature"), message)
    }
}
