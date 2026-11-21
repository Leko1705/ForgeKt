package com.leko.forge.mptest

import com.leko.forge.msi.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the conformance programs with the interpreter, the reference for all backends. */
class ConformanceTest {

    private fun run(program: Conformance.Program): List<String> {
        val out = mutableListOf<String>()
        val problems = program.module.validate()
        assertEquals(emptyList(), problems.map { it.message }, "problems in ${program.module.name}")
        Interpreter(program.module, mapOf("print" to { args -> out += args[0] as String; Unit })).call("main")
        return out
    }

    @Test fun arithmetic() = assertEquals(Conformance.arithmetic.expected, run(Conformance.arithmetic))
    @Test fun control() = assertEquals(Conformance.control.expected, run(Conformance.control))
    @Test fun data() = assertEquals(Conformance.data.expected, run(Conformance.data))
    @Test fun unions() = assertEquals(Conformance.unions.expected, run(Conformance.unions))
    @Test fun closures() = assertEquals(Conformance.closures.expected, run(Conformance.closures))
    @Test fun exceptions() = assertEquals(Conformance.exceptions.expected, run(Conformance.exceptions))
    @Test fun globals() = assertEquals(Conformance.globals.expected, run(Conformance.globals))
    @Test fun ownership() = assertEquals(Conformance.ownership.expected, run(Conformance.ownership))
}
