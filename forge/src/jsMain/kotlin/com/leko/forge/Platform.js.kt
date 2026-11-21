package com.leko.forge

import kotlin.reflect.KClass
import kotlin.reflect.KProperty

// JavaScript is single threaded and has no runtime reflection: children and subtypes come from
// TreeNode, children<...> { } and supertypes<...>() registrations.

private class JsIdentityMap<K : Any, V> : IdentityMap<K, V> {
    // JS Map compares objects by identity
    private val map: dynamic = js("new Map()")
    override fun get(key: K): V? = map.get(key).unsafeCast<V?>()
    override fun set(key: K, value: V) { map.set(key, value) }
    override fun remove(key: K): V? = get(key).also { map.delete(key) }
    override fun containsKey(key: K): Boolean = map.has(key).unsafeCast<Boolean>()
    override val keys: Collection<K>
        get() = js("Array").from(map.keys()).unsafeCast<Array<K>>().toList()
}

internal actual fun <K : Any, V> identityMap(): IdentityMap<K, V> = JsIdentityMap()

/** JS WeakMap for objects. Primitives (strings, numbers) can not be weak keys, they use a normal Map. */
private class JsWeakIdentityMap<K : Any, V : Any> : IdentityMap<K, V> {
    private val weak: dynamic = js("new WeakMap()")
    private val strong = JsIdentityMap<K, V>()
    private fun isObject(key: Any): Boolean {
        val type = jsTypeOf(key)
        return type == "object" || type == "function"
    }
    override fun get(key: K): V? = if (isObject(key)) weak.get(key).unsafeCast<V?>() else strong[key]
    override fun set(key: K, value: V) { if (isObject(key)) weak.set(key, value) else strong[key] = value }
    override fun remove(key: K): V? = get(key).also { if (isObject(key)) weak.delete(key) else strong.remove(key) }
    override fun containsKey(key: K): Boolean = if (isObject(key)) weak.has(key).unsafeCast<Boolean>() else strong.containsKey(key)
    override val keys: Collection<K> get() = error("the keys of a weak map can not be listed")
}

internal actual fun <K : Any, V : Any> weakIdentityMap(): IdentityMap<K, V> = JsWeakIdentityMap()

internal actual fun <T : Any> weakRef(value: T): Ref<T> {
    val type = jsTypeOf(value)
    if (type != "object" && type != "function") return object : Ref<T> { override fun get(): T = value }
    val ref: dynamic = js("new WeakRef(value)")
    return object : Ref<T> { override fun get(): T? = ref.deref().unsafeCast<T?>() }
}

internal actual fun <T> threadLocal(initial: () -> T): Local<T> = object : Local<T> {
    private var value = initial()
    override fun get(): T = value
    override fun set(value: T) { this.value = value }
}

internal actual inline fun <R> locked(lock: Any, block: () -> R): R = block()

internal actual fun reflectedChildren(node: Any): List<Any>? = null
internal actual fun reflectedIsSubtype(sub: KClass<*>, sup: KClass<*>): Boolean? = null
internal actual fun reflectedReceiver(property: KProperty<*>): KClass<*>? = null
