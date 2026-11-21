package com.leko.forge.python

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {

    @Test
    fun errorsWithTheirPositions() {
        val source = """
            def f(a, a):
                return undefined_name
            break
            return 5
            def g():
                nonlocal q
            def h(x=1, y):
                pass
            for i in range(3):
                continue
            print(len(unknown))
            f(1, 2) = 3
        """.trimIndent()
        val result = PythonCompiler().compile(source, "bad.py")
        val reported = result.problems.map { it.toString() }
        val expected = listOf(
            1 to "SyntaxError: duplicate argument 'a' in function definition",
            2 to "NameError: name 'undefined_name' is not defined",
            3 to "SyntaxError: 'break' outside loop",
            4 to "SyntaxError: 'return' outside function",
            6 to "SyntaxError: no binding for nonlocal 'q' found",
            7 to "SyntaxError: parameter without a default follows parameter with a default",
            11 to "NameError: name 'unknown' is not defined",
            12 to "SyntaxError: cannot assign to function call",
        )
        for ((line, message) in expected) assertTrue(reported.any { it.startsWith("bad.py:$line:") && message in it }, "missing line $line: $message in\n${reported.joinToString("\n")}")
        assertEquals(expected.size, reported.size, reported.joinToString("\n"))
    }

    @Test
    fun syntaxAndIndentationErrors() {
        val syntax = PythonCompiler().compile("x = 1\ny = (2 +\nprint(y)\n", "s.py").problems.single().toString()
        assertEquals("s.py:2:5: SyntaxError: '(' was never closed", syntax)
        val other = PythonCompiler().compile("x = 1\nif x\n    y = 2\n", "e.py").problems.single().toString()
        assertTrue(other.startsWith("e.py:2:5: SyntaxError: expected"), other)
        val indentation = PythonCompiler().compile("if True:\n        a = 1\n    b = 2\n", "i.py").problems.single().toString()
        assertEquals("i.py:3:5: IndentationError: unindent does not match any outer indentation level", indentation)
    }

    @Test
    fun namesFollowPythonScopes() {
        val ok = PythonCompiler().compile("""
            x = 1
            class C:
                y = x
                def m(self):
                    return x
            def outer():
                z = 2
                def inner():
                    return z + x
                return inner
            print(C().m(), outer()(), [w for w in range(3)], later())
            def later():
                return 0
        """.trimIndent(), "ok.py")
        assertTrue(ok.succeeded, ok.problems.joinToString("\n"))
        val bad = PythonCompiler().compile("class C:\n    y = 1\n    def m(self):\n        return y\n", "c.py")
        assertEquals(listOf("c.py:4:16: NameError: name 'y' is not defined"), bad.problems.map { it.toString() })
    }

    @Test
    fun importsAndCoroutines() {
        val source = """
            import os
            from __future__ import annotations
            def f():
                from os import *
                await g()
            async def g():
                yield from range(3)
                async with lock:
                    pass
            async for x in g():
                pass
            lambda: await g()
            from . import sibling
            from __future__ import braces
            [x async for x in g()]
            lock = None
        """.trimIndent()
        val reported = PythonCompiler().compile(source, "bad.py").problems.map { it.toString() }
        val expected = listOf(
            2 to "SyntaxError: from __future__ imports must occur at the beginning of the file",
            4 to "SyntaxError: import * only allowed at module level",
            5 to "SyntaxError: 'await' outside async function",
            7 to "SyntaxError: 'yield from' inside async function",
            10 to "SyntaxError: 'async for' outside async function",
            12 to "SyntaxError: 'await' outside async function",
            13 to "ImportError: attempted relative import with no known parent package",
            14 to "SyntaxError: from __future__ imports must occur at the beginning of the file",
            15 to "SyntaxError: asynchronous comprehension outside of an asynchronous function",
        )
        for ((line, message) in expected) assertTrue(reported.any { it.startsWith("bad.py:$line:") && message in it }, "missing line $line: $message in\n${reported.joinToString("\n")}")
        assertEquals(expected.size, reported.size, reported.joinToString("\n"))
    }

    @Test
    fun importedNamesOfLocalModulesAreChecked() {
        val modules = mapOf(
            "util" to ModuleFile("util.py", "def helper():\n    return 1\n", isPackage = false),
            "pkg" to ModuleFile("pkg/__init__.py", "from .inner import value\nfrom .missing import nothing\n", isPackage = true),
            "pkg.inner" to ModuleFile("pkg/inner.py", "value = 2\nprint(undefined)\n", isPackage = false),
        )
        val source = "import json\nfrom util import helper, helpr\nfrom pkg import value, inner\nimport pkg.nope\nfrom collections import whatever\n"
        val result = PythonCompiler().compile(source, "main.py", ModuleFinder { modules[it] })
        assertEquals(listOf("main", "util", "pkg", "pkg.inner"), result.modules.map { it.qualifiedName.replace("__main__", "main") })
        assertEquals(listOf(
            "pkg/__init__.py:2:1: ModuleNotFoundError: No module named 'pkg.missing'",
            "main.py:2:1: ImportError: cannot import name 'helpr' from 'util' (util.py)",
            "main.py:4:1: ModuleNotFoundError: No module named 'pkg.nope'",
            "pkg/inner.py:2:7: NameError: name 'undefined' is not defined",
        ).sorted(), result.problems.map { it.toString() }.sorted())
    }

    @Test
    fun parameterLists() {
        val ok = PythonCompiler().compile("def f(a, /, b=1, *, c, d=2, **e): pass\ndef g(*, x): pass\nh = lambda a, /, *b, c=1: a\n", "p.py")
        assertTrue(ok.succeeded, ok.problems.joinToString("\n"))
        assertTrue("def f(a, /, b=1, *, c, d=2, **e):" in ok.toPython(), ok.toPython())
        assertTrue("h = lambda a, /, *b, c=1: a" in ok.toPython(), ok.toPython())
        for (bad in listOf("def f(*): pass\n", "def f(/, a): pass\n", "def f(a, *, b, /): pass\n")) {
            assertTrue(PythonCompiler().compile(bad, "p.py").problems.single().toString().startsWith("p.py:1:"), bad)
        }
        val order = PythonCompiler().compile("def f(**k, a): pass\n", "p.py").problems.single().toString()
        assertEquals("p.py:1:1: SyntaxError: arguments cannot follow var-keyword argument", order)
    }
}
