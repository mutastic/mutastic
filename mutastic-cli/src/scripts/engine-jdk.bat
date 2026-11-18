@rem mutastic runs on JDK 25 or later, and the project's build on the JDK it was given:
@rem MUTASTIC_JAVA_HOME names mutastic's, when it's set, and MUTASTIC_BUILD_JAVA_HOME keeps the
@rem JAVA_HOME given, a space for none, as an empty one would be unset, for mutastic to give back
@rem to the build. The native mutastic finds a JDK 25 and sets MUTASTIC_JAVA_HOME to it.
set "MUTASTIC_BUILD_JAVA_HOME= "
if defined JAVA_HOME set "MUTASTIC_BUILD_JAVA_HOME=%JAVA_HOME%"
if defined MUTASTIC_JAVA_HOME set "JAVA_HOME=%MUTASTIC_JAVA_HOME%"

