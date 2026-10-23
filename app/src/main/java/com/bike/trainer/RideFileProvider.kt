package com.bike.trainer

import androidx.core.content.FileProvider

/**
 * Shares recorded ride files (TCX) with other apps. Its own class and authority so
 * it doesn't clash with the FileProvider the embedded Godot engine declares.
 */
class RideFileProvider : FileProvider()
