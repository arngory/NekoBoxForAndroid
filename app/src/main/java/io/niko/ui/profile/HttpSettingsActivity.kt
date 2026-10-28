package io.niko.ui.profile

import io.niko.fmt.http.HttpBean

class HttpSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = HttpBean()

}
