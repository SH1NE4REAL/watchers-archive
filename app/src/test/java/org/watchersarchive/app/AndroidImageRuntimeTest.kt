package org.watchersarchive.app

import android.graphics.BitmapFactory
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Robolectric image codec verification, distinct from a connected phone test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidImageRuntimeTest {
    @Test fun bitmapFactoryDecodesPngAndRejectsInvalidContent() {
        // Self-generated 1x1 RGB PNG with valid IDAT CRC, decoded by Android's
        // native codec rather than a dimension-only test shadow.
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQVDIGAACuAGcVHqFfAAAAAElFTkSuQmCC")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        assertEquals(1, bounds.outWidth)
        assertEquals(1, bounds.outHeight)
        val image = BitmapFactory.decodeByteArray(png, 0, png.size)
        assertNotNull(image)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
        val invalid = "<html>download page</html>".toByteArray()
        assertNull(BitmapFactory.decodeByteArray(invalid, 0, invalid.size))
        image.recycle()
    }
}
