/*
 * Original Copyright 2014-2015 Marvin Wißfeld
 * Modified work Copyright (c) 2017, weishu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <android/log.h>
#include <sys/mman.h>
#include <errno.h>
#include <unistd.h>
#include <dlfcn.h>
#include <cstdlib>
#include <sys/system_properties.h>
#include <fcntl.h>
#include "fake_dlfcn.h"
#include "art.h"

////#define TAG_NAME    "epic.native"
//#define LOGV(...)  ((void)__android_log_print(ANDROID_LOG_VERBOSE, "epic.native", __VA_ARGS__))
//#define LOGD(...)  ((void)__android_log_print(ANDROID_LOG_DEBUG, "epic.native", __VA_ARGS__))
//#define LOGI(...)  ((void)__android_log_print(ANDROID_LOG_INFO, "epic.native", __VA_ARGS__))
//#define LOGE(...)  ((void)__android_log_print(ANDROID_LOG_ERROR, "epic.native", __VA_ARGS__))

#define TAG_NAME    "epic.native"
#define LOGV(...)  ((void)__android_log_print(ANDROID_LOG_VERBOSE, TAG_NAME, __VA_ARGS__))
#define LOGD(...)  ((void)__android_log_print(ANDROID_LOG_DEBUG, TAG_NAME, __VA_ARGS__))
#define LOGI(...)  ((void)__android_log_print(ANDROID_LOG_INFO, TAG_NAME, __VA_ARGS__))
#define LOGE(...)  ((void)__android_log_print(ANDROID_LOG_ERROR, TAG_NAME, __VA_ARGS__))


#define JNIHOOK_CLASS "me/weishu/epic/art/EpicNative"

//把 art::mirror::Object 转换为 jobject对象，这样我们可以通过JNI进而转化为Java对象。
//art::JavaVMExt::AddWeakGlobalReference(art::Thread*, art::mirror::Object*)
//此函数在 libart.so中，我们可以通过 dlsym拿到函数指针，然后直接调用。
//      不过这个函数有一个art::Thread 的参数，如何拿到这个参数呢？
//      查阅 art::Thread 的源码发现，这个 art::Thread 与 java.lang.Thread 也有某种对应关系，
//      它们是通过peer结合在一起的（JNI文档中有讲）。
//      也就是说，java.lang.Thread类中的 nativePeer 成员代表的就是当前线程的 art::Thread对象。
//      这个问题迎刃而解。
jobject (*addWeakGloablReference)(JavaVM *, void *, void *) = nullptr;

void *(*jit_load_)(bool *) = nullptr;

void *jit_compiler_handle_ = nullptr;

bool (*jit_compile_method_)(void *, void *, void *, bool) = nullptr;

void *(*JitCodeCache_GetCurrentRegion)(void *) = nullptr;

typedef bool (*JIT_COMPILE_METHOD1)(void *, void *, void *, bool);

typedef bool (*JIT_COMPILE_METHOD2)(void *, void *, void *, bool, bool); // Android Q
typedef bool (*JIT_COMPILE_METHOD3)(void *, void *, void *, void *, bool, bool); // Android R
typedef bool (*JIT_COMPILE_METHOD4)(void *, void *, void *, void *, int); // Android S

void (*jit_unload_)(void *) = nullptr;


/**
 * 在Hook的过程中暂停所有其他线程，不让它们有机会修改代码；在Hook完毕之后在恢复执行。
 *      那么问题来了，如何暂停/恢复所有线程？Google了一番发现有人通过ptrace实现：
 *      开一个linux task然后挨个ptrace本进程内的所有子线程，这样就是实现了暂停。
 *      这种方式很重而且不是特别稳定，于是我就放弃了。ART虚拟机内部一定也有暂停线程的需求（比如GC），
 *      因此我可以选择直接调用ART的内部函数。
 *
 * 在源码里面捞了一番之后果然在thread_list.cc 中找到了这样的函数 resumeAll/suspendAll；
 *      不过遗憾的是这两个函数是ThreadList类的成员函数，要调用他们必须拿到ThreadList的指针；
 *      一般情况下是没有比较稳定的方式拿到这个对象的。不过好在Android 源码通过RAII机制对 suspendAll/resumeAll做了一个封装，
 *      名为 ScopedSuspendAll 这类的构造函数里面执行暂停操作，析构函数执行恢复操作，
 *      在栈上分配变量此类型的变量之后，在这个变量的作用域内可以自动实现暂停和恢复。
 *      因此我只需要用 dlsym 拿到构造函数和析构函数的符号之后，直接调用就能实现暂停恢复功能
 */
