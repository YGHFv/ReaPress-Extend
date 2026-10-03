package io.github.YGHFv.ReaPressExtend.testing

internal class ObjectStateScope : AutoCloseable {
    private val restorations = mutableListOf<() -> Unit>()

    fun set(owner: Any, name: String, value: Any?) {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val original = field.get(owner)
        restorations.add { field.set(owner, original) }
        field.set(owner, value)
    }

    override fun close() {
        restorations.asReversed().forEach { it() }
    }
}
