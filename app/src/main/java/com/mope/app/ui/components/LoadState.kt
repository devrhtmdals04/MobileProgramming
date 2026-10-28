package com.mope.app.ui.components

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data object Empty : LoadState<Nothing>
    data class Error(val message: String) : LoadState<Nothing>
    data class Content<T>(val value: T) : LoadState<T>
}
