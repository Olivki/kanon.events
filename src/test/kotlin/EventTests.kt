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

package moe.kanon.tests

import io.kotlintest.fail
import io.kotlintest.matchers.boolean.shouldBeTrue
import io.kotlintest.matchers.string.shouldContainIgnoringCase
import io.kotlintest.shouldBe
import io.kotlintest.shouldFail
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ExpectSpec
import moe.kanon.events.BasicEvent
import moe.kanon.events.EventBus
import moe.kanon.events.EventPriority
import moe.kanon.events.Subscribe

// basic events for testing purposes
data class AssertionEvent(var success: Boolean) : BasicEvent

data class PriorityEvent(var value: Int) : BasicEvent
class EmptyEvent : BasicEvent

class InvokeEventHandlerFunctionTest : ExpectSpec({
    val bus = EventBus.default
    
    class EventListener {
        @Subscribe
        fun `event-handler function invocation test`(event: AssertionEvent) {
            event.success shouldBe false
            event.success = true
        }
    }
    
    context("registering the class <${EventListener::class}> as an event-listener") {
        expect("that the event handler function should be called") {
            val listener = EventListener()
            bus.register(listener)
            
            val event = AssertionEvent(success = false)
            
            bus.fire(event)
            
            event.success.shouldBeTrue()
        }
    }
})

class UnregisterEventListenerTest : ExpectSpec({
    val bus = EventBus.default
    
    class EventListener {
        @Subscribe
        fun `unregister event listener test`(event: EmptyEvent) {
            fail("event listener was not unregistered")
        }
    }
    
    context("attempting to unregister a class <${EventListener::class}> that's registered as an event-listener") {
        expect("that the event listener function should not be called") {
            val listener = EventListener()
            
            bus.register(listener)
            // make sure that the listener event accepts the event in the first place.
            shouldFail { bus.fire(EmptyEvent()) }
            bus.unregister(listener)
            bus.fire(EmptyEvent())
        }
    }
})

class EventPriorityHandlingTest : ExpectSpec({
    val bus = EventBus.default
    
    class EventListener {
        @Subscribe(priority = EventPriority.LOWEST)
        fun `lowest priority level`(event: PriorityEvent) {
            event.value++ shouldBe 0
        }
        
        @Subscribe(priority = EventPriority.LOW)
        fun `low priority level`(event: PriorityEvent) {
            event.value++ shouldBe 1
        }
        
        @Subscribe(priority = EventPriority.NORMAL)
        fun `normal priority level`(event: PriorityEvent) {
            event.value++ shouldBe 2
        }
        
        @Subscribe(priority = EventPriority.HIGH)
        fun `high priority level`(event: PriorityEvent) {
            event.value++ shouldBe 3
        }
        
        @Subscribe(priority = EventPriority.HIGHEST)
        fun `highest priority level`(event: PriorityEvent) {
            event.value++ shouldBe 4
        }
        
        @Subscribe(priority = EventPriority.OBSERVER)
        fun `observer priority level`(event: PriorityEvent) {
            event.value++ shouldBe 5
        }
    }
    
    context("firing an event to a class <${EventListener::class}> that has multiple event handler functions with different priorities") {
        expect("that they should all fire in the correct order") {
            val listener = EventListener()
            bus.register(listener)
            
            val event = PriorityEvent(value = 0)
            
            bus.fire(event)
            
            event.value shouldBe 6
        }
    }
})

class EventHandlerVisibilityTests : ExpectSpec({
    val bus = EventBus.default
    
    class EventListener {
        @Subscribe
        private fun `private function test`(event: EmptyEvent) {
            fail("a private function should not be able to be a event handler")
        }
        
        @Subscribe
        protected fun `protected function test`(event: EmptyEvent) {
            fail("a protected function should not be able to be a event handler")
        }
    }
    
    context("attempting to invoke event handler functions that have a lower visibility than public on class <${EventListener::class}>") {
        expect("that it should fail with a 'IllegalAccessError'") {
            val listener = EventListener()
            bus.register(listener)
            
            shouldThrow<IllegalAccessError> { bus.fire(EmptyEvent()) }
        }
    }
})

class EventExceptionPassThroughTests : ExpectSpec({
    class CustomException : Exception("This is a custom exception.")
    
    val bus = EventBus.default
    
    class EventListener {
        @Subscribe
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

class InvalidEventHandlerFunctionTests : ExpectSpec({
    val bus = EventBus.default
    
    // only one argument is supported per event handler function, this is intentional design as to make sure the user
    // doesn't start making functions that are deeply nested and ugly looking.
    // the main problem with multiple arguments is that then you can't ever be sure of *what* has called your function
    // without explicitly checking for some variable that differentiates the events from each other, which quickly
    // devolves into cluttered and nested code, and it also makes it a lot harder to write function names that are
    // self-explanatory.
    class EventListener {
        @Subscribe
        fun `no arguments function`() {
            fail("an event handler function with no arguments is invalid and should never be invoked by the system")
        }
        
        @Subscribe
        fun `two event arguments function`(`event one`: EmptyEvent, `event two`: EmptyEvent) {
            fail("an event handler function with more than one event argument is invalid and should never be invoked by the system")
        }
        
        @Subscribe
        fun `invalid argument type function`(`invalid type`: String) {
            fail("an event handler function is only allowed to have an argument of a type that's the same as the one defined when creating the event-bus")
        }
    }
    
    context("registering a listener that has event handler functions that all have faulty arguments") {
        expect("that all the functions should fail with a 'IllegalArgumentException'") {
            val listener = EventListener()
            val exception = shouldThrow<IllegalArgumentException> { bus.register(listener) }
            exception.message shouldContainIgnoringCase "does not contain a valid event parameter"
        }
    }
})