class ScopedSuspendAll {
};

void (*suspendAll)(ScopedSuspendAll *, char *) = nullptr;

void (*resumeAll)(ScopedSuspendAll *) = nullptr;

class ScopedJitSuspend {
};

void (*startJit)(ScopedJitSuspend *) = nullptr;

void (*stopJit)(ScopedJitSuspend *) = nullptr;

void (*DisableMovingGc)(void *) = nullptr;

void *(*JniIdManager_DecodeMethodId_)(void *, jlong) = nullptr;

void *(*ClassLinker_MakeInitializedClassesVisiblyInitialized_)(void *, void *, bool) = nullptr;

void *__self() {

#ifdef __arm__
    register uint32_t r9 asm("r9");
    return (void*) r9;
#elif defined(__aarch64__)
    register uint64_t x19 asm("x19");
    return (void*) x19;
#else
#endif
};

static int api_level;

void init_entries(JNIEnv *env) {
    // get device version
    char api_level_str[5];
    __system_property_get("ro.build.version.sdk", api_level_str);
    //int atoi (const char * str); 字符串转int
    api_level = atoi(api_level_str);
    LOGV("api level: %d", api_level);
    ArtHelper::init(env, api_level);
    // 操作方式:
    // 1. 加载libart.so库 （N之后是二进制方式加载）
    // 2. 获取 addWeakGloablReference地址
    // 3. 7.1以上版本 加载libart-compiler.so库,额外获取jit_compile_method、jit_load、suspendAll、resumeAll等 ，根据版本不同，调整JIT编译相关的函数获取(纯属个人理解)
    //
    if (api_level < 23) {
        // Android L, art::JavaVMExt::AddWeakGlobalReference(art::Thread*, art::mirror::Object*)
        void *handle = dlopen("libart.so", RTLD_LAZY | RTLD_GLOBAL);
        addWeakGloablReference = (jobject (*)(JavaVM *, void *, void *)) dlsym(handle,
                                                                               "_ZN3art9JavaVMExt22AddWeakGlobalReferenceEPNS_6ThreadEPNS_6mirror6ObjectE");
    } else if (api_level < 24) {
        // Android M, art::JavaVMExt::AddWeakGlobalRef(art::Thread*, art::mirror::Object*)
        void *handle = dlopen("libart.so", RTLD_LAZY | RTLD_GLOBAL);
        addWeakGloablReference = (jobject (*)(JavaVM *, void *, void *)) dlsym(handle,
                                                                               "_ZN3art9JavaVMExt16AddWeakGlobalRefEPNS_6ThreadEPNS_6mirror6ObjectE");
    } else {
        // Android N and O, Google disallow us use dlsym;
        void *handle = dlopen_ex("libart.so", RTLD_NOW);
        void *jit_lib = dlopen_ex("libart-compiler.so", RTLD_NOW);
        LOGV("fake dlopen libart install: %p", handle);
        LOGV("fake dlopen libart-compiler install: %p", jit_lib);
        const char *addWeakGloablReferenceSymbol = api_level <= 25
                                                   ? "_ZN3art9JavaVMExt16AddWeakGlobalRefEPNS_6ThreadEPNS_6mirror6ObjectE"
                                                   : "_ZN3art9JavaVMExt16AddWeakGlobalRefEPNS_6ThreadENS_6ObjPtrINS_6mirror6ObjectEEE";
        addWeakGloablReference = (jobject (*)(JavaVM *, void *, void *)) dlsym_ex(handle,
                                                                                  addWeakGloablReferenceSymbol);

        jit_compile_method_ = (bool (*)(void *, void *, void *, bool)) dlsym_ex(jit_lib,
                                                                                "jit_compile_method");
        jit_load_ = reinterpret_cast<void *(*)(bool *)>(dlsym_ex(jit_lib, "jit_load"));
        bool generate_debug_info = false;
        jit_compiler_handle_ = (jit_load_)(&generate_debug_info);
        LOGV("jit compile_method: %p", jit_compile_method_);

        // elf打开搜索符号表 DynamicSymbols
        // 下面两个啥区别呀，木有搞定
        // [dlopen libart.so]_ZN3art16ScopedSuspendAllC1EPKcb
        // [dlopen libart.so]_ZN3art16ScopedSuspendAllD1Ev
        // [dlopen libart.so]_ZN3art16ScopedSuspendAllC2EPKcb
        // [dlopen libart.so]_ZN3art16ScopedSuspendAllD2Ev
        suspendAll = reinterpret_cast<void (*)(ScopedSuspendAll *, char *)>(dlsym_ex(handle,
                                                                                     "_ZN3art16ScopedSuspendAllC1EPKcb"));
        resumeAll = reinterpret_cast<void (*)(ScopedSuspendAll *)>(dlsym_ex(handle,
                                                                            "_ZN3art16ScopedSuspendAllD1Ev"
                                                                            ));

        if (api_level >= 30) {
            // Android R would not directly return ArtMethod address but an internal id
            ClassLinker_MakeInitializedClassesVisiblyInitialized_ = reinterpret_cast<void *(*)(
                    void *, void *, bool)>(dlsym_ex(handle,
                                                    "_ZN3art11ClassLinker40MakeInitializedClassesVisiblyInitializedEPNS_6ThreadEb"));
            JniIdManager_DecodeMethodId_ = reinterpret_cast<void *(*)(void *, jlong)>(dlsym_ex(
                    handle, "_ZN3art3jni12JniIdManager14DecodeMethodIdEP10_jmethodID"));
            if (api_level >= 31) {
                // Android S CompileMethod accepts a CompilationKind enum instead of two booleans
                // source: https://android.googlesource.com/platform/art/+/refs/heads/android12-release/compiler/jit/jit_compiler.cc
                jit_compile_method_ = (bool (*)(void *, void *, void *, bool)) dlsym_ex(jit_lib,
                                                                                        "_ZN3art3jit11JitCompiler13CompileMethodEPNS_6ThreadEPNS0_15JitMemoryRegionEPNS_9ArtMethodENS_15CompilationKindE");
            } else {
                jit_compile_method_ = (bool (*)(void *, void *, void *, bool)) dlsym_ex(jit_lib,
                                                                                        "_ZN3art3jit11JitCompiler13CompileMethodEPNS_6ThreadEPNS0_15JitMemoryRegionEPNS_9ArtMethodEbb");
            }
            JitCodeCache_GetCurrentRegion = (void *(*)(void *)) dlsym_ex(handle,
                                                                         "_ZN3art3jit12JitCodeCache16GetCurrentRegionEv");
        }
        // Disable this now.
        // startJit = reinterpret_cast<void(*)(ScopedJitSuspend*)>(dlsym_ex(handle, "_ZN3art3jit16ScopedJitSuspendD1Ev"));
        // stopJit = reinterpret_cast<void(*)(ScopedJitSuspend*)>(dlsym_ex(handle, "_ZN3art3jit16ScopedJitSuspendC1Ev"));

        // DisableMovingGc = reinterpret_cast<void(*)(void*)>(dlsym_ex(handle, "_ZN3art2gc4Heap15DisableMovingGcEv"));
    }

    LOGV("addWeakGloablReference: %p", addWeakGloablReference);
}

