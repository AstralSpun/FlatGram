#include <android/bitmap.h>
#include <jni.h>
#include <rlottie.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <functional>
#include <inttypes.h>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#define FLATGRAM_HAVE_NEON 1
#else
#define FLATGRAM_HAVE_NEON 0
#endif

namespace {

JavaVM* gJavaVm = nullptr;
jmethodID gOnNativeFrameReadyMethod = nullptr;
std::mutex gMethodIdMutex;

uint64_t fnv1a64(const std::string& value) {
    uint64_t hash = 14695981039346656037ULL;
    for (unsigned char byte : value) {
        hash ^= static_cast<uint64_t>(byte);
        hash *= 1099511628211ULL;
    }
    return hash;
}

std::string makeLottieCacheKey(const std::string& json) {
    char buffer[48];
    snprintf(buffer, sizeof(buffer), "flatgram_emoji_%016" PRIx64, fnv1a64(json));
    return std::string(buffer);
}

// Convert rlottie's ARGB32-premultiplied output (which on little-endian Android
// is laid out in memory as B,G,R,A) into Android Bitmap's RGBA_8888 layout
// (R,G,B,A).  Uses NEON 4-way de-interleave when available.
inline void convertBgraToRgba(const uint32_t* src, uint8_t* dst, int dstStride, int width, int height) {
    const auto* srcBytes = reinterpret_cast<const uint8_t*>(src);
    const int srcStride = width * 4;
    for (int y = 0; y < height; ++y) {
        const uint8_t* srcRow = srcBytes + static_cast<size_t>(y) * srcStride;
        uint8_t* dstRow = dst + static_cast<size_t>(y) * dstStride;
        int x = 0;
#if FLATGRAM_HAVE_NEON
        for (; x + 16 <= width; x += 16) {
            uint8x16x4_t bgra = vld4q_u8(srcRow + x * 4);
            uint8x16x4_t rgba;
            rgba.val[0] = bgra.val[2];
            rgba.val[1] = bgra.val[1];
            rgba.val[2] = bgra.val[0];
            rgba.val[3] = bgra.val[3];
            vst4q_u8(dstRow + x * 4, rgba);
        }
#endif
        for (; x < width; ++x) {
            const int o = x * 4;
            const uint8_t b = srcRow[o];
            const uint8_t g = srcRow[o + 1];
            const uint8_t r = srcRow[o + 2];
            const uint8_t a = srcRow[o + 3];
            dstRow[o] = r;
            dstRow[o + 1] = g;
            dstRow[o + 2] = b;
            dstRow[o + 3] = a;
        }
    }
}

void detachThreadIfAttached() {
    // Used from worker shutdown path only — normally workers stay attached
    // for the lifetime of the process via AttachCurrentThreadAsDaemon.
    if (gJavaVm) gJavaVm->DetachCurrentThread();
}

struct LottieHandle {
    std::unique_ptr<rlottie::Animation> animation;
    std::unique_ptr<uint32_t[]> renderBuffer;
    std::mutex renderMutex;        // Guards renderBuffer + animation->renderSync.
    int width = 0;
    int height = 0;
    int renderWidth = 0;
    int renderHeight = 0;

    // JNI globals — cached once on first renderLottieFrameAsync call,
    // released in destructor.  This eliminates per-frame NewGlobalRef /
    // DeleteGlobalRef overhead which would otherwise be called from the
    // worker thread for every frame.
    std::mutex globalRefMutex;
    jobject bitmapRef = nullptr;
    jobject listenerRef = nullptr;
    std::atomic<bool> destroyed{false};

    explicit LottieHandle(const std::string& json) {
        animation = rlottie::Animation::loadFromData(json, makeLottieCacheKey(json));
        if (!animation) return;
        size_t w = 0;
        size_t h = 0;
        animation->size(w, h);
        width = static_cast<int>(w);
        height = static_cast<int>(h);
    }

    ~LottieHandle() {
        destroyed.store(true);
        if (!gJavaVm) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        const jint envStatus = gJavaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (envStatus == JNI_EDETACHED) {
            if (gJavaVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                env = nullptr;
            }
        } else if (envStatus != JNI_OK) {
            env = nullptr;
        }
        if (env) {
            if (bitmapRef) {
                env->DeleteGlobalRef(bitmapRef);
                bitmapRef = nullptr;
            }
            if (listenerRef) {
                env->DeleteGlobalRef(listenerRef);
                listenerRef = nullptr;
            }
        }
        if (attached) {
            gJavaVm->DetachCurrentThread();
        }
    }

    void prepare(int w, int h) {
        renderWidth = w > 0 ? w : 1;
        renderHeight = h > 0 ? h : 1;
        renderBuffer = std::unique_ptr<uint32_t[]>(new uint32_t[static_cast<size_t>(renderWidth) * renderHeight]);
    }
};

class RenderQueue {
public:
    explicit RenderQueue(int workerCount) {
        if (workerCount < 1) workerCount = 1;
        if (workerCount > 4) workerCount = 4;
        workers.reserve(static_cast<size_t>(workerCount));
        for (int i = 0; i < workerCount; ++i) {
            workers.emplace_back(&RenderQueue::run, this);
        }
    }

