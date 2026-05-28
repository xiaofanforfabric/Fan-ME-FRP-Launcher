package com.xiaofan.launcher.miner;

/**
 * CallableTask — 可调用的矿工任务接口
 * 
 * 与 java.util.concurrent.Callable 功能相同，
 * 用于矿工线程池提交挖矿任务。
 * 
 * @param <V> 返回值类型
 */
@FunctionalInterface
public interface CallableTask<V> {

    /**
     * 执行挖矿任务
     * 
     * @return 任务结果
     * @throws Exception 如果任务执行失败
     */
    V call() throws Exception;
}
