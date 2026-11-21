package com.leko.forge.java

import com.leko.forge.Child
import com.leko.forge.java.extensions.PowerOperator
import com.leko.forge.java.extensions.Records
import com.leko.forge.java.extensions.Unless
import com.leko.forge.msi.Block
import com.leko.forge.msi.Branch
import com.leko.forge.msi.Stmt
import com.leko.forge.msi.StrLit
import com.leko.forge.msi.Throw
import com.leko.forge.msi.Unary
import com.leko.forge.msi.UnaryOp
import com.leko.forge.syn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A feature written outside of the compiler module: Java's `assert` statement. */
data class AssertStmt(@Child val condition: JExpr, @Child val message: JExpr?) : JStmt

object Assertions : JavaFeature("assert statements") {
    init {
        JavaGrammar.stmt.on {
            +"assert"; requireEnabled()
            val c = JavaGrammar.expr(); val m = optional { +":"; JavaGrammar.expr() }; +";"
            AssertStmt(c, m)
        }
        problems.on<AssertStmt> { p ->
            if (condition.jtype == BooleanType || condition.jtype == ErrorType) p else p + condition.problem("assert needs a boolean, not ${condition.jtype.display}")
        }
    }

    private val AssertStmt.msi: Stmt by syn {
        Branch(Unary(UnaryOp.NOT, condition.msi), Block(Throw(newException("IllegalStateException", message?.msiString() ?: StrLit("assertion failed")))))
    }
}

class ExtensionsTest {

    private val program = """
        record Point(int x, int y) {
            int sum() { return x + y; }
        }
        public class Ext {
            public static void main(String[] args) {
                Point p = new Point(3, 4);
                System.out.println(p);
                System.out.println(p.x() + " " + p.sum() + " " + p.equals(new Point(3, 4)) + " " + p.equals(new Point(4, 3)));
                unless (p.x() > 5) System.out.println("small x");
                System.out.println(2 ** 10 + " " + (-2 ** 2) + " " + 2 ** 3 ** 2 + " " + 3L ** 3);
                assert p.sum() == 7 : "sum";
                assert p.sum() == 8 : "the sum is " + p.sum();
            }
        }
    """.trimIndent()

    private fun output(compilation: Compilation): List<String> {
        assertTrue(compilation.succeeded, compilation.problems.joinToString("\n"))
        val out = StringBuilder()
        compilation.run { out.append(it) }
        return out.lines().dropLastWhile { it.isEmpty() }
    }

    @Test
    fun extensionsAddSyntaxChecksAndLowering() {
        val compiler = JavaCompiler(JavaCompiler.standardFeatures + Records + Unless + PowerOperator + Assertions)
        assertEquals(listOf(
            "Point[x=3, y=4]", "3 7 true false", "small x", "1024 -4 512 27",
            "Exception in thread \"main\" IllegalStateException: the sum is 7",
        ), output(compiler.compile("Ext.java" to program)))
    }

    @Test
    fun disabledFeaturesAreRejected() {
        val result = JavaCompiler().compile("Ext.java" to program)
        val message = result.problems.single().toString()
        assertTrue("Ext.java:1:13: error: records are not enabled (add the Records feature to the compiler)" in message, message)
    }

    @Test
    fun extensionChecksTheirConstructs() {
        val compiler = JavaCompiler(JavaCompiler.standardFeatures + Unless + PowerOperator + Assertions)
        val result = compiler.compile("Bad.java" to """
            class Bad {
                static void f() {
                    unless (1) { }
                    int x = 2.0 ** 2;
                    assert 3;
                }
            }
        """.trimIndent())
        val messages = result.problems.map { it.message }
        assertTrue(messages.any { "int cannot be converted to boolean" in it }, "$messages")
        assertTrue(messages.any { "bad operand types for binary operator **: double and int" in it }, "$messages")
        assertTrue(messages.any { "assert needs a boolean, not int" in it }, "$messages")
    }
}
