package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PhoneOwnMicrophonePersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test fun carMicrophoneStaysTheDefault() {
        assertFalse(AirPlayPersistence.loadPhoneOwnMicrophone(context))
    }

    @Test fun phoneOwnMicrophoneSurvivesARestart() {
        AirPlayPersistence.savePhoneOwnMicrophone(context, true)
        assertTrue(AirPlayPersistence.loadPhoneOwnMicrophone(context))
        AirPlayPersistence.savePhoneOwnMicrophone(context, false)
        assertFalse(AirPlayPersistence.loadPhoneOwnMicrophone(context))
    }
}
