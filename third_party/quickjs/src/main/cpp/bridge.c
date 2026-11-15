#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "quickjs.h"

typedef struct {
    atomic_bool cancelled;
    int64_t deadline;
    JNIEnv *env;
    jobject bridge; /* Borrowed local reference, valid only during nativeEvaluate. */
    jmethodID call;
} Engine;

static int64_t monotonic_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int interrupt(JSRuntime *rt, void *opaque) {
    Engine *e = opaque;
    return atomic_load_explicit(&e->cancelled, memory_order_relaxed) || monotonic_ms() >= e->deadline;
}
static jstring java_string(JNIEnv *env, const char *text, size_t length) {
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (!bytes) return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)text);
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jmethodID constructor = (*env)->GetMethodID(env, cls, "<init>", "([BLjava/lang/String;)V");
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    jstring result = (*env)->NewObject(env, cls, constructor, bytes, encoding);
    (*env)->DeleteLocalRef(env, bytes); (*env)->DeleteLocalRef(env, encoding); (*env)->DeleteLocalRef(env, cls);
    return result;
}
static void fail(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jstring text = java_string(env, message, strlen(message));
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    jmethodID constructor = (*env)->GetMethodID(env, cls, "<init>", "(Ljava/lang/String;)V");
    jobject error = (*env)->NewObject(env, cls, constructor, text);
    (*env)->Throw(env, error);
    (*env)->DeleteLocalRef(env, error); (*env)->DeleteLocalRef(env, text); (*env)->DeleteLocalRef(env, cls);
}
static JSValue host_call(JSContext *ctx, JSValueConst this_value, int argc, JSValueConst *argv) {
    Engine *e = JS_GetContextOpaque(ctx);
    if (argc != 2 || !JS_IsString(argv[0]) || !JS_IsString(argv[1])) return JS_ThrowTypeError(ctx, "Expected method and JSON strings");
    if (interrupt(JS_GetRuntime(ctx), e)) return JS_ThrowInternalError(ctx, "JS interrupted or timed out");
    size_t method_len = 0, args_len = 0;
    const char *method = JS_ToCStringLen(ctx, &method_len, argv[0]);
    const char *args = JS_ToCStringLen(ctx, &args_len, argv[1]);
    if (!method || !args) { if (method) JS_FreeCString(ctx, method); if (args) JS_FreeCString(ctx, args); return JS_EXCEPTION; }
    if (method_len > 40 || args_len > 196608) { JS_FreeCString(ctx, method); JS_FreeCString(ctx, args); return JS_ThrowRangeError(ctx, "Bridge request too large"); }
    JNIEnv *env = e->env;
    jstring java_method = java_string(env, method, method_len), java_args = java_string(env, args, args_len);
    JS_FreeCString(ctx, method); JS_FreeCString(ctx, args);
    jstring response = NULL;
    if (!(*env)->ExceptionCheck(env)) response = (*env)->CallObjectMethod(env, e->bridge, e->call, java_method, java_args);
    if (java_method) (*env)->DeleteLocalRef(env, java_method);
    if (java_args) (*env)->DeleteLocalRef(env, java_args);
    if ((*env)->ExceptionCheck(env) || !response) {
        (*env)->ExceptionClear(env); if (response) (*env)->DeleteLocalRef(env, response);
        return JS_ThrowInternalError(ctx, "Host bridge failed");
    }
    jclass cls = (*env)->FindClass(env, "java/lang/String");
    jmethodID get_bytes = (*env)->GetMethodID(env, cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring encoding = (*env)->NewStringUTF(env, "UTF-8");
    jbyteArray bytes = (*env)->CallObjectMethod(env, response, get_bytes, encoding);
    JSValue result = JS_EXCEPTION;
    if (bytes && !(*env)->ExceptionCheck(env)) {
        jsize length = (*env)->GetArrayLength(env, bytes);
        jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
        if (data) { result = JS_NewStringLen(ctx, (const char *)data, length); (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT); }
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = JS_ThrowInternalError(ctx, "Host response conversion failed"); }
    if (bytes) (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, encoding); (*env)->DeleteLocalRef(env, cls); (*env)->DeleteLocalRef(env, response);
    return result;
}
JNIEXPORT jlong JNICALL Java_com_wukong_phone_script_QuickJsEngine_nativeCreate(JNIEnv *env, jobject self) {
    Engine *e = calloc(1, sizeof(*e));
    if (!e) { fail(env, "Unable to create QuickJS control"); return 0; }
    atomic_init(&e->cancelled, 0);
    return (jlong)(intptr_t)e;
}
JNIEXPORT void JNICALL Java_com_wukong_phone_script_QuickJsEngine_nativeInterrupt(JNIEnv *env, jobject self, jlong handle) {
    Engine *e = (Engine *)(intptr_t)handle;
    if (e) atomic_store_explicit(&e->cancelled, 1, memory_order_relaxed);
}
JNIEXPORT void JNICALL Java_com_wukong_phone_script_QuickJsEngine_nativeDestroy(JNIEnv *env, jobject self, jlong handle) { free((void *)(intptr_t)handle); }
JNIEXPORT jbyteArray JNICALL Java_com_wukong_phone_script_QuickJsEngine_nativeEvaluate(JNIEnv *env, jobject self, jlong handle, jbyteArray source, jobject bridge, jlong timeout_ms) {
    Engine *e = (Engine *)(intptr_t)handle;
    if (!e || !source || !bridge || timeout_ms < 200 || timeout_ms > 180000) { fail(env, "Invalid QuickJS request"); return NULL; }
    jsize source_len = (*env)->GetArrayLength(env, source);
    if (source_len > 240000) { fail(env, "Script too large"); return NULL; }
    e->deadline = monotonic_ms() + timeout_ms; e->env = env; e->bridge = bridge;
    jclass cls = (*env)->GetObjectClass(env, bridge);
    e->call = (*env)->GetMethodID(env, cls, "call", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, cls);
    if (!e->call) return NULL;
    JSRuntime *rt = JS_NewRuntime();
    if (!rt) { fail(env, "QuickJS out of memory"); return NULL; }
    JS_SetMemoryLimit(rt, 64 * 1024 * 1024);
    JS_SetCanBlock(rt, 0); /* Atomics.wait must not bypass deadline/cancellation. */
    JS_SetMaxStackSize(rt, 256 * 1024);
    JS_SetInterruptHandler(rt, interrupt, e);
    JSContext *ctx = JS_NewContext(rt);
    if (!ctx) { JS_FreeRuntime(rt); fail(env, "QuickJS context out of memory"); return NULL; }
    JS_SetContextOpaque(ctx, e);
    JSValue global = JS_GetGlobalObject(ctx), object = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, object, "call", JS_NewCFunction(ctx, host_call, "call", 2));
    JS_SetPropertyStr(ctx, global, "__phoneBridge", object);
    JS_FreeValue(ctx, global);
    /* JS_Eval requires a trailing zero even with an explicit byte length. */
    char *text = malloc((size_t)source_len + 1);
    if (!text) { JS_FreeContext(ctx); JS_FreeRuntime(rt); fail(env, "Source allocation failed"); return NULL; }
    (*env)->GetByteArrayRegion(env, source, 0, source_len, (jbyte *)text); text[source_len] = 0;
    JSValue value = JS_Eval(ctx, text, source_len, "wukong-step.js", JS_EVAL_TYPE_GLOBAL);
    free(text);
    jbyteArray output = NULL;
    if (JS_IsException(value)) {
        JSValue exception = JS_GetException(ctx);
        const char *message = JS_ToCString(ctx, exception);
        if (message) { fail(env, message); JS_FreeCString(ctx, message); } else fail(env, "QuickJS evaluation failed (possibly out of memory)");
        JS_FreeValue(ctx, exception);
    } else {
        size_t length = 0;
        const char *result = JS_ToCStringLen(ctx, &length, value);
        if (!result) fail(env, "QuickJS result conversion failed");
        else {
            if (length > 288000) fail(env, "JS result exceeds transport limit");
            else { output = (*env)->NewByteArray(env, (jsize)length); if (output) (*env)->SetByteArrayRegion(env, output, 0, (jsize)length, (const jbyte *)result); }
            JS_FreeCString(ctx, result);
        }
    }
    JS_FreeValue(ctx, value); JS_FreeContext(ctx); JS_FreeRuntime(rt);
    e->env = NULL; e->bridge = NULL;
    return output;
}
