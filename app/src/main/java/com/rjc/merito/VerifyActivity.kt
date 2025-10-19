package com.rjc.merito

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class VerifyActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var uid: String? = null
    private lateinit var tvInfo: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnResend: Button
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_verify)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        uid = intent.getStringExtra("uid")

        val prefs = getSharedPreferences("app_prefs", 0)
        if (uid.isNullOrEmpty()) {
            val pendingUid = prefs.getString("pending_uid", null)
            if (!pendingUid.isNullOrEmpty()) uid = pendingUid
        }

        tvInfo = findViewById(R.id.tvVerifyInfo)
        btnCheck = findViewById(R.id.btnVerifyCheck)
        btnResend = findViewById(R.id.btnVerifyResend)
        btnCancel = findViewById(R.id.btnVerifyCancel)

        tvInfo.text = getString(R.string.verify_email_instructions)

        btnCheck.setOnClickListener {
            btnCheck.isEnabled = false
            val currentUser = auth.currentUser
            if (currentUser == null) {
                Toast.makeText(this, getString(R.string.verify_not_signed_in), Toast.LENGTH_LONG).show()
                btnCheck.isEnabled = true
                return@setOnClickListener
            }
            currentUser.reload().addOnCompleteListener { reloadTask ->
                if (!reloadTask.isSuccessful) {
                    Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                    btnCheck.isEnabled = true
                    return@addOnCompleteListener
                }
                if (currentUser.isEmailVerified) {
                    val myUid = uid ?: currentUser.uid
                    val userRef = firestore.collection("users").document(myUid)
                    userRef.get()
                        .addOnSuccessListener { userSnap ->
                            val usernameOriginal = userSnap.getString("username") ?: ""
                            val email = userSnap.getString("email") ?: currentUser.email ?: ""
                            val usernameLower = usernameOriginal.lowercase()
                            val usernameRef = firestore.collection("usernames").document(usernameLower)

                            firestore.runTransaction { tx ->
                                val uSnap = tx.get(userRef)
                                if (!uSnap.exists()) throw Exception("NO_USER")
                                val unameSnap = tx.get(usernameRef)
                                val existingUid = if (unameSnap.exists()) unameSnap.getString("uid") else null
                                if (existingUid != null && existingUid != myUid) throw Exception("USERNAME_TAKEN")
                                if (!unameSnap.exists()) {
                                    tx.set(usernameRef, mapOf("uid" to myUid, "email" to email, "username" to usernameOriginal))
                                }
                                val data = uSnap.data?.toMutableMap() ?: mutableMapOf<String, Any>()
                                data["email_verified"] = true
                                data.remove("verificationCode")
                                data["verifiedAt"] = FieldValue.serverTimestamp()
                                tx.set(userRef, data)
                                null
                            }.addOnSuccessListener {
                                Toast.makeText(this, getString(R.string.verify_code_success), Toast.LENGTH_SHORT).show()
                                prefs.edit().remove("pending_uid").remove("pending_email").remove("pending_username").apply()
                                try {
                                    val i = Intent(this, GalleryActivity::class.java)
                                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    startActivity(i)
                                    finish()
                                } catch (t: Throwable) {
                                    Log.e("VerifyAct", "start gallery failed", t)
                                } finally {
                                    btnCheck.isEnabled = true
                                }
                            }.addOnFailureListener { txEx ->
                                Log.e("VerifyAct", "transaction failed", txEx)
                                val msg = if (txEx.message?.contains("USERNAME_TAKEN") == true) getString(R.string.err_username_taken) else getString(R.string.err_generic)
                                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                                btnCheck.isEnabled = true
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("VerifyAct", "user read failed", e)
                            Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                            btnCheck.isEnabled = true
                        }
                } else {
                    Toast.makeText(this, getString(R.string.verify_email_failed), Toast.LENGTH_LONG).show()
                    btnCheck.isEnabled = true
                }
            }
        }

        btnResend.setOnClickListener {
            btnResend.isEnabled = false
            val currentUser = auth.currentUser
            if (currentUser != null) {
                currentUser.sendEmailVerification().addOnCompleteListener {
                    btnResend.isEnabled = true
                    if (it.isSuccessful) Toast.makeText(this, getString(R.string.verify_email_sent), Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this, getString(R.string.verify_email_send_failed), Toast.LENGTH_LONG).show()
                }
            } else {
                val pendingEmail = prefs.getString("pending_email", null)
                if (!pendingEmail.isNullOrEmpty()) {
                    auth.sendPasswordResetEmail(pendingEmail).addOnCompleteListener { t ->
                        btnResend.isEnabled = true
                        if (t.isSuccessful) Toast.makeText(this, getString(R.string.reset_email_sent), Toast.LENGTH_LONG).show()
                        else Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                    }
                } else {
                    btnResend.isEnabled = true
                    Toast.makeText(this, getString(R.string.verify_no_pending), Toast.LENGTH_LONG).show()
                }
            }
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }
}
