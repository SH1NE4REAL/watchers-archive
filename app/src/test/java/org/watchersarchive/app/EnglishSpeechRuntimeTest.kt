package org.watchersarchive.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

/** Android TTS control flow, not a claim to validate the sound of an installed voice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class EnglishSpeechRuntimeTest {
    @Before fun resetTtsFixture() { ShadowTextToSpeech.reset() }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private fun shadow() = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())

    @Test fun pronunciationIsExplicitAndPrefersAnAvailableOfflineEnglishVoice() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val offlineVoice = Voice("fixture-en-offline", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        ShadowTextToSpeech.addVoice(offlineVoice)
        val speech = DeviceEnglishSpeech(context())
        val tts = shadow()
        try {
            assertFalse(speech.ready.value)
            assertFalse(speech.speak("hero"))
            assertTrue(tts.spokenTextList.isEmpty())
            tts.onInitListener.onInit(TextToSpeech.SUCCESS)
            assertTrue(speech.ready.value)
            assertTrue("Initialization must not autoplay OCR text", tts.spokenTextList.isEmpty())
            assertTrue(speech.speak("slow down"))
            assertEquals("slow down", tts.lastSpokenText)
            assertEquals(TextToSpeech.QUEUE_FLUSH, tts.queueMode)
            assertEquals(offlineVoice, tts.currentVoice)
        } finally { speech.close() }
    }

    @Test fun unsupportedEnglishLanguageNeverClaimsSpeechIsReady() {
        // No English language is advertised by the fixture's installed engine.
        val speech = DeviceEnglishSpeech(context())
        val tts = shadow()
        try {
            tts.onInitListener.onInit(TextToSpeech.SUCCESS)
            assertFalse(speech.ready.value)
            assertFalse(speech.speak("hello"))
            assertTrue(tts.spokenTextList.isEmpty())
        } finally { speech.close() }
    }

    @Test fun initializationFailureAndLateCallbackAfterCloseNeverEnableSpeech() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val speech = DeviceEnglishSpeech(context())
        val tts = shadow()
        tts.onInitListener.onInit(TextToSpeech.ERROR)
        assertFalse(speech.ready.value)
        assertFalse(speech.speak("hello"))
        speech.close()
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertFalse(speech.ready.value)
        assertFalse(speech.speak("hello"))
        assertTrue(tts.isShutdown)
        assertTrue(tts.spokenTextList.isEmpty())
    }

    @Test fun stopAndCloseEndPlaybackAndRejectFurtherUtterances() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val speech = DeviceEnglishSpeech(context())
        val tts = shadow()
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        assertTrue(speech.speak("x".repeat(400)))
        assertEquals(300, tts.lastSpokenText.length)
        assertFalse(tts.isStopped)
        speech.stop()
        assertTrue(tts.isStopped)
        assertFalse(speech.speak("   "))
        assertTrue(speech.speak("hero"))
        speech.close(); speech.close()
        assertTrue(tts.isStopped)
        assertTrue(tts.isShutdown)
        assertFalse(speech.ready.value)
        val played = tts.spokenTextList.size
        assertFalse(speech.speak("never play"))
        speech.stop()
        assertEquals(played, tts.spokenTextList.size)
    }
}
