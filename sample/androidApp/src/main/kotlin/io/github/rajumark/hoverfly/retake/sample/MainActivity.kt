package io.github.rajumark.hoverfly.retake.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var pending: CompletableDeferred<ByteArray?>? = null

    // The system photo picker: no storage permission needed.
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val p = pending ?: return@registerForActivityResult
        pending = null
        if (uri == null) p.complete(null)
        else p.complete(runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App("Android") {
                val d = CompletableDeferred<ByteArray?>().also { pending = it }
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                withContext(Dispatchers.IO) { d.await() }
            }
        }
    }
}
