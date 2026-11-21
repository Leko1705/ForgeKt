package com.leko.forge.mptest

import com.leko.forge.msi.*

/**
 * MSI programs with their expected output. Every backend must print exactly the same.
 * Each module has `external fun print(s: str)` and `fun main()`.
 */
object Conformance {

    // ---- a small DSL to keep the programs readable -------------------------------------------

    val I32 = Type.I32
    val I64 = Type.I64
    val F64 = Type.F64
    val STR = Type.Str
    val BOOL = Type.Bool
    val UNIT = Type.Unit

    fun i(v: Long) = IntLit(v)
    fun i32(v: Long) = IntLit(v, Type.I32)
    fun f(v: Double) = FloatLit(v)
    fun s(v: String) = StrLit(v)
    fun v(name: String) = Local(name)
    operator fun Expr.plus(o: Expr) = Binary(BinaryOp.ADD, this, o)
    operator fun Expr.minus(o: Expr) = Binary(BinaryOp.SUB, this, o)
    operator fun Expr.times(o: Expr) = Binary(BinaryOp.MUL, this, o)
    operator fun Expr.div(o: Expr) = Binary(BinaryOp.DIV, this, o)
    operator fun Expr.rem(o: Expr) = Binary(BinaryOp.REM, this, o)
    infix fun Expr.lt(o: Expr) = Binary(BinaryOp.LT, this, o)
    infix fun Expr.le(o: Expr) = Binary(BinaryOp.LE, this, o)
    infix fun Expr.gt(o: Expr) = Binary(BinaryOp.GT, this, o)
    infix fun Expr.eq(o: Expr) = Binary(BinaryOp.EQ, this, o)
    infix fun Expr.ne(o: Expr) = Binary(BinaryOp.NE, this, o)
    infix fun Expr.and(o: Expr) = Binary(BinaryOp.AND, this, o)
    infix fun Expr.or(o: Expr) = Binary(BinaryOp.OR, this, o)
    fun str(e: Expr) = Convert(e, STR)
    fun print(e: Expr) = Eval(Call("print", e))
    fun printNum(label: String, e: Expr) = print(s("$label=") + str(e))
    fun set(name: String, e: Expr) = Assign(v(name), e)
    fun inc(name: String) = set(name, v(name) + i(1))
    fun block(vararg stmts: Stmt) = Block(stmts.toList())
    fun fn(name: String, params: List<Pair<String, Type>>, ret: Type, vararg body: Stmt, typeParams: List<String> = emptyList()) =
        Func(name, params.map { Param(it.first, it.second) }, ret, Block(body.toList()), typeParams)
    fun main(vararg body: Stmt) = fn("main", emptyList(), UNIT, *body)
    val printFn = Func("print", listOf(Param("s", STR)), UNIT, null)

    fun module(name: String, vararg functions: Func, structs: List<Struct> = emptyList(), unions: List<Union> = emptyList(), globals: List<Global> = emptyList()) =
        Module(name, structs, listOf(printFn) + functions, globals, unions)

    class Program(val module: Module, val expected: List<String>)

    // ---- the programs ----------------------------------------------------------------------------

    val arithmetic = Program(
        module("arithmetic", main(
            printNum("add", i(40) + i(2)),
            printNum("div", i(-7) / i(2)),
            printNum("rem", i(-7) % i(2)),
            printNum("i32", i32(2147483647) + i32(1)),
            printNum("i64", i(9000000000) * i(3)),
            printNum("shl", Binary(BinaryOp.SHL, i(1), i(40))),
            printNum("bits", Binary(BinaryOp.BIT_XOR, Binary(BinaryOp.BIT_AND, i(12), i(10)), Binary(BinaryOp.BIT_OR, i(1), i(4)))),
            printNum("neg", Unary(UnaryOp.NEG, i(5)) - Unary(UnaryOp.BIT_NOT, i(0))),
            printNum("float", f(1.5) * f(3.0) + f(0.25)),
            printNum("whole", f(10.0)),
            printNum("convert", Convert(f(-3.9), I64) + Convert(Convert(i(7), F64) / f(2.0), I64)),
            printNum("narrow", Convert(i(4294967297), I32)),
            printNum("cmp", (i(1) lt i(2)) and (s("abc") lt s("abd")) and (f(2.0) le f(2.0))),
            printNum("eq", (s("a") + s("b")) eq s("ab")),
            print(Conditional(i(3) gt i(4), s("yes"), s("no"))),
        )),
        listOf("add=42", "div=-3", "rem=-1", "i32=-2147483648", "i64=27000000000", "shl=1099511627776", "bits=13",
            "neg=-4", "float=4.75", "whole=10.0", "convert=0", "narrow=1", "cmp=true", "eq=true", "no"),
    )

