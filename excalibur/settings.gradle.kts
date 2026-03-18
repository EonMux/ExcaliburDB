rootProject.name = 'excalibur'

include ':core'
include ':excalibur-test'

core {
    dependencies {
        implementation 'org.openjdk.jmh:jmh-core:1.37'
        jmh 'org.openjdk.jmh:jmh-generator-annprocess:1.37'
    }
}

excalibur-test {
    dependencies {
        implementation 'org.openjdk.jmh:jmh-core:1.37'
        jmh 'org.openjdk.jmh:jmh-generator-annprocess:1.37'
    }
}
