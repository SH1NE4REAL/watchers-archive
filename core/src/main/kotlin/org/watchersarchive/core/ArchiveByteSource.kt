package org.watchersarchive.core

/**
 * Bounded random access to one archive representation. Implementations must check
 * the supplied identity before returning bytes and must never copy the archive
 * merely to make it seekable. Network sources additionally enforce HTTP Range.
 */
interface ArchiveByteSource {
    fun discover(token: CancellationToken = CancellationToken()): RemoteIdentity

    fun readRange(
        identity: RemoteIdentity,
        start: Long,
        length: Int,
        token: CancellationToken = CancellationToken(),
    ): ByteArray
}
