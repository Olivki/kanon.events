/*
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
 */

@file:Suppress("UNCHECKED_CAST")

package moe.kanon.events

import moe.kanon.events.internal.TypeFactory
import moe.kanon.events.internal.clz
import moe.kanon.events.internal.hasAnnotation
import moe.kanon.events.internal.requireValidListener
import mu.KLogger
import mu.KotlinLogging
import net.bytebuddy.ByteBuddy
import net.bytebuddy.NamingStrategy
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.implementation.MethodCall
import net.bytebuddy.matcher.ElementMatchers.named
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod
import moe.kanon.events.internal.InvalidListenerFunctionException

/**
 * An implementation of the [publish-subscribe pattern](https://en.wikipedia.org/wiki/Publish%E2%80%93subscribe_pattern).
 *
 * This event-bus is concurrent, and can therefore correctly handle multiple threads accessing it at the same time.
 *
 * @property [eventClass] The super-class that all events sent through `this` bus inherits from.
 * @property [listenerClass] The super-class that all listeners registered to `this` bus inherits from.
 * @property [strategy] The [InvocationStrategy] that should be used for invoking the listener functions registered to
 * `this` bus.
 * @property [name] The name that the [logger] should use.
 */
@Suppress("DataClassPrivateConstructor")
data class EventBus<E : Any, L : Any> private constructor(
    val eventClass: KClass<out E>,
    val listenerClass: KClass<out L>,
    val strategy: InvocationStrategy = InvocationStrategy.ASM,
    private val name: String = "EventBus"
) {
    companion object {
        /**
         * Returns a default implementation of an event-bus, where all events need to be sub-classes of [Event]
         * and the listeners can be anything as long as they inherit from [Any], and the [strategy] used is the
         * [ASM][InvocationStrategy.ASM] one.
         */
        val default: EventBus<Event, Any>
            @JvmStatic get() = EventBus(InvocationStrategy.ASM)

        /**
         * Returns a new [EventBus] using the given arguments.
         *
         * @param [eventClass] the super-class that all events sent through the bus inherit from
         * @param [listenerClass] the super-class that all listeners registered to the bus inherit from
         * @param [strategy] the [InvocationStrategy] that the bus will use for invoking the listener functions
         * registered to it
         * @param [name] the name that the underlying [logger][EventBus.logger] uses
         */
        @JvmOverloads @JvmStatic fun <E : Any, L : Any> newInstance(
            eventClass: Class<E>,
            listenerClass: Class<L>,
            strategy: InvocationStrategy = InvocationStrategy.ASM,
            name: String = "EventBus"
        ): EventBus<E, L> = EventBus(eventClass.kotlin, listenerClass.kotlin, strategy, name)

        /**
         * Returns a new [EventBus] using the given arguments.
         *
         * @param [eventClass] the super-class that all events sent through the bus inherit from
         * @param [listenerClass] the super-class that all listeners registered to the bus inherit from
         * @param [strategy] the [InvocationStrategy] that the bus will use for invoking the listener functions
         * registered to it
         * @param [name] the name that the underlying [logger][EventBus.logger] uses
         */
        @JvmName("newInstance")
        operator fun <E : Any, L : Any> invoke(
            eventClass: KClass<E>,
            listenerClass: KClass<L>,
            strategy: InvocationStrategy = InvocationStrategy.ASM,
            name: String = "EventBus"
        ): EventBus<E, L> = EventBus(eventClass, listenerClass, strategy, name)

        /**
         * Returns a new [EventBus] using the given arguments.
         *
         * @param [EventType] the super-type that all events sent through the bus inherits from
         * @param [ListenerType] the super-type that all listeners registered to the bus inherits from
         * @param [strategy] the [InvocationStrategy] that the bus will use for invoking the listener functions
         * registered to it
         * @param [name] the name that the underlying [logger][EventBus.logger] uses
         */
        @JvmSynthetic inline operator fun <reified EventType : Any, reified ListenerType : Any> invoke(
            strategy: InvocationStrategy = InvocationStrategy.ASM,
            name: String = "EventBus"
        ): EventBus<EventType, ListenerType> = invoke(EventType::class, ListenerType::class, strategy, name)
    }

    private val logger: KLogger = KotlinLogging.logger(name)

    private val repos: MutableMap<KClass<out E>, ListenerRepository<E, L>> = ConcurrentHashMap()

    /**
     * Registers the given [listener] to `this` event-bus.
     *
     * When registering a listener the system will look through all of the functions contained inside of the given
     * [listener] for any functions annotated with [Subscribed], this may cause a slow-down in performance as it is
     * done via reflection.
     *
     * If the system encounters any malformed listener functions, then a [InvalidListenerFunctionException] will be
     * thrown, a listener function is considered malformed if it does *not* conform to the following standards:
     * - It has *more* or *less* than 1 parameter
     * - It returns a value that's *not* [Unit] or [void][Void]
     * - Its visibility is not `public` or `internal`
     * - It is `abstract`
     * - The event parameter accepts an event that's not a sub-class of the [eventClass] defined in `this` bus
     *
     * @throws [InvalidListenerFunctionException] if the system encounters a listener function that is considered
     * malformed.
     */
    fun register(listener: L) {
        require(listenerClass.isInstance(listener)) { "<${listener::class}> is not a sub-class of <$listenerClass>" }

        if (listener in this) return logger.debug { "<${listener::class}> is already registered" }

        val functions = listener::class.declaredMemberFunctions.asSequence()
            .filterIsInstance<KFunction<Unit>>()
            .filter { it.hasAnnotation<Subscribed>() }

        if (functions.none()) return logger.debug { "<${listener::class}> has no listener functions" }

        for (func in functions) {
            val wrapper = ListenerWrapper(
                this,
                func,
                listener,
                func.findAnnotation<Subscribed>()!!.priority.ordinal,
                strategy.create(this, func)
            )
            repos.computeIfAbsent(wrapper.eventClass) { ListenerRepository() }.register(wrapper)
        }

        logger.debug { "Registered event listener <${listener::class}>" }
    }

    /**
     * Unregisters the given [listener] from `this` event-bus.
     *
     * If no reference of the given `listener` is found, this function will just silently fail.
     */
    fun unregister(listener: L) {
        require(listenerClass.isInstance(listener)) { "<${listener::class}> is not a sub-class of <$listenerClass>" }

        if (listener !in this) return logger.debug { "<${listener::class}> is not a registered listener" }

        listener::class.declaredMemberFunctions.asSequence()
            .filterIsInstance<KFunction<Unit>>()
            .filter { it.hasAnnotation<Subscribed>() }
            .map { func ->
                ListenerWrapper(
                    this,
                    func,
                    listener,
                    func.findAnnotation<Subscribed>()!!.priority.ordinal,
                    strategy.create(this, func)
                )
            }
            .filter { repos.containsKey(it.eventClass) }
            .forEach { repos.getValue(it.eventClass).unregister(it) }

        logger.debug { "Unregistered event listener <${listener::class}>" }
    }

    /**
     * Fires the given [event] to any registered listeners.
     *
     * Note that if no listeners are found, and [eventClass] is of type [Event] then a [DeadEvent] will be sent out,
     * wrapping around the given [event].
     *
     * @return the given [event]
     */
    fun <R : E> fire(event: R): R = event.also {
        require(eventClass.isInstance(event)) { "<${event::class}> is not a sub-class of <$eventClass>" }
        val eventClass = event::class
        // 'repos[event::class]' returning 'null' means that there's no listener for the given event
        repos[eventClass]?.notifyAll(event)
    }

    /**
     * Returns whether or not the given [listener] is a registered listener in `this` event-bus.
     */
    @JvmName("isListener")
    operator fun contains(listener: L): Boolean = repos.values.any { it.isListener(listener) }

    /**
     * Returns whether or not the given [listenerClass] is a registered listener in `this` event-bus.
     */
    @JvmName("isListener")
    operator fun contains(listenerClass: KClass<out L>): Boolean = repos.values.any { it.isListener(listenerClass) }

    /**
     * Returns whether or not the given [listener][Listener] is a registered listener in `this` event-bus.
     */
    @JvmSynthetic inline fun <reified Listener : L> isListener(): Boolean = Listener::class in this

    /**
     * Represents different ways that an event-bus can go about executing the registered listener functions.
     *
     * The strategies are ordered from *fastest* to *slowest* in invocation speed.
     */
    enum class InvocationStrategy {
        /**
         * Represents a strategy that utilizes ASM to create dynamic classes that invoke each registered listener
         * function.
         */
        ASM {
            private val cache = ConcurrentHashMap<KFunction<*>, KClass<out EventExecutor<*, *>>>()

            private fun generateExecutor(func: KFunction<*>): KClass<out EventExecutor<*, *>> =
                ByteBuddy().with(NamingStrategy.SuffixingRandom("Generated"))
                    .subclass(
                        TypeFactory.parameterizedType(
                            TypeDescription.ForLoadedType.of(EventExecutor::class.java),
                            TypeFactory.rawType(func.valueParameters[0].clz.java).build(),
                            TypeFactory.rawType(func.javaMethod!!.declaringClass).build()
                        ).build()
                    )
                    .method(named("fire"))
                    .intercept(MethodCall.invoke(func.javaMethod).onArgument(0).withArgument(1))
                    .make()
                    .load(this.javaClass.classLoader, ClassLoadingStrategy.Default.WRAPPER)
                    .loaded.kotlin as KClass<out EventExecutor<*, *>>

            /**
             * Returns a new [EventExecutor] that has been dynamically created via the use of ASM.
             *
             * Note that the created classes will be cached under the given [func], which means that the base class
             * will always be the same, however, new instances will be created on each call of this function. This means
             * that even if the same parameters are passed to this function, there is no guarantee that the resulting
             * instances will be considered the same.
             */
            @JvmSynthetic override fun <E : Any, L : Any> create(
                bus: EventBus<E, L>,
                func: KFunction<Unit>
            ): EventExecutor<E, L> {
                bus.requireValidListener(func)
                val executorClass = cache.computeIfAbsent(func) { generateExecutor(it) }
                return try {
                    executorClass.createInstance() as EventExecutor<E, L>
                } catch (e: InstantiationException) {
                    throw RuntimeException("Unable to initialize <$executorClass>", e)
                } catch (e: IllegalAccessException) {
                    throw RuntimeException("Unable to initialize <$executorClass>", e)
                }
            }
        },
        /**
         * Represents a strategy where simple reflection is utilized to invoke the registered listener functions.
         */
        REFLECTION {
            @JvmSynthetic override fun <E : Any, L : Any> create(
                bus: EventBus<E, L>,
                func: KFunction<Unit>
            ): EventExecutor<E, L> = object : EventExecutor<E, L> {
                override fun fire(listener: L, event: E) {
                    // if an exception is thrown when invoking things reflectively, a 'InvocationTargetException' will
                    // be thrown carrying the actual exception, so what we're doing here is that we're just unwrapping
                    // the target-exception and throwing the exception that it's carrying directly instead.
                    try {
                        func.call(listener, event)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        },
        /**
         * Represents a strategy where the [MethodHandles] class is utilized to invoke the registered listener
         * functions.
         */
        METHOD_HANDLE {
            @JvmSynthetic override fun <E : Any, L : Any> create(
                bus: EventBus<E, L>,
                func: KFunction<Unit>
            ): EventExecutor<E, L> = object : EventExecutor<E, L> {
                private val handle: MethodHandle

                init {
                    val caller = MethodHandles.lookup()
                    var handle = caller.unreflect(func.javaMethod)
                    if (Modifier.isStatic(func.javaMethod!!.modifiers)) handle =
                        MethodHandles.dropArguments(handle, 0, Object::class.java)
                    this.handle = handle
                }

                override fun fire(listener: L, event: E) {
                    handle.invokeWithArguments(listener, event)
                }
            }
        };

        /**
         * Returns a new [EventExecutor] that will invoke the specified [func].
         *
         * How the event-executor varies from implementation to implementation.
         */
        @JvmSynthetic internal abstract fun <E : Any, L : Any> create(
            bus: EventBus<E, L>,
            func: KFunction<Unit>
        ): EventExecutor<E, L>
    }
}

/**
 * Registers the given [listener] to `this` event-bus.
 *
 * When registering a listener the system will look through all of the functions contained inside of the given
 * [listener] for any functions annotated with [Subscribed], this may cause a slow-down in performance as it is
 * done via reflection.
 *
 * If the system encounters any malformed listener functions, then a [InvalidListenerFunctionException] will be
 * thrown, a listener function is considered malformed if it does *not* conform to the following standards:
 * - It has *more* or *less* than 1 parameter
 * - It returns a value that's *not* [Unit] or [void][Void]
 * - Its visibility is not `public` or `internal`
 * - It is `abstract`
 * - The event parameter accepts an event that's not a sub-class of the [eventClass][EventBus.eventClass] defined in
 * `this` bus
 *
 * @throws [InvalidListenerFunctionException] if the system encounters a listener function that is considered
 * malformed.
 */
@JvmName("register")
operator fun <E : Any, L : Any> EventBus<E, L>.plusAssign(listener: L) = register(listener)

/**
 * Unregisters the given [listener] from `this` event-bus.
 */
@JvmName("unregister")
operator fun <E : Any, L : Any> EventBus<E, L>.minusAssign(listener: L) = unregister(listener)

/**
 * Fires the given [event] to any registered listeners.
 *
 * Note that if no listeners are found, and [eventClass] is of type [Event] then a [DeadEvent] will be sent out,
 * wrapping around the given [event].
 */
@JvmName("fire")
operator fun <E : Any, L : Any> EventBus<E, L>.timesAssign(event: E) {
    fire(event)
}

private class ListenerRepository<E : Any, L : Any> {
    private val listeners: MutableSet<ListenerWrapper<E, L>> = ConcurrentHashMap.newKeySet()
    private val cachedListeners: MutableList<ListenerWrapper<E, L>> = ArrayList()

    /**
     * Returns whether or not the [listeners] set has been updated since we last cached it.
     */
    private var hasChanged: Boolean = false

    /**
     * Notifies all the registered listeners that a new event has been sent.
     *
     * The listeners will be notified in the order of their [priority][ListenerWrapper.priority].
     */
    fun notifyAll(event: E) {
        // we need the registered listeners to actually be sorted by their priority
        val sortedListeners: List<ListenerWrapper<E, L>> = when {
            // if there's been any change to the listeners set, we clear out the old one, and then populate it with
            // a newly sorted listeners set.
            hasChanged -> synchronized(this) {
                cachedListeners.apply {
                    clear()
                    addAll(listeners.sorted())
                    hasChanged = false
                }
            }
            // if there's been no change, then just use the already cached one
            else -> cachedListeners
        }

        for (listener in sortedListeners) listener.fire(event)
    }

    /**
     * Registers the given [listener] to `this` repository and updates the cache.
     */
    fun register(listener: ListenerWrapper<E, L>) {
        listeners += listener
        hasChanged = true
    }

    /**
     * Unregisters the given [listener] from `this` repository and updates the cache.
     */
    fun unregister(listener: ListenerWrapper<E, L>) {
        listeners -= listener
        hasChanged = true
    }

    /**
     * Returns whether or not the given [listener] is a registered listener in `this` repository.
     */
    fun isListener(listener: L): Boolean = listeners.any { it.listener::class == listener::class }

    /**
     * Returns whether or not the given [listener] is a registered listener in `this` repository.
     */
    fun isListener(listener: KClass<out L>): Boolean = listeners.any { it.listener::class == listener }
}

/**
 * A wrapper around a listener function that holds a variety of data.
 *
 * @property [bus] The event-bus that this listener belongs to.
 * @property [func] The actual underlying listener function which this class wraps around.
 * @property [listener] The listener instance in which [func] is from.
 * @property [priority] The [EventPriority] of `this` listener function.
 * @property [eventClass] The class of the event as specified by the first parameter of [func].
 * @property [executor] The [EventExecutor] that will be used to fire any events passed to `this` wrapper.
 */
private class ListenerWrapper<E : Any, L : Any>(
    val bus: EventBus<E, L>,
    val func: KFunction<Unit>,
    val listener: L,
    val priority: Int,
    val executor: EventExecutor<E, L>,
    val eventClass: KClass<out E> = func.valueParameters[0].clz as KClass<out E>
) : Comparable<ListenerWrapper<E, L>> {
    /**
     * Passes the specified [event] along to the set [executor] of `this` listener.
     */
    fun fire(event: E) = executor.fire(listener, event)

    override fun compareTo(other: ListenerWrapper<E, L>): Int = priority.compareTo(other.priority)

    override fun hashCode(): Int = func.hashCode() xor listener.hashCode()

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is ListenerWrapper<*, *> -> false
        bus != other.bus -> false
        listener != other.listener -> false
        func != other.func -> false
        priority != other.priority -> false
        else -> true
    }

    override fun toString(): String =
        "${listener::class.java.name}::${if (' ' in func.name) "`${func.name}`" else func.name}()"
}