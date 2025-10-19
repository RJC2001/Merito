package com.rjc.merito

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.random.Random

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
        val etEmail = v.findViewById<EditText>(R.id.etSignupEmail)
        val etLoginName = v.findViewById<EditText>(R.id.etSignupLoginName)
        val etPass = v.findViewById<EditText>(R.id.etSignupPassword)
        val etPassConfirm = v.findViewById<EditText>(R.id.etSignupPasswordConfirm)
        val btnCreate = v.findViewById<Button>(R.id.btnCreateAccount)
        val progress = ProgressBar(requireContext()).apply { visibility = View.GONE }

        btnCreate.setOnClickListener {
            btnCreate.isEnabled = false
            progress.visibility = View.VISIBLE

            val emailRaw = etEmail.text.toString().trim()
            val loginNameOriginal = etLoginName.text.toString().trim()
            val password = etPass.text.toString()
            val passwordConfirm = etPassConfirm.text.toString()

            if (emailRaw.isEmpty() || loginNameOriginal.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
                btnCreate.isEnabled = true
                progress.visibility = View.GONE
                return@setOnClickListener
            }
            if (password != passwordConfirm) {
                Toast.makeText(requireContext(), getString(R.string.passwords_do_not_match), Toast.LENGTH_SHORT).show()
                btnCreate.isEnabled = true
                progress.visibility = View.GONE
                return@setOnClickListener
            }

            val email = emailRaw.lowercase()
            val usernameOriginal = loginNameOriginal

            auth.fetchSignInMethodsForEmail(email).addOnCompleteListener { fetchTask ->
                if (!fetchTask.isSuccessful) {
                    Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                    btnCreate.isEnabled = true
                    progress.visibility = View.GONE
                    return@addOnCompleteListener
                }

                val methods = fetchTask.result?.signInMethods ?: emptyList()
                if (methods.isEmpty()) {
                    auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { createTask ->
                        if (!createTask.isSuccessful) {
                            Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                            btnCreate.isEnabled = true
                            progress.visibility = View.GONE
                            return@addOnCompleteListener
                        }
                        val user = auth.currentUser
                        if (user == null) {
                            Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                            btnCreate.isEnabled = true
                            progress.visibility = View.GONE
                            return@addOnCompleteListener
                        }
                        val uid = user.uid
                        val verificationCode = Random.nextInt(100000, 999999).toString()
                        val userData = mapOf(
                            "uid" to uid,
                            "username" to usernameOriginal,
                            "email" to email,
                            "email_verified" to false,
                            "verificationCode" to verificationCode,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                        firestore.collection("users").document(uid).set(userData)
                            .addOnSuccessListener {
                                val prefs = requireActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                prefs.edit().putString("pending_uid", uid).putString("pending_email", email).putString("pending_username", usernameOriginal).apply()
                                user.sendEmailVerification().addOnCompleteListener {
                                    btnCreate.isEnabled = true
                                    progress.visibility = View.GONE
                                    navigateToVerify(uid)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e("Signup", "failed to create users doc", e)
                                Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                                btnCreate.isEnabled = true
                                progress.visibility = View.GONE
                            }
                    }
                } else {
                    if (methods.contains("password")) {
                        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { signInTask ->
                            if (signInTask.isSuccessful) {
                                val user = auth.currentUser
                                if (user == null) {
                                    Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                                    btnCreate.isEnabled = true
                                    progress.visibility = View.GONE
                                    return@addOnCompleteListener
                                }
                                user.reload().addOnSuccessListener {
                                    if (user.isEmailVerified) {
                                        Toast.makeText(requireContext(), getString(R.string.err_email_used), Toast.LENGTH_LONG).show()
                                        btnCreate.isEnabled = true
                                        progress.visibility = View.GONE
                                    } else {
                                        val uid = user.uid
                                        val verificationCode = Random.nextInt(100000, 999999).toString()
                                        val updateMap = mapOf(
                                            "verificationCode" to verificationCode,
                                            "email_verified" to false,
                                            "username" to (usernameOriginal),
                                            "email" to email,
                                            "uid" to uid,
                                            "createdAt" to FieldValue.serverTimestamp()
                                        )
                                        firestore.collection("users").document(uid).set(updateMap)
                                            .addOnSuccessListener {
                                                val prefs = requireActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                                prefs.edit().putString("pending_uid", uid).putString("pending_email", email).putString("pending_username", usernameOriginal).apply()
                                                user.sendEmailVerification().addOnCompleteListener {
                                                    btnCreate.isEnabled = true
                                                    progress.visibility = View.GONE
                                                    navigateToVerify(uid)
                                                }
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e("Signup", "failed to update users doc on resend", e)
                                                Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                                                btnCreate.isEnabled = true
                                                progress.visibility = View.GONE
                                            }
                                    }
                                }.addOnFailureListener {
                                    Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                                    btnCreate.isEnabled = true
                                    progress.visibility = View.GONE
                                }
                            } else {
                                val prefs = requireActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                val pendingUid = prefs.getString("pending_uid", null)
                                val pendingEmail = prefs.getString("pending_email", null)
                                if (pendingUid != null && pendingEmail == email) {
                                    btnCreate.isEnabled = true
                                    progress.visibility = View.GONE
                                    Toast.makeText(requireContext(), getString(R.string.verify_pending_found), Toast.LENGTH_LONG).show()
                                    navigateToVerify(pendingUid)
                                } else {
                                    auth.sendPasswordResetEmail(email).addOnCompleteListener { resetTask ->
                                        btnCreate.isEnabled = true
                                        progress.visibility = View.GONE
                                        if (resetTask.isSuccessful) {
                                            Toast.makeText(requireContext(), getString(R.string.reset_email_sent), Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(requireContext(), getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        btnCreate.isEnabled = true
                        progress.visibility = View.GONE
                        Toast.makeText(requireContext(), getString(R.string.err_email_used), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        return v
    }

    private fun navigateToVerify(uid: String) {
        val i = Intent(requireActivity(), VerifyActivity::class.java)
        i.putExtra("uid", uid)
        startActivity(i)
    }
}
