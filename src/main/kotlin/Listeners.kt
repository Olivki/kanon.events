/*
 * The Apache 2.0 License
 * Copyright 2019 Oliver Berg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The MIT License
 * Copyright (c) 2015-2016 Techcable
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 */

@file:JvmName("Listeners")

package moe.kanon.events

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.jvmName

/**
 * This class handles the storing and retrieval of all [registered-listeners][RegisteredListener].
 */
open class ListenerHandler<E : Any, L : Any> {
    
    private val listeners: MutableSet<RegisteredListener<E, L>> = ConcurrentHashMap.newKeySet()
    
    private val cachedSortedListeners: MutableList<RegisteredListener<E, L>> = mutableListOf()
    
    /**
     * Has the [listeners] set been updated any since we last sorted it.
     */
    private var isUpdated: Boolean = true
    
    /**
     * Notifies all registered `listeners` that an event has been fired.
     *
     * The `listeners` get notified in the order of their priority.
     *
     * @see RegisteredListener.compareTo
     */
    open fun notify(event: E) {
        // We need the registered listeners to actually be sorted by their priority.
        val sortedListeners: List<RegisteredListener<E, L>> = when {
            // If there's been any change to the listeners set, we clear out the old one, and then populate it with
            // a newly sorted listeners set.
            isUpdated -> synchronized(this) {
                cachedSortedListeners.apply {
                    clear()
                    addAll(listeners.sorted())
                    isUpdated = false
                }
            }
            // If there's been no change, then just use the cached one.
            else -> cachedSortedListeners
        }
        
        for (listener in sortedListeners) listener.fire(event)
    }
    
    /**
     * Adds the given [listener] to this handler.
     */
    @JvmSynthetic // Synthetic so that it won't clutter the namespace if used from java.
    open operator fun plusAssign(listener: RegisteredListener<E, L>) = register(listener)
    
    /**
     * Adds the given [listener] to this handler.
     */
    open fun register(listener: RegisteredListener<E, L>) {
        listeners += listener
        isUpdated = true
    }
    
    /**
     * Removes the given [listener] from this handler.
     */
    @JvmSynthetic
    open operator fun minusAssign(listener: RegisteredListener<E, L>) = unregister(listener)
    
    /**
     * Removes the given [listener] from this handler.
     */
    open fun unregister(listener: RegisteredListener<E, L>) {
        listeners -= listener
        isUpdated = true
    }
}

/**
 * This class handles the storing and retrieval of all [registered-listeners][RegisteredListener].
 */
open class SynchronizedListenerHandler<E : SynchronizedEvent, L : Any> : ListenerHandler<E, L>() {
    
    private val lock: Lock = ReentrantLock()
    
    /**
     * Notifies all registered `listeners` that an event has been fired, and locks the thread.
     *
     * The `listeners` get notified in the order of their priority.
     */
    override fun notify(event: E) {
        lock.lock()
        try {
            super.notify(event)
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Represents an event listener.
 *
 * Takes care of the actual firing of events to the appropriate listener functions.
 *
 * @property [bus] The [event-bus][EventBus] this listener is operating under.
 * @property [listener] The specific listener class the `event-bus` is operating under.
 * @property [func] The listener `function` this listener is tied to.
 *
 * *This might be changed to *only* allow [Unit] functions at a later date.*
 * @property [annotation] The [@Subscribe][Subscribe] annotation that marks the listener `function`.
 */
@Suppress("UNCHECKED_CAST")
class RegisteredListener<E : Any, L : Any> internal constructor(
    val bus: EventBus<E, L>,
    val listener: L,
    val func: KFunction<*>,
    val annotation: Subscribe
) : Comparable<RegisteredListener<E, L>> {
    
    init {
        // Make sure the given function is correct.
        func.isValidListenerFunction(bus)
    }
    
    private val executor = AsmEventExecutorFactory.create(bus, func.javaMethod!!) // We know that
    // our "func" isn't a constructor.
    
    /**
     * The priority that this listener is marked with.
     */
    val priority: Int = annotation.priority.ordinal
    
    /**
     * The class of the event that the listener function of this listener is asking for.
     */
    val eventClass: KClass<out E> = func.valueParameters[0].type.jvmErasure as KClass<out E>
    
    /**
     * Fires the [event] to the [executor].
     */
    fun fire(event: E) = executor.fire(listener, event)
    
    override fun toString(): String = "${listener::class.jvmName}::${func.name}"
    
    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is RegisteredListener<*, *> -> false
        bus != other.bus -> false
        listener != other.listener -> false
        func != other.func -> false
        annotation != other.annotation -> false
        else -> true
    }
    
    override fun hashCode(): Int = func.hashCode() xor listener.hashCode()
    
    override fun compareTo(other: RegisteredListener<E, L>): Int = priority.compareTo(other.priority)
    
}

/**
 * Checks if everything is up to snuff with the receiver function.
 *
 * @receiver The function to check against.
 */
@Throws(IllegalArgumentException::class)
internal fun <E : Any, L : Any> KFunction<*>.isValidListenerFunction(bus: EventBus<E, L>) {
    // Pretty redundant check seeing as we will only be passing functions that we *know* are annotated, will probably
    // be removed.
    require(this.hasAnnotation<Subscribe>()) { "$this is missing the @Subscribe annotation, invalid event handler." }
    // Gotta make sure we only check for value parameters, otherwise things could get ugly.
    // This is really just to enforce a style and make sure the user isn't trying to pass the event handler around like
    // a normal function and invoking it.
    require(this.valueParameters.size == 1) { "$this has an invalid amount of parameters, only 1 allowed, invalid event handler." }
    // And now we make sure that the parameter is actually a valid event.
    require(this.valueParameters.first().type.jvmErasure.isSubclassOf(bus.eventClass)) { "$this does not contain a valid event parameter, invalid event handler." }
}