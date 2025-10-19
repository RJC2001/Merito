package com.rjc.merito

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.firebase.auth.FirebaseAuth

class GalleryActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        auth = FirebaseAuth.getInstance()
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                performLogoutAndReturnToAuth()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun performLogoutAndReturnToAuth() {
        try {
            auth.signOut()
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("pending_uid").remove("pending_email").remove("pending_username").apply()
            val intent = Intent(this, AuthActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                finishAffinity()
                return
            }
            finishAffinity()
            Toast.makeText(this, getString(R.string.action_logout), Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            try {
                finishAffinity()
            } catch (_: Throwable) { }
        }
    }
}
