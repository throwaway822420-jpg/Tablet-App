package app.slate.tablet

import android.app.Application
import app.slate.tablet.ai.Spend
import app.slate.tablet.link.SlateLink

class SlateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Start listening for the PC over USB as soon as the app runs.
        Spend.init(this)
        app.slate.tablet.study.StudyHub.init(this)
        SlateLink.start()
        app.slate.tablet.ai.TermuxClaude.refresh(this)
    }
}
