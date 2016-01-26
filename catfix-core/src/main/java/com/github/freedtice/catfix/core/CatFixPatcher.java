package com.github.freedtice.catfix.core;

import android.app.Application;
import android.content.Context;
import android.support.annotation.NonNull;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * <p>
 * CatFixPatcher is a static method provider for loading patch dex file.
 * It loads specified dex files, insert the dex elements.
 * at first index.
 * </p>
 *
 * @author yuantong
 */
public class CatFixPatcher {


  /**
   * Static method container. make constructor as private.
   */
  private CatFixPatcher() {
  }

  /**
   * <p>
   * The default class used for preventing class pre-verify. <strong>If you are using <a href="https://github
   * .com/fREEDtICE/gradle-catfix">gradle-catfix</a>, DO NOT CHANGE THIS </strong>
   * </p>
   */
  public static String PRE_VERIFY_CLASS_NAME = "com.github.freedtice.catfix.android.ClassPreverifyPreventor";

  /**
   * Insert the specified dex file into
   *
   * @param app
   * @param dexPath
   */
  public static void patch(@NonNull Application app, @NonNull String dexPath) {
    injectSystemClassLoader(app, dexPath, PRE_VERIFY_CLASS_NAME);
  }

  public static void patch(@NonNull Application app, @NonNull String dexPath, @NonNull String preverifyName) {
    injectSystemClassLoader(app, dexPath, preverifyName);
  }

  private static boolean injectSystemClassLoader(Application app, String libPath, String startupClassName) {
    try {
      Class.forName("dalvik.system.LexClassLoader");
      return patchForAliYunOS(app, libPath, startupClassName);
    } catch (ClassNotFoundException ignore) {
    }

    // check for android version
    boolean hasBaseClassLoader = true;
    try {
      Class.forName("dalvik.system.BaseDexClassLoader");
    } catch (ClassNotFoundException e) {
      hasBaseClassLoader = false;
    }

    if (!hasBaseClassLoader) {
      return patchBelowICS(app, libPath, startupClassName);
    } else {
      return patchEqualOrUpICS(app, libPath, startupClassName);
    }
  }

  /**
   * patch for aliyun os
   * @param app
   * @param libPath
   * @param startupClassName
   * @return
   */
  private static boolean patchForAliYunOS(Application app, String libPath, String startupClassName) {
    PathClassLoader pathClassLoader = (PathClassLoader) app.getClassLoader();
    new DexClassLoader(libPath, app.getDir("patch_dex", Context.MODE_PRIVATE).getAbsolutePath(), null,
        pathClassLoader);
    String lexFileName = new File(libPath).getName();
    lexFileName = lexFileName.replaceAll("\\.[a-zA-Z0-9]+", ".lex");
    try {
      Class<?> classLexClassLoader = Class.forName("dalvik.system.LexClassLoader");
      Constructor<?> constructor = classLexClassLoader.getConstructor(String.class, String.class, String.class,
          ClassLoader.class);
      Object localLexClassLoader = constructor.newInstance(app.getDir("dex", Context.MODE_PRIVATE).getAbsolutePath()
              + File.separator + lexFileName, app.getDir("dex", Context.MODE_PRIVATE).getAbsolutePath(), libPath,
          pathClassLoader);
      Method methodLoadClass = classLexClassLoader.getMethod("loadClass", String.class);
      methodLoadClass.invoke(localLexClassLoader, startupClassName);

      setField(pathClassLoader, PathClassLoader.class, "mPaths", appendArray(
          getField(localLexClassLoader, classLexClassLoader, "mRawDexPath"),
          getField(pathClassLoader, PathClassLoader.class, "mPaths")
      ));
      setField(pathClassLoader, PathClassLoader.class, "mFiles", combineArray(
          getField(localLexClassLoader, classLexClassLoader, "mFiles"),
          getField(pathClassLoader, PathClassLoader.class, "mFiles")
      ));
      setField(pathClassLoader, PathClassLoader.class, "mZips", combineArray(
          getField(localLexClassLoader, classLexClassLoader, "mZips"),
          getField(pathClassLoader, PathClassLoader.class, "mZips")
      ));
      setField(pathClassLoader, PathClassLoader.class, "mLexs", combineArray(
          getField(localLexClassLoader, classLexClassLoader, "mDexs"),
          getField(pathClassLoader, PathClassLoader.class, "mLexs")
      ));
      return true;
    } catch (Throwable throwable) {
      throwable.printStackTrace();
      return false;
    }
  }

