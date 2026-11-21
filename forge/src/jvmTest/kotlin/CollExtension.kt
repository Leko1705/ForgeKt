package com.leko.colltest

import com.leko.parsertest.This

// ... and an extension module adds more contributions to it, without touching the base language
object ThisChecks {
    init {
        diagnostics.on<This>(to = { root }) { diags -> diags + listOfNotNull(t.error?.let(::Diagnostic)) }
    }
}
