package com.leko.forge

import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/*
 * The platform layer: everything that differs between JVM, JS and native. The rest of Forge is
 * plain common Kotlin. Porting Forge to another target means implementing these declarations.
 */

/** A map that compares keys by identity, not `equals`: equal data class nodes are separate nodes. */
internal interface IdentityMap<K : Any, V> {
    operator fun get(key: K): V?
    operator fun set(key: K, value: V)
    fun remove(key: K): V?
    fun containsKey(key: K): Boolean
    val keys: Collection<K>
}

internal expect fun <K : Any, V> identityMap(): IdentityMap<K, V>

/**
 * Like [identityMap], but the keys are held weakly where the platform supports it, so cached
 * attribute values do not keep a tree alive.
 */
internal expect fun <K : Any, V : Any> weakIdentityMap(): IdentityMap<K, V>

internal class IdentitySet<T : Any> {
    private val map = identityMap<T, Unit>()
    /** Adds [element]. Returns false if it was already present. */
    fun add(element: T): Boolean {
        if (map.containsKey(element)) return false
        map[element] = Unit
        return true
    }
    fun remove(element: T) { map.remove(element) }
    operator fun contains(element: T): Boolean = map.containsKey(element)
}

/** A reference that does not keep its value alive, where the platform supports it. */
internal interface Ref<T : Any> { fun get(): T? }

internal expect fun <T : Any> weakRef(value: T): Ref<T>

/** A value per thread (one value on single threaded platforms). */
internal interface Local<T> {
    fun get(): T
    fun set(value: T)
}

internal expect fun <T> threadLocal(initial: () -> T): Local<T>

/** Runs [block] while holding the lock of [lock] (no-op on single threaded platforms). */
internal expect inline fun <R> locked(lock: Any, block: () -> R): R

// ---- optional reflection: used where available, replaced by registrations elsewhere -------------

/** The children of [node] found by reflection, or `null` if the platform has no reflection. */
internal expect fun reflectedChildren(node: Any): List<Any>?

/** Whether [sub] is a subtype of [sup], or `null` if the platform can not tell. */
internal expect fun reflectedIsSubtype(sub: KClass<*>, sup: KClass<*>): Boolean?

/** The receiver type of an extension property (`Root` in `val Root.x`), or `null` if unknown. */
internal expect fun reflectedReceiver(property: KProperty<*>): KClass<*>?
