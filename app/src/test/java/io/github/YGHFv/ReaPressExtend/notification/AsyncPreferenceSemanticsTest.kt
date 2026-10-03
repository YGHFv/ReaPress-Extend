package io.github.YGHFv.ReaPressExtend.notification

import android.content.Context
import android.content.SharedPreferences
import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import io.github.YGHFv.ReaPressExtend.relay.WatchState
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class AsyncPreferenceSemanticsTest {
    @Test
    fun watchWritesStillApplyWithoutSynchronousCommit() {
        val f = Fixture()
        WatchState.setNextDueAt(f.context, 1234L)
        WatchState.markRoundDone(f.context, "test-tracking")
        WatchState.clearRoundDone(f.context)
        verify(f.editor, times(3)).apply()
        verify(f.editor, never()).commit()
    }

    @Test
    fun recordUpsertStillAppliesWithoutSynchronousCommit() {
        val f = Fixture()
        ExpressRecordStore.upsert(f.context, ExpressRecord(sourcePackage = "test", rawText = "synthetic", trackingNumber = "77300000000001"))
        verify(f.editor).apply()
        verify(f.editor, never()).commit()
    }

    @Test
    fun auditAppendStillAppliesWithoutSynchronousCommit() {
        val f = Fixture()
        ExpressNotificationLog.record(f.context, ExpressRecord(sourcePackage = "test", rawText = "synthetic"), delivered = false)
        verify(f.editor).apply()
        verify(f.editor, never()).commit()
    }

    private class Fixture {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)

        init {
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
            `when`(preferences.edit()).thenReturn(editor)
        }
    }
}
