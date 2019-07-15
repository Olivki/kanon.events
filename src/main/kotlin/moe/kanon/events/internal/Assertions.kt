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

@file:Suppress("ClassName")

package moe.kanon.events.internal

import moe.kanon.events.EventBus
import moe.kanon.events.Subscribed
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.typeOf

class InvalidListenerFunctionException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {
    companion object {
        @JvmOverloads @JvmStatic fun of(
            func: KFunction<*>,
            reason: String,
            cause: Throwable? = null
        ): InvalidListenerFunctionException =
            InvalidListenerFunctionException("Function <$func> is not a valid listener function; $reason", cause)
    }
}

@DslMarker internal annotation class assertion

@assertion private inline fun requireFunc(func: KFunction<*>, condition: Boolean, lazyMsg: () -> Any) {
    if (!condition) throw InvalidListenerFunctionException.of(func, lazyMsg().toString())
}

@PublishedApi @assertion internal fun <E : Any, L : Any> EventBus<E, L>.requireValidListener(func: KFunction<*>) {
    requireFunc(func, func.javaMethod?.returnType == Void.TYPE) { "return type is not Unit/void" }
    requireFunc(func, func.visibility == KVisibility.PUBLIC || func.visibility == KVisibility.INTERNAL) {
        "visibility needs to be public or internal"
    }
    requireFunc(func, !func.isAbstract) { "is abstract" }
    requireFunc(func, func.valueParameters.size == 1) { "contains more or less than one parameter" }
    // this needs to be accessing the java 'Method' instance because the Kotlin func instance has problems with getting
    // type information regarding local objects sometimes.
    requireFunc(func, func.javaMethod!!.parameters[0].type.kotlin.isSubclassOf(this.eventClass)) {
        "event parameter is not a sub-class of event type <$eventClass>"
    }
}