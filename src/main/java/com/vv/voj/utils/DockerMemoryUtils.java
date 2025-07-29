package com.vv.voj.utils;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Statistics;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Docker 容器内存监控工具类
 */
public class DockerMemoryUtils {

    /**
     * 内存监控线程类，用于持续记录容器运行期间的最大内存使用值
     */
    public static class MemoryMonitor {
        private final DockerClient dockerClient;
        private final String containerId;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private volatile long maxMemory = 0L;
        private Thread monitorThread;

        public MemoryMonitor(DockerClient dockerClient, String containerId) {
            this.dockerClient = dockerClient;
            this.containerId = containerId;
        }

        /**
         * 启动内存监控线程
         */
        public void start() {
            running.set(true);
            monitorThread = new Thread(() -> {
                try {
                    StatsCmd statsCmd = dockerClient.statsCmd(containerId);
                    statsCmd.exec(new ResultCallback.Adapter<Statistics>() {
                        @Override
                        public void onNext(Statistics stats) {
                            if (!running.get()) {
                                try {
                                    close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                                return;
                            }

                            try {
                                if (stats != null && stats.getMemoryStats() != null) {
                                    Long usage = stats.getMemoryStats().getUsage();
                                    if (usage != null) {
                                        maxMemory = Math.max(maxMemory, usage);
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            System.err.println("内存监控异常: " + throwable.getMessage());
                        }

                        @Override
                        public void onStart(Closeable closeable) {
                            // 可选
                        }

                        @Override
                        public void onComplete() {
                            // 可选
                        }
                    });

                    // 持续运行，直到调用 stop()
                    while (running.get()) {
                        Thread.sleep(200);
                    }

                } catch (Exception e) {
                    System.err.println("内存监控线程启动失败: " + e.getMessage());
                }
            });

            monitorThread.setDaemon(true);
            monitorThread.start();
        }

        /**
         * 停止监控线程
         */
        public void stop() {
            running.set(false);
            try {
                if (monitorThread != null) {
                    monitorThread.join(1000);  // 最多等待1秒关闭
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 获取容器运行期间的最大内存使用（单位：字节）
         */
        public long getMaxMemory() {
            return maxMemory;
        }
    }

    /**
     * 获取某一时刻的容器内存使用（一次性），用于调试
     */
    public static long getContainerMemoryUsageOnce(DockerClient dockerClient, String containerId) {
        final long[] memoryUsage = {0L};
        final Object lock = new Object();

        StatsCmd statsCmd = dockerClient.statsCmd(containerId);
        ResultCallback.Adapter<Statistics> callback = new ResultCallback.Adapter<Statistics>() {
            @Override
            public void onNext(Statistics stats) {
                if (stats != null && stats.getMemoryStats() != null) {
                    memoryUsage[0] = stats.getMemoryStats().getUsage();
                }
                synchronized (lock) {
                    lock.notify();
                }
                try {
                    this.close();
                } catch (IOException ignored) {}
            }

            @Override
            public void onError(Throwable throwable) {
                System.err.println("一次性内存获取失败: " + throwable.getMessage());
                synchronized (lock) {
                    lock.notify();
                }
            }
        };

        synchronized (lock) {
            statsCmd.exec(callback);
            try {
                lock.wait(3000); // 最多等3秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return memoryUsage[0];
    }
}
