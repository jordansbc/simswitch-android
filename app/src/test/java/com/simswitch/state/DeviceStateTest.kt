package com.simswitch.state

import com.simswitch.state.DeviceState.Blocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The interlock rules the owner specified:
 *
 *   "make sure that the auto switching is not while I am using data if possible. So if the screen
 *    is off, you can switch, or if the screen is on, but there is no or very little data, then you
 *    can switch. I just don't want it to switch during a video call, or VoIP call etc."
 *
 * These are tested here rather than on the phone because Samsung blocks toggling Wi-Fi from adb,
 * so several of these combinations can't be produced on demand on the device.
 */
class DeviceStateTest {

    private fun blocker(
        screenOn: Boolean = true,
        onWifi: Boolean = false,
        inCall: Boolean = false,
        tethering: Boolean = false,
        mediaActive: Boolean = false,
        kbps: Double = 0.0,
        foregroundQuiet: Boolean = true,
    ) = DeviceState.blockerFor(screenOn, onWifi, inCall, tethering, mediaActive, kbps, foregroundQuiet)

    // ---- the explicit requirement: never during a call ----

    @Test
    fun `call blocks switching`() {
        assertEquals(Blocker.CALL, blocker(inCall = true))
    }

    @Test
    fun `call blocks even with the screen off`() {
        // Screen-off is normally the ideal window, but a VoIP call runs with the screen off all the
        // time — pocket calls, car calls. The call has to win.
        assertEquals(Blocker.CALL, blocker(screenOn = false, inCall = true))
    }

    @Test
    fun `microphone in use counts as a call`() {
        // Second, independent signal. If a VoIP app on this Samsung build fails to set the audio
        // mode, the mic being held still stops us switching through the call. Over-cautious on
        // purpose: a false positive delays a switch, a false negative drops a call.
        assertEquals(Blocker.CALL, blocker(inCall = true))
        assertEquals(Blocker.CALL, blocker(screenOn = false, inCall = true, kbps = 0.0))
    }

    @Test
    fun `call blocks even when the link looks idle`() {
        // A VoIP call is only ~50-100 kbps, which is below BUSY_KBPS. This is exactly why calls are
        // detected via the audio mode and not inferred from throughput.
        assertEquals(Blocker.CALL, blocker(inCall = true, kbps = 60.0))
    }

    // ---- "if the screen is off, you can switch" ----

    @Test
    fun `screen off is safe even under heavy load`() {
        assertNull(blocker(screenOn = false, kbps = 5_000.0))
    }

    @Test
    fun `screen off is safe while media plays`() {
        assertNull(blocker(screenOn = false, mediaActive = true))
    }

    // ---- "screen on, but there is no or very little data" ----

    @Test
    fun `screen on and idle is safe`() {
        assertNull(blocker(screenOn = true, kbps = 0.0))
    }

    @Test
    fun `screen on with trickle traffic is safe`() {
        // Push keepalives and background sync must not count as "in use".
        assertNull(blocker(screenOn = true, kbps = DeviceState.BUSY_KBPS - 1))
    }

    @Test
    fun `screen on with real traffic blocks`() {
        assertEquals(Blocker.ACTIVE_DATA, blocker(screenOn = true, kbps = DeviceState.BUSY_KBPS + 1))
    }

    @Test
    fun `screen on while streaming blocks`() {
        // Buffered video can be quiet on the wire but is still very much in use.
        assertEquals(Blocker.MEDIA_PLAYING, blocker(screenOn = true, mediaActive = true, kbps = 0.0))
    }

    // ---- other interlocks ----

    @Test
    fun `tethering blocks because every client would drop at once`() {
        assertEquals(Blocker.TETHERING, blocker(tethering = true))
        assertEquals(Blocker.TETHERING, blocker(screenOn = false, tethering = true))
    }

    @Test
    fun `wifi always blocks, screen on or off`() {
        // The owner's rule: never change the data SIM while on Wi-Fi.
        assertEquals(Blocker.ON_WIFI, blocker(screenOn = true, onWifi = true))
        assertEquals(Blocker.ON_WIFI, blocker(screenOn = false, onWifi = true))
        assertEquals(Blocker.ON_WIFI, blocker(screenOn = true, onWifi = true, mediaActive = true))
    }

    @Test
    fun `wifi does not override a call`() {
        assertEquals(Blocker.CALL, blocker(onWifi = true, inCall = true))
    }

    // ---- "don't pull me out of what I'm doing" (2026-09-03) ----
    //
    // The switch stopped being invisible when it moved to the AccessibilityService: it opens the
    // SIM manager over the foreground app for a couple of seconds. Maps, WhatsApp and Messages all
    // sit under BUSY_KBPS, so every rule above reads them as "screen on but idle" and let them be
    // interrupted — which is exactly what happened, in Maps, on a drive.

    @Test
    fun `an app in the foreground blocks`() {
        assertEquals(Blocker.APP_IN_USE, blocker(screenOn = true, foregroundQuiet = false))
    }

    @Test
    fun `an app in the foreground blocks even when the link is idle`() {
        // The whole point. Maps navigating is well under BUSY_KBPS, so throughput says "go".
        assertEquals(
            Blocker.APP_IN_USE,
            blocker(screenOn = true, kbps = DeviceState.BUSY_KBPS - 1, foregroundQuiet = false),
        )
    }

    @Test
    fun `wifi blocks even when an app is in front`() {
        assertEquals(
            Blocker.ON_WIFI,
            blocker(screenOn = true, onWifi = true, foregroundQuiet = false),
        )
    }

    @Test
    fun `an app in the foreground does not matter with the screen off`() {
        // Nothing is "in the foreground" in any sense a person would notice.
        assertNull(blocker(screenOn = false, foregroundQuiet = false))
    }

    @Test
    fun `a call still outranks an app in the foreground`() {
        assertEquals(Blocker.CALL, blocker(inCall = true, foregroundQuiet = false))
    }

    @Test
    fun `the launcher is a quiet moment`() {
        // The target window: phone awake, unlocked, nothing open.
        assertNull(blocker(screenOn = true, foregroundQuiet = true))
    }
}
