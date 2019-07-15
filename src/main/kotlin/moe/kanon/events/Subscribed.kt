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

import moe.kanon.events.internal.InvalidListenerFunctionException

/**
 * Marks a `function` as an event subscriber, allowing it to receive an event if registered as a listener on an
 * event-bus.
 *
 * The type of the event will be indicated by the functions first *(and only)* parameter. If this annotation is
 * applied to a function that has `0`, or more than `1` parameters, it will not be registered as a valid event
 * subscriber, and a [InvalidListenerFunctionException] will be thrown upon registration.
 */
// 'Subscribe' -> 'Subscribed'
@Target(AnnotationTarget.FUNCTION)
annotation class Subscribed(val priority: EventPriority = EventPriority.NORMAL)

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