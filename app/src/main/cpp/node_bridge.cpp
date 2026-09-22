// Minimal JNI glue for nodejs-mobile (digidem libnode.so).
// We forward-declare node::Start instead of including node.h/v8.h, so there is
// no heavy header compilation to go wrong. If the linker reports an undefined
// reference to node::Start, that symbol name is the one thing to adjust.

#include <jni.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "NODEJS"

namespace node { int Start(int argc, char** argv); }

static int   s_pfd[2];
static bool  s_logging = false;

static void* log_pump(void*) {
    ssize_t rdsz;
    char buf[2048];
    while ((rdsz = read(s_pfd[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[rdsz - 1] == '\n') rdsz--;
        buf[rdsz] = '\0';
        __android_log_write(ANDROID_LOG_INFO, TAG, buf);
    }
    return nullptr;
}

static void start_logging() {
    if (s_logging) return;
    s_logging = true;
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pipe(s_pfd);
    dup2(s_pfd[1], STDOUT_FILENO);
    dup2(s_pfd[1], STDERR_FILENO);
    pthread_t t;
    pthread_create(&t, nullptr, log_pump, nullptr);
    pthread_detach(t);
}

extern "C" JNIEXPORT void JNICALL
Java_ayx_whatsapp_NodeBridge_nativeSetenv(JNIEnv* env, jclass, jstring key, jstring val) {
    const char* k = env->GetStringUTFChars(key, nullptr);
    const char* v = env->GetStringUTFChars(val, nullptr);
    setenv(k, v, 1);
    env->ReleaseStringUTFChars(key, k);
    env->ReleaseStringUTFChars(val, v);
}

extern "C" JNIEXPORT jint JNICALL
Java_ayx_whatsapp_NodeBridge_nativeStart(JNIEnv* env, jclass, jobjectArray args, jstring workdir) {
    start_logging();

    const char* wd = env->GetStringUTFChars(workdir, nullptr);
    chdir(wd);
    env->ReleaseStringUTFChars(workdir, wd);

    int argc = env->GetArrayLength(args);
    std::vector<std::string> store;
    store.reserve(argc);
    for (int i = 0; i < argc; i++) {
        auto s = (jstring) env->GetObjectArrayElement(args, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        store.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    std::vector<char*> argv;
    argv.reserve(argc + 1);
    for (auto& s : store) argv.push_back(const_cast<char*>(s.c_str()));
    argv.push_back(nullptr);

    return (jint) node::Start(argc, argv.data());
}
