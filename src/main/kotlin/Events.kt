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

// TODO Rename?

package moe.kanon.events

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KAnnotatedElement
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

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
    MONITOR
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
 * @see eventBus
 */
class EventBus<E : Any, L : Any> private constructor(
    val eventClass: KClass<E>,
    val listenerClass: KClass<L>
) {
    
    /**
     * Holds the instances of the listener handlers.
     */
    val handlers: MutableMap<KClass<*>, ListenerHandler<E, L>> = ConcurrentHashMap()
    
    /**
     * Fires the given [event] to the appropriate [listener-handler][ListenerHandler], which then spreads it to all
     * [registered-listeners][RegisteredListener].
     *
     * Any `exceptions` *(even checked ones)* are propagated upwards, untouched.
     *
     * This function is weakly consistent, and may not see `listeners` that are added while it is firing.
     *
     * @param [event] The `event` to fire.
     */
    fun fire(event: E) {
        // If the given listener is somehow not an instance of the set eventClass, then just fail loudly.
        require(eventClass.isInstance(event)) { "${event::class} is not a valid event, it needs to be an instance of $eventClass." }
        
        handlers[event::class]?.notify(event) // If it returns null it just means that there's no registered listener
        // that's waiting for that specific event, so we just don't do anything.
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
        
        // because the .forEach {...} closure is slower than a normal loop.
        for (func in listener::class.memberFunctions.filter { it.hasAnnotation<Subscribe>() }) {
            val registeredListener = RegisteredListener(this, listener, func, func.findAnnotation()!!) // We know that
            // the @Subscribe annotation is there because we filtered for it explicitly.
            val handler = handlers.computeIfAbsent(registeredListener.eventClass) { ListenerHandler() }
            
            handler.register(registeredListener)
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
        require(!listenerClass.isInstance(listener)) { "Invalid listener type: ${listener::class}" }
        
        // because the .forEach {...} closure is slower than a normal loop.
        for (func in listener::class.memberFunctions.filter { it.hasAnnotation<Subscribe>() }) {
            val registeredListener = RegisteredListener(this, listener, func, func.findAnnotation()!!) // We know that
            // the @Subscribe annotation is there because we filtered for it explicitly.
            val handler = handlers.computeIfAbsent(registeredListener.eventClass) {
                SynchronizedListenerHandler<SE, L>() as ListenerHandler<E, L>
            }
            
            handler.register(registeredListener)
        }
    }
    
    /**
     * Converts the given [listener] into a [registered-listener][RegisteredListener] and then sends it to the
     * appropriate handler for registration.
     *
     * @param [listener] The object to convert into a [registered-listener][RegisteredListener].
     */
    @JvmSynthetic
    operator fun plusAssign(listener: L) = register(listener)
    
    /**
     * Attempts to unregister the given [listener] from the handlers.
     *
     * If no reference of the given `listener` is found, this function will just silently fail.
     *
     * @param [listener] The listener to unregister.
     */
    @Throws(IllegalArgumentException::class)
    fun unregister(listener: L) {
        require(!listenerClass.isInstance(listener)) { "Invalid listener type: ${listener::class}" }
    
        for (func in listener::class.memberFunctions.filter { it.hasAnnotation<Subscribe>() }) {
            val registeredListener = RegisteredListener(this, listener, func, func.findAnnotation()!!) // We know that
            // the @Subscribe annotation is there because we filtered for it explicitly.
            
            if (!handlers.containsKey(registeredListener.eventClass)) continue
            
            // We know that it won't be null, because we already checked that the map contained the key.
            handlers[registeredListener.eventClass]!! -= registeredListener
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
    operator fun minusAssign(listener: L) = unregister(listener)
    
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
         * @see eventBus
         */
        @JvmStatic
        fun <E : Any, L : Any> create(eventClass: KClass<E>, listenerClass: KClass<L>): EventBus<E, L> =
            EventBus(eventClass, listenerClass)
        
        /**
         * Returns an [event-bus][EventBus] created from the classes of the given [E] and [L].
         *
         * @see eventBus
         */
        // no static here because inline functions don't really exist in java, or even the jvm.
        inline fun <reified E : Any, reified L : Any> create(): EventBus<E, L> = create(E::class, L::class)
    }
}

interface EventExecutor<E : Any, L : Any> {
    
    fun fire(listener: L, event: E)
    
    interface Factory {
        
        fun <E : Any, L : Any> create(bus: EventBus<E, L>, method: Method): EventExecutor<E, L>
        
    }
}

/**
 * Returns an [event-bus][EventBus] created from the given [eventClass] and [listenerClass].
 *
 * @see EventBus.create
 */
fun <E : Any, L : Any> eventBus(eventClass: KClass<E>, listenerClass: KClass<L>): EventBus<E, L> =
    EventBus.create(eventClass, listenerClass)

/**
 * Returns an [event-bus][EventBus] created from the classes of the given [E] and [L].
 *
 * @see EventBus.create
 */
inline fun <reified E : Any, reified L : Any> eventBus(): EventBus<E, L> = EventBus.create(E::class, L::class)

// Taken from kanon.kommons, got no interest in including that entire library just for one function.
internal inline fun <reified A : Annotation> KAnnotatedElement.hasAnnotation(): Boolean =
    this.annotations.any { it is A }