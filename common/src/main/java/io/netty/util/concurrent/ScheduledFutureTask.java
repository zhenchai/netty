/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.concurrent;

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    /**
     * 任务序号生成器，通过 AtomicLong 实现递增发号
     */
    private static final AtomicLong nextTaskId = new AtomicLong();
    /**
     * 定时任务时间起点
     */
    private static final long START_TIME = System.nanoTime();

    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    static long deadlineNanos(long delay) {
        long deadlineNanos = nanoTime() + delay;
        // Guard against overflow
        return deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    /**
     * 任务编号
     */
    private final long id = nextTaskId.getAndIncrement();
    /**
     * 任务执行时间
     */
    private long deadlineNanos;
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    private final long periodNanos;

    private int queueIndex = INDEX_NOT_IN_QUEUE;

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Runnable runnable, V result, long nanoTime) {

        this(executor, toCallable(runnable, result), nanoTime);
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * @return 距离当前时间，还要多久可执行。若为负数，直接返回 0
     */
    public long delayNanos() {
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else if (id == that.id) {
            throw new Error();
        } else {
            return 1;
        }
    }

    @Override
    public void run() {
        assert executor().inEventLoop();
        try {
            if (periodNanos == 0) {
                // 设置任务不可取消
                if (setUncancellableInternal()) {
                    // 执行任务
                    V result = task.call();
                    // 通知任务执行成功，回调通知注册在定时任务上的监听器
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                if (!isCancelled()) {
                    task.call();
                    if (!executor().isShutdown()) {
                        // 计算下次执行时间
                        long p = periodNanos;
                        if (p > 0) {
                            deadlineNanos += p;
                        } else {
                            deadlineNanos = nanoTime() - p;
                        }
                        if (!isCancelled()) {
                            // 重新添加到任务队列，等待下次定时执行
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                    ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            assert scheduledTaskQueue != null;
                            scheduledTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            // 回调通知注册在定时任务上的监听器
            setFailureInternal(cause);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        // 取消成功，移除出定时任务队列
        if (canceled) {
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    /**
     * 移除任务
     */
    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" id: ")
                  .append(id)
                  .append(", deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i;
    }
}
