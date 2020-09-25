package dev.latantal.neo4j.generator.util

import kotlin.reflect.KProperty

fun <T> delegate(get: (KProperty<*>) -> T) = ValDelegate<Any, T> { _, prop -> get(prop) }

fun <T> delegateProvider(getter: (KProperty<*>) -> () -> T) = ValDelegateProvider<Any, T> { _, prop ->
    val get = getter(prop)
    ValDelegate { _, _ -> get() }
}

fun interface ValDelegate<C, P> {
    operator fun getValue(thisRef: C, prop: KProperty<*>): P
}

fun interface ValDelegateProvider<C, P> {
    operator fun provideDelegate(thisRef: C, prop: KProperty<*>): ValDelegate<C, P>
}