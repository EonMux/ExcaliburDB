#!/bin/sh

##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"

# Need this for daisy-chained symlinks.
while [ -h "$PRG" ] ; do
    ls=$( ls -ld "$PRG" )
    link=$( expr "$ls" : '.*-> \(.*\)$' )
    if [ -n "$link" ] ; then
        PRG="$link"
    else
        break
    fi
done

# This is unavoidable.
# The shell can't distinguish between a "real" path and a symlinked one without
# expensive stat calls.
APP_HOME="$( cd "$( dirname "$PRG" )" && pwd )"

# Use this option to run with a different Java home
JAVA_HOME_OPTS=""

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='--add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang.String=ALL-UNNAMED'

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
        echo "You can set the JAVA_HOME variable in the environment to point to the"
        echo "your Java home directory."
        exit 1
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || { echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
        echo "Please set the JAVA_HOME variable in your environment to match the"
        echo "location of your Java installation."
        exit 1
    }
fi

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

warn () {
    echo "$*"
}

die () {
    echo
    echo "$*"
    echo
    exit 1
}

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
nonstop=false
case "$( uname )" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MSYS* | MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_OPTS" ] || [ -n "$GRADLE_OPTS" ] ; then
    DEFAULT_JVM_OPTS="$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS"
fi

# Collect all arguments for the java command, stacking in reverse order:
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "--add-opens=java.base/java.util=ALL-UNNAMED" "--add-opens=java.base/java.lang.String=ALL-UNNAMED" "-Dorg.gradle.appname=gradlew" "-classpath" "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "$@"
