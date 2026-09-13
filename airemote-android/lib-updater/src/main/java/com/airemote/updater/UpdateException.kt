package com.airemote.updater

import java.io.IOException
import kotlinx.serialization.SerializationException

sealed class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String, cause: Throwable? = null) : UpdateException(message, cause)
    class ManifestInvalid(message: String) : UpdateException(message)
    class ChannelMismatch(expected: String, actual: String) : UpdateException("channel mismatch: expected=$expected actual=$actual")
    class ChecksumMismatch(expected: String, actual: String) : UpdateException("sha256 mismatch: expected=$expected actual=$actual")
    class InstallNotResolved(message: String, cause: Throwable? = null) : UpdateException(message, cause)
    class Storage(message: String, cause: Throwable? = null) : UpdateException(message, cause)

    companion object {
        fun from(throwable: Throwable): UpdateException = when (throwable) {
            is UpdateException -> throwable
            is SerializationException -> ManifestInvalid("manifest JSON is invalid: ${throwable.message}")
            is IOException -> Network(throwable.message ?: "network error", throwable)
            else -> Network(throwable.message ?: throwable::class.java.simpleName, throwable)
        }
    }
}
