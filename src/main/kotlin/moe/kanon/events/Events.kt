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

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A basic implementation of an event that can be used as the base for event classes made for usage in [EventBus].
 *
 * Note that while this class is marked as `abstract`, it holds no actual abstract members as it is only marked as
 * `abstract` to avoid any direct instantiations of it being created. Some very basic implementations of [equals],
 * [hashCode] and [toString] are also provided.
 *
 * @see [CancellableEvent]
 */
abstract class Event {
    /**
     * Returns the [current time millis](https://currentmillis.com/) that was captured the moment `this` event was
     * created.
     */
    val timeMillis: Long = System.currentTimeMillis()

    /**
     * Lazily returns a [LocalDateTime] instance based on the captured [timeMillis].
     */
    val creationDate: LocalDateTime by lazy {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMillis), ZoneId.systemDefault())
    }

    /**
     * Lazily looks up the [simple class name][Class.getSimpleName] of `this` event and then stores it.
     *
     * This is so that [toString] will return an accurate class name if a class inherits from this without providing
     * its own implementation of [toString].
     */
    protected val simpleClassName: String by lazy { javaClass.simpleName }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is Event -> false
        timeMillis != other.timeMillis -> false
        else -> true
    }

    override fun hashCode(): Int = timeMillis.hashCode()

    override fun toString(): String = "$simpleClassName(timeMillis=$timeMillis)"
}

/**
 * A basic implementation of a an event that can be cancelled.
 *
 * Some very basic implementations of [equals], [hashCode] and [toString] have been provided.
 *
 * @see [Event]
 */
abstract class CancellableEvent : Event() {
    /**
     * Returns whether or not `this` event has been cancelled.
     */
    var isCancelled: Boolean = false

    /**
     * Cancels `this` event.
     *
     * What an event being cancelled entails is highly implementation specific and is therefore not documented.
     */
    fun cancel() {
        isCancelled = true
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is CancellableEvent -> false
        !super.equals(other) -> false
        isCancelled != other.isCancelled -> false
        else -> true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + isCancelled.hashCode()
        return result
    }

    override fun toString(): String = "$simpleClassName(timeMillis=$timeMillis, isCancelled=$isCancelled)"
}