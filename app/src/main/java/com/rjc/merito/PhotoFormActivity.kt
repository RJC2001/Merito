package com.rjc.merito

import android.app.DownloadManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.rjc.merito.databinding.ActivityPhotoFormBinding
import com.rjc.merito.firebase.awaitSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class PhotoFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoFormBinding
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var isEditMode = false
    private lateinit var photoId: String
    private var photoFullFromIntent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_photo_form)

        photoId = intent.getStringExtra("photo_id") ?: ""
        photoFullFromIntent = intent.getStringExtra("photo_full") ?: ""
        val photoTitle = intent.getStringExtra("photo_title") ?: ""
        isEditMode = photoId.isNotEmpty()

        binding.etTitle.setText(photoTitle)
        binding.etDescription.visibility = android.view.View.VISIBLE

        // Initialize download button state and click handler
        binding.btnDownload.visibility = android.view.View.GONE
        binding.btnDownload.isEnabled = false
        binding.btnDownload.setOnClickListener {
            val url = photoFullFromIntent.trim()
            if (url.isEmpty()) {
                Toast.makeText(this, getString(R.string.err_no_url), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = Uri.parse(url)
                val request = DownloadManager.Request(uri)
                    .setTitle(binding.etTitle.text?.toString()?.ifEmpty { "image" })
                    .setDescription(getString(R.string.download_description))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, "${UUID.randomUUID()}.jpg")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)

                dm.enqueue(request)
                Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.err_download_failed) + ": " + (e.localizedMessage ?: "error"), Toast.LENGTH_LONG).show()
            }
        }

        if (photoFullFromIntent.isNotEmpty()) {
            binding.imgPreview.visibility = android.view.View.VISIBLE
            // enable download button when we have a URL
            binding.btnDownload.visibility = android.view.View.VISIBLE
            binding.btnDownload.isEnabled = true

            Glide.with(this)
                .load(photoFullFromIntent)
                .centerCrop()
                .error(android.R.drawable.ic_menu_report_image)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                        runOnUiThread {
                            Toast.makeText(this@PhotoFormActivity, getString(R.string.err_preview_failed), Toast.LENGTH_SHORT).show()
                        }
                        return false
                    }
                    override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean = false
                })
                .into(binding.imgPreview)
        } else {
            binding.imgPreview.visibility = android.view.View.GONE
            binding.btnDownload.visibility = android.view.View.GONE
            binding.btnDownload.isEnabled = false
        }

        if (isEditMode) {
            binding.btnDelete.visibility = android.view.View.VISIBLE
            binding.btnSave.text = getString(R.string.update)
            binding.btnDelete.text = getString(R.string.delete)
            binding.btnSave.setOnClickListener { onSaveClicked() }
            binding.btnDelete.setOnClickListener { onDeleteClicked() }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val doc = firestore.collection("gallery").document(photoId).get().awaitSuspend()
                    val desc = doc.getString("description") ?: ""
                    val remote = doc.getString("remoteUrl") ?: ""
                    val title = doc.getString("title") ?: ""
                    withContext(Dispatchers.Main) {
                        binding.etDescription.setText(desc)
                        binding.etTitle.setText(title)

                        // prefer remote URL from firestore if intent didn't supply one
                        if (photoFullFromIntent.isEmpty() && remote.isNotEmpty()) {
                            photoFullFromIntent = remote
                            binding.btnDownload.visibility = android.view.View.VISIBLE
                            binding.btnDownload.isEnabled = true
                        }

                        if (remote.isNotEmpty() && binding.imgPreview.drawable == null) {
                            Glide.with(this@PhotoFormActivity).load(remote).centerCrop().into(binding.imgPreview)
                            binding.imgPreview.visibility = android.view.View.VISIBLE
                        }
                    }
                } catch (_: Exception) { }
            }
        } else {
            binding.btnDelete.visibility = android.view.View.GONE
            binding.btnSave.text = getString(R.string.save)
            binding.btnSave.setOnClickListener { onSaveClicked() }
        }
    }

    private fun onDeleteClicked() {
        if (!isEditMode) return
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(this, getString(R.string.err_not_authenticated), Toast.LENGTH_LONG).show()
            return
        }
        binding.btnDelete.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snap = firestore.collection("gallery").document(photoId).get().awaitSuspend()
                val owner = snap.getString("ownerUid")
                if (owner != uid) {
                    withContext(Dispatchers.Main) {
                        binding.btnDelete.isEnabled = true
                        Toast.makeText(this@PhotoFormActivity, getString(R.string.err_not_owner), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                firestore.collection("gallery").document(photoId).delete().awaitSuspend()
                withContext(Dispatchers.Main) {
                    setResult(RESULT_OK)
                    Toast.makeText(this@PhotoFormActivity, getString(R.string.delete_success), Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnDelete.isEnabled = true
                    Toast.makeText(this@PhotoFormActivity, getString(R.string.err_api_failed) + ": " + (e.localizedMessage ?: "error"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onSaveClicked() {
        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        val desc = binding.etDescription.text?.toString()?.trim().orEmpty()
        val remoteUrl = photoFullFromIntent

        if (title.isEmpty()) {
            Toast.makeText(this, getString(R.string.err_fill_all_fields), Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSave.isEnabled = false
        binding.btnSave.alpha = 0.6f

        val idToUse = if (isEditMode) photoId else UUID.randomUUID().toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    withContext(Dispatchers.Main) {
                        binding.btnSave.isEnabled = true
                        binding.btnSave.alpha = 1f
                        Toast.makeText(this@PhotoFormActivity, getString(R.string.err_not_authenticated), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val doc = mapOf(
                    "id" to idToUse,
                    "title" to title,
                    "description" to desc,
                    "remoteUrl" to remoteUrl,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "ownerUid" to currentUser.uid
                )

                firestore.collection("gallery").document(idToUse).set(doc).awaitSuspend()

                withContext(Dispatchers.Main) {
                    setResult(RESULT_OK)
                    Toast.makeText(this@PhotoFormActivity, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnSave.isEnabled = true
                    binding.btnSave.alpha = 1f
                    Toast.makeText(this@PhotoFormActivity, getString(R.string.err_api_failed) + ": " + (e.localizedMessage ?: "error"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
