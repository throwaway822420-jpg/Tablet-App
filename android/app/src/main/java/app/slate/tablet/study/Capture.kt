package app.slate.tablet.study

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import app.slate.tablet.R

/**
 * Invisible activity that screenshots whatever is on the tablet (via Android's screen-capture
 * permission), then opens it for annotation. It stays open, fully transparent, during the capture
 * so it may start the annotation screen afterwards.
 */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST || resultCode != RESULT_OK || data == null) {
            finish()
            return
        }
        pending = this
        startForegroundService(Intent(this, CaptureService::class.java).putExtra(CaptureService.EXTRA_CODE, resultCode).putExtra(CaptureService.EXTRA_DATA, data))
    }

    internal fun onCaptured(bitmap: Bitmap?) {
        pending = null
        if (bitmap == null) Toast.makeText(this, "Couldn't capture the screen.", Toast.LENGTH_SHORT).show()
        else AskActivity.start(this, bitmap, StudyHub.recentSession(tabletOnly = false))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST = 7
        @Volatile internal var pending: CaptureActivity? = null
    }
}

/** Foreground service Android requires while capturing; grabs one frame and stops. */
class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var done = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotice()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null) return finish(null).let { START_NOT_STICKY }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!done) finish(null)
            }
        }, main)
        // Give the consent dialog a moment to disappear so it isn't in the picture.
        main.postDelayed({ startCapture(mp) }, 450)
        return START_NOT_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        var frames = 0
        r.setOnImageAvailableListener({ ir ->
            val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            // Skip the first frame: it can still show the dialog fading out.
            if (++frames < 2 || done) {
                img.close()
                return@setOnImageAvailableListener
            }
            val plane = img.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            img.close()
            finish(Bitmap.createBitmap(padded, 0, 0, w, h))
        }, main)
        display = try {
            mp.createVirtualDisplay("slate-capture", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
        } catch (e: SecurityException) {
            Log.w("SlateCapture", "Capture refused", e)
            finish(null)
            null
        }
        main.postDelayed({ if (!done) finish(null) }, 4000)
    }

    private fun finish(bitmap: Bitmap?) {
        if (done) return
        done = true
        display?.release()
        reader?.close()
        projection?.stop()
        CaptureActivity.pending?.onCaptured(bitmap)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen capture for Ask Claude", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Capturing the screen for Claude")
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "capture"
    }
}

/** Quick Settings tile: "Ask Claude" about whatever is on the tablet's screen. */
class AskTileService : TileService() {
    override fun onClick() {
        val intent = Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
