import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class FanesMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FanesMovies())
    }
}