jboolean epic_compile(JNIEnv *env, jclass, jobject method, jlong self) {
    LOGV("epic_compile() self from native peer: %p, from register: %p",
         reinterpret_cast<void *>(self), __self());
    jlong art_method = (jlong) env->FromReflectedMethod(method);

    if (art_method % 2 == 1) {
        art_method = reinterpret_cast<jlong>(JniIdManager_DecodeMethodId_(
                ArtHelper::getJniIdManager(), art_method));
    }
    bool ret;
    if (api_level >= 30) {
        void *current_region = JitCodeCache_GetCurrentRegion(ArtHelper::getJitCodeCache());
        if (api_level >= 31) {
            ret = ((JIT_COMPILE_METHOD4) jit_compile_method_)(jit_compiler_handle_,
                                                              reinterpret_cast<void *>(self),
                                                              reinterpret_cast<void *>(current_region),
                                                              reinterpret_cast<void *>(art_method),
                                                              1);
        } else {
            ret = ((JIT_COMPILE_METHOD3) jit_compile_method_)(jit_compiler_handle_,
                                                              reinterpret_cast<void *>(self),
                                                              reinterpret_cast<void *>(current_region),
                                                              reinterpret_cast<void *>(art_method),
                                                              false, false);
        }
    } else if (api_level >= 29) {
        ret = ((JIT_COMPILE_METHOD2) jit_compile_method_)(jit_compiler_handle_,
                                                          reinterpret_cast<void *>(art_method),
                                                          reinterpret_cast<void *>(self), false,
                                                          false);
    } else {
        ret = ((JIT_COMPILE_METHOD1) jit_compile_method_)(jit_compiler_handle_,
                                                          reinterpret_cast<void *>(art_method),
                                                          reinterpret_cast<void *>(self), false);
    }
    return (jboolean) ret;
}

