package com.rjc.merito

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.rjc.merito.databinding.FragmentGalleryBinding
import com.rjc.merito.firebase.awaitSuspend
import com.rjc.merito.network.RetrofitApi
import com.rjc.merito.network.RetrofitProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryFragment : Fragment(), Refreshable {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!
    private val firestore = FirebaseFirestore.getInstance()
    private val photos = mutableListOf<com.rjc.merito.model.Photo>()
    private lateinit var adapter: PhotoAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid

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
            onDelete = { photo ->
                photo.ownerUid?.let { uid ->
                    if (uid == currentUserId) {
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    firestore.collection("gallery").document(photo.id).delete().awaitSuspend()
                                }
                                refresh()
                            } catch (e: Exception) {
                                Log.e("GalleryFragment", "delete failed", e)
                            }
                        }
                    }
                }
            }
        )

        binding.rvPhotos.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvPhotos.adapter = adapter
        binding.emptyState.visibility = View.GONE
        binding.swipeRefresh.setOnRefreshListener { refresh() }
        refresh()
    }

    override fun refresh() {
        loadFromApiOrFallback()
    }

    private fun mapApiToModel(api: com.rjc.merito.network.ApiPhoto): com.rjc.merito.model.Photo {
        val id = api.id
        val title = api.description ?: api.alt_description ?: ""
        val thumb = api.urls?.thumb ?: api.urls?.small ?: ""
        val full = api.urls?.regular ?: api.urls?.full ?: thumb
        return com.rjc.merito.model.Photo(id = id, title = title, thumbUrl = thumb, fullUrl = full, ownerUid = null)
    }

    private fun loadFromApiOrFallback() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            try {
                val retrofit = RetrofitProvider.create(requireContext(), forceNetwork = false)
                val api = retrofit.create(RetrofitApi::class.java)
                val apiList = withContext(Dispatchers.IO) { api.listPhotos(page = 1, per_page = 30) }
                if (!apiList.isNullOrEmpty()) {
                    photos.clear()
                    photos.addAll(apiList.map { mapApiToModel(it) })
                    adapter.notifyDataSetChanged()
                    binding.emptyState.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                    Log.d("GalleryFragment", "Loaded ${photos.size} photos from API")
                    binding.swipeRefresh.isRefreshing = false
                    return@launch
                } else {
                    Log.w("GalleryFragment", "API returned empty list, falling back to Firestore")
                }
            } catch (e: Exception) {
                Log.w("GalleryFragment", "API fetch failed, falling back to Firestore", e)
            }

            try {
                val snapshot = withContext(Dispatchers.IO) {
                    firestore.collection("gallery")
                        .orderBy("createdAt")
                        .get()
                        .awaitSuspend()
                }
                photos.clear()
                val docs: List<com.google.firebase.firestore.DocumentSnapshot> = snapshot.documents
                for (doc in docs) {
                    val id = doc.getString("id") ?: doc.id
                    val title = doc.getString("title") ?: ""
                    val thumb = doc.getString("thumbUrl") ?: doc.getString("remoteUrl") ?: ""
                    val full = doc.getString("remoteUrl") ?: thumb
                    val owner = doc.getString("ownerUid")
                    photos.add(com.rjc.merito.model.Photo(id = id, title = title, thumbUrl = thumb, fullUrl = full, ownerUid = owner))
                }
                adapter.notifyDataSetChanged()
                binding.emptyState.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                Log.d("GalleryFragment", "Loaded firestore photos=${photos.size}")
            } catch (e: Exception) {
                Log.e("GalleryFragment", "loadAllPhotos failed", e)
                binding.emptyState.visibility = View.VISIBLE
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
