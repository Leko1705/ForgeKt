package com.leko.graphtest

import com.leko.forge.*

// A language extension: a new statement and its flow, without touching the base language
data class DoWhile(val body: Stmt, val cond: Ex) : Stmt

object DoWhileExtension {
    init {
        cfg.flow<DoWhile> { (body, cond) ->
            entry(body)
            body then cond
            onTrue(cond) then body
            onFalse(cond) then next
            brk(body) then next
        }
    }
}
