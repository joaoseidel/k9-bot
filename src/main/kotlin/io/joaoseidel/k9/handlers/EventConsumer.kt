package io.joaoseidel.k9.handlers

interface EventConsumer<T> {
  suspend fun consume(event: T)
}