    ~RenderQueue() {
        {
            std::lock_guard<std::mutex> lock(mutex);
            stopped = true;
        }
        condition.notify_all();
        for (auto& t : workers) {
            if (t.joinable()) t.join();
        }
    }

    void enqueue(std::function<void()> task) {
        {
            std::lock_guard<std::mutex> lock(mutex);
            if (stopped) return;
            queue.push_back(std::move(task));
        }
        condition.notify_one();
    }

private:
    void run() {
        // gJavaVm is set by JNI_OnLoad, which runs AFTER static initializers.
        // RenderQueue's static constructor spawns these threads during static
        // init — so gJavaVm may still be null here.  Defer JVM attach to the
        // first task instead; by then the .so is fully loaded.
        bool attached = false;
        while (true) {
            std::function<void()> task;
            {
                std::unique_lock<std::mutex> lock(mutex);
                condition.wait(lock, [this] { return stopped || !queue.empty(); });
                if (stopped && queue.empty()) return;
                task = std::move(queue.front());
                queue.pop_front();
            }
            if (!attached && gJavaVm) {
                JNIEnv* env = nullptr;
                if (gJavaVm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK) {
                    attached = true;
                }
            }
            task();
        }
    }

    std::mutex mutex;
    std::condition_variable condition;
    std::deque<std::function<void()>> queue;
    std::vector<std::thread> workers;
    bool stopped = false;
};

// Two render workers — empirically a good balance: lets two visible stickers
// overlap rendering without oversubscribing the CPU.
RenderQueue gRenderQueue(2);
std::mutex gHandlesMutex;
std::unordered_map<int64_t, std::shared_ptr<LottieHandle>> gHandles;

std::shared_ptr<LottieHandle> acquireHandle(int64_t ptr) {
    std::lock_guard<std::mutex> lock(gHandlesMutex);
    auto it = gHandles.find(ptr);
    if (it == gHandles.end()) return nullptr;
    return it->second;
}

void putSize(JNIEnv* env, jintArray outArray, int width, int height) {
    if (!outArray || env->GetArrayLength(outArray) < 2) return;
    jint values[2] = {width, height};
    env->SetIntArrayRegion(outArray, 0, 2, values);
}

jmethodID resolveOnFrameReadyMethod(JNIEnv* env, jobject listener) {
    {
        std::lock_guard<std::mutex> lock(gMethodIdMutex);
        if (gOnNativeFrameReadyMethod) return gOnNativeFrameReadyMethod;
    }
    jclass cls = env->GetObjectClass(listener);
    if (!cls) return nullptr;
    jmethodID method = env->GetMethodID(cls, "onNativeFrameReady", "(Z)V");
    env->DeleteLocalRef(cls);
    if (!method) return nullptr;
    std::lock_guard<std::mutex> lock(gMethodIdMutex);
    gOnNativeFrameReadyMethod = method;
    return method;
}

bool renderIntoBitmap(JNIEnv* env, jobject bitmapRef, const std::shared_ptr<LottieHandle>& handle, int frame) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmapRef, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmapRef, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return false;

    bool rendered = false;
    {
        std::lock_guard<std::mutex> lock(handle->renderMutex);
        if (!handle->animation || handle->destroyed.load()) {
            AndroidBitmap_unlockPixels(env, bitmapRef);
            return false;
        }
        const int w = static_cast<int>(info.width);
        const int h = static_cast<int>(info.height);
        if (!handle->renderBuffer || handle->renderWidth != w || handle->renderHeight != h) {
            handle->prepare(w, h);
        }
        rlottie::Surface surface(handle->renderBuffer.get(), handle->renderWidth, handle->renderHeight, handle->renderWidth * 4);
        handle->animation->renderSync(static_cast<size_t>(frame), surface, true);
        convertBgraToRgba(handle->renderBuffer.get(), reinterpret_cast<uint8_t*>(pixels), static_cast<int>(info.stride), handle->renderWidth, handle->renderHeight);
        rendered = true;
    }

