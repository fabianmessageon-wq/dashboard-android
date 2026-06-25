package dev.jaredhq.dashboardandroid

import android.content.Intent
import dev.jaredhq.dashboardandroid.work.BootReceiver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM test for [BootReceiver]'s action filter — it must re-arm only on the boot/update
 * broadcasts it registers for and ignore anything else delivered to it.
 */
class BootReceiverTest {

    @Test
    fun reArmsOnBootCompleted() {
        assertTrue(BootReceiver.handles(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun reArmsOnPackageReplaced() {
        assertTrue(BootReceiver.handles(Intent.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun ignoresOtherActionsAndNull() {
        assertFalse(BootReceiver.handles(Intent.ACTION_BATTERY_LOW))
        assertFalse(BootReceiver.handles("android.intent.action.QUICKBOOT_POWERON"))
        assertFalse(BootReceiver.handles(null))
    }
}
