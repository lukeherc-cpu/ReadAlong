package com.example.readalong.compat

val <T : Any> kotlin.reflect.KClass<T>.java: org.junit.Class<T> get() = org.junit.Class(this)
