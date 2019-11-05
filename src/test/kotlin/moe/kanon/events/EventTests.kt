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

package moe.kanon.events

import io.kotlintest.fail
import io.kotlintest.matchers.boolean.shouldBeFalse
import io.kotlintest.matchers.boolean.shouldBeTrue
import io.kotlintest.matchers.string.shouldContainIgnoringCase
import io.kotlintest.shouldBe
import io.kotlintest.shouldFail
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ExpectSpec
import moe.kanon.events.internal.InvalidListenerFunctionException

data class AssertionEvent(var success: Boolean) : Event()

data class PriorityEvent(var value: Int) : Event()
class EmptyEvent : Event()

class InvokeEventListenerFunctionTest : ExpectSpec({
    val bus = EventBus.default

    class EventListener {
        @Subscribed
        fun `event-handler function invocation test`(event: AssertionEvent) {
            event.success shouldBe false
            event.success = true
        }
    }

    context("registering the class <${EventListener::class}> as an event-listener") {
        expect("that the event listener function should be called") {
            val listener = EventListener()
            bus.register(listener)

            val event = AssertionEvent(success = false)

            bus.fire(event)

            event.success.shouldBeTrue()
        }
    }
})

class IsListenerTest : ExpectSpec({
    val bus = EventBus.default

    class EventListener {
        @Subscribed
        fun `is listener test`(event: EmptyEvent) {
            fail("for checking")
        }
    }

    context("checking whether or not a class is registered as a listener") {
        expect("that the function should return the correct values") {
            val listener = EventListener()

            bus.register(listener)
            bus.isListener<EventListener>().shouldBeTrue()

            // make sure that the listener event accepts the event in the first place.
            shouldFail { bus.fire(EmptyEvent()) }

            bus.unregister(listener)
            bus.isListener<EventListener>().shouldBeFalse()
        }
    }
})

class UnregisterEventListenerTest : ExpectSpec({
    val bus = EventBus.default

    class EventListener {
        @Subscribed
        fun `unregister event listener test`(event: EmptyEvent) {
            fail("event listener was not unregistered")
        }
    }

    context("attempting to unregister a class <${EventListener::class}> that's registered as an event-listener") {
        expect("that the event listener function should not be called") {
            val listener = EventListener()

            bus.register(listener)
            bus.isListener<EventListener>().shouldBeTrue()
            // make sure that the listener event accepts the event in the first place.
            shouldFail { bus.fire(EmptyEvent()) }
            bus.unregister(listener)
            bus.isListener<EventListener>().shouldBeFalse()
            bus.fire(EmptyEvent())
        }
    }
})

class EventPriorityHandlingTest : ExpectSpec({
    val bus = EventBus.default

    class EventListener {
        @Subscribed(EventPriority.LOWEST)
        fun `lowest priority level`(event: PriorityEvent) {
            event.value++ shouldBe 0
        }

        @Subscribed(EventPriority.LOW)
        fun `low priority level`(event: PriorityEvent) {
            event.value++ shouldBe 1
        }

        @Subscribed(EventPriority.NORMAL)
        fun `normal priority level`(event: PriorityEvent) {
            event.value++ shouldBe 2
        }

        @Subscribed(EventPriority.HIGH)
        fun `high priority level`(event: PriorityEvent) {
            event.value++ shouldBe 3
        }

        @Subscribed(EventPriority.HIGHEST)
        fun `highest priority level`(event: PriorityEvent) {
            event.value++ shouldBe 4
        }

        @Subscribed(EventPriority.OBSERVER)
        fun `observer priority level`(event: PriorityEvent) {
            event.value++ shouldBe 5
        }
    }

    context("firing an event to a class <${EventListener::class}> that has multiple event listener functions with different priorities") {
        expect("that they should all fire in the correct order") {
            val listener = EventListener()
            bus.register(listener)

            val event = PriorityEvent(value = 0)

            bus.fire(event)

            event.value shouldBe 6
        }
    }
})

class EventExceptionPassThroughTests : ExpectSpec({
    class CustomException : Exception("This is a custom exception.")

    val bus = EventBus.default

    class EventListener {
        @Subscribed
        fun `event handler function that throws a CustomException`(event: EmptyEvent) {
            throw CustomException()
        }
    }

    context("firing an EmptyEvent to an event-listener that has a function that will throw a CustomException") {
        expect("that the exception should be passed up where the 'fire' function is invoked") {
            val listener = EventListener()
            bus.register(listener)
            val exception = shouldThrow<CustomException> { bus.fire(EmptyEvent()) }
            exception.message shouldBe "This is a custom exception."
        }
    }
})