    val control = Program(
        module("control",
            fn("fib", listOf("n" to I64), I64,
                Branch(v("n") lt i(2), block(Return(v("n")))),
                Return(Call("fib", v("n") - i(1)) + Call("fib", v("n") - i(2))),
            ),
            fn("firstOver", listOf("limit" to I64), I64,
                Let("i", I64),
                Loop(null, block(
                    inc("i"),
                    Branch((v("i") * v("i")) gt v("limit"), block(Return(v("i")))),
                )),
            ),
            main(
                printNum("fib", Call("fib", i(20))),
                printNum("first", Call("firstOver", i(50))),
                Let("found", STR, s("none")),
                Let("a", I64),
                Loop(v("a") lt i(5), block(
                    inc("a"),
                    Let("b", I64),
                    Loop(v("b") lt i(5), block(
                        inc("b"),
                        Branch(v("b") eq i(2), block(Continue())),
                        Branch((v("a") * v("b")) eq i(12), block(set("found", str(v("a")) + s("x") + str(v("b"))), Break("outer"))),
                    )),
                ), label = "outer"),
                print(s("found ") + v("found")),
                Let("count", I64),
                Let("k", I64),
                Loop(v("k") lt i(10), block(inc("k"), Branch((v("k") % i(3)) eq i(0), block(Continue())), inc("count"))),
                printNum("count", v("count")),
            ),
        ),
        listOf("fib=6765", "first=8", "found 3x4", "count=7"),
    )

    private val point = Struct("Point", listOf(Field("x", I64), Field("y", I64)))
    private val box = Struct("Box", listOf(Field("value", Type.Param("T"))), typeParams = listOf("T"))

    val data = Program(
        module("data",
            fn("swap", listOf("p" to Type.Struct("Point")), UNIT,
                Let("t", I64, Get(v("p"), "x")),
                Assign(Get(v("p"), "x"), Get(v("p"), "y")),
                Assign(Get(v("p"), "y"), v("t")),
            ),
            fn("unbox", listOf("b" to Type.Struct("Box", listOf(Type.Param("T")))), Type.Param("T"),
                Return(Get(v("b"), "value")), typeParams = listOf("T")),
            main(
                Let("p", Type.Struct("Point"), New("Point", i(1), i(2))),
                Eval(Call("swap", v("p"))),
                printNum("point", Get(v("p"), "x") * i(10) + Get(v("p"), "y")),
                Let("points", Type.Array(Type.Struct("Point")), NewArray(Type.Struct("Point"), i(3))),
                Let("i", I64),
                Loop(v("i") lt Length(v("points")), block(
                    Assign(Index(v("points"), v("i")), New("Point", v("i"), v("i") * v("i"))),
                    inc("i"),
                )),
                printNum("sum", Get(Index(v("points"), i(2)), "y") + Get(Index(v("points"), i(1)), "x")),
                Let("same", BOOL, Index(v("points"), i(0)) eq Index(v("points"), i(0))),
                Let("other", BOOL, New("Point", i(0), i(0)) eq Index(v("points"), i(0))),
                printNum("identity", v("same")),
                printNum("other", v("other")),
                Let("numbers", Type.Array(F64), NewArray(F64, i32(2))),
                Assign(Index(v("numbers"), i(1)), f(0.5)),
                printNum("floats", Index(v("numbers"), i(0)) + Index(v("numbers"), i(1))),
                Let("b", Type.Struct("Box", listOf(I64)), New("Box", i(41))),
                Assign(Get(v("b"), "value"), Get(v("b"), "value") + i(1)),
                printNum("box", Call("unbox", v("b"))),
                Let("sb", Type.Struct("Box", listOf(STR)), New("Box", listOf(s("hi")), listOf(STR))),
                print(Call("unbox", v("sb"))),
            ),
            structs = listOf(point, box),
        ),
        listOf("point=21", "sum=5", "identity=true", "other=false", "floats=0.5", "box=42", "hi"),
    )

    private val option = Union("Option", listOf(Variant("None"), Variant("Some", listOf(Field("value", Type.Param("T"))))), listOf("T"))
    private val shape = Union("Shape", listOf(
        Variant("Circle", listOf(Field("r", F64))),
        Variant("Rect", listOf(Field("w", F64), Field("h", F64))),
        Variant("Empty"),
    ))

