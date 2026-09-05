package com.sungyoon.helper.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    private val releasePath = "github.com/oup030416/sungyoon-s-touch-macro/releases/download"

    @Test
    fun acceptsOfficialReleaseApksIncludingEncodedKoreanNames() {
        listOf(
            "https://$releasePath/v1.08/app-release.apk",
            "https://$releasePath/v1.09/%EC%84%B1%EC%9C%A4%20v1.09.apk",
        ).forEach { assertTrue(it, AppUpdateChecker.isTrustedDownloadUrl(it)) }
    }

    @Test
    fun rejectsUntrustedOriginsAndNonReleasePaths() {
        listOf(
            "http://$releasePath/v1.09/app.apk",
            "https://evil.example/oup030416/sungyoon-s-touch-macro/releases/download/v1.09/app.apk",
            "https://github.com.evil.example/oup030416/sungyoon-s-touch-macro/releases/download/v1.09/app.apk",
            "https://github.com/other/repo/releases/download/v1.09/app.apk",
            "https://github.com/oup030416/sungyoon-s-touch-macro/blob/main/app.apk",
            "https://user@$releasePath/v1.09/app.apk",
            "https://github.com:8443/oup030416/sungyoon-s-touch-macro/releases/download/v1.09/app.apk",
            "https://$releasePath/v1.09/app.apk?redirect=evil",
            "https://$releasePath/v1.09/app.apk#fragment",
            "https://$releasePath/../app.apk",
            "https://$releasePath/%2E%2e/app.apk",
            "https://$releasePath/v1.09/other%2Fapp.apk",
            "https://$releasePath/v1.09/other%5capp.apk",
            "https://$releasePath/v1.09/extra/app.apk",
            "https://$releasePath//app.apk",
            "https://$releasePath/v1.09/app.zip",
            "file:///Download/app.apk",
            "",
            "not a URL",
        ).forEach { assertFalse(it, AppUpdateChecker.isTrustedDownloadUrl(it)) }
    }
}
