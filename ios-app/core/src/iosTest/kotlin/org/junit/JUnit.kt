// JUnit 4's annotations and asserts over kotlin.test, so the phone's own unit tests run on the iOS simulator.
package org.junit

typealias Test = kotlin.test.Test
typealias Before = kotlin.test.BeforeTest
typealias After = kotlin.test.AfterTest
typealias Ignore = kotlin.test.Ignore

class AssumptionViolatedException(message: String) : RuntimeException(message)

/** `Foo::class.java` in the phone's tests; only assertThrows uses it. */
class Class<T : Any>(val kotlin: kotlin.reflect.KClass<T>)
