package com.leko.binarytest

import com.leko.forge.*
import com.leko.parsertest.IntLit
import com.leko.parsertest.LeftAssoc
import com.leko.parsertest.Plus
import com.leko.parsertest.Expr
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// A small part of the JVM class file format
sealed interface Constant
data class Utf8(val value: String) : Constant
data class IntConst(val value: Int) : Constant
data class ClassFile(val minor: Int, val major: Int, val constants: List<Constant>, val accessFlags: Int)

object ClassFormat {
    val classFile by rule {
        +hex("CAFEBABE")
        val minor = u2()
        val major = u2()
        val count = u2()
        val constants = List(count - 1) { constant() }
        ClassFile(minor, major, constants, u2())
    }
    val constant: Rule<Constant> by rule { oneOf(utf8, integer) }
    val utf8 by rule { +hex("01"); Utf8(bytes(u2()).decodeToString()) }
    val integer by rule { +hex("03"); IntConst(u4().toInt()) }
}

// Fields that are not byte aligned: 3 bit version "101", 5 bit kind, 4 bit signed value, 4 bit padding
data class Header(val kind: Int, val delta: Int)
val header by rule { +bits("101"); val kind = uint(5).toInt(); val delta = sint(4).toInt(); +bits("0000"); Header(kind, delta) }

// Mixed: an ASCII tag, then a length-prefixed embedded source text parsed with a text grammar
data class Script(val name: String, val code: Expr)
object Container {
    val name by token("[a-z]+")
    val script by rule {
        +"SRC"; +hex("00")
        val n = name(); +hex("00")
        Script(n, LeftAssoc.expr.parse(bytes(u2()).decodeToString()))
    }
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
private fun u2(v: Int) = intArrayOf(v shr 8, v and 0xFF)

class BinaryParserTest {

    @Test
    fun parsesClassFileHeaderAndConstants() {
        val input = bytes(
            0xCA, 0xFE, 0xBA, 0xBE, *u2(0), *u2(65),
            *u2(3),                                 // constant pool count = 2 entries + 1
            0x01, *u2(4), 'M'.code, 'a'.code, 'i'.code, 'n'.code,
            0x03, 0x00, 0x00, 0x01, 0x00,
            *u2(0x0021),
        )
        assertEquals(
            ClassFile(0, 65, listOf(Utf8("Main"), IntConst(256)), 0x21),
            ClassFormat.classFile.parse(input)
        )
    }

    @Test
    fun parsesUnalignedBitFields() {
        // 101 00110 | 1110 0000  -> kind 6, delta -2
        assertEquals(Header(6, -2), header.parse(bytes(0b1010_0110, 0b1110_0000)))
        assertEquals(bits(0b0101, width = 4).toString(), bits("0101").toString())
    }

    @Test
    fun mixesTextAndBinary() {
        val source = "1 + 2 + 3".encodeToByteArray()
        val input = "SRC".encodeToByteArray() + bytes(0) + "calc".encodeToByteArray() + bytes(0, *u2(source.size)) + source
        val script = Container.script.parse(input)
        assertEquals(Script("calc", Plus(Plus(IntLit(1), IntLit(2)), IntLit(3))), script)
        assertEquals(script, parentOf(script.code)) // the whole result is registered as one tree
    }

    @Test
    fun reportsBinaryErrorPosition() {
        val e = assertFailsWith<ParseException> { ClassFormat.classFile.parse(bytes(0xCA, 0xFE, 0x00, 0x00)) }
        assertEquals("byte 0: expected 0xCAFEBABE, found 0xCA", e.message)
        val e2 = assertFailsWith<ParseException> { header.parse(bytes(0b1010_0110)) }
        assertEquals("byte 1: expected 4 more bits, found end of input", e2.message)
    }

    @Test
    fun binaryPrimitivesNeedBinaryInput() {
        assertFailsWith<IllegalStateException> { header.parse("abc") }
    }
}
