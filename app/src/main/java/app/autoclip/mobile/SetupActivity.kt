package app.autoclip.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** First-run screen: ask for the server address and verify it via /api/health. */
class SetupActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var save: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        input = findViewById(R.id.url_input)
        save = findViewById(R.id.save)
        status = findViewById(R.id.status)

        input.setText(Prefs.serverUrl(this) ?: "")
        save.setOnClickListener { testAndSave() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                testAndSave()
                true
            } else {
                false
            }
        }
    }

    private fun testAndSave() {
        val url = Prefs.normalize(input.text.toString())
        if (url.isEmpty()) return

        save.isEnabled = false
        status.text = getString(R.string.setup_testing)

        Thread {
            val result = runCatching { fetchVersion(url) }
            runOnUiThread {
                save.isEnabled = true
                result.onSuccess { version ->
                    status.text = getString(R.string.setup_ok, version)
                    Prefs.setServerUrl(this, url)
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }.onFailure { e ->
                    status.text = getString(R.string.setup_fail, e.message ?: e.javaClass.simpleName)
                }
            }
        }.start()
    }

    /** Returns the AutoClip version reported by /api/health, or throws. */
    private fun fetchVersion(baseUrl: String): String {
        val conn = URL("$baseUrl/api/health").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            if (code != 200) error("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("status") != "ok") error("unexpected response")
            return json.optString("version", "")
        } finally {
            conn.disconnect()
        }
    }
}
