package io.niko.bg.proto

import io.niko.database.DataStore
import io.niko.database.ProxyEntity

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = 5000

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest()
    }

}