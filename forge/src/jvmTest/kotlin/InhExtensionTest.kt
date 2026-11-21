package com.leko.inhexttest

import com.leko.forge.*
import kotlin.test.Test
import kotlin.test.assertEquals

sealed interface Item
data class Module(val name: String, val items: List<Item>) : Item
data class Leaf(val id: Int) : Item

// the base language
val owners = inh<String>().on<Module> { name }
val Item.owner: String by owners

// a language extension: a new node type and its equation, from another module
data class Namespace(val prefix: String, val items: List<Item>) : Item

object Namespaces {
    init {
        owners.on<Namespace> { "$owner.$prefix" } // `owner` here is the namespace's own value
    }
}

// replacing a base equation: the one added last wins among equally specific ones
val labels = inh<String>().on<Module> { "base" }.on<Module> { "replaced" }
val Item.label: String by labels

val counts = inh<Int>().on<Module> { 1 }
val Item.count: Int by counts

class InhExtensionTest {

    @Test
    fun extensionAddsEquationForNewNodeType() {
        Namespaces // load the extension
        val leaf = Leaf(1)
        val plain = Leaf(2)
        tree(Module("app", listOf(Namespace("util", listOf(leaf)), plain)))
        assertEquals("app.util", leaf.owner)
        assertEquals("app", plain.owner)
    }

    @Test
    fun laterEquationReplacesEarlierOne() {
        val leaf = Leaf(1)
        tree(Module("m", listOf(leaf)))
        assertEquals("replaced", leaf.label)
    }

    @Test
    fun addingAnEquationClearsCachedValues() {
        val leaf = Leaf(1)
        tree(Module("m", listOf(Namespace("n", listOf(leaf)))))
        assertEquals(1, leaf.count)
        counts.on<Namespace> { 2 }
        assertEquals(2, leaf.count)
    }
}
