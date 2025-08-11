package io.amichne.checKer.ksp

data class Violation(
    val name: String,
    val rule: String,
    val value: Long,
    val message: String
)
