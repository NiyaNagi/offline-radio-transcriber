plugins {
    id("ort.jvm-library")
}

// :core has NO dependency on any other module and NO Android dependency (technical design
// rule 3; constitution VII). The dependencyRules task and the "no android.jar" test below
// both keep it that way. Only the Kotlin stdlib and JUnit (test) belong here.
dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
}
