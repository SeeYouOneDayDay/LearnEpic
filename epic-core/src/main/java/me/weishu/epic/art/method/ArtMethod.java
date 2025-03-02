/*
 * Copyright (c) 2017, weishu twsxtd@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.weishu.epic.art.method;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;

import de.robv.android.xposed.XposedHelpers;
import me.weishu.epic.art.EpicNative;
import utils.Logger;
import utils.NeverCalled;

/**
 * Object stands for a Java Method, may be a constructor or a method.
 */
public class ArtMethod {

    private static final String TAG = "ArtMethod";

    /**
     * The address of the Art method. this is not the real memory address of the java.lang.reflect.Method
     * But the address used by VM which stand for the Java method.
     * generally, it was the address of art::mirror::ArtMethod. @{link #objectAddress}
     *
     * Art 方法的地址。 这不是 java.lang.reflect.Method 的真实内存地址，而是代表 Java 方法的 VM 使用的地址。
     * 通常，它是 art::mirror::ArtMethod 的地址。 @{链接#objectAddress}
     */
    private long address;

    /**
     * the origin object if this is a constructor
     * 如果这是构造函数，则为原始对象
     */
    private Constructor constructor;

    /**
     * the origin object if this is a method;
     * 如果这是一个方法，则为源对象；
     */
    private Method method;

    /**
     * the origin ArtMethod if this method is a backup of someone, null when this is not backup
     * 如果此方法是某人的备份，则为原始 ArtMethod，如果不是备份，则为 null
     */
    private ArtMethod origin;

    /**
     * The size of ArtMethod, usually the java part of ArtMethod may not stand for the whole one
     * may be some native field is placed in the end of header.
     * ArtMethod 的大小，通常 ArtMethod 的 java 部分可能不代表整个，可能是一些 native 字段放在 header 的末尾。
     */
    private static int artMethodSize = -1;

    private ArtMethod(Constructor constructor) {
        if (constructor == null) {
            throw new IllegalArgumentException("constructor can not be null");
        }
        this.constructor = constructor;
        init();
    }

    private ArtMethod(Method method, long address) {
        if (method == null) {
            throw new IllegalArgumentException("method can not be null");
        }
        this.method = method;
        if (address != -1) {
            Logger.d(TAG, "ArtMethod the address is " + address);
            this.address = address;
        } else {
            Logger.d(TAG, "ArtMethod the address will get native");
            init();
        }
    }

    private void init() {

        if (constructor != null) {
//            Logger.d(TAG,Log.getStackTraceString(new Exception("=======ArtMethod["+constructor+"]=====")));
            address = EpicNative.getMethodAddress(constructor);
//            address = ArtHelper.getConstructorAddress(constructor);
            Logger.d(TAG, "init() getMethodAddress constructor :" + EpicNative.getMethodAddress(constructor) + "--->" + address + "____JAVA___" + address);
        } else {
//            Logger.d(TAG,Log.getStackTraceString(new Exception("=======ArtMethod["+method+"]=====")));
            address = EpicNative.getMethodAddress(method);
//            address = ArtHelper.getMethodAddress(method);
            Logger.d(TAG, "init() getMethodAddress method: " + method + "----->" + EpicNative.getMethodAddress(method) + "____JAVA___" + address);
        }
    }

    public static ArtMethod of(Method method) {
        return new ArtMethod(method, -1);
    }

    public static ArtMethod of(Method method, long address) {
        return new ArtMethod(method, address);
    }

    public static ArtMethod of(Constructor constructor) {
        return new ArtMethod(constructor);
    }


