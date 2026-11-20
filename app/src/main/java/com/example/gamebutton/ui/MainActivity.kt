package com.example.gamebutton.ui

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.gamebutton.R
import com.example.gamebutton.data.Prefs
import com.example.gamebutton.input.TapInjector
import com.example.gamebutton.overlay.OverlayService

/** Головне меню. TODO: вибір стилю -> Select Game -> Start Game. */
class MainActivity : AppCompatActivity() {

    private lateinit var grant: Button
    private lateinit var a11y: Button
    private lateinit var start: Button
    private lateinit var stop: Button

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            startForegroundService(
                Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_CODE, r.resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, d)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 24f })
        grant = Button(this).apply {
            text = getString(R.string.grant_overlay)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        a11y = Button(this).apply {
            text = getString(R.string.grant_a11y)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        start = Button(this).apply {
            text = getString(R.string.start_overlay)
            setOnClickListener {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                projection.launch(mpm.createScreenCaptureIntent())
            }
        }
        stop = Button(this).apply {
            text = getString(R.string.stop_overlay)
            setOnClickListener { stopService(Intent(context, OverlayService::class.java)) }
        }
        listOf(grant, a11y, start, stop).forEach { root.addView(it) }

        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val keyField = EditText(this).apply {
            hint = getString(R.string.ai_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString(Prefs.KEY, ""))
        }
        val modelField = EditText(this).apply {
            hint = Prefs.DEFAULT_MODEL
            setText(prefs.getString(Prefs.MODEL, Prefs.DEFAULT_MODEL))
        }
        val aiBox = CheckBox(this).apply {
            text = getString(R.string.ai_mode)
            isChecked = prefs.getBoolean(Prefs.AI_ON, false)
        }
        val save = Button(this).apply {
            text = getString(R.string.save)
            setOnClickListener {
                prefs.edit()
                    .putString(Prefs.KEY, keyField.text.toString().trim())
                    .putString(Prefs.MODEL, modelField.text.toString().trim().ifEmpty { Prefs.DEFAULT_MODEL })
                    .putBoolean(Prefs.AI_ON, aiBox.isChecked)
                    .apply()
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
            }
        }
        listOf(aiBox, keyField, modelField, save).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = TapInjector.isEnabled()
        grant.isEnabled = !overlayOk
        a11y.isEnabled = !a11yOk
        start.isEnabled = overlayOk && a11yOk
        stop.isEnabled = overlayOk
    }
}
