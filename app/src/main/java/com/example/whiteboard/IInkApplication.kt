package com.example.whiteboard

import android.app.Application
import com.example.certificate.MyCertificate
import com.myscript.iink.Engine

object IInkApplication : Application() {
    @get:Synchronized
    var engine: Engine? = null
        get() {
            if (field == null) {
                field = Engine.create(MyCertificate.getBytes())
            }

            return field
        }
        private set
}