    public ArtMethod backup() {
        try {
            // 考虑版本兼容
            // Before Oreo, it is: java.lang.reflect.AbstractMethod
            // After Oreo, it is: java.lang.reflect.Executable
            Class<?> abstractMethodClass = Method.class.getSuperclass();

            Object executable = this.getExecutable();
            ArtMethod artMethod;
            if (Build.VERSION.SDK_INT < 23) {
                Class<?> artMethodClass = Class.forName("java.lang.reflect.ArtMethod");
                //Get the original artMethod field
                Field artMethodField = abstractMethodClass.getDeclaredField("artMethod");
                if (!artMethodField.isAccessible()) {
                    artMethodField.setAccessible(true);
                }
                Object srcArtMethod = artMethodField.get(executable);

                Constructor<?> constructor = artMethodClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object destArtMethod = constructor.newInstance();

                //Fill the fields to the new method we created
                for (Field field : artMethodClass.getDeclaredFields()) {
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    field.set(destArtMethod, field.get(srcArtMethod));
                }
                Method newMethod = Method.class.getConstructor(artMethodClass).newInstance(destArtMethod);
                newMethod.setAccessible(true);
                artMethod = ArtMethod.of(newMethod);
                // 交换地址
                artMethod.setEntryPointFromQuickCompiledCode(getEntryPointFromQuickCompiledCode());
                artMethod.setEntryPointFromJni(getEntryPointFromJni());

                Logger.i("ErDog", "ArtMethod backup() <23 artMethod"
                        + "\r\n\taddr:" + artMethod.getAddress()
                        + "\r\n\tEntryPointFromQuickCompiledCode:" + artMethod.getEntryPointFromQuickCompiledCode()
                        + "\r\n\tEntryPointFromQuickCompiledCode:" + artMethod.getEntryPointFromJni()
                );

            } else {
                Constructor<Method> constructor = Method.class.getDeclaredConstructor();
                // we can't use constructor.setAccessible(true); because Google does not like it
                //我们不能使用 constructor.setAccessible(true); 因为谷歌会限制它
                // AccessibleObject.setAccessible(new AccessibleObject[]{constructor}, true);

                // Indicates whether language-level access checks are overridden by this object. Initializes to "false". This field is used by Field, Method, and Constructor.
                //  NOTE: for security purposes, this field must not be visible outside this package.
                //指示此对象是否覆盖语言级别的访问检查。 初始化为“假”。 此字段由字段、方法和构造函数使用。
                //注意：出于安全目的，此字段在此包外不得可见。
                // boolean override;
                Field override = AccessibleObject.class.getDeclaredField(
                        Build.VERSION.SDK_INT == Build.VERSION_CODES.M ? "flag" : "override");
                override.setAccessible(true);
                override.set(constructor, true);

                Method m = constructor.newInstance();
                m.setAccessible(true);
                for (Field field : abstractMethodClass.getDeclaredFields()) {
                    field.setAccessible(true);
                    field.set(m, field.get(executable));
                }
                Field artMethodField = abstractMethodClass.getDeclaredField("artMethod");
                artMethodField.setAccessible(true);
                int artMethodSize = getArtMethodSize();
                long memoryAddress = EpicNative.map(artMethodSize);

                // 交换地址
                byte[] data = EpicNative.get(address, artMethodSize);
                EpicNative.put(data, memoryAddress);
                artMethodField.set(m, memoryAddress);
                // From Android R, getting method address may involve the jni_id_manager which uses
                // ids mapping instead of directly returning the method address. During resolving the
                // id->address mapping, it will assume the art method to be from the "methods_" array
                // in class. However this address may be out of the range of the methods array. Thus
                // it will cause a crash during using the method offset to resolve method array.
                artMethod = ArtMethod.of(m, memoryAddress);
            }
            artMethod.makePrivate();
            artMethod.setAccessible(true);
            artMethod.origin = this; // save origin method.


            Logger.i("ErDog", "ArtMethod backup() >=23 artMethod"
                    + "\r\n\taddr:" + artMethod.getAddress()
                    + "\r\n\tEntryPointFromQuickCompiledCode:" + artMethod.getEntryPointFromQuickCompiledCode()
                    + "\r\n\tEntryPointFromQuickCompiledCode:" + artMethod.getEntryPointFromJni()
            );
            return artMethod;

        } catch (Throwable e) {
            Log.e(TAG, "backup method error:", e);
            throw new IllegalStateException("Cannot create backup method from :: " + getExecutable(), e);
        }
    }

    /**
     * @return is method/constructor accessible
     */
    public boolean isAccessible() {
        if (constructor != null) {
            return constructor.isAccessible();
        } else {
            return method.isAccessible();
        }
    }

    /**
     * make the constructor or method accessible
     * @param accessible accessible
     */
    public void setAccessible(boolean accessible) {
        if (constructor != null) {
            constructor.setAccessible(accessible);
        } else {
            method.setAccessible(accessible);
        }
    }

    /**
     * get the origin method's name
     * @return constructor name of method name
     */
    public String getName() {
        if (constructor != null) {
            return constructor.getName();
        } else {
            return method.getName();
        }
    }

    public Class<?> getDeclaringClass() {
        if (constructor != null) {
            return constructor.getDeclaringClass();
        } else {
            return method.getDeclaringClass();
        }
    }

    /**
     * Force compile the method to avoid interpreter mode.
     * This is only used above Android N
     * @return if compile success return true, otherwise false.
     */
    public boolean compile() {
        if (constructor != null) {
            return EpicNative.compileMethod(constructor);
        } else {
            return EpicNative.compileMethod(method);
        }
    }

