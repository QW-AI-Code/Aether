package studio.cluvex.aether.ui.settings

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * The QR scanner, locked to portrait.
 *
 * zxing-android-embedded's own [CaptureActivity] is declared landscape and only
 * follows the sensor when the orientation lock is off. This subclass carries
 * `android:screenOrientation="portrait"` in the manifest, and the scan options
 * set `setOrientationLocked(true)`, so the camera preview and the viewfinder
 * always open upright, the way the phone is held.
 */
class PortraitCaptureActivity : CaptureActivity()
