rootProject.name = 'excalibur'

include ':core'

core {
    dependencies {
        implementation 'org.openjdk.jmh:jmh-core:1.37'
        jmh 'org.openjdk.jmh:jmh-generator-annprocess:1.37'
    }
}
