package org.watchersarchive.app

/** The image and floating dictionary share coordinates in the full reader viewport. */
internal data class ReaderImageGeometry(
    val pageKey: Any,
    val transform: ReaderImageTransform,
    val bounds: ReaderImageBounds,
)
