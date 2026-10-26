# Debug builds do not minify. Release keeps BDK JNI entry points if minify is enabled later.
-keep class org.bitcoindevkit.** { *; }
