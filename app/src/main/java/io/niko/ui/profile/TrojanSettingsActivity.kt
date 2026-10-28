package io.niko.ui.profile

import io.niko.fmt.trojan.TrojanBean

class TrojanSettingsActivity : StandardV2RaySettingsActivity() {

    override fun createEntity() = TrojanBean()

}
