package com.rjc.merito

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class VerifyActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    private lateinit var btnVerifyCheck: Button
    private lateinit var btnResend: Button
    private lateinit var btnCancel: Button
    private lateinit var btnConfirmRegistration: Button

    private lateinit var etVerifyUsername: TextInputEditText
    private lateinit var etVerifyPassword: TextInputEditText
    private lateinit var etVerifyPasswordConfirm: TextInputEditText

    private lateinit var layoutUsername: View
    private lateinit var layoutPassword: View
    private lateinit var layoutPasswordConfirm: View

    private var lastCheck = 0L
    private var lastResend = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        setContentView(R.layout.activity_verify)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        btnVerifyCheck = findViewById(R.id.btnVerifyCheck)
        btnResend = findViewById(R.id.btnVerifyResend)
        btnCancel = findViewById(R.id.btnCancelVerify)
        btnConfirmRegistration = findViewById(R.id.btnConfirmRegistration)

        etVerifyUsername = findViewById(R.id.etVerifyUsername)
        etVerifyPassword = findViewById(R.id.etVerifyPassword)
        etVerifyPasswordConfirm = findViewById(R.id.etVerifyPasswordConfirm)

        layoutUsername = findViewById(R.id.layoutUsername)
        layoutPassword = findViewById(R.id.layoutPassword)
        layoutPasswordConfirm = findViewById(R.id.layoutPasswordConfirm)

        layoutUsername.visibility = View.GONE
        layoutPassword.visibility = View.GONE
        layoutPasswordConfirm.visibility = View.GONE
        btnConfirmRegistration.visibility = View.GONE

        checkAndAutoSignInIfVerified()

        val prefs = getSharedPreferences("app_prefs", 0)

        btnVerifyCheck.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheck < 1500L) return@setOnClickListener
            lastCheck = now
            checkAndAutoSignInIfVerified()
        }

        btnResend.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastResend < Constants.RESEND_COOLDOWN_MS) {
                Toast.makeText(this, getString(R.string.err_wait_before_resend), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastResend = now
            btnResend.isEnabled = false

            val current = auth.currentUser
            if (current != null) {
                current.sendEmailVerification().addOnCompleteListener { task ->
                    btnResend.isEnabled = true
                    if (task.isSuccessful) {
                        Toast.makeText(this, getString(R.string.verify_email_sent), Toast.LENGTH_SHORT).show()
                        Log.i("VerifyActivity", "sendEmailVerification: success")
                    } else {
                        val m = task.exception?.localizedMessage ?: getString(R.string.err_generic)
                        Log.e("VerifyActivity", "sendEmailVerification failed: $m", task.exception)
                        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
                    }
                }
                return@setOnClickListener
            }

            val pendingEmail = prefs.getString("pending_email", null)
            if (pendingEmail.isNullOrEmpty()) {
                btnResend.isEnabled = true
                Toast.makeText(this, getString(R.string.verify_not_signed_in), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            auth.fetchSignInMethodsForEmail(pendingEmail).addOnCompleteListener { fetchTask ->
                btnResend.isEnabled = true
                if (!fetchTask.isSuccessful) {
                    Log.e("VerifyActivity", "fetchSignInMethodsForEmail failed", fetchTask.exception)
                    Toast.makeText(this, fetchTask.exception?.localizedMessage ?: getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }
                val methods = fetchTask.result?.signInMethods ?: emptyList()
                Log.i("VerifyActivity", "fetchSignInMethodsForEmail for $pendingEmail: $methods")
                if (methods.contains("password")) {
                    auth.signInWithEmailAndPassword(pendingEmail, Constants.TEMP_PASSWORD).addOnCompleteListener { signInTask ->
                        if (signInTask.isSuccessful) {
                            val userNow = auth.currentUser
                            if (userNow != null) {
                                userNow.sendEmailVerification().addOnCompleteListener { sendTask ->
                                    if (sendTask.isSuccessful) {
                                        Toast.makeText(this, getString(R.string.verify_email_sent), Toast.LENGTH_SHORT).show()
                                        Log.i("VerifyActivity", "resend via TEMP sign-in success")
                                    } else {
                                        val m = sendTask.exception?.localizedMessage ?: getString(R.string.err_generic)
                                        Log.e("VerifyActivity", "resend sendEmailVerification failed: $m", sendTask.exception)
                                        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                Log.e("VerifyActivity", "auth.currentUser null after TEMP sign-in")
                                Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                            }
                        } else {
                            val m = signInTask.exception?.localizedMessage ?: getString(R.string.err_generic)
                            Log.w("VerifyActivity", "TEMP sign-in failed: $m", signInTask.exception)
                            Toast.makeText(this, getString(R.string.err_email_in_use_try_reset), Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(this, getString(R.string.err_email_in_use_try_reset_or_oauth), Toast.LENGTH_LONG).show()
                }
            }
        }

        btnCancel.setOnClickListener {
            startActivity(Intent(this, AuthActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }

        btnConfirmRegistration.setOnClickListener {
            btnConfirmRegistration.isEnabled = false

            val username = etVerifyUsername.text?.toString()?.trim() ?: ""
            val pwd = etVerifyPassword.text?.toString() ?: ""
            val pwdConfirm = etVerifyPasswordConfirm.text?.toString() ?: ""

            if (username.isEmpty() || pwd.length < 6) {
                Toast.makeText(this, getString(R.string.err_fill_username_password_min6), Toast.LENGTH_LONG).show()
                btnConfirmRegistration.isEnabled = true
                return@setOnClickListener
            }
            if (pwd != pwdConfirm) {
                Toast.makeText(this, getString(R.string.err_passwords_mismatch), Toast.LENGTH_LONG).show()
                btnConfirmRegistration.isEnabled = true
                return@setOnClickListener
            }

            val currentUser = auth.currentUser
            if (currentUser == null) {
                Toast.makeText(this, getString(R.string.verify_not_signed_in), Toast.LENGTH_LONG).show()
                btnConfirmRegistration.isEnabled = true
                return@setOnClickListener
            }

            reserveUsernameThenProceed(currentUser, username, pwd)
        }
    }

    private fun reserveUsernameThenProceed(user: com.google.firebase.auth.FirebaseUser, username: String, newPassword: String) {
        val uid = user.uid
        val usernameLower = username.lowercase()
        val usernameRef = firestore.collection("usernames").document(usernameLower)

        Log.i("VerifyActivity", "Attempting username reservation for '$usernameLower' by uid=$uid")
        firestore.runTransaction { tx ->
            val unameSnap = tx.get(usernameRef)
            if (unameSnap.exists()) {
                val existingUid = unameSnap.getString("uid")
                val reservedBy = unameSnap.getString("reservedBy")
                if (existingUid != null && existingUid != uid) throw Exception("USERNAME_TAKEN")
                if (reservedBy != null && reservedBy != uid) throw Exception("USERNAME_RESERVED")
            } else {
                tx.set(usernameRef, mapOf("reservedBy" to uid, "reservedAt" to FieldValue.serverTimestamp()))
            }
            null
        }.addOnSuccessListener {
            Log.i("VerifyActivity", "Username reservation ok for $usernameLower")
            reauthUpdatePasswordRefreshTokenAndCommit(user, username, newPassword, usernameRef)
        }.addOnFailureListener { ex ->
            btnConfirmRegistration.isEnabled = true
            Log.e("VerifyActivity", "reserve username failed", ex)
            if (ex.message?.contains("USERNAME_TAKEN") == true) {
                Toast.makeText(this, getString(R.string.err_username_taken), Toast.LENGTH_LONG).show()
            } else if (ex.message?.contains("USERNAME_RESERVED") == true) {
                Toast.makeText(this, getString(R.string.err_username_reserved), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun reauthUpdatePasswordRefreshTokenAndCommit(
        user: com.google.firebase.auth.FirebaseUser,
        username: String,
        newPassword: String,
        usernameRef: com.google.firebase.firestore.DocumentReference
    ) {
        val email = user.email ?: ""
        val uid = user.uid
        val userRef = firestore.collection("users").document(uid)
        val prefs = getSharedPreferences("app_prefs", 0)

        Log.i("VerifyActivity", "Starting reauth for uid=$uid")
        val credential = EmailAuthProvider.getCredential(email, Constants.TEMP_PASSWORD)
        user.reauthenticate(credential).addOnCompleteListener { reauthTask ->
            if (!reauthTask.isSuccessful) {
                Log.w("VerifyActivity", "reauth failed", reauthTask.exception)
                auth.signInWithEmailAndPassword(email, Constants.TEMP_PASSWORD).addOnCompleteListener { signInTask ->
                    if (!signInTask.isSuccessful) {
                        btnConfirmRegistration.isEnabled = true
                        Log.e("VerifyActivity", "cannot reauth/sign-in with TEMP_PASSWORD", signInTask.exception)
                        Toast.makeText(this, getString(R.string.err_recent_login_required), Toast.LENGTH_LONG).show()
                        rollbackUsernameReservationIfOwned(usernameRef, uid)
                        return@addOnCompleteListener
                    }
                    val signed = auth.currentUser
                    if (signed == null) {
                        btnConfirmRegistration.isEnabled = true
                        Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                        rollbackUsernameReservationIfOwned(usernameRef, uid)
                        return@addOnCompleteListener
                    }
                    doUpdatePasswordThenRefreshTokenAndCommit(signed, username, newPassword, userRef, usernameRef, prefs)
                }
                return@addOnCompleteListener
            }
            Log.i("VerifyActivity", "reauth success for uid=$uid")
            doUpdatePasswordThenRefreshTokenAndCommit(user, username, newPassword, userRef, usernameRef, prefs)
        }
    }

    private fun doUpdatePasswordThenRefreshTokenAndCommit(
        user: com.google.firebase.auth.FirebaseUser,
        username: String,
        newPassword: String,
        userRef: com.google.firebase.firestore.DocumentReference,
        usernameRef: com.google.firebase.firestore.DocumentReference,
        prefs: android.content.SharedPreferences
    ) {
        val uid = user.uid
        val email = user.email ?: ""

        Log.i("VerifyActivity", "Updating password for uid=$uid")
        user.updatePassword(newPassword).addOnCompleteListener { pwTask ->
            if (!pwTask.isSuccessful) {
                btnConfirmRegistration.isEnabled = true
                Log.e("VerifyActivity", "updatePassword failed", pwTask.exception)
                Toast.makeText(this, pwTask.exception?.localizedMessage ?: getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                rollbackUsernameReservationIfOwned(usernameRef, uid)
                return@addOnCompleteListener
            }
            Log.i("VerifyActivity", "updatePassword success for uid=$uid")

            auth.currentUser?.reload()?.addOnCompleteListener { reloadTask ->
                if (!reloadTask.isSuccessful) {
                    Log.w("VerifyActivity", "currentUser.reload failed", reloadTask.exception)
                } else {
                    Log.i("VerifyActivity", "currentUser.reload succeeded")
                }

                val maybeCurrent = auth.currentUser
                if (maybeCurrent == null) {
                    btnConfirmRegistration.isEnabled = true
                    Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                    rollbackUsernameReservationIfOwned(usernameRef, uid)
                    return@addOnCompleteListener
                }

                Log.i("VerifyActivity", "Forcing token refresh (getIdToken(true))")
                maybeCurrent.getIdToken(true).addOnCompleteListener { tokenTask ->
                    if (!tokenTask.isSuccessful) {
                        Log.w("VerifyActivity", "getIdToken(true) failed, fallback to signOut/signIn", tokenTask.exception)
                        auth.signOut()
                        auth.signInWithEmailAndPassword(email, newPassword).addOnCompleteListener { signInNew ->
                            if (!signInNew.isSuccessful) {
                                btnConfirmRegistration.isEnabled = true
                                Log.e("VerifyActivity", "signIn with new password failed", signInNew.exception)
                                Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                                rollbackUsernameReservationIfOwned(usernameRef, uid)
                                return@addOnCompleteListener
                            }
                            val cur = auth.currentUser ?: run {
                                btnConfirmRegistration.isEnabled = true
                                Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
                                rollbackUsernameReservationIfOwned(usernameRef, uid)
                                return@addOnCompleteListener
                            }
                            cur.getIdToken(true).addOnCompleteListener { tokenTask2 ->
                                attemptCommitWithLogging(cur, tokenTask2, userRef, usernameRef, username, uid, prefs)
                            }
                        }
                        return@addOnCompleteListener
                    }
                    attemptCommitWithLogging(maybeCurrent, tokenTask, userRef, usernameRef, username, uid, prefs)
                }
            }
        }
    }

    private fun attemptCommitWithLogging(
        current: com.google.firebase.auth.FirebaseUser,
        tokenTask: com.google.android.gms.tasks.Task<GetTokenResult>?,
        userRef: com.google.firebase.firestore.DocumentReference,
        usernameRef: com.google.firebase.firestore.DocumentReference,
        username: String,
        uid: String,
        prefs: android.content.SharedPreferences
    ) {
        try {
            val tokenClaims = tokenTask?.result?.claims
            Log.i("VerifyActivity", "current.isEmailVerified = ${current.isEmailVerified}")
            Log.i("VerifyActivity", "idToken claims = $tokenClaims")
        } catch (e: Exception) {
            Log.w("VerifyActivity", "Failed reading token claims", e)
        }

        Log.i("VerifyActivity", "Attempting Firestore transaction commit for uid=$uid")
        firestore.runTransaction { tx ->
            val uSnap = tx.get(userRef)
            val unameSnap = tx.get(usernameRef)
            val existingUid = if (unameSnap.exists()) unameSnap.getString("uid") else null
            val reservedBy = if (unameSnap.exists()) unameSnap.getString("reservedBy") else null

            if (existingUid != null && existingUid != uid) throw Exception("USERNAME_TAKEN")
            if (existingUid == null) {
                tx.set(usernameRef, mapOf("uid" to uid, "email" to current.email, "username" to username, "claimedAt" to FieldValue.serverTimestamp()), com.google.firebase.firestore.SetOptions.merge())
            } else {
                tx.set(usernameRef, mapOf("email" to current.email), com.google.firebase.firestore.SetOptions.merge())
            }
            val data = uSnap.data?.toMutableMap() ?: mutableMapOf()
            data["username"] = username
            data["email_verified"] = true
            data["pending"] = false
            data["verifiedAt"] = FieldValue.serverTimestamp()
            tx.set(userRef, data, com.google.firebase.firestore.SetOptions.merge())
            null
        }.addOnSuccessListener {
            Log.i("VerifyActivity", "Firestore transaction succeeded for uid=$uid")
            val profileUpdates = UserProfileChangeRequest.Builder().setDisplayName(username).build()
            current.updateProfile(profileUpdates)
            prefs.edit().remove("pending_uid").remove("pending_email").apply()
            btnConfirmRegistration.isEnabled = true
            Toast.makeText(this, getString(R.string.verify_code_success), Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }.addOnFailureListener { ex ->
            btnConfirmRegistration.isEnabled = true
            Log.e("VerifyActivity", "Firestore transaction failed", ex)
            if (ex is com.google.firebase.firestore.FirebaseFirestoreException) {
                Log.e("VerifyActivity", "FirestoreException code=${ex.code} message=${ex.message}")
            }
            rollbackUsernameReservationIfOwned(usernameRef, uid)
            if (ex.message?.contains("USERNAME_TAKEN") == true) {
                Toast.makeText(this, getString(R.string.err_username_taken), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.err_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun rollbackUsernameReservationIfOwned(usernameRef: com.google.firebase.firestore.DocumentReference, uid: String) {
        usernameRef.get().addOnSuccessListener { snap ->
            if (!snap.exists()) return@addOnSuccessListener
            val reservedBy = snap.getString("reservedBy")
            val existingUid = snap.getString("uid")
            if (existingUid != null && existingUid != uid) return@addOnSuccessListener
            if (reservedBy == uid && existingUid == null) {
                usernameRef.delete().addOnSuccessListener {
                    Log.i("VerifyActivity", "Rolled back username reservation for $uid")
                }.addOnFailureListener { ex ->
                    Log.w("VerifyActivity", "Rollback delete failed", ex)
                }
            }
        }.addOnFailureListener { ex ->
            Log.w("VerifyActivity", "Rollback check failed", ex)
        }
    }

    private fun checkAndAutoSignInIfVerified() {
        val prefs = getSharedPreferences("app_prefs", 0)
        val pendingEmail = prefs.getString("pending_email", null)
        val current = auth.currentUser

        if (current != null) {
            current.reload().addOnCompleteListener { t ->
                if (!t.isSuccessful) {
                    Log.w("VerifyActivity", "reload current failed", t.exception)
                    return@addOnCompleteListener
                }
                Log.i("VerifyActivity", "current.reload done; isEmailVerified=${current.isEmailVerified}")
                if (current.isEmailVerified) {
                    onEmailVerifiedState(current)
                } else {
                    btnResend.visibility = View.VISIBLE
                }
            }
            return
        }

        if (pendingEmail.isNullOrEmpty()) {
            Log.i("VerifyActivity", "No pending_email in prefs")
            return
        }

        Log.i("VerifyActivity", "Attempting auto sign-in for pending_email=$pendingEmail")
        auth.signInWithEmailAndPassword(pendingEmail, Constants.TEMP_PASSWORD)
            .addOnCompleteListener { signInTask ->
                if (!signInTask.isSuccessful) {
                    Log.w("VerifyActivity", "auto sign-in failed", signInTask.exception)
                    return@addOnCompleteListener
                }
                val userNow = auth.currentUser ?: return@addOnCompleteListener
                userNow.reload().addOnCompleteListener { reloadTask ->
                    if (!reloadTask.isSuccessful) {
                        Log.w("VerifyActivity", "reload after auto sign-in failed", reloadTask.exception)
                        return@addOnCompleteListener
                    }
                    Log.i("VerifyActivity", "auto sign-in reload done; isEmailVerified=${userNow.isEmailVerified}")
                    if (userNow.isEmailVerified) {
                        onEmailVerifiedState(userNow)
                    } else {
                        btnResend.visibility = View.VISIBLE
                    }
                }
            }
    }

    private fun onEmailVerifiedState(user: com.google.firebase.auth.FirebaseUser) {
        layoutUsername.visibility = View.VISIBLE
        layoutPassword.visibility = View.VISIBLE
        layoutPasswordConfirm.visibility = View.VISIBLE
        btnConfirmRegistration.visibility = View.VISIBLE
        btnResend.visibility = View.GONE
        Log.i("VerifyActivity", "UI unlocked for username/password input")
    }
}
