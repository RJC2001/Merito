package com.rjc.merito

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class LoginFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_login, container, false)
        val etEmailOrUsername = v.findViewById<EditText>(R.id.etLoginEmail)
        val etPass = v.findViewById<EditText>(R.id.etLoginPassword)
        val btnLogin = v.findViewById<Button>(R.id.btnLogin)
        val tvForgot = v.findViewById<TextView>(R.id.tvForgotPassword)

        btnLogin.setOnClickListener {
            val identifier = etEmailOrUsername.text.toString().trim()
            val password = etPass.text.toString()
            if (identifier.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.fill_credentials), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            if (identifier.contains("@")) {
                signInWithEmail(identifier.lowercase(), password, btnLogin)
            } else {
                signInWithUsername(identifier, password, btnLogin)
            }
        }

        tvForgot.setOnClickListener {
            showForgotPasswordDialog()
        }

        return v
    }

    private fun showForgotPasswordDialog() {
        val edit = EditText(requireContext())
        edit.hint = getString(R.string.hint_email_or_username)
        val d = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.forgot_password))
            .setView(edit)
            .setPositiveButton(getString(R.string.action_send)) { _, _ -> }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        d.setOnShowListener {
            val btn = d.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val input = edit.text.toString().trim()
                if (input.isEmpty()) {
                    edit.error = getString(R.string.fill_credentials)
                    return@setOnClickListener
                }
                btn.isEnabled = false
                sendPasswordResetForIdentifier(input) { success ->
                    btn.isEnabled = true
                    if (success) d.dismiss()
                }
            }
        }
        d.show()
    }

    private fun sendPasswordResetForIdentifier(identifier: String, onFinished: (Boolean) -> Unit) {
        val id = identifier.trim()
        if (id.contains("@")) {
            val email = id.lowercase()
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    onFinished(task.isSuccessful)
                    if (task.isSuccessful) {
                        Toast.makeText(requireContext(), getString(R.string.reset_email_sent), Toast.LENGTH_LONG).show()
                    } else {
                        Log.e("Login", "sendPasswordResetEmail failed", task.exception)
                        Toast.makeText(requireContext(), getString(R.string.verify_email_send_failed), Toast.LENGTH_LONG).show()
                    }
                }
        } else {
            val usernameLower = id.lowercase()
            firestore.collection("usernames").document(usernameLower).get()
                .addOnSuccessListener { doc ->
                    val email = doc.getString("email")
                    if (email.isNullOrEmpty()) {
                        Log.i("Login", "username lookup for reset: not found or no email for $usernameLower")
                        Toast.makeText(requireContext(), getString(R.string.reset_email_if_exists), Toast.LENGTH_LONG).show()
                        onFinished(true)
                        return@addOnSuccessListener
                    }
                    auth.sendPasswordResetEmail(email.lowercase())
                        .addOnCompleteListener { task ->
                            onFinished(task.isSuccessful)
                            if (task.isSuccessful) {
                                Toast.makeText(requireContext(), getString(R.string.reset_email_sent), Toast.LENGTH_LONG).show()
                            } else {
                                Log.e("Login", "sendPasswordResetEmail (via username) failed", task.exception)
                                Toast.makeText(requireContext(), getString(R.string.verify_email_send_failed), Toast.LENGTH_LONG).show()
                            }
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("Login", "username lookup for reset failed", e)
                    Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                    onFinished(false)
                }
        }
    }

    private fun signInWithEmail(email: String, password: String, btn: Button) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.e("Login", "signInWithEmail failed", task.exception)
                    showGenericAuthFailure(btn)
                    return@addOnCompleteListener
                }
                val user = auth.currentUser
                if (user == null) {
                    Log.e("Login", "signInWithEmail: user == null")
                    showGenericAuthFailure(btn)
                    return@addOnCompleteListener
                }

                val uid = user.uid
                firestore.collection("users").document(uid).get()
                    .addOnSuccessListener { snap ->
                        val verified = snap.getBoolean("email_verified") ?: false
                        if (!verified) {
                            auth.signOut()
                            btn.isEnabled = true
                            Toast.makeText(requireContext(), getString(R.string.verify_code_required), Toast.LENGTH_LONG).show()
                            return@addOnSuccessListener
                        }
                        val emailFromDoc = snap.getString("email") ?: email
                        ensureUserDocsAndProceed(uid, emailFromDoc, btn)
                    }
                    .addOnFailureListener { e ->
                        Log.e("Login", "users doc read failed", e)
                        showGenericAuthFailure(btn)
                    }
            }
    }

    private fun signInWithUsername(inputUsername: String, password: String, btn: Button) {
        val usernameLower = inputUsername.lowercase()
        val usernameDoc = firestore.collection("usernames").document(usernameLower)
        usernameDoc.get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Log.i("Login", "username lookup: not found for $usernameLower")
                    showGenericAuthFailure(btn)
                    return@addOnSuccessListener
                }
                val email = doc.getString("email")
                if (email.isNullOrEmpty()) {
                    Log.e("Login", "username lookup: missing email for $usernameLower")
                    showGenericAuthFailure(btn)
                    return@addOnSuccessListener
                }
                signInWithEmail(email.lowercase(), password, btn)
            }
            .addOnFailureListener { e ->
                Log.e("Login", "username lookup failed", e)
                showGenericAuthFailure(btn)
            }
    }

    private fun ensureUserDocsAndProceed(uid: String, email: String, btn: Button) {
        val usernameDoc = firestore.collection("usernames")
        val userDoc = firestore.collection("users").document(uid)

        firestore.runTransaction { tx ->
            val userSnap = tx.get(userDoc)
            val usernameOriginal = userSnap.getString("username") ?: email.substringBefore("@")
            val usernameLower = usernameOriginal.lowercase()
            val unameSnap = tx.get(usernameDoc.document(usernameLower))
            if (unameSnap.exists()) {
                val existingUid = unameSnap.getString("uid")
                if (existingUid != uid) throw Exception("USERNAME_TAKEN")
            } else {
                tx.set(usernameDoc.document(usernameLower), mapOf("uid" to uid, "email" to email, "username" to usernameOriginal))
            }
            null
        }.addOnSuccessListener {
            val prefs2 = requireActivity().getSharedPreferences("app_prefs", 0)
            prefs2.edit().remove("pending_username").remove("pending_email").apply()
            Toast.makeText(requireContext(), getString(R.string.logged_in_success), Toast.LENGTH_SHORT).show()
            try {
                val i = Intent(requireActivity(), GalleryActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(i)
                requireActivity().finish()
            } catch (t: Throwable) {
                Log.e("Login", "Failed to start GalleryActivity", t)
            } finally {
                btn.isEnabled = true
            }
        }.addOnFailureListener { ex ->
            Log.e("Login", "ensureUserDocs failed", ex)
            auth.signOut()
            btn.isEnabled = true
            if (ex.message == "USERNAME_TAKEN") {
                Toast.makeText(requireContext(), getString(R.string.err_username_taken), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.err_generic_login), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showGenericAuthFailure(btn: Button) {
        btn.isEnabled = true
        Toast.makeText(requireContext(), getString(R.string.err_generic_login), Toast.LENGTH_LONG).show()
    }
}
