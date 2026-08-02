package com.example.touchengine

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class UiLanguageTest {
    @Test
    fun supportedSystemLanguagesChooseMatchingDefault() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.defaultForSystemLocale(Locale.CHINESE))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.defaultForSystemLocale(Locale.JAPANESE))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.defaultForSystemLocale(Locale.ENGLISH))
    }

    @Test
    fun unsupportedSystemLanguageDefaultsToEnglish() {
        assertEquals(
            AppLanguage.ENGLISH,
            AppLanguage.defaultForSystemLocale(Locale.forLanguageTag("fr-FR"))
        )
    }

    @Test
    fun savedUserChoiceOverridesSystemLanguage() {
        assertEquals(
            AppLanguage.JAPANESE,
            AppLanguage.savedOrSystemDefault("ja", Locale.CHINESE)
        )
    }

    @Test
    fun translatedScreensUseDifferentVisibleLabels() {
        val chinese = UiStrings.forLanguage(AppLanguage.CHINESE)
        val japanese = UiStrings.forLanguage(AppLanguage.JAPANESE)
        val english = UiStrings.forLanguage(AppLanguage.ENGLISH)

        assertEquals("控制中心", chinese.controlCenter)
        assertEquals("コントロールセンター", japanese.controlCenter)
        assertEquals("Control Center", english.controlCenter)
    }
}
