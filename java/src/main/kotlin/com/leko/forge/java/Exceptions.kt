package com.leko.forge.java

import com.leko.forge.msi.*
import com.leko.forge.syn
import com.leko.forge.java.JavaGrammar.block
import com.leko.forge.java.JavaGrammar.expr
import com.leko.forge.java.JavaGrammar.ident
import com.leko.forge.java.JavaGrammar.stmt

/** `throw`, `try`/`catch`/`finally`. Exceptions are objects of classes extending `Throwable`. */
object CoreExceptions : JavaFeature("exceptions") {
    init {
        stmt.on { +"throw"; val v = expr(); +";"; ThrowStmt(v) }
            .on {
                +"try"; val body = block()
                val catches = many { +"catch"; +"("; val types = sepBy(ident, "|"); val n = ident(); +")"; CatchClause(types, n, block()) }
                val fin = optional { +"finally"; block() }
                if (catches.isEmpty() && fin == null) fail("catch or finally")
                TryStmt(body, catches, fin)
            }
        problems
            .on<ThrowStmt> { p ->
                val t = value.jtype
                if (t == ErrorType || t is ClassType && classes.isSubclass(t.name, "Throwable")) p
                else p + problem("incompatible types: ${t.display} cannot be converted to Throwable")
            }
            .on<CatchClause> { p ->
                p + types.flatMap { t ->
                    when {
                        classes[t] == null -> problem("cannot find class $t")
                        !classes.isSubclass(t, "Throwable") -> problem("incompatible types: $t cannot be converted to Throwable")
                        else -> emptyList()
                    }
                }
            }
    }

    private val ThrowStmt.completesNormally: Boolean by syn { false }
    private val TryStmt.completesNormally: Boolean by syn {
        (body.completesNormally || catches.any { it.body.completesNormally }) && (finally?.completesNormally ?: true)
    }

    private val ThrowStmt.msi: Stmt by syn { Throw(value.msi) }

    /** One MSI catch for all objects; the Java catch clauses test the class in order, otherwise the exception is thrown again. */
    private val TryStmt.msi: Stmt by syn {
        val e = fresh("exception")
        val dispatch = catches.foldRight(Block(Throw(Local(e))) as Block) { c, rest ->
            val test = c.types.map { t -> instanceTest(Local(e), classes[t]!!) }.reduce { a, b -> Binary(BinaryOp.OR, a, b) }
            Block(Branch(test, Block(Let(c.name, OBJECT_TYPE, Local(e)), c.body.msi), rest))
        }
        val msiCatches = if (catches.isEmpty()) emptyList() else listOf(Catch(e, OBJECT_TYPE, dispatch))
        Try(body.msi as Block, msiCatches, finally?.let { it.msi as Block })
    }
}