    /**
     * invoke the origin method
     * @param receiver the receiver
     * @param args origin method/constructor's parameters
     * @return origin method's return value.
     * @throws IllegalAccessException throw if no access, impossible.
     * @throws InvocationTargetException invoke target error.
     * @throws InstantiationException throw when the constructor can not create instance.
     */
    public Object invoke(Object receiver, Object... args) throws IllegalAccessException, InvocationTargetException, InstantiationException {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (origin != null) {
                byte[] currentAddress = EpicNative.get(origin.address, 4);
                byte[] backupAddress = EpicNative.get(address, 4);
                if (!Arrays.equals(currentAddress, backupAddress)) {
                    Logger.i(TAG, "the address of java method was moved by gc, backup it now! origin address: 0x"
                            + Arrays.toString(currentAddress) + " , currentAddress: 0x" + Arrays.toString(backupAddress));
                    EpicNative.put(currentAddress, address);
                    return invokeInternal(receiver, args);
                } else {
                    Logger.i(TAG, "the address is same with last invoke, not moved by gc");
                }
            }
        }

        return invokeInternal(receiver, args);
    }

    private Object invokeInternal(Object receiver, Object... args) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        if (constructor != null) {
            return constructor.newInstance(args);
        } else {
            return method.invoke(receiver, args);
        }
    }

    /**
     * get the modifiers of origin method/constructor
     * @return the modifiers
     */
    public int getModifiers() {
        if (constructor != null) {
            return constructor.getModifiers();
        } else {
            return method.getModifiers();
        }
    }

    /**
     * get the parameter type of origin method/constructor
     * @return the parameter types.
     */
    public Class<?>[] getParameterTypes() {
        if (constructor != null) {
            return constructor.getParameterTypes();
        } else {
            return method.getParameterTypes();
        }
    }

    /**
     * get the return type of origin method/constructor
     * @return the return type, if it is a constructor, return Object.class
     */
    public Class<?> getReturnType() {
        if (constructor != null) {
            return Object.class;
        } else {
            return method.getReturnType();
        }
    }

    /**
     * get the exception declared by the method/constructor
     * @return the array of declared exception.
     */
    public Class<?>[] getExceptionTypes() {
        if (constructor != null) {
            return constructor.getExceptionTypes();
        } else {
            return method.getExceptionTypes();
        }
    }

    public String toGenericString() {
        if (constructor != null) {
            return constructor.toGenericString();
        } else {
            return method.toGenericString();
        }
    }

    /**
     * @return the origin method/constructor
     */
    public Object getExecutable() {
        if (constructor != null) {
            return constructor;
        } else {
            return method;
        }
    }

    /**
     * get the memory address of the inner constructor/method
     * @return the method address, in general, it was the pointer of art::mirror::ArtMethod
     */
    public long getAddress() {
        return address;
    }

    /**
     * get the unique identifier of the constructor/method
     * @return the method identifier
     */
    public String getIdentifier() {
        // Can we use address, may gc move it??
        return String.valueOf(getAddress());
    }

    /**
     * force set the private flag of the method.
     */
    public void makePrivate() {
        int accessFlags = getAccessFlags();
        accessFlags &= ~Modifier.PUBLIC;
        accessFlags |= Modifier.PRIVATE;
        setAccessFlags(accessFlags);
    }

    /**
     * the static method is lazy resolved, when not resolved, the entry point is a trampoline of
     * a bridge, we can not hook these entry. this method force the static method to be resolved.
     */
    public void ensureResolved() {
        Logger.d(TAG, "inside ensureResolved");
        if (!Modifier.isStatic(getModifiers())) {
            Logger.d(TAG, "not static, ignore.");
            return;
        }

//        try {
//            invoke(null);
//            Logger.d(TAG, "ensure resolved");
//        } catch (Exception ignored) {
//            // we should never make a successful call.
//            Logger.e(ignored);
//        } finally {
//            Logger.d(TAG, "------");
//            EpicNative.MakeInitializedClassVisibilyInitialized();
//        }


        try {
//            invoke(null);
            if (method != null) {
                method.setAccessible(true);

                Class[] pars = method.getParameterTypes();
                if (pars.length < 1) {
                    method.invoke(null);
                } else {
                    method.invoke(null, getFakeArgs(pars));
                }
            }
            if (constructor != null) {
                constructor.setAccessible(true);
                constructor.newInstance((Object[]) null);
            }
            Logger.d(TAG, "ensure resolved");
        } catch (Exception ignored) {
            // we should never make a successful call.
            Logger.e(ignored);
        } finally {
            Logger.d(TAG, "---貌似注释去除也可以正常使用。需要大面积兼容测试---");
            EpicNative.MakeInitializedClassVisibilyInitialized();
        }
    }

    private static Object[] getFakeArgs(Class[] pars) {
        if (pars == null || pars.length == 0) {
            return null;
        } else {
            Object[] obj = new Object[pars.length];
            for (int i = 0; i < pars.length; i++) {
                if (
                        pars[i].isAssignableFrom(int.class) || pars[i].isAssignableFrom(Number.class)
                                || pars[i].isAssignableFrom(long.class) || pars[i].isAssignableFrom(double.class)
                                || pars[i].isAssignableFrom(short.class) || pars[i].isAssignableFrom(float.class)
                ) {
                    obj[i] = 0;
                } else if (pars[i].isAssignableFrom(byte.class)) {
                    obj[i] = (byte) 0;
                } else if (pars[i].isAssignableFrom(char.class)) {
                    obj[i] = (char) 0;
                } else {
                    obj[i] = null;
                }
            }
            return obj;
        }
    }

    /**
     * The entry point of the quick compiled code.
     * @return the entry point.
     */
    public long getEntryPointFromQuickCompiledCode() {
//        Logger.i(TAG, "inside getEntryPointFromQuickCompiledCode " );
        return Offset.read(address, Offset.ART_QUICK_CODE_OFFSET);
    }

    /**
     * @param pointer_entry_point_from_quick_compiled_code the entry point.
     */
    public void setEntryPointFromQuickCompiledCode(long pointer_entry_point_from_quick_compiled_code) {
        Logger.i(TAG, "inside setEntryPointFromQuickCompiledCode: " + pointer_entry_point_from_quick_compiled_code);
        Offset.write(address, Offset.ART_QUICK_CODE_OFFSET, pointer_entry_point_from_quick_compiled_code);
    }

    /**
     * @return the access flags of the method/constructor, not only stand for the modifiers.
     */
    public int getAccessFlags() {
//        Logger.i(TAG, "inside getAccessFlags " );
        return (int) Offset.read(address, Offset.ART_ACCESS_FLAG_OFFSET);
    }

    public void setAccessFlags(int newFlags) {
//        Logger.i(TAG, "===========设置访问标志的偏移量setAccessFlags【" + newFlags + "】============");

        Offset.write(address, Offset.ART_ACCESS_FLAG_OFFSET, newFlags);
    }

    public void setEntryPointFromJni(long entryPointFromJni) {
//        Logger.i(TAG, "===========设置Jni 入口点的偏移量setEntryPointFromJni【" + entryPointFromJni + "】============");
        Offset.write(address, Offset.ART_JNI_ENTRY_OFFSET, entryPointFromJni);
    }

    public long getEntryPointFromJni() {
//        Logger.i(TAG, "===========读取Jni 入口点的偏移量getEntryPointFromJni============");

        return Offset.read(address, Offset.ART_JNI_ENTRY_OFFSET);
    }

    /**
     * The size of an art::mirror::ArtMethod, we use two rule method to measure the size
     * @return the size
     */
    public static int getArtMethodSize() {
        if (artMethodSize > 0) {
            return artMethodSize;
        }
        final Method rule1 = XposedHelpers.findMethodExact(ArtMethod.class, "rule1");
        final Method rule2 = XposedHelpers.findMethodExact(ArtMethod.class, "rule2");
        final long rule2Address = EpicNative.getMethodAddress(rule2);
        final long rule1Address = EpicNative.getMethodAddress(rule1);
        final long size = Math.abs(rule2Address - rule1Address);
        artMethodSize = (int) size;
        Logger.d(TAG, "getArtMethodSize() art Method "
                + "\r\n\trule1Address: " + EpicNative.getMethodAddress(rule1)
                + "\r\n\trule2Address: " + EpicNative.getMethodAddress(rule2)
                + "\r\n\tartMethodSize: " + artMethodSize
        );
        return artMethodSize;
    }

    private void rule1() {
        Log.i(TAG, "do not inline me!!");
    }

    private void rule2() {
        Log.i(TAG, "do not inline me!!");
    }

    public static long getQuickToInterpreterBridge() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return -1L;
        }
        final Method fake = XposedHelpers.findMethodExact(NeverCalled.class, "fake", int.class);
        return ArtMethod.of(fake).getEntryPointFromQuickCompiledCode();
    }

    public long getFieldOffset() {
        // searchOffset(address, )
        return 0L;
    }

    /**
     * search Offset in memory
     * @param base base address
     * @param range search range
     * @param value search value
     * @return the first address of value if found
     */
    public static long searchOffset(long base, long range, int value) {
        final int align = 4;
        final long step = range / align;
        for (long i = 0; i < step; i++) {
            long offset = i * align;
            final byte[] bytes = EpicNative.memget(base + i * align, align);
            final int valueInOffset = ByteBuffer.allocate(4).put(bytes).getInt();
            if (valueInOffset == value) {
                return offset;
            }
        }
        return -1;
    }

    public static long searchOffset(long base, long range, long value) {
        final int align = 4;
        final long step = range / align;
        for (long i = 0; i < step; i++) {
            long offset = i * align;
            final byte[] bytes = EpicNative.memget(base + i * align, align);
            final long valueInOffset = ByteBuffer.allocate(8).put(bytes).getLong();
            if (valueInOffset == value) {
                return offset;
            }
        }
        return -1;
    }
}
