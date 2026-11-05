// Frame-by-frame stepping. libVLC has libvlc_media_player_next_frame() but the Java
// binding doesn't expose it, so call it through libvlc.so directly.
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>

typedef void (*next_frame_fn)(void *);
typedef int64_t (*get_time_fn)(void *);

static void *sym(const char *name) {
    void *h = dlopen("libvlc.so", RTLD_NOW | RTLD_NOLOAD);
    if (!h) h = dlopen("libvlc.so", RTLD_NOW);
    return h ? dlsym(h, name) : NULL;
}

JNIEXPORT jlong JNICALL
Java_com_novaplay_player_ui_player_FrameStepper_nativeTime(JNIEnv *env, jclass c, jlong mp) {
    get_time_fn f = (get_time_fn) sym("libvlc_media_player_get_time");
    return (f && mp) ? (jlong) f((void *) (intptr_t) mp) : -2;
}

JNIEXPORT jboolean JNICALL
Java_com_novaplay_player_ui_player_FrameStepper_nativeNextFrame(JNIEnv *env, jclass c, jlong mp) {
    next_frame_fn f = (next_frame_fn) sym("libvlc_media_player_next_frame");
    if (!f || !mp) return JNI_FALSE;
    f((void *) (intptr_t) mp);
    return JNI_TRUE;
}
