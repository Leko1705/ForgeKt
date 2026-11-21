package com.leko.tiny

import com.leko.forge.syn
import com.leko.forge.msi.Binary
import com.leko.forge.msi.BinaryOp
import com.leko.forge.msi.BoolLit
import com.leko.forge.msi.IntLit
import com.leko.forge.msi.Local
import com.leko.forge.msi.Unary
import com.leko.forge.msi.UnaryOp
import com.leko.forge.msi.Assign as MAssign
import com.leko.forge.msi.Block as MBlock
import com.leko.forge.msi.Branch as MBranch
import com.leko.forge.msi.Expr as MExpr
import com.leko.forge.msi.Func as MFunc
import com.leko.forge.msi.Let as MLet
import com.leko.forge.msi.Loop as MLoop
import com.leko.forge.msi.Module as MModule
import com.leko.forge.msi.Param as MParam
import com.leko.forge.msi.Return as MReturn
import com.leko.forge.msi.Stmt as MStmt
import com.leko.forge.msi.Type as MType

/*
 * 10. Conversion to the Multi-System-Tree (MSI), with synthesized attributes. The MSI module can
 * be checked, run by the MSI interpreter, and translated by any MSI backend.
 */

val Type.msi: MType get() = when (this) {
    Type.INT -> MType.I64
    Type.BOOL -> MType.Bool
    Type.ERROR -> MType.Invalid
}

/** The functions without errors, as an MSI module. */
val Program.msi: MModule by syn {
    val broken = diags.filter { it.severity == "error" }.map { it.function }.toSet()
    MModule("tiny", functions = funcs.filter { it.name !in broken }.map { it.msi })
}

val Func.msi: MFunc by syn { MFunc(name, params.map { MParam(it.name, it.declared.msi) }, ret.msi, body.msi) }

val Stmt.msi: MStmt by syn()
val Block.msi: MBlock by syn { MBlock(stmts.map { it.msi }) }
val VarDecl.msi: MStmt by syn { MLet(name, declType.msi, init.msi) }
val Assign.msi: MStmt by syn { MAssign(Local(name), value.msi) }
val While.msi: MStmt by syn { MLoop(cond.msi, body.msi) }
val If.msi: MStmt by syn { MBranch(cond.msi, thenBlock.msi, elseBlock?.msi) }
val Return.msi: MStmt by syn { MReturn(value.msi) }

val Expr.msi: MExpr by syn()
val Num.msi: MExpr by syn { IntLit(value.toLong()) }
val Bool.msi: MExpr by syn { BoolLit(value) }
val Name.msi: MExpr by syn { Local(name) }
val Add.msi: MExpr by syn { Binary(BinaryOp.ADD, l.msi, r.msi) }
val Less.msi: MExpr by syn { Binary(BinaryOp.LT, l.msi, r.msi) }
val And.msi: MExpr by syn { Binary(BinaryOp.AND, l.msi, r.msi) }
val Not.msi: MExpr by syn { Unary(UnaryOp.NOT, e.msi) }