  /**
   * @param app
   * @param libPath
   * @param startupClassName
   * @return
   */
  private static boolean patchBelowICS(Application app, String libPath, String startupClassName) {
    PathClassLoader pathClassLoader = (PathClassLoader) app.getClassLoader();
    DexClassLoader dexClassLoader = new DexClassLoader(libPath, app.getDir("patch_dex", Context.MODE_PRIVATE)
        .getAbsolutePath(), null, app.getClassLoader());
    try {
      // load the start up class to prevent class pre-verify
      dexClassLoader.loadClass(startupClassName);
      setField(pathClassLoader, PathClassLoader.class, "mPaths", appendArray(
          getField(dexClassLoader, DexClassLoader.class, "mRawDexPath"),
          getField(pathClassLoader, PathClassLoader.class, "mPath")));

      setField(pathClassLoader, PathClassLoader.class, "mFiles", combineArray(
          getField(dexClassLoader, DexClassLoader.class, "mFiles"),
          getField(pathClassLoader, PathClassLoader.class, "mFiles")));

      setField(pathClassLoader, PathClassLoader.class, "mZips", combineArray(
          getField(dexClassLoader, DexClassLoader.class, "mZips"),
          getField(pathClassLoader, PathClassLoader.class, "mZips")));

      setField(pathClassLoader, PathClassLoader.class, "mDexs", appendArray(
          getField(dexClassLoader, DexClassLoader.class, "mDexs"),
          getField(pathClassLoader, PathClassLoader.class, "mDexs")));
      return true;
    } catch (Throwable e) {
      e.printStackTrace();
      return false;
    }
  }

  private static boolean patchEqualOrUpICS(Application app, String libPath, String startupClassName) {
    PathClassLoader pathClassLoader = (PathClassLoader) app.getClassLoader();
    DexClassLoader dexLoader = new DexClassLoader(libPath, app.getDir("patch_dex", Context.MODE_PRIVATE)
        .getAbsolutePath(),
        null, app.getClassLoader());

    try {
      dexLoader.loadClass(startupClassName);
      Object dexElements = combineArray(
          getDexElements(getPathList(dexLoader)),
          getDexElements(getPathList(pathClassLoader))
      );

      Object pathList = getPathList(pathClassLoader);
      setField(pathList, pathList.getClass(), "dexElements", dexElements);
      setField(pathClassLoader, Class.forName("dalvik.system.BaseDexClassLoader"), "pathList", pathList);
      return true;
    } catch (Throwable e) {
      e.printStackTrace();
      return false;
    }
  }

  private static Object appendArray(Object array, Object value) {
    Class<?> loadClass = array.getClass().getComponentType();
    int i = Array.getLength(array);
    int j = i + 1;
    Object localObject = Array.newInstance(loadClass, j);
    for (int k = 0; k < j; ++k) {
      if (k < i) {
        Array.set(loadClass, k, Array.get(array, k));
      } else {
        Array.set(localObject, k, value);
      }
    }
    return localObject;
  }

  private static void setField(Object obj, Class<?> cls, String field, Object value) throws IllegalAccessException,
      NoSuchFieldException {
    Field localField = cls.getDeclaredField(field);
    localField.setAccessible(true);
    localField.set(obj, value);
  }

  private static Object getPathList(Object baseDexClassLoader) throws ClassNotFoundException, NoSuchFieldException,
      IllegalAccessException {
    return getField(baseDexClassLoader, Class.forName("dalvik.system.BaseDexClassLoader"), "pathList");
  }

  private static Object getDexElements(Object paramObj) throws NoSuchFieldException, IllegalAccessException {
    return getField(paramObj, paramObj.getClass(), "dexElements");
  }

  private static Object getField(Object obj, Class<?> cl, String field) throws IllegalAccessException,
      NoSuchFieldException {
    Field localField = cl.getDeclaredField(field);
    localField.setAccessible(true);
    return localField.get(obj);
  }


  /**
   * combine two arrays to single
   * @param arrayLhs
   * @param arrayRhs
   * @return
   */
  private static Object combineArray(Object arrayLhs, Object arrayRhs) {
    Class<?> loadCLass = arrayLhs.getClass().getComponentType();
    int i = Array.getLength(arrayLhs);
    int j = i + Array.getLength(arrayRhs);
    Object result = Array.newInstance(loadCLass, j);
    for (int k = 0; k < j; k++) {
      if (k < i) {
        Array.set(result, k, Array.get(arrayLhs, k));
      } else {
        Array.set(result, k, Array.get(arrayRhs, k - i));
      }
    }

    return result;
  }
}

