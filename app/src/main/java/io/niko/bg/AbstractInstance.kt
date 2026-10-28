package io.niko.bg

import java.io.Closeable

interface AbstractInstance : Closeable {

    fun launch()

}