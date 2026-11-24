package com.raven.app.core.database

import androidx.room.withTransaction

/**
 * Abstraction over Room `withTransaction` so repository atomic mutations and backup restores
 * run in a real SQLite transaction on Android while staying 100% JVM unit-testable.
 */
interface DatabaseTransactionRunner {
    suspend operator fun <R> invoke(block: suspend () -> R): R
}

class RoomTransactionRunner(
    private val database: RavenDatabase,
) : DatabaseTransactionRunner {
    override suspend fun <R> invoke(block: suspend () -> R): R =
        database.withTransaction(block)
}
