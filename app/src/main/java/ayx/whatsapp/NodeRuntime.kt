package ayx.whatsapp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Owns the single embedded node process: extracts the bundled node project on
 * first run (or after an app update), sets the env node needs on Android, and
 * starts it exactly once per OS process.
 */
object NodeRuntime {
    private const val TAG = "NodeRuntime"
    private val started = AtomicBoolean(false)

    const val PORT = 8765

    fun ensureStarted(ctx: Context) {
        if (!started.compareAndSet(false, true)) return

        val projectDir = File(ctx.filesDir, "nodejs-project")
        extractIfNeeded(ctx, projectDir)

        // Android has no /tmp and no HOME — node/npm code assumes both exist.
        val authDir = File(ctx.noBackupFilesDir, "auth").apply { mkdirs() }
        val compileCache = File(ctx.cacheDir, "node-compile-cache").apply { mkdirs() }

        NodeBridge.nativeSetenv("TMPDIR", ctx.cacheDir.absolutePath)
        NodeBridge.nativeSetenv("HOME", ctx.filesDir.absolutePath)
        NodeBridge.nativeSetenv("NODE_COMPILE_CACHE", compileCache.absolutePath)
        // V8 sizes old-space from total RAM; cap it so the OS doesn't OOM-kill the app.
        NodeBridge.nativeSetenv("NODE_OPTIONS", "--max-old-space-size-percentage=50")
        NodeBridge.nativeSetenv("WAGW_AUTH_DIR", authDir.absolutePath)
        NodeBridge.nativeSetenv("WAGW_PORT", PORT.toString())
        val mediaDir = File(ctx.filesDir, "media").apply { mkdirs() }
        NodeBridge.nativeSetenv("WAGW_MEDIA_DIR", mediaDir.absolutePath)

        val mainJs = File(projectDir, "main.js").absolutePath
        Thread({
            Log.i(TAG, "starting node: $mainJs")
            val rc = NodeBridge.nativeStart(arrayOf("node", mainJs), projectDir.absolutePath)
            Log.w(TAG, "node exited rc=$rc") // node::Start only returns on process exit
        }, "nodejs").start()
    }

    private fun extractIfNeeded(ctx: Context, projectDir: File) {
        val marker = File(projectDir, ".version")
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == current) return

        Log.i(TAG, "extracting nodejs-project.zip (v=$current)")
        projectDir.deleteRecursively()
        projectDir.mkdirs()

        ctx.assets.open("nodejs-project.zip").use { raw ->
            ZipInputStream(raw).use { zip ->
                var e = zip.nextEntry
                val buf = ByteArray(64 * 1024)
                while (e != null) {
                    val out = File(projectDir, e.name)
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            var n = zip.read(buf)
                            while (n >= 0) { fos.write(buf, 0, n); n = zip.read(buf) }
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
        }
        marker.writeText(current)
        Log.i(TAG, "extraction done")
    }
}
