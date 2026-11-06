// Reference JNI entry point for optional native QuickJS-NG ExtendScript acceleration when NDK is present.
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_scriptengine_NativeQuickJsBridge_nativeVersion(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF("QuickJS-NG-ES3-Bridge/1.0");
}
