package com.rjc.merito

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.rjc.merito.databinding.FragmentProfileBinding
import com.rjc.merito.firebase.awaitSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class ProfileFragment : Fragment(), Refreshable {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val photos = mutableListOf<com.rjc.merito.model.Photo>()
    private var adapter: PhotoAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvUserPhotos.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.emptyState.visibility = View.GONE
        binding.swipeRefresh.setOnRefreshListener { refresh() }

        val currentUserId = auth.currentUser?.uid
        adapter = PhotoAdapter(
            items = photos,
            currentUserId = currentUserId,
            onClick = { photo ->
                if (photo.ownerUid != null && photo.ownerUid == currentUserId) {
                    startActivity(Intent(requireContext(), PhotoFormActivity::class.java).apply {
                        putExtra("photo_id", photo.id)
                        putExtra("photo_full", photo.fullUrl)
                        putExtra("photo_title", photo.title)
                    })
                } else {
                    startActivity(Intent(requireContext(), PhotoFormActivity::class.java).apply {
                        putExtra("photo_full", photo.fullUrl)
                        putExtra("readonly", true)
                    })
                }
            },
            onDelete = { photo -> confirmAndDelete(photo.id) }
        )
        binding.rvUserPhotos.adapter = adapter
        refresh()
    }

    override fun refresh() { loadUserPhotos() }

    private fun loadUserPhotos() {
        val uid = auth.currentUser?.uid
        Log.d("Profile", "Loading for uid=$uid")
        if (uid == null) {
            photos.clear()
            adapter?.notifyDataSetChanged()
            binding.emptyState.visibility = View.GONE
            return
        }

        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    firestore.collection("gallery")
                        .whereEqualTo("ownerUid", uid)
                        .get()
                        .awaitSuspend()
                }

                val temp = mutableListOf<Pair<com.rjc.merito.model.Photo, Long?>>()

                val docs = snapshot.documents
                for (doc in docs) {
                    val id = doc.getString("id") ?: doc.id
                    val title = doc.getString("title") ?: ""
                    val thumb = doc.getString("thumbUrl") ?: doc.getString("remoteUrl") ?: ""
                    val full = doc.getString("remoteUrl") ?: thumb
                    val owner = doc.getString("ownerUid")
                    val ts = doc.getTimestamp("createdAt")?.toDate()?.time
                    val photo = com.rjc.merito.model.Photo(id = id, title = title, thumbUrl = thumb, fullUrl = full, ownerUid = owner)
                    temp.add(photo to ts)
                }

                temp.sortWith(compareByDescending<Pair<com.rjc.merito.model.Photo, Long?>> { it.second ?: Long.MIN_VALUE })

                photos.clear()
                photos.addAll(temp.map { it.first })

                adapter?.notifyDataSetChanged()
                binding.emptyState.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                Log.d("Profile", "Loaded user photos=${photos.size} for uid=$uid")
            } catch (e: Exception) {
                Log.e("Profile", "loadUserPhotos failed", e)
                binding.emptyState.visibility = View.GONE
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun confirmAndDelete(photoId: String) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            Toast.makeText(requireContext(), getString(R.string.err_not_authenticated), Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                val snap = withContext(Dispatchers.IO) {
                    firestore.collection("gallery").document(photoId).get().awaitSuspend()
                }
                val owner = snap.getString("ownerUid")
                Log.d("Delete", "doc=$photoId owner=$owner current=$uid")
                if (owner == null) {
                    Toast.makeText(requireContext(), getString(R.string.err_api_failed), Toast.LENGTH_LONG).show()
                    return@launch
                }
                if (owner != uid) {
                    Toast.makeText(requireContext(), getString(R.string.err_not_owner), Toast.LENGTH_LONG).show()
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    firestore.collection("gallery").document(photoId).delete().awaitSuspend()
                }

                loadUserPhotos()
            } catch (e: Exception) {
                Log.e("Delete", "delete failed", e)
                Toast.makeText(requireContext(), getString(R.string.err_api_failed) + ": " + (e.localizedMessage ?: "error"), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}