    AndroidBitmap_unlockPixels(env, bitmapRef);
    return rendered;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    gJavaVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_flatgram_messenger_sticker_NativeStickerCore_createLottieHandle(JNIEnv* env, jobject, jstring jsonString) {
    if (!jsonString) return 0;
    const char* rawJson = env->GetStringUTFChars(jsonString, nullptr);
    if (!rawJson) return 0;
    auto handle = std::make_shared<LottieHandle>(std::string(rawJson));
    env->ReleaseStringUTFChars(jsonString, rawJson);
    if (!handle->animation) return 0;
    auto ptr = reinterpret_cast<int64_t>(handle.get());
    {
        std::lock_guard<std::mutex> lock(gHandlesMutex);
        gHandles[ptr] = handle;
    }
    return ptr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_flatgram_messenger_sticker_NativeStickerCore_destroyLottieHandle(JNIEnv*, jobject, jlong ptr) {
    std::shared_ptr<LottieHandle> handle;
    {
        std::lock_guard<std::mutex> lock(gHandlesMutex);
        auto it = gHandles.find(static_cast<int64_t>(ptr));
        if (it != gHandles.end()) {
            handle = std::move(it->second);
            gHandles.erase(it);
        }
    }
    if (handle) handle->destroyed.store(true);
    // Outstanding shared_ptr references in worker tasks keep the handle alive
    // until those tasks complete; the destructor then runs and releases JNI globals.
}

extern "C" JNIEXPORT jint JNICALL
Java_org_flatgram_messenger_sticker_NativeStickerCore_getLottieFrameCount(JNIEnv*, jobject, jlong ptr) {
    auto handle = acquireHandle(static_cast<int64_t>(ptr));
    if (!handle || !handle->animation) return 0;
    return static_cast<jint>(handle->animation->totalFrame());
}

extern "C" JNIEXPORT jfloat JNICALL
Java_org_flatgram_messenger_sticker_NativeStickerCore_getLottieFrameRate(JNIEnv*, jobject, jlong ptr) {
    auto handle = acquireHandle(static_cast<int64_t>(ptr));
    if (!handle || !handle->animation) return 0.0f;
    return static_cast<jfloat>(handle->animation->frameRate());
}

extern "C" JNIEXPORT void JNICALL
Java_org_flatgram_messenger_sticker_NativeStickerCore_getLottieSize(JNIEnv* env, jobject, jlong ptr, jintArray outArray) {
    auto handle = acquireHandle(static_cast<int64_t>(ptr));
    if (!handle) return;
    putSize(env, outArray, handle->width, handle->height);
}

extern "C" JNIEXPORT void JNICALL
Java_org_flatgram_messenger_sticker_NativeStickerCore_prepareLottieRendering(JNIEnv*, jobject, jlong ptr, jint width, jint height) {
    auto handle = acquireHandle(static_cast<int64_t>(ptr));
    if (!handle) return;
    std::lock_guard<std::mutex> lock(handle->renderMutex);
    handle->prepare(width, height);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_flatgram_messenger_sticker_NativeStickerCore_renderLottieFrame(JNIEnv* env, jobject, jlong ptr, jint frame, jobject bitmap) {
    auto handle = acquireHandle(static_cast<int64_t>(ptr));
    if (!handle || !handle->animation || !bitmap) return JNI_FALSE;
    return renderIntoBitmap(env, bitmap, handle, frame) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_flatgram_messenger_sticker_NativeStickerCore_renderLottieFrameAsync(JNIEnv* env, jobject, jlong ptr, jobject bitmap, jint frame, jobject listener) {
    auto handle = acquireHandle(static_cast<int64_t>(ptr));
    if (!handle || !handle->animation || !bitmap || !listener) return JNI_FALSE;

    if (!resolveOnFrameReadyMethod(env, listener)) return JNI_FALSE;
    {
        std::lock_guard<std::mutex> lock(handle->globalRefMutex);
        if (handle->bitmapRef == nullptr) {
            handle->bitmapRef = env->NewGlobalRef(bitmap);
            if (!handle->bitmapRef) return JNI_FALSE;
        }
        if (handle->listenerRef == nullptr) {
            handle->listenerRef = env->NewGlobalRef(listener);
            if (!handle->listenerRef) return JNI_FALSE;
        }
    }

    gRenderQueue.enqueue([handle, frame]() {
        if (handle->destroyed.load()) return;
        if (!gJavaVm) return;

        JNIEnv* callbackEnv = nullptr;
        jint envStatus = gJavaVm->GetEnv(reinterpret_cast<void**>(&callbackEnv), JNI_VERSION_1_6);
        if (envStatus == JNI_EDETACHED) {
            if (gJavaVm->AttachCurrentThreadAsDaemon(&callbackEnv, nullptr) != JNI_OK) {
                return;
            }
        } else if (envStatus != JNI_OK || !callbackEnv) {
            return;
        }

        jobject bitmapRef;
        jobject listenerRef;
        {
            std::lock_guard<std::mutex> lock(handle->globalRefMutex);
            bitmapRef = handle->bitmapRef;
            listenerRef = handle->listenerRef;
        }
        if (!bitmapRef || !listenerRef) return;

        const bool rendered = renderIntoBitmap(callbackEnv, bitmapRef, handle, frame);

        if (handle->destroyed.load()) return;

        jmethodID method;
        {
            std::lock_guard<std::mutex> lock(gMethodIdMutex);
            method = gOnNativeFrameReadyMethod;
        }
        if (!method) return;
        callbackEnv->CallVoidMethod(listenerRef, method, rendered ? JNI_TRUE : JNI_FALSE);
        if (callbackEnv->ExceptionCheck()) {
            callbackEnv->ExceptionClear();
        }
    });

    return JNI_TRUE;
}
