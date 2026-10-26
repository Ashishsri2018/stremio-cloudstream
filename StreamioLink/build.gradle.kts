dependencies {
    implementation("com.google.android.material:material:1.12.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
}

// Use an integer for version numbers
version = 32

cloudstream {
    description = "Play streams from Stremio addons. Configure one catalog addon (default: Cinemeta) plus as many source addons as you like in the settings."
    authors = listOf("Ashishsri2018")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    language = "en"

    iconUrl = "https://raw.githubusercontent.com/Ashishsri2018/stremio-cloudstream/main/StreamioLink/icon.png"
}
