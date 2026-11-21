package com.leko.forge.java

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compiles Java programs and runs them with the MSI interpreter. */
class JavaCompilerTest {

    private fun source(name: String) = name to javaClass.getResource("/$name")!!.readText()

    private fun run(name: String, compiler: JavaCompiler = JavaCompiler()): List<String> {
        val result = compiler.compile(source(name))
        assertTrue(result.succeeded, result.problems.joinToString("\n"))
        val out = StringBuilder()
        result.run { out.append(it) }
        return out.lines().dropLastWhile { it.isEmpty() }
    }

    @Test fun basics() = assertEquals(Programs.expected.getValue("Basics.java"), run("Basics.java"))
    @Test fun classesInterfacesAndDispatch() = assertEquals(Programs.expected.getValue("Shapes.java"), run("Shapes.java"))
    @Test fun exceptions() = assertEquals(Programs.expected.getValue("Exceptions.java"), run("Exceptions.java"))
    @Test fun algorithms() = assertEquals(Programs.expected.getValue("Algorithms.java"), run("Algorithms.java"))
}

/** The test programs and what they print (as `java` prints it; the uncaught exception line is the compiler's format). */
object Programs {
    val expected = mapOf(
        "Basics.java" to listOf(
            "fib(15) = 610", "20! = 2432902008176640000", "3 1 3.5", "3345", "d = 2.5", "sum of squares = 30",
            "primes: 4, last 7", "counter = 19", "k = 5", "flag = true, max = 9, 2.5, abs = 4", "big", "bits = 59",
        ),
        "Shapes.java" to listOf(
            "circle#1 with area 3.0", "shape#2 with area 6.0", "square#3 with area 4.0", "total area 13.0", "created 3",
            "a rect of width 2.0", "false", "caught a ClassCastException",
        ),
        "Exceptions.java" to listOf(
            "failed: missing 30 (30)", "balance 70", "42 -1 -1", "log .[negative: -5].[zero].", "inner finally", "outer caught inner",
            "data 123", "Exception in thread \"main\" UnsupportedOperationException: done",
        ),
        "Algorithms.java" to listOf("1,3,5,7,9", "1,5,10,10,5,1", "3x4 8", "1099511627776 0 4", "-2147483648"),
    )

    fun source(name: String) = name to Programs::class.java.getResource("/$name")!!.readText()
}
