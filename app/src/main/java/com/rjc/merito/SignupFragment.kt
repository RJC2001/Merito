package com.rjc.merito

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class SignupFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.fragment_signup, container, false)
        val etEmail = v.findViewById<TextInputEditText>(R.id.etSignupEmail)
        val btnCreate = v.findViewById<MaterialButton>(R.id.btnCreateAccount)

        btnCreate.setOnClickListener {
            btnCreate.isEnabled = false
            val email = etEmail.text?.toString()?.trim()?.lowercase() ?: ""
            if (email.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
                btnCreate.isEnabled = true
                return@setOnClickListener
            }

            val tempPassword = Constants.TEMP_PASSWORD

            auth.createUserWithEmailAndPassword(email, tempPassword).addOnCompleteListener { t ->
                btnCreate.isEnabled = true
                if (!t.isSuccessful) {
                    val msg = t.exception?.localizedMessage ?: getString(R.string.err_generic)
                    if (msg.contains("already in use", ignoreCase = true) ||
                        msg.contains("The email address is already in use", ignoreCase = true)
                    ) {
                        requireActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit().putString("pending_email", email).apply()
                        Toast.makeText(requireContext(), getString(R.string.err_email_in_use_try_resend_or_reset), Toast.LENGTH_LONG).show()
                        startActivity(Intent(requireActivity(), VerifyActivity::class.java))
                        requireActivity().finish()
                    } else {
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                    return@addOnCompleteListener
                }

                val user = auth.currentUser
                if (user == null) {
                    Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }

                val uid = user.uid
                val userData = mapOf(
                    "uid" to uid,
                    "email" to email,
                    "email_verified" to false,
                    "pending" to true,
                    "createdAt" to FieldValue.serverTimestamp()
                )

                firestore.collection("users").document(uid).set(userData)
                    .addOnSuccessListener {
                        requireActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit().putString("pending_uid", uid).putString("pending_email", email).apply()

                        user.sendEmailVerification()
                        startActivity(Intent(requireActivity(), VerifyActivity::class.java))
                        requireActivity().finish()
                    }
                    .addOnFailureListener {
                        user.delete()
                        Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                    }
            }
        }

        return v
    }
}
