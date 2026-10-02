package io.github.YGHFv.ReaPressExtend.testing

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.withSettings

internal fun <Value> withAndroidTestRuntime(block: () -> Value): Value =
    mockStatic(Log::class.java).use {
        mockStatic(Looper::class.java).use {
            mockConstruction(Handler::class.java).use {
                mockConstruction(Intent::class.java, withSettings().defaultAnswer(RETURNS_SELF)).use {
                    block()
                }
            }
        }
    }
