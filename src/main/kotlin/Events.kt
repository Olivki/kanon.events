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

@file:JvmName("Events")
@file:Suppress("NOTHING_TO_INLINE")

// TODO Rename?

package moe.kanon.events

import mu.KotlinLogging
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation

/**
 * Represents a basic implementation of an event.
 *
 * This interface itself holds no additional data, and should just be used to represent a specific subsets of events.
 */
interface BasicEvent

/**
 * Represents a basic implementation of a cancellable event.
 *
 * This class can be used as a parent for events that can be cancelled/interrupted during execution.
 *
 * @property [isCancelled] Whether or not the event is cancelled, if `true` the operation will generally terminate.
 */
abstract class BasicCancellableEvent : BasicEvent {
    
    var isCancelled: Boolean = false
    
    /**
     * Cancels the current event, terminating whatever operation it was wrapped around.
     *
     * @see [isCancelled]
     */
    fun cancel() {
        isCancelled = true
    }
}

/**
 * Represents a basic implementation of a synchronized event.
 */
interface SynchronizedEvent

/**
 * Represents a basic implementation of a cancellable synchronized event.
 *
 * This class can be used as a parent for events that can be cancelled/interrupted during execution.
 *
 * @property [isCancelled] Whether or not the event is cancelled, if `true` the operation will generally terminate.
 */
abstract class SynchronizedCancellableEvent : SynchronizedEvent {
    
    var isCancelled: Boolean = false
    
    /**
     * Cancels the current event, terminating whatever operation it was wrapped around.
     *
     * @see [isCancelled]
     */
    fun cancel() {
        isCancelled = true
    }
}

/**
 * Marks a `function` as an event subscriber, allowing it to receive an event if registered as a listener on an
 * event-bus.
 *
 * The type of the event will be indicated by the functions first *(and only)* parameter. If this annotation is
 * applied to a function that has `0`, or more than `1` parameters, it will not be registered as a valid event
 * subscriber.
 */
@kotlin.annotation.Retention(AnnotationRetention.RUNTIME)
@kotlin.annotation.Target(AnnotationTarget.FUNCTION)
annotation class Subscribe(val priority: EventPriority = EventPriority.NORMAL)

/**
 * Houses the different priorities available for the listener functions.
 *
 * The priority is determined by the [ordinal][Enum.ordinal] of the enum, i.e;
 *
 * `LOWEST` has a priority of `0`.
 *
 * `HIGHEST` has a priority of `4`.
 *
 * etc...
 */
enum class EventPriority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST,
    OBSERVER
}

/**
 * The EventBus handles the firing, registration and removal of events and listeners.
 *
 * This event-bus is concurrent, and can therefore correctly handle multiple threads accessing it at the same time.
 *
 * @property [eventClass] The super-class of the events that will be fired from this bus.
 * @property [listenerClass] The super-class of the listeners that will be registered to this bus.
 *
 * @constructor The constructor for the event-bus.
 * @see EventBus.default
 * @see EventBus.create
 * @see eventBusOf
 */
