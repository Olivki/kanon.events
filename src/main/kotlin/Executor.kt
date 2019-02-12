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

package moe.kanon.events

import jdk.internal.org.objectweb.asm.ClassWriter
import jdk.internal.org.objectweb.asm.Type
import jdk.internal.org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.Opcodes.*
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.jvm.kotlinFunction

@Suppress("UNCHECKED_CAST")
object AsmEventExecutorFactory : EventExecutor.Factory {
    
    private val cache = ConcurrentHashMap<Method, Class<out EventExecutor<*, *>>>()
    
    override fun <E : Any, L : Any> create(bus: EventBus<E, L>, method: Method): EventExecutor<E, L> {
        method.kotlinFunction?.isValidListenerFunction(bus)
        
        val executorClass = cache.computeIfAbsent(method) { generateExecutor(it) }
        try {
            return executorClass.newInstance() as EventExecutor<E, L>
        } catch (e: InstantiationException) {
            throw RuntimeException("Unable to initialize $executorClass", e)
        } catch (e: IllegalAccessException) {
            throw RuntimeException("Unable to initialize $executorClass", e)
        }
        
    }
    
    private fun generateExecutor(method: Method): Class<out EventExecutor<*, *>> { // DOESN'T CACHE!
        val name = generateName()
        val data = generateEventExecutor(method, name)
        val listenerLoader = method.declaringClass.classLoader
        val loader = CustomClassLoader.getLoader(listenerLoader)
        return loader.defineClass(name, data).asSubclass(EventExecutor::class.java)
    }
    
    private fun generateEventExecutor(m: Method, name: String): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        
        writer.visit(
            V1_8, ACC_PUBLIC, name, null, "java/lang/Object", arrayOf(
                Type.getInternalName(EventExecutor::class.java)
            )
        )
        
        // Generate constructor
        var methodGenerator = GeneratorAdapter(
            writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null),
            ACC_PUBLIC, "<init>", "()V"
        )
        methodGenerator.loadThis()
        methodGenerator.visitMethodInsn(
            INVOKESPECIAL, "java/lang/Object", "<init>", "()V",
            false
        )
        
        // Invoke the super class (Object) constructor
        methodGenerator.returnValue()
        methodGenerator.endMethod()
        
        // Generate the execute method
        methodGenerator = GeneratorAdapter(
            writer.visitMethod(
                ACC_PUBLIC, "fire",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null
            ), ACC_PUBLIC, "fire", "(Ljava/lang/Object;Ljava/lang/Object;)V"
        )
        
        methodGenerator.loadArg(0)
        methodGenerator.checkCast(Type.getType(m.declaringClass))
        methodGenerator.loadArg(1)
        methodGenerator.checkCast(Type.getType(m.parameterTypes[0]))
        methodGenerator.visitMethodInsn(
            INVOKEVIRTUAL,
            Type.getInternalName(m.declaringClass),
            m.name,
            Type.getMethodDescriptor(m),
            m.declaringClass.isInterface
        )
        
        if (m.returnType != Void.TYPE) methodGenerator.pop()
        
        methodGenerator.returnValue()
        methodGenerator.endMethod()
        writer.visitEnd()
        
        return writer.toByteArray()
    }
    
    private val GENERATED_EXECUTOR_BASE_NAME: String
    
    init {
        val className = AsmEventExecutorFactory::class.java.name.replace('.', '/')
        val packageName = className.substring(0, className.lastIndexOf('/'))
        GENERATED_EXECUTOR_BASE_NAME = "$packageName/GeneratedEventExecutor"
    }
    
    private val NEXT_ID = AtomicInteger(1)
    
    private fun generateName(): String = "$GENERATED_EXECUTOR_BASE_NAME${NEXT_ID.getAndIncrement()}"
}

@Suppress("LocalVariableName")
private class CustomClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    
    fun defineClass(name: String, data: ByteArray): Class<*> {
        val _name = name.replace('/', '.')
        
        synchronized(getClassLoadingLock(_name)) {
            if (hasClass(_name)) throw IllegalStateException("$_name is already defined")
            val clz = this.define(_name, Objects.requireNonNull(data, "Null data"))
            if (clz.name != _name) throw IllegalArgumentException("class name ${clz.name} != requested name $_name")
            return clz
        }
    }
    
    private fun define(name: String, data: ByteArray): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            if (hasClass(name)) throw IllegalStateException("Already contains class: $name")
            
            val clz: Class<*> = try {
                defineClass(name, data, 0, data.size)
            } catch (e: ClassFormatError) {
                throw IllegalArgumentException("Illegal class data", e)
            }
            
            resolveClass(clz)
            return clz
        }
    }
    
    public override fun getClassLoadingLock(name: String): Any = super.getClassLoadingLock(name)
    
    fun hasClass(name: String): Boolean {
        synchronized(getClassLoadingLock(name)) {
            return try {
                Class.forName(name)
                true
            } catch (e: ClassNotFoundException) {
                false
            }
            
        }
    }
    
    companion object {
        
        private val loaders = ConcurrentHashMap<ClassLoader, CustomClassLoader>()
        
        fun getLoader(parent: ClassLoader): CustomClassLoader =
            loaders.computeIfAbsent(parent) { CustomClassLoader(it) }
    }
}