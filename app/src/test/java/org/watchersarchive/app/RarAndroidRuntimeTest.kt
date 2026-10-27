package org.watchersarchive.app

import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.watchersarchive.core.*

/** Real core RAR4 decompression plus Android's native image codec, using only generated pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RarAndroidRuntimeTest {
    @Test fun rar4IndependentPagesPassCrcAndAndroidImageDecode() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fixtures/rar4-independent-pages.cbr")).use { it.readBytes() }
        val identity = RemoteIdentity("https://example.org/own-fixture.cbr", bytes.size.toLong(), "\"fixture-v1\"", null)
        val source = object : ArchiveByteSource {
            override fun discover(token: CancellationToken): RemoteIdentity { token.throwIfCancelled(); return identity }
            override fun readRange(identity: RemoteIdentity, start: Long, length: Int, token: CancellationToken): ByteArray {
                token.throwIfCancelled(); check(start >= 0 && start <= bytes.size - length)
                return bytes.copyOfRange(start.toInt(), start.toInt() + length)
            }
        }
        ComicArchiveReaders.create(source).use { reader ->
            val index = reader.open()
            assertEquals(4, index.pages.size)
            index.pages.indices.forEach { page ->
                val encoded = reader.readPage(index, page)
                val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                assertNotNull(decoded)
                assertEquals(240, decoded.width); assertEquals(360, decoded.height)
                decoded.recycle()
            }
        }
    }
}