jlong epic_suspendAll(JNIEnv *, jclass) {
    // 申请内存
    ScopedSuspendAll *scopedSuspendAll = (ScopedSuspendAll *) malloc(sizeof(ScopedSuspendAll));
    //  将要调用的函数放进去
    suspendAll(scopedSuspendAll, "stop_jit");
    return reinterpret_cast<jlong >(scopedSuspendAll);
}

void epic_resumeAll(JNIEnv *env, jclass, jlong obj) {
    ScopedSuspendAll *scopedSuspendAll = reinterpret_cast<ScopedSuspendAll *>(obj);
    resumeAll(scopedSuspendAll);
}

jlong epic_stopJit(JNIEnv *, jclass) {
    ScopedJitSuspend *scopedJitSuspend = (ScopedJitSuspend *) malloc(sizeof(ScopedJitSuspend));
    stopJit(scopedJitSuspend);
    return reinterpret_cast<jlong >(scopedJitSuspend);
}

void epic_startJit(JNIEnv *, jclass, jlong obj) {
    ScopedJitSuspend *scopedJitSuspend = reinterpret_cast<ScopedJitSuspend *>(obj);
    startJit(scopedJitSuspend);
}

void epic_disableMovingGc(JNIEnv *env, jclass, jint api) {
    void *heap = ArtHelper::getHeap();
    DisableMovingGc(heap);
}