class InvalidEventListenerFunctionTests : ExpectSpec({
    val bus = EventBus.default

    // only one argument is supported per event handler function, this is intentional design as to make sure the user
    // doesn't start making functions that are deeply nested and ugly looking.
    // the main problem with multiple arguments is that then you can't ever be sure of *what* has called your function
    // without explicitly checking for some variable that differentiates the events from each other, which quickly
    // devolves into cluttered and nested code, and it also makes it a lot harder to write function names that are
    // self-explanatory.
    class EventListenerArgumentLength {
        @Subscribed
        fun `no arguments function`() {
            fail("an event listener function with no arguments is invalid and should never be invoked by the system")
        }

        @Subscribed
        fun `two event arguments function`(`event one`: EmptyEvent, `event two`: EmptyEvent) {
            fail("an event listener function with more than one event argument is invalid and should never be invoked by the system")
        }
    }

    context("registering a listener that has event listener functions that all have the wrong argument lengths") {
        expect("that the registration should fail with an 'InvalidListenerFunctionException'") {
            val listener = EventListenerArgumentLength()
            val exception = shouldThrow<InvalidListenerFunctionException> { bus.register(listener) }
            exception.message shouldContainIgnoringCase "contains more or less than one parameter"
        }
    }

    class EventListenerParamType {
        @Subscribed
        fun `invalid argument type function`(`invalid type`: String) {
            fail("an event listener function is only allowed to have an argument of a type that's the same as the one defined when creating the event-bus")
        }
    }

    context("registering a listener that has event listener function that has the wrong event type parameter") {
        expect("that the registration should fail with an 'InvalidListenerFunctionException'") {
            val listener = EventListenerParamType()
            val exception = shouldThrow<InvalidListenerFunctionException> { bus.register(listener) }
            exception.message shouldContainIgnoringCase "event parameter is not a sub-class of event type"
        }
    }

    class EventListenerVisibility {
        @Subscribed
        private fun `private function test`(event: EmptyEvent) {
            fail("a private function should not be able to be a event listener")
        }

        @Subscribed
        protected fun `protected function test`(event: EmptyEvent) {
            fail("a protected function should not be able to be a event listener")
        }
    }

    context("attempting to register a class with event listener functions with visibility lower than public") {
        expect("that the registration should fail with an 'InvalidListenerFunctionException'") {
            val listener = EventListenerVisibility()
            shouldThrow<InvalidListenerFunctionException> { bus.register(listener) }
        }
    }
})

class InvocationStrategyTests : ExpectSpec({
    for (strategy in EventBus.InvocationStrategy.values()) {
        context("creating an event-bus using the '${strategy.name}' invocation strategy") {
            expect("that it should be able to invoke functions") {
                val bus = EventBus<Event, Any>(strategy)

                class EventListener {
                    @Subscribed
                    fun `receive event func`(event: EmptyEvent) {
                        fail("event was received")
                    }
                }
                val listener = EventListener()

                bus.register(listener)

                shouldFail { bus.fire(EmptyEvent()) }
            }
        }
    }
})

class LocalListenerTests : ExpectSpec({
    val bus = EventBus.default

    context("creating a local object tied to a variable to act as a listener") {
        val listener = object {
            @Subscribed
            fun `subscribed function`(event: EmptyEvent) {
                fail("function invoked")
            }
        }

        bus.register(listener)

        expect("that the object will receive invocations") {
            shouldFail { bus.fire(EmptyEvent()) }
        }

        expect("that the object can be unregistered") {
            (listener in bus).shouldBeTrue()
            bus.unregister(listener)
            (listener in bus).shouldBeFalse()
        }
    }

    context("creating a local object directly in the register invocation to act as a listener") {
        expect("that the object will receive invocations") {
            bus.register(object {
                @Subscribed
                fun `subscribed function`(event: EmptyEvent) {
                    fail("function invoked")
                }
            })
            shouldFail { bus.fire(EmptyEvent()) }
        }
    }
})

interface ConstrainedListener

class EventListenerTypeTest : ExpectSpec({
    val bus = EventBus<Event, ConstrainedListener>()

    class ValidListener : ConstrainedListener {
        @Subscribed
        fun `i should be valid`(event: EmptyEvent) {
            fail("i've been invoked")
        }
    }

    context("registering a listener that inherits from the specified listener super-class") {
        expect("that the listener should be properly registered") {
            bus.register(ValidListener())

            shouldFail { bus.fire(EmptyEvent()) }
        }
    }
})