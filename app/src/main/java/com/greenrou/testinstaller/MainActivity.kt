package com.greenrou.testinstaller

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_MUTABLE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.greenrou.testinstaller.ui.theme.TestinstallerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val action = intent.action
        if (action != null && action.startsWith(INSTALL_ACTION)) {
            handleInstallResult(intent)
        }

        setContent {
            TestinstallerTheme {

                Scaffold(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Button(
                            onClick = {
                                if (checkPermissions()) {
                                    pickApkFile()
                                } else {
                                    requestPermissions()
                                }
                            }
                        ) {
                            Text(text = "Choose APK")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val packageInfo = packageManager.getPackageInfo(packageName, 0)
                        val versionName = packageInfo.versionName

                        Text(
                            text = "version: $versionName"
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        return ContextCompat.checkSelfPermission(
            this,
            readPermission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(readPermission, Manifest.permission.REQUEST_INSTALL_PACKAGES),
            REQUEST_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pickApkFile()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select APK"), PICK_APK_FILE_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_APK_FILE_CODE && resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val packageInputStream = contentResolver.openInputStream(uri)
                        val apkPackageName = packageInputStream?.let { getPackageNameFromApk(it) } ?: ""
                        packageInputStream?.close()

                        val installInputStream = contentResolver.openInputStream(uri)
                        if (installInputStream != null) {
                            install(
                                id = 2,
                                packageName = apkPackageName,
                                streams = listOf(installInputStream),
                                context = this@MainActivity
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Installation started",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("LOG_COM", "error ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Error: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun handleInstallResult(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                startActivity(confirmIntent)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(this, "Installation successful", Toast.LENGTH_SHORT).show()
            }

            else -> {
                Log.e("LOG_COM", "error2 ${message}")
                Toast.makeText(this, "Installation failed: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun getPackageNameFromApk(inputStream: InputStream): String {
        val zip = ZipInputStream(inputStream)
        try {
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (entry.name == "AndroidManifest.xml") {
                    val manifestData = ByteArray(entry.size.toInt())
                    zip.read(manifestData)
                    val manifestXml = String(manifestData)
                    val regex = "package=\"([^\"]+)\"".toRegex()
                    val matchResult = regex.find(manifestXml)
                    if (matchResult != null) {
                        return matchResult.groupValues[1]
                    }
                }
                entry = zip.nextEntry
            }
            return ""
        } finally {
            try {
                zip.close()
            } catch (e: Exception) {
                Log.e("LOG_COM", "Error closing ZipInputStream: ${e.message}")
            }
        }
    }

    private fun install(
        id: Int,
        packageName: String,
        streams: List<InputStream>,
        context: Context
    ) {
        val packageInstaller: PackageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        params.setAppPackageName(packageName)

        val sessionId = packageInstaller.createSession(params)
        var bytes = 0L
        packageInstaller.openSession(sessionId).use { session ->
            streams.forEach {
                session.openWrite("$packageName.${UUID.randomUUID()}", 0, -1).use { output ->
                    bytes += it.copyToAndNotify(output)
                    it.close()
                    session.fsync(output)
                }
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "$INSTALL_ACTION.$id"
            }
            val pending = PendingIntent.getActivity(context, 0, intent, FLAG_MUTABLE)
            session.commit(pending.intentSender)
            session.close()
        }
    }

    private fun InputStream.copyToAndNotify(
        out: OutputStream,
        bufferSize: Int = DEFAULT_BUFFER_SIZE
    ): Long {
        var bytesCopied: Long = 0
        val buffer = ByteArray(bufferSize)
        var bytes = read(buffer)
        while (bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = read(buffer)
        }
        return bytesCopied
    }

    private companion object {
        const val INSTALL_ACTION = "installAction"
        const val REQUEST_PERMISSION_CODE = 123
        const val PICK_APK_FILE_CODE = 456
    }
}