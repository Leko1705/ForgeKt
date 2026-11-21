package com.leko.forge

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.full.extensionReceiverParameter

// ---- collections ---------------------------------------------------------------------------

private class JvmIdentityMap<K : Any, V> : IdentityMap<K, V> {
    private val map = IdentityHashMap<K, V>()
    override fun get(key: K): V? = map[key]
    override fun set(key: K, value: V) { map[key] = value }
    override fun remove(key: K): V? = map.remove(key)
    override fun containsKey(key: K): Boolean = map.containsKey(key)
    override val keys: Collection<K> get() = map.keys
}

internal actual fun <K : Any, V> identityMap(): IdentityMap<K, V> = JvmIdentityMap()

/** Weak keys compared by identity. The JDK has no such map, so this one is built on [WeakReference]. */
private class JvmWeakIdentityMap<K : Any, V : Any> : IdentityMap<K, V> {
    private val queue = ReferenceQueue<K>()
    private val map = HashMap<Key<K>, V>()

    private class Key<K : Any>(referent: K, queue: ReferenceQueue<K>?) : WeakReference<K>(referent, queue) {
        private val hash = System.identityHashCode(referent)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key<*>) return false
            val referent = get() ?: return false
            return referent === other.get()
        }
    }

    @Synchronized override fun get(key: K): V? { expunge(); return map[Key(key, null)] }
    @Synchronized override fun set(key: K, value: V) { expunge(); map[Key(key, queue)] = value }
    @Synchronized override fun remove(key: K): V? { expunge(); return map.remove(Key(key, null)) }
    @Synchronized override fun containsKey(key: K): Boolean { expunge(); return map.containsKey(Key(key, null)) }
    override val keys: Collection<K> @Synchronized get() = map.keys.mapNotNull { it.get() }

    private fun expunge() {
        while (true) map.remove(queue.poll() ?: return)
    }
}

internal actual fun <K : Any, V : Any> weakIdentityMap(): IdentityMap<K, V> = JvmWeakIdentityMap()

internal actual fun <T : Any> weakRef(value: T): Ref<T> {
    val ref = WeakReference(value)
    return object : Ref<T> { override fun get(): T? = ref.get() }
}

internal actual fun <T> threadLocal(initial: () -> T): Local<T> {
    val local = ThreadLocal.withInitial(initial)
    return object : Local<T> {
        override fun get(): T = local.get()
        override fun set(value: T) = local.set(value)
    }
}

internal actual inline fun <R> locked(lock: Any, block: () -> R): R = synchronized(lock, block)

// ---- reflection ----------------------------------------------------------------------------

private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

/**
 * Children are the values of the node's fields. If any field of the class is annotated with
 * [Child], only the annotated fields are children (so references to other nodes can be stored in
 * fields without becoming children). JDK and Kotlin standard library classes are leaves.
 */
internal actual fun reflectedChildren(node: Any): List<Any>? {
    if (isLibraryClass(node.javaClass)) return emptyList()
    val children = mutableListOf<Any>()
    for (field in fieldsOf(node.javaClass)) flattenChildren(field.get(node), children)
    return children.filterNot { isLibraryClass(it.javaClass) || it.javaClass.isArray }
}

private fun isLibraryClass(cls: Class<*>): Boolean {
    val name = cls.name
    return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("kotlin.") ||
            name.startsWith("jdk.") || name.startsWith("sun.")
}

private fun fieldsOf(cls: Class<*>): List<Field> = fieldCache.getOrPut(cls) {
    val all = mutableListOf<Field>()
    var c: Class<*>? = cls
    while (c != null && c != Any::class.java) {
        if (!isLibraryClass(c)) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic || f.type.isPrimitive) continue
                if (runCatching { f.isAccessible = true }.isSuccess) all += f
            }
        }
        c = c.superclass
    }
    val annotated = all.filter { it.isAnnotationPresent(Child::class.java) }
    annotated.ifEmpty { all }
}

internal actual fun reflectedIsSubtype(sub: KClass<*>, sup: KClass<*>): Boolean? =
    sup.java.isAssignableFrom(sub.java)

internal actual fun reflectedReceiver(property: KProperty<*>): KClass<*>? =
    runCatching { property.extensionReceiverParameter?.type?.classifier as? KClass<*> }.getOrNull()
