package com.rjc.merito

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import com.google.firebase.auth.FirebaseAuth
import com.rjc.merito.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val auth = FirebaseAuth.getInstance()

    private val galleryTag = "gallery_fragment"
    private val profileTag = "profile_fragment"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, GalleryFragment(), galleryTag)
                .commit()
            binding.bottomNav.selectedItemId = R.id.nav_gallery
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_gallery -> {
                    showFragment(galleryTag) { GalleryFragment() }
                    true
                }
                R.id.nav_profile -> {
                    showFragment(profileTag) { ProfileFragment() }
                    true
                }
                else -> false
            }
        }

        intent?.getStringExtra("start_tab")?.let { tab ->
            when (tab) {
                "profile", "user" -> binding.bottomNav.selectedItemId = R.id.nav_profile
                "gallery", "all" -> binding.bottomNav.selectedItemId = R.id.nav_gallery
            }
        }
    }

    private fun showFragment(tag: String, factory: () -> androidx.fragment.app.Fragment) {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(tag)
        val transaction = fm.beginTransaction()
        fm.fragments.forEach { transaction.hide(it) }
        if (existing == null) {
            val f = factory()
            transaction.add(R.id.fragment_container, f, tag)
        } else {
            transaction.show(existing)
        }
        transaction.commitAllowingStateLoss()
    }

    override fun onResume() {
        super.onResume()
        val active = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (active is Refreshable) active.refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_gallery_toolbar, menu)
        val item = menu?.findItem(R.id.action_search)
        val searchView = item?.actionView as? SearchView
        searchView?.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val active = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (active is SearchableFragment) active.onSearchQuery(query ?: "")
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                val active = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (active is SearchableFragment) active.onSearchQuery(newText ?: "")
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(this, AuthActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                })
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