// 申请一段可读取的对其的空间
jboolean epic_munprotect(JNIEnv *env, jclass, jlong addr, jlong len) {
    //sysconf - 在运行时获取配置信息。 这是个查看缓存内存页面大小
    // 有一个获取物理内存的小的方案：通过将 sysconf (_SC_PHYS_PAGES) 和 sysconf (_SC_PAGESIZE) 相乘，来确定物理内存的总量 (以字节为单位) 可以返回一个值
    long pagesize = sysconf(_SC_PAGESIZE);
    unsigned alignment = (unsigned) ((unsigned long long) addr % pagesize);
    LOGV("munprotect page size: %d, alignment: %d", pagesize, alignment);

    //    mprotect()函数可以用来修改一段指定内存区域的保护属性
    //https://bbs.pediy.com/thread-266527.htm
    //https://bbs.huaweicloud.com/blogs/325120
    //http://drops.xmd5.com/static/drops/papers-10156.html
    int i = mprotect((void *) (addr - alignment), (size_t) (alignment + len),
                     PROT_READ | PROT_WRITE | PROT_EXEC);
    if (i == -1) {
        LOGV("mprotect failed: %s (%d)", strerror(errno), errno);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jboolean epic_cacheflush(JNIEnv *env, jclass, jlong addr, jlong len) {
#if defined(__arm__)
    int i = cacheflush(addr, addr + len, 0);
    LOGV("arm cacheflush for, %ul", addr);
    if (i == -1) {
        LOGV("cache flush failed: %s (%d)", strerror(errno), errno);
        return JNI_FALSE;
    }
#elif defined(__aarch64__)
    char* begin = reinterpret_cast<char*>(addr);
    __builtin___clear_cache(begin, begin + len);
    LOGV("aarch64 __builtin___clear_cache, %p", (void*)begin);
#endif
    return JNI_TRUE;
}

void epic_MakeInitializedClassVisibilyInitialized(JNIEnv *env, jclass, jlong self) {
    if (api_level >= 30 && ClassLinker_MakeInitializedClassesVisiblyInitialized_ &&
        ArtHelper::getClassLinker()) {
        ClassLinker_MakeInitializedClassesVisiblyInitialized_(ArtHelper::getClassLinker(),
                                                              reinterpret_cast<void *>(self), true);
    }
}

void epic_memcpy(JNIEnv *env, jclass, jlong src, jlong dest, jint length) {
    char *srcPnt = (char *) src;
    char *destPnt = (char *) dest;
    for (int i = 0; i < length; ++i) {
        destPnt[i] = srcPnt[i];
    }
}

void epic_memput(JNIEnv *env, jclass, jbyteArray src, jlong dest) {
    // 获取列表第一个元素
    jbyte *srcPnt = env->GetByteArrayElements(src, 0);
    // 获取列表大小
    jsize length = env->GetArrayLength(src);
    // 获取目标指针
    unsigned char *destPnt = (unsigned char *) dest;
    for (int i = 0; i < length; ++i) {
        LOGV("epic_memput() put %d with %d", i, *(srcPnt + i));
        destPnt[i] = (unsigned char) srcPnt[i];
    }
    env->ReleaseByteArrayElements(src, srcPnt, 0);
}

jbyteArray epic_memget(JNIEnv *env, jclass, jlong src, jint length) {

    // 新建对应长度的array
    jbyteArray dest = env->NewByteArray(length);
    if (dest == NULL) {
        return NULL;
    }
    LOGD("epic_memget() , src: %ld, length: %ld ", src, length);

    // 获取array的第一个元素。第一个元素存放对应内容， why?
    unsigned char *destPnt = (unsigned char *) env->GetByteArrayElements(dest, 0);
    // 读取对应地址内容。确保是无符号的指针
    unsigned char *srcPnt = (unsigned char *) src;
    // 根据传递长度进行挨个复制
    for (int i = 0; i < length; ++i) {
        destPnt[i] = srcPnt[i];
    }
    //释放. what.
    env->ReleaseByteArrayElements(dest, (jbyte *) destPnt, 0);
    LOGD("epic_memget() , dest: %p, destPnt: %p, srcPnt: %p ", (void *) dest, destPnt, srcPnt);
    return dest;
}

jobject epic_getobject(JNIEnv *env, jclass clazz, jlong self, jlong address) {
    JavaVM *vm;
    env->GetJavaVM(&vm);
    LOGD("epic_getobject java vm: %p, self: %p, address: %p", vm, (void *) self, (void *) address);
    jobject object = addWeakGloablReference(vm, (void *) self, (void *) address);

    return object;
}

jlong epic_mmap(JNIEnv *env, jclass, jint length) {
    void *space = mmap(0, (size_t) length, PROT_READ | PROT_WRITE | PROT_EXEC,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (space == MAP_FAILED) {
        LOGV("mmap failed: %d", errno);
        return 0;
    }
    return (jlong) space;
}

//释放制定地址后的地址
void epic_munmap(JNIEnv *env, jclass, jlong addr, jint length) {
    int r = munmap((void *) addr, (size_t) length);
    if (r == -1) {
        LOGV("munmap failed: %d", errno);
    }
}

//这个单纯申请空间。
jlong epic_malloc(JNIEnv *env, jclass, jint size) {
    LOGV("inside malloc. sizeof(void *) :%d  size: %p", sizeof(void *), size);

    size_t length = sizeof(void *) * size;
    void *ptr = malloc(length);
    LOGV("malloc :%d of memory at: %p", (int) length, ptr);
    return (jlong) ptr;
}

jlong epic_getMethodAddress(JNIEnv *env, jclass clazz, jobject method) {
    //转化获取方法ID,就是ArtMethod指针
    //将java.lang.reflect.Method或者java.lang.reflect.Constructor对象转换为方法ID
    //jmethodID FromReflectedMethod(JNIEnv *env,jobject method);
    jlong art_method = (jlong) env->FromReflectedMethod(method);
    if (art_method % 2 == 1) {
        art_method = reinterpret_cast<jlong>(JniIdManager_DecodeMethodId_(
                ArtHelper::getJniIdManager(), art_method));
    }
    return art_method;
}

jboolean epic_isGetObjectAvaliable(JNIEnv *, jclass) {
    return (jboolean) (addWeakGloablReference != nullptr);
}


//activateNative(long jumpToAddress, long pc, long sizeOfTargetJump, long sizeOfBridgeJump, byte[] code)
jboolean epic_activate(JNIEnv *env, jclass jclazz, jlong jumpToAddress, jlong pc, jlong sizeOfDirectJump,
              jlong sizeOfBridgeJump, jbyteArray code) {

    // fetch the array, we can not call this when thread suspend(may lead deadlock)
    jbyte *srcPnt = env->GetByteArrayElements(code, 0);
    jsize length = env->GetArrayLength(code);

    jlong cookie = 0;
    bool isNougat = api_level >= 24;
    if (isNougat) {
        // We do thus things:
        // 1. modify the code mprotect
        // 2. modify the code

        // Ideal, this two operation must be atomic. Below N, this is safe, because no one
        // modify the code except ourselves;
        // But in Android N, When the jit is working, between our step 1 and step 2,
        // if we modity the mprotect of the code, and planning to write the code,
        // the jit thread may modify the mprotect of the code meanwhile
        // we must suspend all thread to ensure the atomic operation.

        LOGV("suspend all thread.");
        //android 7+ 暂停所有线程
        cookie = epic_suspendAll(env, jclazz);
    }

    // 修改对应地址的权限
    jboolean result = epic_munprotect(env, jclazz, jumpToAddress, sizeOfDirectJump);
    if (result) {
        // 拷贝跳转地址到另一个空间上
        unsigned char *destPnt = (unsigned char *) jumpToAddress;
        for (int i = 0; i < length; ++i) {
            destPnt[i] = (unsigned char) srcPnt[i];
        }
        // 回收原来的内容。因为内容已经更新到 destPnt
        jboolean ret = epic_cacheflush(env, jclazz, pc, sizeOfBridgeJump);
        if (!ret) {
            LOGV("cache flush failed!!");
        }
    } else {
        LOGV("Writing hook failed: Unable to unprotect memory at %d", jumpToAddress);
    }

    if (cookie != 0) {
        LOGV("resume all thread.");
        // 重新开放JIT编译
        epic_resumeAll(env, jclazz, cookie);
    }
    env->ReleaseByteArrayElements(code, srcPnt, 0);
    return result;
}

static JNINativeMethod dexposedMethods[] = {

        {"mmap",                                    "(I)J",                           (void *) epic_mmap},
        {"munmap",                                  "(JI)Z",                          (void *) epic_munmap},
        {"memcpy",                                  "(JJI)V",                         (void *) epic_memcpy},
        {"memput",                                  "([BJ)V",                         (void *) epic_memput},
        {"memget",                                  "(JI)[B",                         (void *) epic_memget},
        {"munprotect",                              "(JJ)Z",                          (void *) epic_munprotect},
        {"getMethodAddress",                        "(Ljava/lang/reflect/Member;)J",  (void *) epic_getMethodAddress},
        {"cacheflush",                              "(JJ)Z",                          (void *) epic_cacheflush},
        {"MakeInitializedClassVisibilyInitialized", "(J)V",                           (void *) epic_MakeInitializedClassVisibilyInitialized},
        {"malloc",                                  "(I)J",                           (void *) epic_malloc},
        {"getObjectNative",                         "(JJ)Ljava/lang/Object;",         (void *) epic_getobject},
        {"compileMethod",                           "(Ljava/lang/reflect/Member;J)Z", (void *) epic_compile},
        {"suspendAll",                              "()J",                            (void *) epic_suspendAll},
        {"resumeAll",                               "(J)V",                           (void *) epic_resumeAll},
        {"stopJit",                                 "()J",                            (void *) epic_stopJit},
        {"startJit",                                "(J)V",                           (void *) epic_startJit},
        {"disableMovingGc",                         "(I)V",                           (void *) epic_disableMovingGc},
        {"activateNative",                          "(JJJJ[B)Z",                      (void *) epic_activate},
        {"isGetObjectAvailable",                    "()Z",                            (void *) epic_isGetObjectAvaliable}
};

static int registerNativeMethods(JNIEnv *env, const char *className,
                                 JNINativeMethod *gMethods, int numMethods) {

    jclass clazz = env->FindClass(className);
    if (clazz == NULL) {
        return JNI_FALSE;
    }

    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {

    JNIEnv *env = NULL;

    LOGV(" inside JNI_OnLoad");

    if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }

    if (!registerNativeMethods(env, JNIHOOK_CLASS, dexposedMethods,
                               sizeof(dexposedMethods) / sizeof(dexposedMethods[0]))) {
        return -1;
    }

    init_entries(env);
    return JNI_VERSION_1_6;
}