    val unions = Program(
        module("unions",
            fn("area", listOf("s" to Type.Union("Shape")), F64,
                Match(v("s"), listOf(
                    Case("Circle", listOf("r"), block(Return(f(3.0) * v("r") * v("r")))),
                    Case("Rect", listOf("w", "h"), block(Return(v("w") * v("h")))),
                ), otherwise = block(Return(f(0.0)))),
            ),
            fn("orElse", listOf("o" to Type.Union("Option", listOf(Type.Param("T"))), "fallback" to Type.Param("T")), Type.Param("T"),
                Match(v("o"), listOf(Case("Some", listOf("x"), block(Return(v("x")))))),
                Return(v("fallback")),
                typeParams = listOf("T")),
            main(
                printNum("circle", Call("area", Make("Shape", "Circle", listOf(f(2.0))))),
                printNum("rect", Call("area", Make("Shape", "Rect", listOf(f(2.0), f(4.5))))),
                printNum("empty", Call("area", Make("Shape", "Empty"))),
                printNum("some", Call("orElse", Make("Option", "Some", listOf(i(7))), i(0))),
                printNum("none", Call("orElse", Make("Option", "None", typeArgs = listOf(I64)), i(-1))),
                print(Call("orElse", Make("Option", "None", typeArgs = listOf(STR)), s("default"))),
                Let("e", Type.Union("Shape"), Make("Shape", "Empty")),
                printNum("is", Is(v("e"), "Empty") and Unary(UnaryOp.NOT, Is(v("e"), "Circle"))),
            ),
            unions = listOf(option, shape),
        ),
        listOf("circle=12.0", "rect=9.0", "empty=0.0", "some=7", "none=-1", "default", "is=true"),
    )

    private val fnI64 = Type.Function(listOf(I64), I64)

    val closures = Program(
        module("closures",
            fn("counter", emptyList(), Type.Function(emptyList(), I64),
                Let("n", I64),
                Return(Lambda(emptyList(), I64, block(inc("n"), Return(v("n"))))),
            ),
            fn("map", listOf("xs" to Type.Array(Type.Param("T")), "f" to Type.Function(listOf(Type.Param("T")), Type.Param("T"))), UNIT,
                Let("i", I64),
                Loop(v("i") lt Length(v("xs")), block(
                    Assign(Index(v("xs"), v("i")), Invoke(v("f"), Index(v("xs"), v("i")))),
                    inc("i"),
                )),
                typeParams = listOf("T")),
            fn("double", listOf("x" to I64), I64, Return(v("x") * i(2))),
            main(
                Let("next", Type.Function(emptyList(), I64), Call("counter")),
                Eval(Invoke(v("next"))),
                Eval(Invoke(v("next"))),
                printNum("counter", Invoke(v("next"))),
                Let("other", Type.Function(emptyList(), I64), Call("counter")),
                printNum("other", Invoke(v("other"))),
                Let("xs", Type.Array(I64), NewArray(I64, i(3))),
                Assign(Index(v("xs"), i(0)), i(1)), Assign(Index(v("xs"), i(1)), i(2)), Assign(Index(v("xs"), i(2)), i(3)),
                Let("offset", I64, i(10)),
                Let("addOffset", fnI64, Lambda(listOf(Param("x", I64)), I64, block(Return(v("x") + v("offset"))))),
                Eval(Call("map", v("xs"), v("addOffset"))),
                set("offset", i(100)), // the lambda sees later assignments to captured variables
                Eval(Call("map", v("xs"), v("addOffset"))),
                Eval(Call("map", v("xs"), FuncRef("double"))),
                printNum("mapped", Index(v("xs"), i(0)) + Index(v("xs"), i(2))),
                Let("total", I64),
                Let("add", fnI64, Lambda(listOf(Param("x", I64)), I64, block(set("total", v("total") + v("x")), Return(v("total"))))),
                Eval(Invoke(v("add"), i(5))),
                Eval(Invoke(v("add"), i(6))),
                printNum("total", v("total")),
                Let("words", Type.Array(STR), NewArray(STR, i(2))),
                Assign(Index(v("words"), i(0)), s("a")), Assign(Index(v("words"), i(1)), s("b")),
                Eval(Call("map", v("words"), Lambda(listOf(Param("w", STR)), STR, block(Return(v("w") + v("w")))))),
                print(Index(v("words"), i(0)) + Index(v("words"), i(1))),
            ),
        ),
        listOf("counter=3", "other=1", "mapped=448", "total=11", "aabb"),
    )

