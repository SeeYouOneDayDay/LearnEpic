package de.robv.android.xposed;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.closeSilently;
import static de.robv.android.xposed.XposedHelpers.fileContains;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findFieldIfExists;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getOverriddenMethods;
import static de.robv.android.xposed.XposedHelpers.getParameterIndexByType;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static de.robv.android.xposed.XposedHelpers.setStaticBooleanField;
import static de.robv.android.xposed.XposedHelpers.setStaticLongField;
import static de.robv.android.xposed.XposedHelpers.setStaticObjectField;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XCallback;
import de.robv.android.xposed.services.BaseService;
import utils.Reflect;
import utils.Subclass;

/*package*/ final class XposedInit {
    private static final String TAG = XposedBridge.TAG;

    private static final boolean startsSystemServer = XposedBridge.startsSystemServer();
    private static final String startClassName = XposedBridge.getStartClassName();

    private static final String INSTALLER_PACKAGE_NAME = "de.robv.android.xposed.installer";
    @SuppressLint("SdCardPath")
    private static final String BASE_DIR = Build.VERSION.SDK_INT >= 24
            ? "/data/user_de/0/" + INSTALLER_PACKAGE_NAME + "/"
            : "/data/data/" + INSTALLER_PACKAGE_NAME + "/";
    private static final String INSTANT_RUN_CLASS = "com.android.tools.fd.runtime.BootstrapApplication";

    private static boolean disableResources = false;
    private static final String[] XRESOURCES_CONFLICTING_PACKAGES = {"com.sygic.aura"};

    private XposedInit() {
    }

    /**
     * Hook some methods which we want to create an easier interface for developers.
     */
    /*package*/
    static void initForZygote() throws Throwable {
        if (needsToCloseFilesForFork()) {
            XC_MethodHook callback = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.closeFilesBeforeForkNative();
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XposedBridge.reopenFilesAfterForkNative();
                }
            };

            Class<?> zygote = findClass("com.android.internal.os.Zygote", null);
            hookAllMethods(zygote, "nativeForkAndSpecialize", callback);
            hookAllMethods(zygote, "nativeForkSystemServer", callback);
        }

        final HashSet<String> loadedPackagesInProcess = new HashSet<>(1);


        // normal process initialization (for new Activity, Service, BroadcastReceiver etc.)
        findAndHookMethod(Reflect.findClass("android.app.ActivityThread")
                , "handleBindApplication", "android.app.ActivityThread.AppBindData"
                , new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object activityThread = param.thisObject;
                        ApplicationInfo appInfo = (ApplicationInfo) getObjectField(param.args[0], "appInfo");
                        String reportedPackageName = appInfo.packageName.equals("android") ? "system" : appInfo.packageName;
                        SELinuxHelper.initForProcess(reportedPackageName);
                        ComponentName instrumentationName = (ComponentName) getObjectField(param.args[0], "instrumentationName");
                        if (instrumentationName != null) {
                            Log.w(TAG, "Instrumentation detected, disabling framework for " + reportedPackageName);
                            XposedBridge.disableHooks = true;
                            return;
                        }
                        Object compatInfo = getObjectField(param.args[0], "compatInfo");
                        if (appInfo.sourceDir == null)
                            return;

                        setObjectField(activityThread, "mBoundApplication", param.args[0]);
                        loadedPackagesInProcess.add(reportedPackageName);

//                LoadedApk loadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);
//                XResources.setPackageNameForResDir(appInfo.packageName, loadedApk.getResDir());
                        Object loadedApk = Reflect.on(activityThread).call("getPackageInfoNoCheck", appInfo, compatInfo).get();
                        Reflect.on("android.content.res.XResources").call("setPackageNameForResDir", appInfo.packageName
                                , Reflect.on(loadedApk).call("getResDir").get());

                        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
                        lpparam.packageName = reportedPackageName;
                        lpparam.processName = (String) getObjectField(param.args[0], "processName");
//                        lpparam.classLoader = loadedApk.getClassLoader();
                        lpparam.classLoader = Reflect.on(loadedApk).call("getClassLoader").get();
                        lpparam.appInfo = appInfo;
                        lpparam.isFirstApplication = true;
                        XC_LoadPackage.callAll(lpparam);

                        if (reportedPackageName.equals(INSTALLER_PACKAGE_NAME))
                            hookXposedInstaller(lpparam.classLoader);
                    }
                });

        // system_server initialization
        if (Build.VERSION.SDK_INT < 21) {
            findAndHookMethod("com.android.server.ServerThread", null,
                    Build.VERSION.SDK_INT < 19 ? "run" : "initAndLoop", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            SELinuxHelper.initForProcess("android");
                            loadedPackagesInProcess.add("android");

                            XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
                            lpparam.packageName = "android";
                            lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
                            lpparam.classLoader = XposedBridge.BOOTCLASSLOADER;
                            lpparam.appInfo = null;
                            lpparam.isFirstApplication = true;
                            XC_LoadPackage.callAll(lpparam);
                        }
                    });
        } else if (startsSystemServer) {
            findAndHookMethod(Reflect.findClass("android.app.ActivityThread"), "systemMain"
                    , new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final ClassLoader cl = Thread.currentThread().getContextClassLoader();
                            findAndHookMethod("com.android.server.SystemServer", cl, "startBootstrapServices", new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    SELinuxHelper.initForProcess("android");
                                    loadedPackagesInProcess.add("android");

                                    XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
                                    lpparam.packageName = "android";
                                    lpparam.processName = "android"; // it's actually system_server, but other functions return this as well
                                    lpparam.classLoader = cl;
                                    lpparam.appInfo = null;
                                    lpparam.isFirstApplication = true;
                                    XC_LoadPackage.callAll(lpparam);

                                    // Huawei
                                    try {
                                        findAndHookMethod("com.android.server.pm.HwPackageManagerService", cl, "isOdexMode", XC_MethodReplacement.returnConstant(false));
                                    } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {
                                    }

                                    try {
                                        String className = "com.android.server.pm." + (Build.VERSION.SDK_INT >= 23 ? "PackageDexOptimizer" : "PackageManagerService");
                                        findAndHookMethod(className, cl, "dexEntryExists", String.class, XC_MethodReplacement.returnConstant(true));
                                    } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {
                                    }
                                }
                            });
                        }
                    });
        }

        // when a package is loaded for an existing process, trigger the callbacks as well
        hookAllConstructors(Reflect.findClass("android.app.LoadedApk"), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object loadedApk = param.thisObject;


//                String packageName = loadedApk.getPackageName();
//                XResources.setPackageNameForResDir(packageName, loadedApk.getResDir());
                String packageName = Reflect.on(loadedApk).call("getPackageName").get();
                Reflect.on("android.content.res.XResources").call("setPackageNameForResDir", packageName
                        , Reflect.on(loadedApk).call("getResDir").get());
                if (packageName.equals("android") || !loadedPackagesInProcess.add(packageName))
                    return;

                if (!getBooleanField(loadedApk, "mIncludeCode"))
                    return;

                XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(XposedBridge.sLoadedPackageCallbacks);
                lpparam.packageName = packageName;
//                lpparam.processName = AndroidAppHelper.currentProcessName();
//                lpparam.classLoader = loadedApk.getClassLoader();
//                lpparam.appInfo = loadedApk.getApplicationInfo();

                lpparam.processName = Reflect.on("android.app.AndroidAppHelper").call("currentProcessName").get();
                lpparam.classLoader = Reflect.on(loadedApk).call("getClassLoader").get();
                lpparam.appInfo = Reflect.on(loadedApk).call("getApplicationInfo").get();
                lpparam.isFirstApplication = false;
                XC_LoadPackage.callAll(lpparam);
            }
        });

        findAndHookMethod("android.app.ApplicationPackageManager", null, "getResourcesForApplication",
                ApplicationInfo.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        ApplicationInfo app = (ApplicationInfo) param.args[0];
//                        XResources.setPackageNameForResDir(app.packageName,
//                                app.uid == Process.myUid() ? app.sourceDir : app.publicSourceDir);

                        Reflect.on("android.content.res.XResources").call("setPackageNameForResDir", app.packageName,
                                app.uid == Process.myUid() ? app.sourceDir : app.publicSourceDir);
                    }
                });

        // MIUI
