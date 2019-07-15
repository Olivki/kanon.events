## kanon.events 

[![Download](https://api.bintray.com/packages/olivki/kanon/kanon.events/images/download.svg)](https://bintray.com/olivki/kanon/kanon.events/_latestVersion)

Annotation based [publish-subscribe](https://en.wikipedia.org/wiki/Publish%E2%80%93subscribe_pattern) style event handling for Kotlin and Java.

Version 2.x is a complete rewrite of the event system, notable changes is that the `Subscribe` annotation has been renamed to `Subscribed`.

### How to Use

#### Creating a new `EventBus` instance
##### Kotlin

The easiest and fastest way to create a new `EventBus` instance is to use the `EventBus.default` property, which will give you a new `EventBus` instance with all the possible values set to their defaults. This means that the library provided `Event` class will be used as the super-class for all events and `Any` will be used as the super-class for the listeners.

```kotlin
val bus = EventBus.default
```

If more customization is neeed, ie. you want to use your own `Event` class implementation or you want to limit the scope of listeners to a specific type, then there are two functions available to use:

```kotlin
val bus = EventBus<Event, Any>(strategy = EventBus.InvocationStrategy.ASM, name = "EventBus")
```

This (^) function uses `reified` type parameters to set the super-classes of the bus. The code shown above will produce the same type of listener as the `EventBus.default` property would. The `strategy` and `name` parameters are optional, and the values shown above are what they are set to by *default*.

If you can't use the `reified` type parameters, ie. you're dealing with class instances and might not have direct access to the type of the desired event/listener, you can use the following function:

```kotlin
val bus = EventBus(Event::class, Any::class, strategy = EventBus.InvocationStrategy.ASM, name = "EventBus")
```

This works in the exact same manner as specified for the function above, and once again, `strategy` and `name` are optional.

##### Java

Like in the Kotlin example, the fastest and easiest way to create a new `EventBus` instance in Java is to use the `EventBus.defautl` property:

```java
final EventBus<Event, Object> bus = EventBus.getDefault();
```

And if one wants to create a more specific `EventBus` instance from Java there's a factory function available for that:

```java
final EventBus<Event, Object> bus = EventBus.newInstance(Event.class, Object.class, EventBus.InvocationStrategy.ASM, "EventBus");
```

This will return a new `EventBus` instance with `Event` as the super-class for all events and `Object` as the super-class for all listeners. Like in the Kotlin variant, the `strategy` and `name` parameters are optional, this is achieved by providing method overloads for the factory method, so if you want the default values for those, just leave those parameters out. *(Unlike Kotlin, however, if you want to customize the `name` parameter, you will need to manually define the `strategy` parameter too)*

#### Registering listeners and firing events

Registering and unregistering listeners is very straightforward, as all you need to is `bus.register(...)` and `bus.unregister(...)` respectively, but for a listener to actually be useful you need to make sure that there are functions inside of the class of the listener that are marked as listener functions.

Firing events is also very simple, as all it requires is the an invocation of `bus.fire(...)` with an event instance supplied as the only parameter.

Below is a very basic example of how to utilize the `EventBus` system:

```kotlin
// We are using the default 'EventBus' implementation in this example

data class MessageEvent(val username: String, val message: String) : Event()

object MessageListener {
    @Subscribed fun onMessageSent(event: MessageEvent) {
        println(event)
    }
}

val bus = EventBus.default

fun main() {
    bus.register(MessageListener)
    
    bus.fire(MessageEvent("steve56", "how's it going?")) // println MessageEvent(username='steve56', message='how's it going?')
}

```

So what's happening here is that the first thing we do in our `main` function is to register the `MessageListener` singleton as an event-listener, which means that the system will look through all the functions of `MessageListener` and register any of the functions marked with the `@Subscribed` annotation as listener functions. *(This is as long as they are seen as valid listener functions, if they aren't, a `InvalidListenerFunctionException` will be thrown)*

It then fires a new `MessageEvent` instance to the defined `EventBus` which results in the line `MessageEvent(username='steve56', message='how's it going?')` being printed to the console as the `onMessageEvent` function of `MessageListener` was invoked by the event-bus system.

While registering and unregistering listeners and firing events is done in essentially the same way in Kotlin and Java, in Kotlin some operator functions have been provided for nicer syntax; `bus += listener`, `bus -= listener` and `bus *= event` which will invoke `bus.register(listener)`, `bus.unregister(listener)` and `bus.fire(event)` respectively.

## Credits

- [Event4J](https://github.com/Techcable/Event4J) by [Techcable](https://github.com/Techcable).
The old 1.x version of `kanon.events` was essentially a direct port of `Event4J` to Kotlin, the current version *(2.x)* is a full rewrite of the system, and it is now not as much of a "port" anymore. However, large inspiration and some code is still taken from the `Event4J` project. 
`Event4J` is under the Apache 2.0 License.

## Installation

Gradle

- Groovy

  ```groovy
  repositories {
      maven { url "https://dl.bintray.com/olivki/kanon" }
  }
  
  dependencies {
      implementation "moe.kanon.events:kanon.events:${LATEST_VERSION}"
  }
  ```

- Kotlin

  ```kotlin
  repositories {
      maven(url = "https://dl.bintray.com/olivki/kanon")
  }
  
  dependencies {
      implementation(group = "moe.kanon.events", name = "kanon.events", version = "${LATEST_VERSION}")
  }
  ```

Maven

```xml
<dependency>
    <groupId>moe.kanon.events</groupId>
    <artifactId>kanon.events</artifactId>
    <version>${LATEST_VERSION}</version>
    <type>pom</type>
</dependency>
```

## License

````
Copyright 2019 Oliver Berg

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
````