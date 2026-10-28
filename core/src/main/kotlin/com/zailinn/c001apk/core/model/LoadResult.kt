package com.zailinn.c001apk.core.model

sealed interface LoadResult<out T> {
    data class Success<T>(val value: T, val isPublic: Boolean = false) : LoadResult<T>
    data object Empty : LoadResult<Nothing>
    data class Error(val message: String, val kind: com.zailinn.c001apk.core.net.ApiFailureKind? = null) : LoadResult<Nothing>
    data object Loading : LoadResult<Nothing>
}
