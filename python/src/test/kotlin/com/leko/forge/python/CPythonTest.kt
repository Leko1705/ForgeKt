package com.leko.forge.python

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every program must print exactly what CPython prints (and exit with the same status), compiled to
 * Python and, where the JavaScript backend supports it, to JavaScript. The generated Python must
 * also have the same syntax tree as the source (as `ast.dump` prints it).
 */
class CPythonTest {

    private fun file(name: String) = File(javaClass.getResource("/$name")!!.toURI())

    private fun process(vararg command: String, input: String? = null): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { if (input != null) it.write(input.toByteArray()) }
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return process.exitValue() to out
    }

    private fun compile(name: String): PyCompilation {
        val result = PythonCompiler().compile(file(name))
        assertTrue(result.succeeded, result.problems.joinToString("\n"))
        return result
    }

    private fun output(run: ((String) -> Unit, (String) -> Unit) -> Int): Pair<Int, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        val status = run({ out.append(it) }, { err.append(it) })
        if (status != 0 && err.isNotEmpty()) println("stderr:\n$err")
        return status to out.toString()
    }

    private fun compare(name: String, javaScript: Boolean = true) {
        val expected = process(PyCompilation.python(), file(name).path)
        val result = compile(name)
        val python = output { out, err -> result.run(out, err) }
        assertEquals(expected.second, python.second, "Python output of $name")
        assertEquals(expected.first, python.first, "Python exit status of $name")
        if (javaScript) {
            val js = output { out, err -> result.runJavaScript(out, err) }
            assertEquals(expected.second, js.second, "JavaScript output of $name")
            assertEquals(expected.first, js.first, "JavaScript exit status of $name")
        }
        for ((path, code) in result.pythonFiles()) {
            val source = if (path == file(name).name) file(name) else File(file(name).parentFile, path)
            assertEquals(ast(source.readText()), ast(code), "syntax tree of $path")
        }
    }

    /** CPython's syntax tree of [code]. */
    private fun ast(code: String): String {
        val (status, out) = process(PyCompilation.python(), "-c", "import ast, sys; print(ast.dump(ast.parse(sys.stdin.read()), indent=1))", input = code)
        assertEquals(0, status, "not valid Python:\n$code")
        return out
    }

    @Test fun basics() = compare("basics.py")
    @Test fun classes() = compare("classes.py")
    @Test fun errors() = compare("errors.py")
    @Test fun algorithms() = compare("algorithms.py")
    @Test fun contextManagersAndParameters() = compare("context.py")
    @Test fun imports() = compare("imports/main.py", javaScript = false)
    @Test fun coroutines() = compare("coroutines.py", javaScript = false)
    @Test fun modernSyntax() = compare("modern.py", javaScript = false)

    @Test
    fun tracebacksPointToSourceLines() {
        val source = "def f(x):\n\n    # a comment\n    return 1 / x\n\n\nprint(\n    f(\n        0))\n"
        val result = PythonCompiler().compile(source, "crash.py")
        val err = StringBuilder()
        assertEquals(1, result.run({}, { err.append(it) }))
        assertTrue("crash.py\", line 4, in f" in err && "crash.py\", line 7, in <module>" in err, err.toString())
        assertTrue("ZeroDivisionError: division by zero" in err, err.toString())
    }

    @Test
    fun theJavaScriptBackendReportsWhatItDoesNotSupport() {
        val result = PythonCompiler().compile("x = 1\nasync def f():\n    await f()\n", "a.py")
        val e = kotlin.runCatching { result.toJavaScript() }.exceptionOrNull()
        assertTrue(e is UnsupportedOnTarget && e.message == "a.py:2:1: NotImplementedError: async def is not supported by the JavaScript backend", e.toString())
    }
}
