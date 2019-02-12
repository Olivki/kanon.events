## kanon.events

Event handling with annotations, and in C# style for Kotlin.

## Installation

Gradle

- Groovy

  ```groovy
  repositories {
      maven { url "https://dl.bintray.com/olivki/kanon" }
  }
  
  dependencies {
      implementation "moe.kanon.events:moe.kanon:${LATEST_VERSION}"
  }
  ```

- Kotlin

  ```kotlin
  repositories {
      maven(url = "https://dl.bintray.com/olivki/kanon")
  }
  
  dependencies {
      implementation(group = "moe.kanon.events", name = "moe.kanon", version = "${LATEST_VERSION}")
  }
  ```

Maven

```xml
<dependency>
    <groupId>moe.kanon.events</groupId>
    <artifactId>moe.kanon</artifactId>
    <version>${LATEST_VERSION}</version>
    <type>pom</type>
</dependency>
```

## Credits

[Event4J](https://github.com/Techcable/Event4J) by [Techcable](https://github.com/Techcable).
This project is *largely* derivate of Event4J, it could even be seen as a Kotlin "port" of it, so major credits goes to Techcable for the original project.
Do note that while Event4J is made for Java, kanon.events is generally just focused on Kotlin, and not Java.
The Event4J project is licensed under the MIT license, which means any entries that are derivate will be marked with the appropriate license in the source code. This project is, however, still published under the Apache 2.0 license.

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