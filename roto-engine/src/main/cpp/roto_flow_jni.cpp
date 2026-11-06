// Reference C++ JNI implementation for pyramidal optical-flow mask warp when NDK toolchain is enabled.
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_rotoengine_MobileRotoEngine_nativeBackendInfo(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF("RotoFlow-VOS-NEON/1.0");
}
