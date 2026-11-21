package com.leko.forge.java

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {

    private val bad = """
        public class Bad {
            static int f(int x) {
                if (x > 0) return 1;
            }
            public static void main(String[] args) {
                int a = "text";
                undefined = 3;
                int b = 5 + true;
                foo(1);
                new Missing();
                break;
                Math.max(1, true);
                String s = this.toString();
            }
        }
        abstract class Animal { abstract String sound(); }
        class Dog extends Animal { }
    """.trimIndent()

    @Test
    fun errorsAreReportedWithTheirPosition() {
        val result = JavaCompiler().compile("Bad.java" to bad)
        assertTrue(!result.succeeded)
        val expected = listOf(
            2 to "missing return statement in f",
            6 to "incompatible types: String cannot be converted to int",
            7 to "cannot find symbol undefined",
            8 to "bad operand types for binary operator +: int and boolean",
            9 to "cannot find method foo",
            10 to "cannot find class Missing",
            11 to "break outside of a loop",
            12 to "no method max(int, boolean) found",
            13 to "non-static variable this cannot be referenced from a static context",
            17 to "Dog is not abstract and does not override abstract method sound() in Animal",
        )
        val reported = result.problems.map { it.toString() }
        for ((line, message) in expected) assertTrue(reported.any { it.startsWith("Bad.java:$line:") && message in it }, "missing line $line: $message in\n${reported.joinToString("\n")}")
        assertEquals(expected.size, reported.size, reported.joinToString("\n"))
    }

    @Test
    fun syntaxErrors() {
        val result = JavaCompiler().compile("Syntax.java" to "class A {\n    void f() { int x = ; }\n}")
        val message = result.problems.single().toString()
        assertTrue(message.startsWith("Syntax.java:2:24: error: expected"), message)
    }
}
