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
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.rjc.merito.databinding.FragmentGalleryBinding
import com.rjc.merito.firebase.awaitSuspend
import com.rjc.merito.network.RetrofitApi
import com.rjc.merito.network.RetrofitProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryFragment : Fragment(), Refreshable, SearchableFragment {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!
    private val firestore = FirebaseFirestore.getInstance()
    private val photos = mutableListOf<com.rjc.merito.model.Photo>()
    private lateinit var adapter: PhotoAdapter

    private var apiPage = 1
    private val apiPerPage = 30
    private var isLoading = false
    private var isLastPage = false

    private var fsPageSize = 30L
    private var lastFsSnapshot: DocumentSnapshot? = null
    private var usingApiSource = true

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
                                resetAndRefresh()
                            } catch (e: Exception) {
                                Log.e("GalleryFragment", "delete failed", e)
                            }
                        }
                    }
                }
            }
        )

        val layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvPhotos.layoutManager = layoutManager
        binding.rvPhotos.adapter = adapter

        binding.rvPhotos.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy <= 0) return
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
                val threshold = 6
                if (!isLoading && !isLastPage) {
                    if (visibleItemCount + firstVisibleItemPosition + threshold >= totalItemCount) {
                        loadNextPage()
                    }
                }
            }
        })

        binding.emptyState.visibility = View.GONE
        binding.swipeRefresh.setOnRefreshListener { resetAndRefresh() }
        resetAndRefresh()
    }

    private fun resetAndRefresh() {
        apiPage = 1
        isLastPage = false
        lastFsSnapshot = null
        photos.clear()
        adapter.replaceAll(photos)
        usingApiSource = true
        loadFromApiOrFallback()
    }

    private fun loadNextPage() {
        if (usingApiSource) {
            apiPage += 1
            loadFromApiOrFallback()
        } else {
            loadFromFirestorePage()
        }
    }

    override fun refresh() {
        resetAndRefresh()
    }

    override fun onSearchQuery(query: String) {
        adapter.filter(query)
    }

    private fun mapApiToModel(api: com.rjc.merito.network.ApiPhoto): com.rjc.merito.model.Photo {
        val id = api.id
        val title = api.description ?: api.alt_description ?: ""
        val thumb = api.urls?.thumb ?: api.urls?.small ?: ""
        val full = api.urls?.regular ?: api.urls?.full ?: thumb
        val parts = mutableListOf<String>()
        if (title.isNotBlank()) parts.add(title)
        val alt = api.alt_description
        if (!alt.isNullOrBlank()) parts.add(alt)
        val combined = parts.joinToString(" ").trim()
        val searchText = if (combined.isNotBlank()) combined.lowercase() else title.lowercase()
        return com.rjc.merito.model.Photo(
            id = id,
            title = title,
            thumbUrl = thumb,
            fullUrl = full,
            ownerUid = null,
            searchText = searchText
        )
    }

    private fun loadFromApiOrFallback() {
        if (isLoading || isLastPage) return
        isLoading = true
        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            try {
                val retrofit = RetrofitProvider.create(requireContext(), forceNetwork = false)
                val api = retrofit.create(RetrofitApi::class.java)
                val apiList = withContext(Dispatchers.IO) {
                    api.listPhotos(page = apiPage, per_page = apiPerPage)
                }
                if (!apiList.isNullOrEmpty()) {
                    usingApiSource = true
                    val newPhotos = apiList.map { mapApiToModel(it) }
                    photos.addAll(newPhotos)
                    adapter.replaceAll(photos)
                    isLastPage = newPhotos.size < apiPerPage
                    binding.emptyState.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                    Log.d("GalleryFragment", "Loaded page=$apiPage items=${newPhotos.size} total=${photos.size}")
                    isLoading = false
                    binding.swipeRefresh.isRefreshing = false
                    return@launch
                } else {
                    Log.w("GalleryFragment", "API returned empty list on page=$apiPage, switching to Firestore")
                    usingApiSource = false
                }
            } catch (e: Exception) {
                Log.w("GalleryFragment", "API fetch failed page=$apiPage, switching to Firestore", e)
                usingApiSource = false
            }

            if (!usingApiSource) {
                loadFromFirestorePage()
            } else {
                isLoading = false
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun loadFromFirestorePage() {
        if (isLoading) return
        isLoading = true
        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            try {
                val q = withContext(Dispatchers.IO) {
                    var query = firestore.collection("gallery")
                        .orderBy("createdAt")
                        .limit(fsPageSize)
                    lastFsSnapshot?.let { query = query.startAfter(it) }
                    query.get().awaitSuspend()
                }

                val docs = q.documents
                if (docs.isEmpty()) {
                    isLastPage = true
                } else {
                    val newPhotos = mutableListOf<com.rjc.merito.model.Photo>()
                    for (doc in docs) {
                        val id = doc.getString("id") ?: doc.id
                        val title = doc.getString("title") ?: ""
                        val thumb = doc.getString("thumbUrl") ?: doc.getString("remoteUrl") ?: ""
                        val full = doc.getString("remoteUrl") ?: thumb
                        val owner = doc.getString("ownerUid")
                        val combined = listOfNotNull(title, doc.getString("description"), owner).joinToString(" ").trim()
                        newPhotos.add(com.rjc.merito.model.Photo(
                            id = id,
                            title = title,
                            thumbUrl = thumb,
                            fullUrl = full,
                            ownerUid = owner,
                            searchText = combined.lowercase()
                        ))
                    }
                    photos.addAll(newPhotos)
                    adapter.replaceAll(photos)
                    lastFsSnapshot = docs.last()
                    isLastPage = docs.size < fsPageSize
                    usingApiSource = false
                }
                binding.emptyState.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                Log.d("GalleryFragment", "Loaded firestore page items=${docs.size} total=${photos.size}")
            } catch (e: Exception) {
                Log.e("GalleryFragment", "loadFromFirestorePage failed", e)
            } finally {
                isLoading = false
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
