package crap4kotlin

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), { print(it) }, { System.err.print(it) }))
}
