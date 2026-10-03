package io.github.YGHFv.ReaPressExtend.backup

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class BackupTaskGate {
    private val occupied = AtomicBoolean()

    fun acquire(): Boolean = occupied.compareAndSet(false, true)

    fun release() { occupied.set(false) }

    fun submit(executor: Executor, block: () -> Unit, finished: () -> Unit): Boolean {
        if (!acquire()) return false
        try {
            executor.execute {
                try {
                    block()
                } finally {
                    release()
                    finished()
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            release()
            throw error
        }
        return true
    }
}