    private val failure = Struct("Failure", listOf(Field("code", I64)))
    private val signal = Union("Signal", listOf(Variant("Stop"), Variant("Retry", listOf(Field("times", I64)))))

    val exceptions = Program(
        module("exceptions",
            fn("check", listOf("n" to I64), I64,
                Branch(v("n") lt i(0), block(Throw(New("Failure", v("n"))))),
                Branch(v("n") eq i(0), block(Throw(s("zero")))),
                Branch(v("n") gt i(100), block(Throw(Make("Signal", "Retry", listOf(v("n")))))),
                Return(v("n") * i(2)),
            ),
            fn("attempt", listOf("n" to I64), STR,
                Try(block(Return(s("ok ") + str(Call("check", v("n"))))), listOf(
                    Catch("f", Type.Struct("Failure"), block(Return(s("failure ") + str(Get(v("f"), "code"))))),
                    Catch("m", STR, block(Return(s("message ") + v("m")))),
                    Catch("sig", Type.Union("Signal"), block(Match(v("sig"), listOf(
                        Case("Retry", listOf("t"), block(Return(s("retry ") + str(v("t"))))),
                    )), Return(s("stop")))),
                )),
            ),
            fn("withFinally", listOf("n" to I64), I64,
                Try(block(
                    Branch(v("n") gt i(0), block(Return(v("n")))),
                    Throw(s("not positive")),
                ), emptyList(), finally = block(print(s("finally ") + str(v("n"))))),
            ),
            main(
                print(Call("attempt", i(21))),
                print(Call("attempt", i(-3))),
                print(Call("attempt", i(0))),
                print(Call("attempt", i(500))),
                printNum("returned", Call("withFinally", i(5))),
                Try(block(Eval(Call("withFinally", i(-1)))), listOf(Catch("e", STR, block(print(s("caught ") + v("e")))))),
                Let("log", STR, s("")),
                Let("i", I64),
                Loop(v("i") lt i(5), block(
                    inc("i"),
                    Try(block(
                        Branch(v("i") eq i(2), block(Continue())),
                        Branch(v("i") eq i(4), block(Break())),
                        set("log", v("log") + str(v("i"))),
                    ), emptyList(), finally = block(set("log", v("log") + s(".")))),
                )),
                print(s("log ") + v("log")),
                Try(block(
                    Try(block(Throw(New("Failure", i(1)))), listOf(Catch("m", STR, block(print(s("wrong")))))),
                ), listOf(Catch("f", Type.Struct("Failure"), block(print(s("outer ") + str(Get(v("f"), "code"))))))),
            ),
            structs = listOf(failure),
            unions = listOf(signal),
        ),
        listOf("ok 42", "failure -3", "message zero", "retry 500", "finally 5", "returned=5", "finally -1", "caught not positive",
            "log 1..3..", "outer 1"),
    )

    val globals = Program(
        module("globals",
            fn("bump", listOf("by" to I64), UNIT, set("counter", v("counter") + v("by"))),
            fn("shadow", emptyList(), I64, Let("counter", I64, i(-1)), Return(v("counter"))),
            main(
                Eval(Call("bump", i(5))),
                Eval(Call("bump", i(10))),
                printNum("counter", v("counter")),
                printNum("shadow", Call("shadow")),
                print(v("greeting")),
            ),
            globals = listOf(
                Global("base", I64, i(100)),
                Global("counter", I64, v("base") + i(1)),
                Global("greeting", STR, s("hello ") + str(v("base"))),
            ),
        ),
        listOf("counter=116", "shadow=-1", "hello 100"),
    )

    private val vec2 = Struct("Vec2", listOf(Field("x", I64), Field("y", I64)), kind = MemoryKind.VALUE)
    private val buffer = Struct("Buffer", listOf(Field("len", I64), Field("items", Type.Array(I64))), kind = MemoryKind.OWNED)
    private val holder = Struct("Holder", listOf(Field("item", Type.Ref(Type.Struct("Vec2"), false, "a"))), kind = MemoryKind.OWNED, lifetimes = listOf("a"))
    private val counter = Struct("Counter", listOf(Field("n", I64)))
    val VEC2 = Type.Struct("Vec2")
    val BUFFER = Type.Struct("Buffer")
    fun ref(t: Type, mutable: Boolean = false, lifetime: String? = null) = Type.Ref(t, mutable, lifetime)
    fun deref(name: String, field: String) = Get(Deref(v(name)), field)

