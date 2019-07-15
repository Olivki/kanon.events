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

package moe.kanon.events.internal

import net.bytebuddy.description.type.TypeDefinition
import net.bytebuddy.description.type.TypeDescription
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/*
 * At the date of writing this (2019/07/11) Kotlin *does* have a 'typeOf' function that was implemented in v1.3.40
 * albeit in a experimental state. The purpose of that function is to allow one to get the KType of a reified parameter
 * *(although why they made it a function rather than use the reserved keyword 'typeof' is somewhat weird)* there is
 * one major glaring problem with its implementation as of now, which is that KTypes created using the 'createType'
 * function *(which is how 'typeOf' creates its instances)* can *not* be converted to the Java equivalent 'Type',
 * which severely limits its usability, as most of the time when one needs a KType instance it's to convert it to a
 * Type instance for usage with APIs like GSON, Jackson and in our case ByteBuddy. This means that we still need to
 * implement a very basic TypeCapture system as shown below.
 */

typealias TypeFactory = TypeDescription.Generic.Builder
typealias GenericType = TypeDescription.Generic

@PublishedApi internal abstract class TypeCapture<T> protected constructor() {
    private val runtimeType: Type

    init {
        val type = javaClass.genericSuperclass
        require(type is ParameterizedType) { "Type <$type> isn't parameterized" }
        runtimeType = type.actualTypeArguments[0]
    }

    val type: Type get() = runtimeType
    val genericType: GenericType get() = TypeDefinition.Sort.describe(runtimeType)
}

@PublishedApi internal inline fun <reified T> captureType(): Type = object : TypeCapture<T>() {}.type
@PublishedApi internal inline fun <reified T> captureGenericType(): GenericType =
    object : TypeCapture<T>() {}.genericType