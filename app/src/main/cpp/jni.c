#include <jni.h>
#include <stdio.h>
#include "uvc.h"

#define FN(name) Java_io_github_bigtimer_1dev_ircam_usb_NativeUvc_##name

JNIEXPORT jlong JNICALL FN(nativeOpen)(JNIEnv *env, jclass cls, jint fd, jint w, jint h, jobjectArray errOut) {
    char err[200] = "";
    uvc_cam *c = uvc_open(fd, w, h, err, sizeof err);
    if (!c && errOut && (*env)->GetArrayLength(env, errOut) > 0)
        (*env)->SetObjectArrayElement(env, errOut, 0, (*env)->NewStringUTF(env, err));
    return (jlong) (intptr_t) c;
}

JNIEXPORT jstring JNICALL FN(nativeStart)(JNIEnv *env, jclass cls, jlong p) {
    uvc_cam *c = (uvc_cam *) (intptr_t) p;
    if (uvc_start(c) < 0) return (*env)->NewStringUTF(env, uvc_last_error(c));
    return NULL;
}

JNIEXPORT jint JNICALL FN(nativeRead)(JNIEnv *env, jclass cls, jlong p, jbyteArray out, jint timeoutMs) {
    uvc_cam *c = (uvc_cam *) (intptr_t) p;
    jsize n = (*env)->GetArrayLength(env, out);
    jbyte *buf = (*env)->GetByteArrayElements(env, out, NULL);
    int r = uvc_read(c, (uint8_t *) buf, (size_t) n, timeoutMs);
    (*env)->ReleaseByteArrayElements(env, out, buf, r > 0 ? 0 : JNI_ABORT);
    return r;
}

JNIEXPORT jlong JNICALL FN(nativeDropped)(JNIEnv *env, jclass cls, jlong p) {
    return uvc_dropped((uvc_cam *) (intptr_t) p);
}

JNIEXPORT void JNICALL FN(nativeClose)(JNIEnv *env, jclass cls, jlong p) {
    uvc_close((uvc_cam *) (intptr_t) p);
}
