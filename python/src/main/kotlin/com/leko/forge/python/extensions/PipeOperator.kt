package com.leko.forge.python.extensions

import com.leko.forge.Child
import com.leko.forge.syn
import com.leko.forge.python.*
import com.leko.forge.python.PythonGrammar.expr

/** `value |> function`. */
data class Pipe(@Child val value: PyExpr, @Child val function: PyExpr) : PyExpr

/**
 * A pipe operator (not Python): `data |> sorted |> list` calls the right side with the left one;
 * with a call on the right, the value becomes its first argument: `x |> pow(2)` is `pow(x, 2)`.
 * It binds weaker than everything but the conditional expression.
 */
object PipeOperator : PythonFeature("pipe operators") {
    init {
        expr.infixLeft(2) { l -> +"|>"; requireEnabled(); Pipe(l, operand()) }
    }

    /** Lowered to a call: `f(x)`, or `f(x, args)` for `x |> f(args)`. */
    private val Pipe.py: String by syn {
        val f = function
        if (f is Call) "${pyOperand(f.func, PyPrecedence.ATOM)}(${CoreExpressions.pyArguments(listOf(value) + f.args, f.keywords)})"
        else "${pyOperand(f, PyPrecedence.ATOM)}(${pyItem(value)})"
    }

    private val Pipe.js: String by syn {
        val f = function
        if (f is Call) "\$call(${f.func.js}, ${CoreExpressions.callArguments(listOf(value) + f.args, f.keywords)})"
        else "\$call(${f.js}, [${value.js}])"
    }
}
