package io.github.YGHFv.ReaPressExtend.testing

import android.os.Build
import sun.misc.Unsafe

/** Only changes the JVM Android stub; always restore it before another test runs. */
internal fun <T> withAndroidSdk(sdk: Int, block: () -> T): T {
    val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    val field = Build.VERSION::class.java.getDeclaredField("SDK_INT")
    val base = unsafe.staticFieldBase(field)
    val offset = unsafe.staticFieldOffset(field)
    val previous = unsafe.getInt(base, offset)
    unsafe.putInt(base, offset, sdk)
    return try { block() } finally { unsafe.putInt(base, offset, previous) }
}
