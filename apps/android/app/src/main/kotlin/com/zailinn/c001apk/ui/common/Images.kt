package com.zailinn.c001apk.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage

private fun String.isWebUrl() = startsWith("https://") || startsWith("http://")

/** Remote image with the themed placeholder used across the app. */
@Composable
fun NetworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    isAvatar: Boolean = false,
    contentDescription: String? = null,
) {
    if (url.isNullOrEmpty() || !url.isWebUrl()) {
        ImagePlaceholder(modifier, isAvatar)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = { ImagePlaceholder(Modifier.fillMaxSize(), isAvatar) },
        error = { ImagePlaceholder(Modifier.fillMaxSize(), isAvatar) },
    )
}

@Composable
private fun ImagePlaceholder(modifier: Modifier, isAvatar: Boolean) {
    Box(
        modifier.background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isAvatar) Icons.Default.Person else Icons.Default.AllInclusive,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
fun Avatar(url: String?, size: Dp = 36.dp, onClick: (() -> Unit)? = null) {
    NetworkImage(
        url,
        Modifier.size(size).clip(CircleShape).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        isAvatar = true,
    )
}

@Composable
fun RoundedImage(url: String?, size: Dp, radius: Dp = 8.dp) {
    NetworkImage(url, Modifier.size(size).clip(RoundedCornerShape(radius)))
}

/** Coolapk encodes original image sizes in the URL: `...@1080x1920.jpg`. */
fun imageAspectRatio(url: String): Float? {
    val at = url.lastIndexOf('@')
    val x = url.lastIndexOf('x')
    val dot = url.lastIndexOf('.')
    if (at == -1 || x <= at || dot <= x) return null
    val width = url.substring(at + 1, x).toFloatOrNull() ?: return null
    val height = url.substring(x + 1, dot).toFloatOrNull() ?: return null
    return if (width > 0 && height > 0) width / height else null
}

fun thumbnail(url: String) = if (url.endsWith(".gif")) url else "$url.s.jpg"
