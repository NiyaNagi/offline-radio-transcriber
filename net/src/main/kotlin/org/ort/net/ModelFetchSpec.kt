package org.ort.net

import java.io.File

/** What to fetch (or verify a side-loaded copy of), and where it must end up. */
public data class ModelFetchSpec(val url: String, val destination: File, val checksum: Checksum)

/** [fromCache] is true when an already-verified copy at [path] made a network call unnecessary. */
public data class AcquiredModel(val path: File, val checksum: Checksum, val fromCache: Boolean)
