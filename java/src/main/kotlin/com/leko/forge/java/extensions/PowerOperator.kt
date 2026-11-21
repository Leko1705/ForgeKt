package com.leko.forge.java.extensions

import com.leko.forge.Child
import com.leko.forge.java.*
import com.leko.forge.java.JavaGrammar.expr
import com.leko.forge.msi.*
import com.leko.forge.syn

/** `base ** exponent` for int and long. */
public data class PowerExpr(@Child val base: JExpr, @Child val exponent: JExpr) : JExpr

/**
 * A new operator: `**`, binding stronger than unary minus (`-2 ** 2` is `-(2 ** 2)`) and right
 * associative (`2 ** 3 ** 2` is `2 ** 9`). Lowered to a generated MSI function.
 */
public object PowerOperator : JavaFeature("the ** operator") {
    init {
        expr.infixRight(14) { l -> +"**"; requireEnabled(); PowerExpr(l, operand()) }
        problems.on<PowerExpr> { p ->
            val b = base.jtype
            val e = exponent.jtype
            if (b == ErrorType || e == ErrorType || b.isIntegral && e.isIntegral) p
            else p + problem("bad operand types for binary operator **: ${b.display} and ${e.display} (only int and long are supported)")
        }
    }

    private val PowerExpr.jtype: JType by syn { if (base.jtype.isIntegral && exponent.jtype.isIntegral) promote(base.jtype, IntType) else ErrorType }

    private val PowerExpr.msi: Expr by syn {
        val t = jtype
        val m = t.msi
        val one = if (t == LongType) IntLit(1) else IntLit(1, Type.I32)
        val name = helper("\$pow_${t.display}") {
            Func("\$pow_${t.display}", listOf(Param("b", m), Param("e", Type.I64)), m, Block(
                Let("r", m, one),
                Let("i", Type.I64, IntLit(0)),
                Loop(Binary(BinaryOp.LT, Local("i"), Local("e")), Block(
                    Assign(Local("r"), Binary(BinaryOp.MUL, Local("r"), Local("b"))),
                    Assign(Local("i"), Binary(BinaryOp.ADD, Local("i"), IntLit(1))),
                )),
                Return(Local("r")),
            ))
        }
        Call(name, base.msiAs(t), exponent.msiAs(LongType))
    }
}