class EventBus<E : Any, L : Any> private constructor(
    val eventClass: KClass<out E>,
    val listenerClass: KClass<out L>
) : Iterable<ListenerHandler<E, L>> {
    
    /**
     * Holds the instances of the listener handlers.
     */
    private val handlers: MutableMap<KClass<out E>, ListenerHandler<E, L>> = ConcurrentHashMap()
    
    /**
     * Returns how many listeners are registered.
     */
    val size: Int @JvmName("size") get() = handlers.size
    
    /**
     * The logger used by the event-bus.
     */
    private val logger = KotlinLogging.logger {}
    
    /**
     * Fires the given [event] to the appropriate [listener-handler][ListenerHandler], which then spreads it to all
     * [registered-listeners][RegisteredListener].
     *
     * Any `exceptions` *(even checked ones)* are propagated upwards, untouched.
     *
     * This function is weakly consistent, and may not see `listeners` that are added while it is firing.
     *
     * @param [event] The `event` to fire.
     *
     * @return the event that was just fired.
     */
    fun fire(event: E): E {
        // If the given listener is somehow not an instance of the set eventClass, then just fail loudly.
        require(eventClass.isInstance(event)) { "${event::class} is not a valid event, it needs to be an instance of $eventClass." }
        
        logger.debug { "Firing event <$event>." }
        
        handlers[event::class]?.notify(event) // If it returns null it just means that there's no registered listener
        // that's waiting for that specific event, so we just don't do anything.
        
        return event
    }
    
    /**
     * Converts the given [listener] into a [registered-listener][RegisteredListener] and then sends it to the
     * appropriate handler for registration.
     *
     * @param [listener] The object to convert into a [registered-listener][RegisteredListener].
     */
    @Throws(IllegalArgumentException::class)
    fun register(listener: L) {
        // If the given listener is somehow not an instance of the set listenerClass, then just fail loudly.
        require(listenerClass.isInstance(listener)) { "Invalid listener type: ${listener::class}, needs to be instance of $listenerClass." }
        
        val listenerFuncs = listener::class.declaredMemberFunctions.filter { it.hasAnnotation<Subscribe>() }
        
        if (listenerFuncs.isEmpty()) logger.debug { "<${listener::class}> has no listener functions." }
        
        // because the .forEach {...} closure is slower than a normal loop.
        for (func in listenerFuncs) {
            val registeredListener =
                RegisteredListener(this, listener, func, func.findAnnotation()!!) // We know that
            // the @Subscribe annotation is there because we filtered for it explicitly.
            val handler = handlers.computeIfAbsent(registeredListener.eventClass) { ListenerHandler() }
            
            handler.register(registeredListener)
            logger.info { "Registered <$listener> as an event-listener." }
        }
    }
    
    /**
     * Converts the given [listener] into a [registered-listener][RegisteredListener] and then sends it to the
     * appropriate handler for registration.
     *
     * This function registers the given `listener` to a [synchronized-listener-handler][SynchronizedListenerHandler]
     * rather than the normal [listener-handler][ListenerHandler].
     *
     * This means that the firing of events will be handled in a more "`synchronized`" manner.
     *
     * @param [SE] **S**ynchronized**E**vent
     * @param [listener] The object to convert into a [synchronized-registered-listener][SynchronizedListenerHandler].
     */
    @Suppress("UNCHECKED_CAST")
    @Throws(IllegalArgumentException::class)
    fun <SE : SynchronizedEvent> registerSynchronized(listener: L) {
        // If the given listener is somehow not an instance of the set listenerClass, then just fail loudly.
        require(listenerClass.isInstance(listener)) { "Invalid listener type <${listener::class}>, expected: $listenerClass" }
    
        val listenerFuncs = listener::class.declaredMemberFunctions.filter { it.hasAnnotation<Subscribe>() }
    
        if (listenerFuncs.isEmpty()) logger.debug { "<${listener::class}> has no listener functions." }
        
        // because the .forEach {...} closure is slower than a normal loop.
        for (func in listenerFuncs) {
            val registeredListener = RegisteredListener(this, listener, func, func.findAnnotation()!!) // We know that
            // the @Subscribe annotation is there because we filtered for it explicitly.
            val handler = handlers.computeIfAbsent(registeredListener.eventClass) {
                SynchronizedListenerHandler<SE, L>() as ListenerHandler<E, L>
            }
            
            handler.register(registeredListener)
            logger.info { "Registered <$listener> as a synchronized event-listener." }
        }
    }
    
    /**
     * Converts the given [listener] into a [registered-listener][RegisteredListener] and then sends it to the
     * appropriate handler for registration.
     *
     * @param [listener] The object to convert into a [registered-listener][RegisteredListener].
     */
    @JvmSynthetic
    inline operator fun plusAssign(listener: L) = register(listener)
    
    /**
     * Attempts to unregister the given [listener] from the handlers.
     *
     * If no reference of the given `listener` is found, this function will just silently fail.
     *
     * @param [listener] The listener to unregister.
     */
    @Throws(IllegalArgumentException::class)
    fun unregister(listener: L) {
        require(listenerClass.isInstance(listener)) { "Invalid listener type <${listener::class.allSuperclasses}>, expected: <$listenerClass>." }
        
        for (func in listener::class.declaredMemberFunctions.filter { it.hasAnnotation<Subscribe>() }) {
            val registeredListener = RegisteredListener(this, listener, func, func.findAnnotation()!!) // We know that
            // the @Subscribe annotation is there because we filtered for it explicitly.
            
            if (!handlers.containsKey(registeredListener.eventClass)) continue
            
            // We know that it won't be null, because we already checked that the map contained the key.
            handlers[registeredListener.eventClass]!! -= registeredListener
            logger.info { "Unregistered <$listener> as an event-listener." }
        }
    }
    
    /**
     * Attempts to unregister the given [listener] from the handlers.
     *
     * If no reference of the given `listener` is found, this function will just silently fail.
     *
     * @param [listener] The listener to unregister.
     */
    @JvmSynthetic
    inline operator fun minusAssign(listener: L) = unregister(listener)
    
    /**
     * Returns the [ListenerHandler] registered under the specified [clz], or throws a [NoSuchElementException] if none is
     * found.
     */
    operator fun get(clz: KClass<out E>): ListenerHandler<E, L> = getOrNull(clz)
        ?: throw NoSuchElementException("There's no listener-handler registered under the class <${clz.qualifiedName}>.")
    
    /**
     * Returns the [ListenerHandler] registered under the specified [clz], or `null` if none is found.
     */
    fun getOrNull(clz: KClass<out E>): ListenerHandler<E, L>? = handlers[clz]
    
    /**
     * Returns whether or not there's a [ListenerHandler] registered under the specified [clz].
     *
     * @see hasClass
     */
    operator fun contains(clz: KClass<out E>): Boolean = clz in handlers
    
    /**
     * Returns whether or not there's a [ListenerHandler] registered under the specified [clz].
     *
     * *Unlike the [contains] operator, this function accepts a star-projected [KClass]. (`KClass<*>`)*
     *
     * @see contains
     */
    fun hasClass(clz: KClass<*>): Boolean = handlers.any { (key) -> key.isInstance(clz) }
    
    /**
     * Returns whether or not there's a [ListenerHandler] registered under the specified [C] type.
     */
    inline fun <reified C : Any> hasClass(): Boolean = hasClass(C::class)
    
    override fun iterator(): Iterator<ListenerHandler<E, L>> = handlers.values.toList().iterator()
    
    companion object {
        
        /**
         * Provides a default implementation of an event-bus, where all events need to be sub-classes of [BasicEvent]
         * and the listeners can be anything as long as they inherit from [Any].
         */
        @JvmStatic
        val default: EventBus<BasicEvent, Any>
            get() = create()
        
        /**
         * Returns an [event-bus][EventBus] created from the given [eventClass] and [listenerClass].
         *
         * @see eventBusOf
         */
        @JvmStatic
        fun <E : Any, L : Any> create(eventClass: KClass<out E>, listenerClass: KClass<out L>): EventBus<E, L> =
            EventBus(eventClass, listenerClass)
        
        /**
         * Returns an [event-bus][EventBus] created from the classes of the given [E] and [L].
         *
         * @see eventBusOf
         */
        inline fun <reified E : Any, reified L : Any> create(): EventBus<E, L> = create(E::class, L::class)
    }
}

interface EventExecutor<E : Any, L : Any> {
    
    /**
     * Fires the specified [event] to the specified [listener].
     */
    fun fire(listener: L, event: E)
    
    interface Factory {
        
        /**
         * Creates the executor factory for the specified [bus] and [method].
         */
        fun <E : Any, L : Any> create(bus: EventBus<E, L>, method: Method): EventExecutor<E, L>
        
    }
}

/**
 * Returns an [event-bus][EventBus] created from the given [eventClass] and [listenerClass].
 *
 * @see EventBus.create
 */
inline fun <E : Any, L : Any> eventBusOf(eventClass: KClass<out E>, listenerClass: KClass<out L>): EventBus<E, L> =
    EventBus.create(eventClass, listenerClass)

/**
 * Returns an [event-bus][EventBus] created from the classes of the given [E] and [L].
 *
 * @see EventBus.create
 */
inline fun <reified E : Any, reified L : Any> eventBusOf(): EventBus<E, L> = EventBus.create(E::class, L::class)

// Taken from kanon.kommons, got no interest in including that entire library just for one function.
internal inline fun <reified A : Annotation> KAnnotatedElement.hasAnnotation(): Boolean =
    this.annotations.any { it is A }