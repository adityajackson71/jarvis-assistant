package com.example.jarvis

object PhoneUtils {
    fun normalize(number: String): String {
        val digitsOnly = number.filter { it.isDigit() }
        return if (digitsOnly.length > 10) digitsOnly.takeLast(10) else digitsOnly
    }
}
