package io.joaoseidel.k9.commands

abstract class AbstractCommand<T>(
  val name: String,
  val description: String
) {

  abstract fun matches(input: String): Boolean
  abstract fun help(): String
  abstract suspend fun execute(args: T)
  abstract suspend fun parseArguments(arguments: List<String>): T
}