//        if (findFieldIfExists(ZygoteInit.class, "BOOT_START_TIME") != null) {
//            setStaticLongField(ZygoteInit.class, "BOOT_START_TIME", XposedBridge.BOOT_START_TIME);
//        }
        if (findFieldIfExists(Reflect.findClass("com.android.internal.os.ZygoteInit"), "BOOT_START_TIME") != null) {
            setStaticLongField(Reflect.findClass("com.android.internal.os.ZygoteInit"), "BOOT_START_TIME", XposedBridge.BOOT_START_TIME);
        }

        // Samsung
        if (Build.VERSION.SDK_INT >= 24) {
            Class<?> zygote = findClass("com.android.internal.os.Zygote", null);
            try {
                setStaticBooleanField(zygote, "isEnhancedZygoteASLREnabled", false);
            } catch (NoSuchFieldError ignored) {
            }
        }
    }

    /*package*/
    static void hookResources() throws Throwable {
        if (SELinuxHelper.getAppDataFileService().checkFileExists(BASE_DIR + "conf/disable_resources")) {
            Log.w(TAG, "Found " + BASE_DIR + "conf/disable_resources, not hooking resources");
            disableResources = true;
            return;
        }

        if (!XposedBridge.initXResourcesNative()) {
            Log.e(TAG, "Cannot hook resources");
            disableResources = true;
            return;
        }

        /*
         * getTopLevelResources(a)
         *   -> getTopLevelResources(b)
         *     -> key = new ResourcesKey()
         *     -> r = new Resources()
         *     -> mActiveResources.put(key, r)
         *     -> return r
         */

        final Class<?> classGTLR;
        final Class<?> classResKey;
        final ThreadLocal<Object> latestResKey = new ThreadLocal<>();

        if (Build.VERSION.SDK_INT <= 18) {
            classGTLR = Reflect.findClass("android.app.ActivityThread");
            classResKey = Reflect.findClass("android.app.ActivityThread$ResourcesKey");
        } else {
            classGTLR = Reflect.findClass("android.app.ResourcesManager");
            classResKey = Reflect.findClass("android.content.res.ResourcesKey");
        }

        if (Build.VERSION.SDK_INT >= 24) {
            hookAllMethods(classGTLR, "getOrCreateResources", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // At least on OnePlus 5, the method has an additional parameter compared to AOSP.
                    final int activityTokenIdx = getParameterIndexByType(param.method, IBinder.class);
                    final int resKeyIdx = getParameterIndexByType(param.method, classResKey);

                    String resDir = (String) getObjectField(param.args[resKeyIdx], "mResDir");
//                    XResources newRes = cloneToXResources(param, resDir);
                    Object newRes = cloneToXResources(param, resDir);
                    if (newRes == null) {
                        return;
                    }

                    Object activityToken = param.args[activityTokenIdx];
                    synchronized (param.thisObject) {
                        ArrayList<WeakReference<Resources>> resourceReferences;
                        if (activityToken != null) {
                            Object activityResources = callMethod(param.thisObject, "getOrCreateActivityResourcesStructLocked", activityToken);
                            resourceReferences = (ArrayList<WeakReference<Resources>>) getObjectField(activityResources, "activityResources");
                        } else {
                            resourceReferences = (ArrayList<WeakReference<Resources>>) getObjectField(param.thisObject, "mResourceReferences");
                        }
                        resourceReferences.add(new WeakReference(newRes));
                    }
                }
            });
        } else {
            hookAllConstructors(classResKey, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    latestResKey.set(param.thisObject);
                }
            });

            hookAllMethods(classGTLR, "getTopLevelResources", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    latestResKey.set(null);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object key = latestResKey.get();
                    if (key == null) {
                        return;
                    }
                    latestResKey.set(null);

                    String resDir = (String) getObjectField(key, "mResDir");
//                    XResources newRes = cloneToXResources(param, resDir);
                    Object newRes = cloneToXResources(param, resDir);
                    if (newRes == null) {
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    Map<Object, WeakReference<Resources>> mActiveResources =
                            (Map<Object, WeakReference<Resources>>) getObjectField(param.thisObject, "mActiveResources");
                    Object lockObject = (Build.VERSION.SDK_INT <= 18)
                            ? getObjectField(param.thisObject, "mPackages") : param.thisObject;

                    synchronized (lockObject) {
                        Resources re = (Resources) newRes;
                        WeakReference<Resources> existing = mActiveResources.put(key, new WeakReference<Resources>(re));
//                        if (existing != null && existing.get() != null && existing.get().getAssets() != newRes.getAssets()) {
                        if (existing != null && existing.get() != null && existing.get().getAssets() != Reflect.on(newRes).call("getAssets").get()) {
                            existing.get().getAssets().close();
                        }
                    }
                }
            });

            if (Build.VERSION.SDK_INT >= 19) {
                // This method exists only on CM-based ROMs
                hookAllMethods(classGTLR, "getTopLevelThemedResources", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        String resDir = (String) param.args[0];
                        cloneToXResources(param, resDir);
                    }
                });
            }
        }

        // Invalidate callers of methods overridden by XTypedArray
        if (Build.VERSION.SDK_INT >= 24) {
//            Set<Method> methods = getOverriddenMethods(XResources.XTypedArray.class);
            Set<Method> methods = getOverriddenMethods(XposedHelpers.findClass("android.content.res.XResources$XTypedArray", null));
            XposedBridge.invalidateCallersNative(methods.toArray(new Member[methods.size()]));
        }

        // Replace TypedArrays with XTypedArrays
        hookAllConstructors(TypedArray.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                TypedArray typedArray = (TypedArray) param.thisObject;
                Resources res = typedArray.getResources();
//                if (res instanceof XResources) {
////                    XposedBridge.setObjectClass(typedArray, XResources.XTypedArray.class);
//                    XposedBridge.setObjectClass(typedArray, XposedHelpers.findClass("android.content.res.XResources$XTypedArray", null));
//                }
                if (Subclass.isSubClass(res, "android.content.res.XResources")) {
                    XposedBridge.setObjectClass(typedArray, XposedHelpers.findClass("android.content.res.XResources$XTypedArray", null));
                }
            }
        });

        // Replace system resources
        Object systemRes = XposedBridge.cloneToSubclass(Resources.getSystem(), Reflect.findClass("android.content.res.XResources"));