    val ownership = Program(
        module("ownership",
            fn("push", listOf("b" to ref(BUFFER, mutable = true), "value" to I64), UNIT,
                Assign(Index(deref("b", "items"), deref("b", "len")), v("value")),
                Assign(deref("b", "len"), deref("b", "len") + i(1)),
            ),
            fn("total", listOf("b" to ref(BUFFER)), I64,
                Let("sum", I64), Let("i", I64),
                Loop(v("i") lt deref("b", "len"), block(set("sum", v("sum") + Index(deref("b", "items"), v("i"))), inc("i"))),
                Return(v("sum")),
            ),
            fn("consume", listOf("b" to BUFFER), I64, Return(Get(v("b"), "len"))),
            // explicit lifetimes: the result lives as long as both arguments
            Func("first", listOf(Param("a", ref(VEC2, lifetime = "a")), Param("b", ref(VEC2, lifetime = "a"))), ref(VEC2, lifetime = "a"),
                block(Return(Conditional(Binary(BinaryOp.GE, deref("a", "x"), deref("b", "x")), v("a"), v("b")))), lifetimes = listOf("a")),
            // elision: one reference parameter, so the result borrows from it
            fn("xOf", listOf("p" to ref(VEC2)), ref(I64), Return(Borrow(deref("p", "x")))),
            fn("holderX", listOf("h" to ref(Type.Struct("Holder"))), I64, Return(Get(Deref(deref("h", "item")), "x"))),
            main(
                Let("v", VEC2, New("Vec2", i(1), i(2))),
                Let("w", VEC2, Copy(v("v"))),
                Let("wr", ref(VEC2, mutable = true), Borrow(v("w"), mutable = true)),
                Assign(deref("wr", "x"), i(10)),
                printNum("v.x", Get(v("v"), "x")),
                printNum("w.x", Get(v("w"), "x")),
                Let("b", BUFFER, New("Buffer", i(0), NewArray(I64, i(10)))),
                Eval(Call("push", Borrow(v("b"), mutable = true), i(5))),
                Eval(Call("push", Borrow(v("b"), mutable = true), i(7))),
                printNum("total", Call("total", Borrow(v("b")))),
                Let("n", I64, Call("consume", v("b"))),
                printNum("consumed", v("n")),
                Let("r", ref(VEC2), Call("first", Borrow(v("v")), Borrow(v("w")))),
                printNum("first", deref("r", "x")),
                Let("px", ref(I64), Call("xOf", Borrow(v("v")))),
                printNum("px", Deref(v("px"))),
                Let("h", Type.Struct("Holder"), New("Holder", Borrow(v("v")))),
                printNum("holder", Call("holderX", Borrow(v("h")))),
                Let("c", Type.Struct("Counter"), New("Counter", i(0))),
                Access(v("c"), true, block(Assign(Get(v("c"), "n"), Get(v("c"), "n") + i(41)))),
                Access(v("c"), false, block(printNum("counter", Get(v("c"), "n") + i(1)))),
            ),
            structs = listOf(vec2, buffer, holder, counter),
        ),
        listOf("v.x=1", "w.x=10", "total=12", "consumed=2", "first=10", "px=1", "holder=1", "counter=42"),
    )

    /** Allocates a lot: shared garbage, owned buffers and value copies. */
    val memory = Program(
        module("memory",
            fn("make", listOf("n" to I64), BUFFER, Return(New("Buffer", v("n"), NewArray(I64, i(2))))),
            main(
                Let("i", I64),
                Let("kept", Type.Struct("Counter"), New("Counter", i(0))),
                Loop(v("i") lt i(200000), block(
                    Let("garbage", Type.Struct("Counter"), New("Counter", v("i"))),
                    Let("b", BUFFER, Call("make", v("i"))),
                    Let("other", BUFFER, Call("make", i(1))),
                    Assign(v("other"), Call("make", i(2))),      // the old buffer is dropped
                    Eval(Call("make", i(3))),                     // a discarded result is dropped
                    Let("p", VEC2, New("Vec2", v("i"), v("i"))),
                    Let("q", VEC2, Copy(v("p"))),
                    Assign(Get(v("kept"), "n"), Get(v("kept"), "n") + Get(v("b"), "len") - Get(v("q"), "x") + i(1)),
                    inc("i"),
                )),
                printNum("kept", Get(v("kept"), "n")),
            ),
            structs = listOf(vec2, buffer, counter),
        ),
        listOf("kept=200000"),
    )

    val all = listOf(arithmetic, control, data, unions, closures, exceptions, globals, ownership)
}
