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

package moe.kanon.events.apt

import com.google.auto.service.AutoService
import java.lang.IllegalArgumentException
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedAnnotationTypes
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement

@Suppress("NOTHING_TO_INLINE")
@AutoService(Processor::class)
@SupportedAnnotationTypes("moe.kanon.events.Subscribed")
class FunctionProcessor : AbstractProcessor() {
    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val annotationType = annotations.first()
        for (element in roundEnv.getElementsAnnotatedWith(annotationType)) {
            element.apply {
                when (this) {
                    is ExecutableElement -> when {
                        Modifier.ABSTRACT in modifiers -> error(this, "can not be abstract")
                        Modifier.PRIVATE in modifiers || Modifier.PROTECTED in modifiers -> {
                            error(this, "visibility must be public")
                        }
                        this.parameters.isEmpty() -> error(this, "missing event parameter")
                        this.parameters.size > 1 -> error(this, "only one parameter is allowed")
                        this.isVarArgs -> error(this, "varargs is not allowed")
                    }
                    else -> wrongElementError(this)
                }
            }
        }
        return true
    }

    private inline fun error(element: Element, reason: String): Nothing =
        throw IllegalArgumentException("<$element> is an invalid listener function; $reason")

    private inline fun wrongElementError(element: Element): Nothing =
        throw IllegalArgumentException("Annotated element <$element> is not a function")
}