//        systemRes.initObject(null);
        Reflect.on(systemRes).call("initObject", (String) null);
        setStaticObjectField(Resources.class, "mSystem", systemRes);

//        XResources.init(latestResKey);
        Reflect.on("android.content.res.XResources").call("init", latestResKey);
    }

    private static Object cloneToXResources(XC_MethodHook.MethodHookParam param, String resDir) {
        Object result = param.getResult();
//        if (result == null || result instanceof XResources ||
//                Arrays.binarySearch(XRESOURCES_CONFLICTING_PACKAGES, AndroidAppHelper.currentPackageName()) == 0) {
//            return null;
//        }
        if (result == null
                || Subclass.isSubClass(result, "android.content.res.XResources")
                || Arrays.binarySearch(XRESOURCES_CONFLICTING_PACKAGES, Reflect.on("android.app.AndroidAppHelper").call("currentPackageName").get()) == 0) {
            return null;
        }

        // Replace the returned resources with our subclass.
        Object newRes = XposedBridge.cloneToSubclass(result, Reflect.findClass("android.content.res.XResources"));
//        newRes.initObject(resDir);
        Reflect.on(newRes).call("initObject", resDir);

//        // Invoke handleInitPackageResources().
//        if (newRes.isFirstLoad()) {
//            String packageName = newRes.getPackageName();
//            XC_InitPackageResources.InitPackageResourcesParam resparam = new XC_InitPackageResources.InitPackageResourcesParam(XposedBridge.sInitPackageResourcesCallbacks);
//            resparam.packageName = packageName;
//            resparam.res = newRes;
//            XCallback.callAll(resparam);
//        }

        if (Reflect.on(newRes).call("isFirstLoad").get()) {
            String packageName = Reflect.on(newRes).call("getPackageName").get();
            XC_InitPackageResources.InitPackageResourcesParam resparam = new XC_InitPackageResources.InitPackageResourcesParam(XposedBridge.sInitPackageResourcesCallbacks);
            resparam.packageName = packageName;
            resparam.res = newRes;
            XCallback.callAll(resparam);
        }

        param.setResult(newRes);
        return newRes;
    }

    private static boolean needsToCloseFilesForFork() {
        if (Build.VERSION.SDK_INT >= 24) {
            return true;
        } else if (Build.VERSION.SDK_INT < 21) {
            return false;
        }

        File lib = new File(Environment.getRootDirectory(), "lib/libandroid_runtime.so");
        try {
            return fileContains(lib, "Unable to construct file descriptor table");
        } catch (IOException e) {
            Log.e(TAG, "Could not check whether " + lib + " has security patch level 5");
            // In doubt, just do it. The worst case should be unnecessary work and log messages.
            return true;
        }
    }

    private static void hookXposedInstaller(ClassLoader classLoader) {
        try {
            findAndHookMethod(INSTALLER_PACKAGE_NAME + ".XposedApp", classLoader, "getActiveXposedVersion",
                    XC_MethodReplacement.returnConstant(XposedBridge.getXposedVersion()));

            findAndHookMethod(INSTALLER_PACKAGE_NAME + ".XposedApp", classLoader, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Application application = (Application) param.thisObject;
                    Resources res = application.getResources();
                    if (res.getIdentifier("installer_needs_update", "string", INSTALLER_PACKAGE_NAME) == 0) {
                        // If this resource is missing, take it as indication that the installer is outdated.
                        Log.e("XposedInstaller", "Xposed Installer is outdated (resource string \"installer_needs_update\" is missing)");
                        Toast.makeText(application, "Please update Xposed Installer!", Toast.LENGTH_LONG).show();
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Could not hook Xposed Installer", t);
        }
    }

    /**
     * Try to load all modules defined in <code>BASE_DIR/conf/modules.list</code>
     */
    /*package*/
    static void loadModules() throws IOException {
        final String filename = BASE_DIR + "conf/modules.list";
        BaseService service = SELinuxHelper.getAppDataFileService();
        if (!service.checkFileExists(filename)) {
            Log.e(TAG, "Cannot load any modules because " + filename + " was not found");
            return;
        }

        ClassLoader topClassLoader = XposedBridge.BOOTCLASSLOADER;
        ClassLoader parent;
        while ((parent = topClassLoader.getParent()) != null) {
            topClassLoader = parent;
        }

        InputStream stream = service.getFileInputStream(filename);
        BufferedReader apks = new BufferedReader(new InputStreamReader(stream));
        String apk;
        while ((apk = apks.readLine()) != null) {
            loadModule(apk, topClassLoader);
        }
        apks.close();
    }

    /**
     * Load a module from an APK by calling the init(String) method for all classes defined
     * in <code>assets/xposed_init</code>.
     */
    private static void loadModule(String apk, ClassLoader topClassLoader) {
        Log.i(TAG, "Loading modules from " + apk);

        if (!new File(apk).exists()) {
            Log.e(TAG, "  File does not exist");
            return;
        }

        DexFile dexFile;
        try {
            dexFile = new DexFile(apk);
        } catch (IOException e) {
            Log.e(TAG, "  Cannot load module", e);
            return;
        }

        if (dexFile.loadClass(INSTANT_RUN_CLASS, topClassLoader) != null) {
            Log.e(TAG, "  Cannot load module, please disable \"Instant Run\" in Android Studio.");
            closeSilently(dexFile);
            return;
        }

        if (dexFile.loadClass(XposedBridge.class.getName(), topClassLoader) != null) {
            Log.e(TAG, "  Cannot load module:");
            Log.e(TAG, "  The Xposed API classes are compiled into the module's APK.");
            Log.e(TAG, "  This may cause strange issues and must be fixed by the module developer.");
            Log.e(TAG, "  For details, see: http://api.xposed.info/using.html");
            closeSilently(dexFile);
            return;
        }

        closeSilently(dexFile);

        ZipFile zipFile = null;
        InputStream is;
        try {
            zipFile = new ZipFile(apk);
            ZipEntry zipEntry = zipFile.getEntry("assets/xposed_init");
            if (zipEntry == null) {
                Log.e(TAG, "  assets/xposed_init not found in the APK");
                closeSilently(zipFile);
                return;
            }
            is = zipFile.getInputStream(zipEntry);
        } catch (IOException e) {
            Log.e(TAG, "  Cannot read assets/xposed_init in the APK", e);
            closeSilently(zipFile);
            return;
        }

        ClassLoader mcl = new PathClassLoader(apk, XposedBridge.BOOTCLASSLOADER);
        BufferedReader moduleClassesReader = new BufferedReader(new InputStreamReader(is));
        try {
            String moduleClassName;
            while ((moduleClassName = moduleClassesReader.readLine()) != null) {
                moduleClassName = moduleClassName.trim();
                if (moduleClassName.isEmpty() || moduleClassName.startsWith("#"))
                    continue;

                try {
                    Log.i(TAG, "  Loading class " + moduleClassName);
                    Class<?> moduleClass = mcl.loadClass(moduleClassName);

                    if (!IXposedMod.class.isAssignableFrom(moduleClass)) {
                        Log.e(TAG, "    This class doesn't implement any sub-interface of IXposedMod, skipping it");
                        continue;
                    } else if (disableResources && IXposedHookInitPackageResources.class.isAssignableFrom(moduleClass)) {
                        Log.e(TAG, "    This class requires resource-related hooks (which are disabled), skipping it.");
                        continue;
                    }

                    final Object moduleInstance = moduleClass.newInstance();
                    if (XposedBridge.isZygote) {
                        if (moduleInstance instanceof IXposedHookZygoteInit) {
                            IXposedHookZygoteInit.StartupParam param = new IXposedHookZygoteInit.StartupParam();
                            param.modulePath = apk;
                            param.startsSystemServer = startsSystemServer;
                            ((IXposedHookZygoteInit) moduleInstance).initZygote(param);
                        }

                        if (moduleInstance instanceof IXposedHookLoadPackage)
                            XposedBridge.hookLoadPackage(new IXposedHookLoadPackage.Wrapper((IXposedHookLoadPackage) moduleInstance));

                        if (moduleInstance instanceof IXposedHookInitPackageResources)
                            XposedBridge.hookInitPackageResources(new IXposedHookInitPackageResources.Wrapper((IXposedHookInitPackageResources) moduleInstance));
                    } else {
                        if (moduleInstance instanceof IXposedHookCmdInit) {
                            IXposedHookCmdInit.StartupParam param = new IXposedHookCmdInit.StartupParam();
                            param.modulePath = apk;
                            param.startClassName = startClassName;
                            ((IXposedHookCmdInit) moduleInstance).initCmdApp(param);
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "    Failed to load class " + moduleClassName, t);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "  Failed to load module from " + apk, e);
        } finally {
            closeSilently(is);
            closeSilently(zipFile);
        }
    }
}
