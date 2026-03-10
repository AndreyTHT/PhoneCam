#!/bin/sh

#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#

##############################################################################
# Gradle start up script for UN*X
##############################################################################

# Attempt to set APP_HOME
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Resolve links: $0 may be a link
app_path=$0
while
    APP_HOME=${app_path%"${app_path##*/}"}  # leaves a trailing /; empty if no leading path
    [ -h "$app_path" ]
do
    ls=$( ls -ld "$app_path" )
    link=${ls#*' -> '}
    case $link in             #(
      /*)   app_path=$link ;; #( absolute
      *)    app_path=$APP_HOME$link ;; #( relative
    esac
done
APP_HOME=$( cd "${APP_HOME:-./}" && pwd -P ) || exit

# Use the maximum available or limited to 256 KB stack for perl
DEFAULT_JVM_OPTS='"-Xmx2048m" "-Dfile.encoding=UTF-8"'

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
    fi
else
    JAVACMD=java
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

# Classpath
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Collect all arguments for the java command
set -- \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "$@"
