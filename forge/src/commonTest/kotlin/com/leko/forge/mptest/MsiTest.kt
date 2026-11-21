package com.leko.forge.mptest

import com.leko.forge.msi.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun int(v: Long) = IntLit(v)
private fun local(n: String) = Local(n)
private fun bin(op: BinaryOp, l: Expr, r: Expr) = Binary(op, l, r)
private val I64 = Type.I64

class MsiTest {

    private val point = Struct("Point", listOf(Field("x", I64), Field("y", I64)))

    private val module = Module(
        "demo",
        structs = listOf(point),
        functions = listOf(
            Func("print", listOf(Param("s", Type.Str)), Type.Unit, body = null),   // external
            // fun fact(n: i64): i64 = if n <= 1 then 1 else n * fact(n - 1)
            Func("fact", listOf(Param("n", I64)), I64, Block(
                Branch(bin(BinaryOp.LE, local("n"), int(1)), Block(Return(int(1)))),
                Return(bin(BinaryOp.MUL, local("n"), Call("fact", bin(BinaryOp.SUB, local("n"), int(1))))),
            )),
            // sum of 0 until n with a loop
            Func("sum", listOf(Param("n", I64)), I64, Block(
                Let("total", I64, int(0)),
                Let("i", I64),                                                     // zero value
                Loop(bin(BinaryOp.LT, local("i"), local("n")), Block(
                    Assign(local("total"), bin(BinaryOp.ADD, local("total"), local("i"))),
                    Assign(local("i"), bin(BinaryOp.ADD, local("i"), int(1))),
                )),
                Return(local("total")),
            )),
            // structs: squared length of a point moved by (dx, dy)
            Func("moved", listOf(Param("dx", I64), Param("dy", I64)), I64, Block(
                Let("p", Type.Struct("Point"), New("Point", int(3), int(4))),
                Assign(Get(local("p"), "x"), bin(BinaryOp.ADD, Get(local("p"), "x"), local("dx"))),
                Assign(Get(local("p"), "y"), bin(BinaryOp.ADD, Get(local("p"), "y"), local("dy"))),
                Return(bin(BinaryOp.ADD,
                    bin(BinaryOp.MUL, Get(local("p"), "x"), Get(local("p"), "x")),
                    bin(BinaryOp.MUL, Get(local("p"), "y"), Get(local("p"), "y")))),
            )),
            // arrays and labeled loops: the first pair (i, j) with i * j == target, encoded as i * 100 + j
            Func("pair", listOf(Param("target", I64)), I64, Block(
                Let("squares", Type.Array(I64), NewArray(I64, int(10))),
                Let("k", I64, int(0)),
                Loop(bin(BinaryOp.LT, local("k"), Length(local("squares"))), Block(
                    Assign(Index(local("squares"), local("k")), local("k")),
                    Assign(local("k"), bin(BinaryOp.ADD, local("k"), int(1))),
                )),
                Let("result", I64, int(-1)),
                Let("i", I64, int(0)),
                Loop(null, Block(
                    Branch(bin(BinaryOp.GE, local("i"), int(10)), Block(Break())),
                    Let("j", I64, int(0)),
                    Loop(bin(BinaryOp.LT, local("j"), int(10)), Block(
                        Let("product", I64, bin(BinaryOp.MUL, Index(local("squares"), local("i")), Index(local("squares"), local("j")))),
                        Assign(local("j"), bin(BinaryOp.ADD, local("j"), int(1))),
                        Branch(bin(BinaryOp.EQ, local("product"), int(0)), Block(Continue())),
                        Branch(bin(BinaryOp.EQ, local("product"), local("target")), Block(
                            Assign(local("result"), bin(BinaryOp.ADD, bin(BinaryOp.MUL, local("i"), int(100)), bin(BinaryOp.SUB, local("j"), int(1)))),
                            Break("outer"),
                        )),
                    )),
                    Assign(local("i"), bin(BinaryOp.ADD, local("i"), int(1))),
                ), label = "outer"),
                Return(local("result")),
            )),
            // strings, conversions, conditionals and an external call
            Func("describe", listOf(Param("n", I64)), Type.Str, Block(
                Let("half", Type.F64, bin(BinaryOp.DIV, Convert(local("n"), Type.F64), FloatLit(2.0))),
                Let("text", Type.Str, bin(BinaryOp.ADD, StrLit("n/2 = "), Convert(local("half"), Type.Str))),
                Eval(Call("print", local("text"))),
                Return(Conditional(bin(BinaryOp.GT, local("n"), int(0)), StrLit("positive"), StrLit("not positive"))),
            )),
            // 32 bit arithmetic wraps around
            Func("overflow", emptyList(), Type.I32, Block(
                Return(bin(BinaryOp.ADD, IntLit(Int.MAX_VALUE.toLong(), Type.I32), IntLit(1, Type.I32))),
            )),
        ),
    )

