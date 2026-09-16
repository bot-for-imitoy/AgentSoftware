package com.agent.software.adapters.llm;

import java.util.function.BooleanSupplier;

/**
 * 每 endpoint 的限流排队，重试次数多者优先。
 */
public final class RetryArbiter {

    /** 取得（或创建）某 endpoint 在 JVM 内共享的仲裁器。 */
    public static RetryArbiter forEndpoint(String endpointKey) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 清空全部共享仲裁器（测试隔离用）。 */
    public static void clearShared() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 为一次尝试获取排队名额；keepWaiting 为假或线程中断时返回 null 表示放弃本次尝试。 */
    public Slot acquire(int retries, long pollMillis, BooleanSupplier keepWaiting) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 上报一次可重试的 429/5xx，使 endpoint 进入拥塞排队状态。 */
    public void throttled(String cause) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 当前 endpoint 是否处于拥塞状态。 */
    public boolean isCongested() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 一次尝试占用的名额，用完必须释放。 */
    public final class Slot implements AutoCloseable {

        /** 归名额并声明本次尝试是否成功（成功且无人排队时解除拥塞）。 */
        public void release(boolean succeeded) {
            throw new UnsupportedOperationException("skeleton");
        }

        /** 默认按未成功释放。 */
        @Override
        public void close() {
            throw new UnsupportedOperationException("skeleton");
        }
    }
}
