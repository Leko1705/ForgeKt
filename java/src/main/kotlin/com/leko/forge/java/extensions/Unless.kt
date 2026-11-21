package com.leko.forge.java.extensions

import com.leko.forge.Child
import com.leko.forge.java.*
import com.leko.forge.java.JavaGrammar.expr
import com.leko.forge.java.JavaGrammar.stmt
import com.leko.forge.msi.Block
import com.leko.forge.msi.Branch
import com.leko.forge.msi.Stmt
import com.leko.forge.msi.Unary
import com.leko.forge.msi.UnaryOp
import com.leko.forge.syn

/** `unless (condition) statement`: runs the statement if the condition is false. */
public data class UnlessStmt(@Child val condition: JExpr, @Child val body: JStmt) : JStmt

/** A new statement: syntax, a check and the lowering, all in this feature. */
public object Unless : JavaFeature("unless statements") {
    init {
        stmt.on { +"unless"; +"("; requireEnabled(); val c = expr(); +")"; UnlessStmt(c, stmt()) }
        problems.on<UnlessStmt> { p ->
            if (condition.jtype == BooleanType || condition.jtype == ErrorType) p
            else p + condition.problem("incompatible types: ${condition.jtype.display} cannot be converted to boolean")
        }
    }

    private val UnlessStmt.msi: Stmt by syn {
        val body = when (val b = body.msi) { is Block -> b; else -> Block(b) }
        Branch(Unary(UnaryOp.NOT, condition.msi), body)
    }
}
