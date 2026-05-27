package com.xiaofan.launcher.errors;

/**
 * 异常链测试类：
 * - 前面 7 种异常全部 try-catch 捕获，只打印堆栈，不影响后续执行
 * - 最后一个异常不捕获，直接抛出，触发崩溃报告生成器
 */
public class oneplusoneisnull {

    public static void errors(String[] args) {
        // 1. 捕获 NullPointerException
        try {
            triggerNPE();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. 捕获 ArrayIndexOutOfBoundsException
        try {
            triggerArrayIndexOutOfBounds();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. 捕获 ArithmeticException (除零)
        try {
            triggerArithmetic();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 4. 捕获 ClassCastException
        try {
            triggerClassCast();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 5. 捕获 StackOverflowError (注意：Error 类型，但可以捕获)
        try {
            triggerStackOverflow();
        } catch (StackOverflowError e) {
            e.printStackTrace();
        }

        // 6. 捕获 IllegalArgumentException
        try {
            triggerIllegalArg();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 7. 捕获自定义异常
        try {
            triggerCustom();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ★ 最后一个异常 —— 不捕获，直接抛出，让 JVM 崩溃并生成报告
        triggerLastUncaught();
    }

    // ----------------------------------------------
    // 各种异常的触发方法
    // ----------------------------------------------
    private static void triggerNPE() {
        ((Object) null).hashCode();
    }

    private static void triggerArrayIndexOutOfBounds() {
        int[] arr = new int[0];
        int x = arr[1];  // 越界
    }

    private static void triggerArithmetic() {
        int x = 1 / 0;
    }

    private static void triggerClassCast() {
        Object obj = "abc";
        Integer num = (Integer) obj;
    }

    private static void triggerStackOverflow() {
        recursion();
    }
    private static void recursion() {
        recursion();
    }

    private static void triggerIllegalArg() {
        throw new IllegalArgumentException("测试非法参数异常");
    }

    private static void triggerCustom() {
        throw new RuntimeException("这是一个自定义未捕获异常（但会被捕获）");
    }

    // 最后一个异常：抛出后不捕获，程序终止
    private static void triggerLastUncaught() {
        // 可以改为任何你想测试的异常，例如：
        ((String) null).length();   // NullPointerException
        // 或者：
        // int[] arr = new int[-1]; // NegativeArraySizeException
        // throw new OutOfMemoryError("故意 OOM");
    }
}