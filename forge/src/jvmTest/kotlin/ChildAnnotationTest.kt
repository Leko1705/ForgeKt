package com.leko.childtest

import com.leko.forge.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

data class Decl(val name: String)
data class Use(val name: String)
// `resolved` refers to a node elsewhere in the tree: it must not become a child of Scope
class Scope(@Child val decls: List<Decl>, @Child val uses: List<Use>, val resolved: Map<String, Decl>)

class ChildAnnotationTest {
    @Test
    fun onlyAnnotatedFieldsAreChildren() {
        val d = Decl("x")
        val outside = Decl("y")
        val u = Use("x")
        val scope = tree(Scope(listOf(d), listOf(u), mapOf("y" to outside)))
        assertEquals(listOf<Any>(d, u), childrenOf(scope))
        assertSame(scope, parentOf(d))
        assertNull(parentOf(outside))
    }
}
