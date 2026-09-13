package com.ryouvc

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.collections.iterator

class MainActivity : AppCompatActivity() {

    private lateinit var packageInput: EditText
    private lateinit var logView: TextView
    private lateinit var audioCheckbox: CheckBox
    private lateinit var statusView: TextView
    private lateinit var spinner: ProgressBar
    private var outputDir: Uri? = null

    private var copyCount = 0
    private var copyTotal = 0

    @Volatile private var cancelled = false
    @Volatile private var activeProcess: Process? = null

    private inner class CancellableInputStream(private val base: InputStream) : InputStream() {
        override fun read(): Int {
            checkCancelled()
            return base.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkCancelled()
            return base.read(b, off, len)
        }

        override fun close() {
            base.close()
        }

        private fun checkCancelled() {
            if (cancelled) throw IOException("cancelled")
        }
    }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                outputDir = uri
                log("Output folder: $uri")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        packageInput = findViewById(R.id.package_input)
        logView = findViewById(R.id.log_view)
        audioCheckbox = findViewById(R.id.audio_checkbox)
        statusView = findViewById(R.id.status_view)
        spinner = findViewById(R.id.spinner)
        findViewById<Button>(R.id.pick_folder).setOnClickListener { folderPicker.launch(null) }
        findViewById<Button>(R.id.extract_button).setOnClickListener { extract() }
        findViewById<Button>(R.id.cancel_button).setOnClickListener {
            cancelled = true
            activeProcess?.destroy()
            log("Cancelling...")
        }
    }

    private fun setRunning(running: Boolean) {
        runOnUiThread {
            packageInput.isEnabled = !running
            audioCheckbox.isEnabled = !running
            findViewById<Button>(R.id.pick_folder).isEnabled = !running
            findViewById<Button>(R.id.extract_button).isEnabled = !running
            findViewById<Button>(R.id.cancel_button).visibility =
                if (running) View.VISIBLE else View.GONE
            spinner.visibility = if (running) View.VISIBLE else View.GONE
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            val layout = logView.layout ?: return@runOnUiThread
            val diff = layout.getLineTop(logView.lineCount) - logView.height
            if (diff > 0) logView.scrollBy(0, diff)
        }
    }

    private fun extract() {
        val out = outputDir
        if (out == null) {
            log("Choose an output folder first.")
            return
        }
        cancelled = false
        setRunning(true)
        Thread {
            try {
                doExtract(out)
                if (cancelled) {
                    log("Cancelled: $copyCount files copied.")
                } else {
                    endExtract()
                    log("Done: $copyCount files extracted.")
                }
            } catch (e: Exception) {
                log(if (cancelled) "Cancelled: $copyCount files copied." else "Error: ${e.message}")
            } finally {
                setRunning(false)
            }
        }.start()
    }

    private fun beginExtract(total: Int) {
        copyCount = 0
        copyTotal = total
        runOnUiThread {
            spinner.visibility = View.VISIBLE
            statusView.text = "Starting..."
        }
    }

    private fun updateStatus(name: String) {
        copyCount++
        val cur = copyCount
        val total = copyTotal
        runOnUiThread {
            statusView.text =
                if (total > 0) "Copying $name  ($cur / $total)"
                else "Copying $name  ($cur)"
        }
    }

    private fun endExtract() {
        runOnUiThread {
            spinner.visibility = View.GONE
            statusView.text = "Done: $copyCount files"
        }
    }

    private fun relForZipEntry(name: String): String? {
        return when {
            name.startsWith("lib/arm64-v8a/") -> name.removePrefix("lib/arm64-v8a/")
            name.startsWith("assets/") -> name.removePrefix("assets/")
            else -> null
        }
    }

    private fun doExtract(out: Uri) {
        val pkg = packageInput.text.toString().trim()
        if (pkg.isEmpty()) {
            log("Enter a package name.")
            return
        }
        log("Package: $pkg")

        val info = packageManager.getApplicationInfo(pkg, 0)
        log("base: ${info.sourceDir}")
        val splits = info.splitSourceDirs ?: emptyArray()
        if (splits.isEmpty()) log("(no split APKs)")
        val apks = listOf(info.sourceDir) + splits
        log("APKs to read: ${apks.size}")

        var total = 0
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    if (relForZipEntry(entry.name) != null) total++
                }
            }
        }
        beginExtract(total)

        for (apk in apks) {
            log("Reading ${apk.substringAfterLast('/')}")
            ZipFile(apk).use { zip ->
                for (entry in zip.entries()) {
                    if (cancelled) break
                    if (entry.isDirectory) continue
                    val rel = relForZipEntry(entry.name)
                    if (rel != null && rel.isNotEmpty()) {
                        zip.getInputStream(entry).use { input ->
                            writeStream(out, rel, input)
                        }
                        updateStatus(rel)
                    }
                }
            }
            if (cancelled) break
        }

        if (cancelled) return
        if (audioCheckbox.isChecked) {
            extractAudioAsRoot(out, pkg)
        }
    }

    // root helpers

    private fun hasRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }
    private fun rootText(cmd: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() == 0) out else null
        } catch (e: Exception) {
            null
        }
    }

    private fun writeStream(treeUri: Uri, rel: String, input: InputStream) {
        val parts = rel.split("/")
        var dir = DocumentFile.fromTreeUri(this, treeUri)
            ?: throw IllegalStateException("invalid output folder")
        for (i in 0 until parts.size - 1) {
            dir = dir.findFile(parts[i]) ?: dir.createDirectory(parts[i])!!
        }
        val file = dir.findFile(parts.last())
            ?: dir.createFile("application/octet-stream", parts.last())!!
        val out = contentResolver.openOutputStream(file.uri)
            ?: throw IllegalStateException("cannot write ${file.uri}")
        out.use { output ->
            CancellableInputStream(input).copyTo(output)
        }
    }

    private fun rootCatTo(src: String, treeUri: Uri, rel: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat \"$src\""))
            activeProcess = p
            writeStream(treeUri, rel, p.inputStream)
            p.waitFor()
            p.exitValue() == 0
        } catch (e: Exception) {
            false
        } finally {
            activeProcess = null
        }
    }

    private fun extractAudioAsRoot(out: Uri, pkg: String) {
        if (!hasRoot()) {
            log("Audio checkbox is on, but root is not available.")
            return
        }
        log("Root perms granted. Reading asset packs...")

        val base = "/data/user/0/$pkg/files/assetpacks"
        val packs = (rootText("find \"$base\" -type d -name assets") ?: "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (packs.isEmpty()) {
            log("No asset packs found under $base")
            return
        }

        val jobs = mutableListOf<Pair<String, String>>()
        for (pack in packs) {
            val files = (rootText("find \"$pack\" -type f") ?: "")
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            log("Pack ${pack.removePrefix("$base/").substringBefore('/')}: ${files.size} files")
            for (f in files) {
                jobs += f to f.removePrefix("$pack/")
            }
        }
        copyTotal += jobs.size
        for ((src, rel) in jobs) {
            if (cancelled) break
            updateStatus(rel)
            if (!rootCatTo(src, out, rel)) {
                log("  WARN: could not read $src")
            }
        }
    }
}