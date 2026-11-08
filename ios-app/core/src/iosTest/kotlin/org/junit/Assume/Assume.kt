package org.junit.Assume

import org.junit.AssumptionViolatedException

fun assumeTrue(condition: Boolean) { if (!condition) throw AssumptionViolatedException("assumption failed") }
fun assumeTrue(message: String, condition: Boolean) { if (!condition) throw AssumptionViolatedException(message) }
