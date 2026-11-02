package com.example.touchengine

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    @Test
    fun recognitionBoxSwitchAndGuideAreLocalizedForAllThreeLanguages() {
        val expected = mapOf(
            AppLanguage.CHINESE to ("显示识别绿框" to "无障碍开启教程"),
            AppLanguage.JAPANESE to ("認識した対象の緑枠を表示" to "ユーザー補助の設定ガイド"),
            AppLanguage.ENGLISH to ("Show green target boxes" to "Accessibility setup guide")
        )
        expected.forEach { (language, labels) ->
            val strings = UiStrings.forLanguage(language)
            assertEquals(labels.first, strings.recognitionBoxesTitle)
            assertEquals(labels.second, strings.accessibilityGuide.title)
            assertTrue(strings.recognitionBoxesDescription.isNotBlank())
        }
    }

    @Test
    fun guidesHaveCompleteStepsStatusAndTroubleshootingWithoutLanguageFallback() {
        val guides = AppLanguage.entries.map { UiStrings.forLanguage(it).accessibilityGuide }
        guides.forEach { guide ->
            assertEquals(4, guide.steps.size)
            assertTrue(guide.steps.all { it.isNotBlank() })
            assertTrue(guide.steps[1].contains("{app}"))
            assertTrue(listOf(guide.introduction, guide.enabled, guide.disabled, guide.restrictedTitle,
                guide.restrictedDescription, guide.openSettings, guide.openAppInfo, guide.close,
                guide.settingsUnavailable).all { it.isNotBlank() })
        }
        assertEquals(3, guides.map { it.openSettings }.distinct().size)
        assertEquals(3, guides.map { it.restrictedDescription }.distinct().size)
        assertEquals(3, guides.map { it.steps }.distinct().size)
    }

}
