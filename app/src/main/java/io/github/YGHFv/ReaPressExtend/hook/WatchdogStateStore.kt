package io.github.YGHFv.ReaPressExtend.hook

import io.github.YGHFv.ReaPressExtend.core.WatchdogStateMachine.State
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal class WatchdogStateStore(private val candidates: List<File>) {
    fun read(): State {
        val file = candidates.firstOrNull { it.isFile } ?: return State()
        check(file.length() in 1..16_384)
        val props = Properties()
        file.inputStream().use(props::load)
        fun number(key: String): Int = requireNotNull(props.getProperty(key)?.toIntOrNull()?.takeIf { it >= 0 })
        val state = State(
            attempt = number("attempt"),
            ok = number("ok"),
            failures = number("failures"),
            disabled = requireNotNull(props.getProperty("disabled")?.toBooleanStrictOrNull()),
            reason = props.getProperty("reason", ""),
            consumedResetId = props.getProperty("consumedResetId", ""),
        )
        check(state.ok <= state.attempt && state.consumedResetId.length <= 128)
        return state
    }

    fun write(state: State): Boolean {
        val existing = candidates.firstOrNull { it.isFile }
        val targets = existing?.let(::listOf) ?: candidates
        val props = Properties().apply {
            setProperty("attempt", state.attempt.toString())
            setProperty("ok", state.ok.toString())
            setProperty("failures", state.failures.toString())
            setProperty("disabled", state.disabled.toString())
            setProperty("reason", state.reason)
            setProperty("consumedResetId", state.consumedResetId)
        }
        return targets.any { file ->
            runCatching {
                val directory = requireNotNull(file.absoluteFile.parentFile)
                if (!directory.isDirectory) check(directory.mkdirs())
                val temporary = File.createTempFile("reapress-watchdog-", ".tmp", directory)
                try {
                    FileOutputStream(temporary).use { output ->
                        props.store(output, null)
                        output.fd.sync()
                    }
                    try {
                        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    temporary.delete()
                }
            }.isSuccess
        }
    }
}
