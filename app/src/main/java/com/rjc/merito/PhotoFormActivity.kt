package com.rjc.merito

import android.graphics.drawable.Drawable
import android.os.Bundle
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

        if (photoFullFromIntent.isNotEmpty()) {
            binding.imgPreview.visibility = android.view.View.VISIBLE
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
        }

        if (isEditMode) {
            binding.btnSave.text = getString(R.string.delete)
            binding.btnSave.setOnClickListener { onDeleteClicked() }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val doc = firestore.collection("gallery").document(photoId).get().awaitSuspend()
                    val desc = doc.getString("description") ?: ""
                    val remote = doc.getString("remoteUrl") ?: ""
                    withContext(Dispatchers.Main) {
                        binding.etDescription.setText(desc)
                        if (remote.isNotEmpty() && binding.imgPreview.drawable == null) {
                            Glide.with(this@PhotoFormActivity).load(remote).centerCrop().into(binding.imgPreview)
                            binding.imgPreview.visibility = android.view.View.VISIBLE
                        }
                    }
                } catch (_: Exception) { }
            }
        } else {
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
        binding.btnSave.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snap = firestore.collection("gallery").document(photoId).get().awaitSuspend()
                val owner = snap.getString("ownerUid")
                if (owner != uid) {
                    withContext(Dispatchers.Main) {
                        binding.btnSave.isEnabled = true
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
                    binding.btnSave.isEnabled = true
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
                android.util.Log.d("PhotoForm", "Before save: currentUser=${currentUser?.uid} email=${currentUser?.email}")
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

                android.util.Log.d("PhotoForm", "Document to write: ownerUid=${currentUser.uid} id=$idToUse")
                firestore.collection("gallery").document(idToUse).set(doc).awaitSuspend()
                android.util.Log.d("PhotoForm", "Saved doc ownerUid=${currentUser.uid} id=$idToUse")

                withContext(Dispatchers.Main) {
                    setResult(RESULT_OK)
                    Toast.makeText(this@PhotoFormActivity, getString(R.string.save_success), Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                android.util.Log.e("PhotoForm", "Save failed", e)
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
