package com.rjc.merito.firebase

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.awaitSuspend(): T = suspendCancellableCoroutine { cont ->
    this.addOnSuccessListener { result: T -> cont.resume(result) }
    this.addOnFailureListener { ex: Exception -> cont.resumeWithException(ex) }
    this.addOnCanceledListener { cont.cancel() }
}