    @Test
    fun validModuleHasNoProblems() {
        assertEquals(emptyList(), module.validate())
        val fact = module.functions.first { it.name == "fact" }
        val ret = fact.body!!.stmts[1] as Return
        assertEquals(I64, ret.value!!.type)
        assertEquals(fact, (ret.value as Binary).let { (it.right as Call).callee })
    }

    @Test
    fun interpreterRunsEverything() {
        val printed = mutableListOf<Any?>()
        val run = Interpreter(module, mapOf("print" to { args -> printed += args[0]; Unit }))
        assertEquals(3628800L, run.call("fact", 10L))
        assertEquals(45L, run.call("sum", 10L))
        assertEquals(5L * 5 + 6 * 6, run.call("moved", 2L, 2L))
        assertEquals(209L, run.call("pair", 18L))       // first pair: 2 * 9
        assertEquals(-1L, run.call("pair", 97L))
        assertEquals("positive", run.call("describe", 5L))
        assertEquals(listOf<Any?>("n/2 = 2.5"), printed)
        assertEquals(Int.MIN_VALUE, run.call("overflow"))
    }

    @Test
    fun problemsAreReported() {
        val bad = Module("bad", functions = listOf(
            Func("f", listOf(Param("a", I64), Param("a", Type.Bool)), I64, Block(
                Let("x", I64, BoolLit(true)),
                Assign(local("y"), int(1)),
                Branch(int(1), Block()),
                Break(),
                Eval(Call("g", int(1))),
                Eval(bin(BinaryOp.ADD, StrLit("a"), int(1))),
                Eval(Get(New("Nope"), "x")),
            )),
        ))
        val messages = bad.validate().map { it.message }
        val expected = listOf(
            "fun f may end without returning a value",
            "parameter 'a' of fun f is defined more than once",
            "in fun f: value of 'x' must be i64, not bool",
            "in fun f: unknown variable 'y'",
            "in fun f: condition must be bool, not i64",
            "in fun f: break outside of a loop",
            "in fun f: unknown function 'g'",
            "in fun f: operator + can not be applied to str and i64",
            "in fun f: unknown struct 'Nope'",
        )
        for (m in expected) assertTrue(m in messages, "missing \"$m\" in $messages")
        assertEquals(expected.size, messages.size, messages.joinToString("\n"))
        assertFailsWith<IllegalArgumentException> { Interpreter(bad) }
    }

    @Test
    fun runtimeErrors() {
        val m = Module("m", functions = listOf(
            Func("div", listOf(Param("a", I64), Param("b", I64)), I64, Block(Return(bin(BinaryOp.DIV, local("a"), local("b"))))),
            Func("oob", emptyList(), I64, Block(Return(Index(NewArray(I64, int(2)), int(2))))),
        ))
        val run = Interpreter(m)
        assertEquals(3L, run.call("div", 7L, 2L))
        assertFailsWith<MsiRuntimeException> { run.call("div", 1L, 0L) }
        assertFailsWith<MsiRuntimeException> { run.call("oob") }
    }

    @Test
    fun rendersReadableText() {
        val sum = module.functions.first { it.name == "sum" }
        assertEquals(
            """
            fun sum(n: i64): i64 {
                let total: i64 = 0
                let i: i64
                loop while (i < n) {
                    total = (total + i)
                    i = (i + 1)
                }
                return total
            }
            """.trimIndent(),
            sum.render()
        )
        assertTrue(module.render().contains("external fun print(s: str): unit"))
        assertTrue(module.render().contains("struct Point { x: i64, y: i64 }"))
    }
}
