package io.github.YGHFv.ReaPressExtend.testing

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

internal class MemoryPreferences : SharedPreferences {
    private val lock = Any()
    private val values = LinkedHashMap<String, Any?>()
    @Volatile var afterRead: (String) -> Unit = {}
    @Volatile var afterSnapshot: () -> Unit = {}
    @Volatile var beforeWrite: () -> Unit = {}
    @Volatile var commitSucceeds = true
    var commits = 0
        private set

    override fun getAll(): Map<String, *> {
        val snapshot = synchronized(lock) { values.toMap() }
        afterSnapshot()
        return snapshot
    }

    private fun read(key: String, default: Any?): Any? {
        val value = synchronized(lock) { if (values.containsKey(key)) values[key] else default }
        afterRead(key)
        return value
    }

    override fun getString(key: String, defValue: String?): String? = read(key, defValue) as String?

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (read(key, defValues) as Set<String>?)?.toSet()

    override fun getInt(key: String, defValue: Int): Int = read(key, defValue) as Int
    override fun getLong(key: String, defValue: Long): Long = read(key, defValue) as Long
    override fun getFloat(key: String, defValue: Float): Float = read(key, defValue) as Float
    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key, defValue) as Boolean
    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = LinkedHashMap<String, Any?>()
        private var cleared = false

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply { updates[key] = value }

        override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = put(key, values?.toSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { cleared = true }

        override fun commit(): Boolean {
            beforeWrite()
            synchronized(lock) {
                commits++
                if (cleared) values.clear()
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
            return commitSucceeds
        }

        override fun apply() {
            commit()
        }
    }
}

internal class PreferencesContext(directory: File? = null) {
    val context: Context = mock(Context::class.java)
    private val stores = ConcurrentHashMap<String, MemoryPreferences>()

    init {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.packageName).thenReturn("io.github.YGHFv.ReaPressExtend")
        `when`(context.filesDir).thenReturn(directory)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenAnswer {
            preferences(it.getArgument(0))
        }
    }

    fun preferences(name: String): MemoryPreferences = stores.computeIfAbsent(name) { MemoryPreferences() }
    fun names(): Set<String> = stores.keys.toSet()
}
