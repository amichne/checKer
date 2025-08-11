package io.amichne.app


//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
val message = "Hello, Kotlin!"

fun main() {
    val name = "Kotlin"
    //TIP Press <shortcut actionId="ShowIntentionActions"/> with your caret at the highlighted text
    // to see how IntelliJ IDEA suggests fixing it.
    printer.printMessage()

    for (i in 1..5) {
        //TIP Press <shortcut actionId="Debug"/> to start debugging your code. We have set one <icon src="AllIcons.Debugger.Db_set_breakpoint"/> breakpoint
        // for you, but you can always add more by pressing <shortcut actionId="ToggleLineBreakpoint"/>.
        println("i = $i")
    }

    AppConfig(Percent(200), Port(500), Retries(20000))
}

object printer {
    fun printMessage() {
        println(message)
    }
}

interface CustomInteger {
    val value: Long
}

@JvmInline
value class Percent(override val value: Long) : CustomInteger
@JvmInline
value class Port(override val value: Long) : CustomInteger
@JvmInline
value class Retries(override val value: Long) : CustomInteger

data class AppConfig(
    @Range(min = 0, max = 100) val cpuLimit: Percent?,
    @Min(1024) val port: Port,
    @Max(10_000) val retries: Retries, // another CustomInteger type
    val tags: List<Percent> = emptyList()
)


annotation class Range(val min: Long, val max: Long)
annotation class Min(val value: Long)
annotation class Max(val value: Long)
