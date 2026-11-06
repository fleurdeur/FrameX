// Reference C++ JNI implementation for ONNX/TFLite Depth Anything V2 inference when NDK is enabled.
#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_depthengine_DepthAnythingEngine_nativeRuntimeInfo(JNIEnv* env, jobject /* thiz */) {
    return env->NewStringUTF("DepthAnythingV2-Mobile-NNAPI/1.0");
}
