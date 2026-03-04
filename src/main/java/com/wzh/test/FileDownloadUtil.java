package com.wzh.test;


import com.jcraft.jsch.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

/**
 * 文件分片下载工具类 - 基于SFTP协议，支持多线程分片下载、断点续传、自动合并
 */
public class FileDownloadUtil {

    // ==================== 配置参数（请根据实际情况修改） ====================
    private static final String SERVER_IP = "36.150.236.251";  // 云服务器IP
    private static final int SSH_PORT = 22;  // SSH端口
    private static final String SSH_USERNAME = "root";  // SSH用户名
    private static final String SSH_PASSWORD = "WZHhzw.666";  // SSH密码
    private static final String REMOTE_FILE_PATH = "/home/帮助文档.rar";  // 远程文件路径
    private static final String LOCAL_SAVE_PATH = "D:/finall-shell/帮助文档.rar";  // 本地保存路径

    // 分片数量（线程数）- 20G大文件建议8-10个线程
    private static final int THREAD_COUNT = 8;

    // 缓冲区大小（1MB）
    private static final int BUFFER_SIZE = 1024 * 1024;

    // 连接超时时间（毫秒）- 增加到60秒
    private static final int CONNECT_TIMEOUT = 60000;

    // 服务器响应超时时间（毫秒）- 增加到5分钟
    private static final int SERVER_ALIVE_INTERVAL = 300000;

    // 每个分片的最大重试次数
    private static final int MAX_RETRY_COUNT = 5;

    // 重试等待时间（毫秒）
    private static final int RETRY_WAIT_TIME = 3000;

    // 临时文件目录
    private static final String TEMP_DIR = "D:/finall-shell/temp/";

    /**
     * 主函数入口
     */
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("SFTP文件分片下载工具启动");
        System.out.println("========================================");

        FileDownloadUtil downloader = new FileDownloadUtil();
        boolean success = downloader.downloadFileWithThreads();

        if (success) {
            System.out.println("\n========================================");
            System.out.println("下载任务完成！");
            System.out.println("文件保存位置: " + LOCAL_SAVE_PATH);
            System.out.println("========================================");
        } else {
            System.out.println("\n========================================");
            System.out.println("下载任务失败！");
            System.out.println("========================================");
        }
    }

    /**
     * 多线程分片下载文件
     */
    public boolean downloadFileWithThreads() {
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            // 1. 创建SSH连接
            System.out.println("正在连接服务器: " + SERVER_IP);
            JSch jsch = new JSch();
            session = jsch.getSession(SSH_USERNAME, SERVER_IP, SSH_PORT);
            session.setPassword(SSH_PASSWORD);

            // 跳过主机密钥检查
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            // 增加服务器存活检测，防止连接超时
            config.put("ServerAliveInterval", "60");  // 每60秒发送一次心跳
            config.put("ServerAliveCountMax", "10");  // 最多10次心跳无响应才断开
            session.setConfig(config);
            session.setTimeout(CONNECT_TIMEOUT);
            session.connect();

            System.out.println("SSH连接成功");

            // 2. 打开SFTP通道
            Channel channel = session.openChannel("sftp");
            channel.connect();
            sftpChannel = (ChannelSftp) channel;

            // 3. 获取远程文件大小
            long fileSize = sftpChannel.lstat(REMOTE_FILE_PATH).getSize();

            if (fileSize <= 0) {
                System.out.println("无法获取文件大小");
                return false;
            }

            System.out.println("远程文件: " + REMOTE_FILE_PATH);
            System.out.println("文件大小: " + formatSize(fileSize));
            System.out.println("下载线程数: " + THREAD_COUNT);
            System.out.println();

            // 4. 创建临时目录
            File tempDir = new File(TEMP_DIR);
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            // 5. 计算每个分片的大小
            long blockSize = fileSize / THREAD_COUNT;

            // 6. 创建线程池
            ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
            List<Future<Boolean>> futures = new ArrayList<>();

            // 7. 提交下载任务
            for (int i = 0; i < THREAD_COUNT; i++) {
                long startPos = i * blockSize;
                long endPos = (i == THREAD_COUNT - 1) ? fileSize - 1 : (i + 1) * blockSize - 1;
                String tempFilePath = TEMP_DIR + "part_" + i + ".tmp";

                DownloadTask task = new DownloadTask(startPos, endPos, tempFilePath, i);
                Future<Boolean> future = executor.submit(task);
                futures.add(future);
            }

            // 8. 等待所有任务完成
            boolean allSuccess = true;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    boolean result = futures.get(i).get();
                    if (!result) {
                        allSuccess = false;
                        System.out.println("分片 " + i + " 下载失败");
                    }
                } catch (Exception e) {
                    allSuccess = false;
                    System.out.println("分片 " + i + " 下载异常: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            executor.shutdown();

            if (!allSuccess) {
                System.out.println("\n部分分片下载失败");
                return false;
            }

            System.out.println("\n所有分片下载完成，开始合并文件...");

            // 9. 合并文件
            boolean mergeSuccess = mergeFiles(THREAD_COUNT);

            if (!mergeSuccess) {
                System.out.println("文件合并失败");
                return false;
            }

            // 10. 删除临时文件
            System.out.println("开始清理临时文件...");
            deleteTemporaryFiles(THREAD_COUNT);

            return true;

        } catch (Exception e) {
            System.out.println("下载过程发生异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            // 关闭连接
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * 合并所有分片文件
     */
    private boolean mergeFiles(int threadCount) {
        File outputFile = new File(LOCAL_SAVE_PATH);

        // 确保父目录存在
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];

            for (int i = 0; i < threadCount; i++) {
                File tempFile = new File(TEMP_DIR + "part_" + i + ".tmp");

                if (!tempFile.exists()) {
                    System.out.println("分片文件不存在: " + tempFile.getName());
                    return false;
                }

                try (FileInputStream fis = new FileInputStream(tempFile)) {
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }

                System.out.println("已合并分片 " + i);
            }

            System.out.println("文件合并完成");
            return true;

        } catch (IOException e) {
            System.out.println("合并文件时发生错误: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 删除临时文件
     */
    private void deleteTemporaryFiles(int threadCount) {
        for (int i = 0; i < threadCount; i++) {
            File tempFile = new File(TEMP_DIR + "part_" + i + ".tmp");
            if (tempFile.exists()) {
                if (tempFile.delete()) {
                    System.out.println("已删除临时文件: " + tempFile.getName());
                } else {
                    System.out.println("删除临时文件失败: " + tempFile.getName());
                }
            }
        }

        // 删除临时目录
        File tempDir = new File(TEMP_DIR);
        if (tempDir.exists() && tempDir.list().length == 0) {
            if (tempDir.delete()) {
                System.out.println("已删除临时目录");
            }
        }
    }

    /**
     * 格式化文件大小
     */
    private String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", size / 1024.0 / 1024.0);
        } else {
            return String.format("%.2f GB", size / 1024.0 / 1024.0 / 1024.0);
        }
    }

    /**
     * SFTP下载任务类（支持断点续传和自动重试）
     */
    static class DownloadTask implements Callable<Boolean> {
        private long startPos;
        private long endPos;
        private String filePath;
        private int threadId;

        public DownloadTask(long startPos, long endPos, String filePath, int threadId) {
            this.startPos = startPos;
            this.endPos = endPos;
            this.filePath = filePath;
            this.threadId = threadId;
        }

        @Override
        public Boolean call() {
            int retryCount = 0;

            // 重试循环
            while (retryCount < MAX_RETRY_COUNT) {
                try {
                    boolean success = downloadWithRetry();
                    if (success) {
                        return true;
                    }
                    // 下载未完成，继续重试
                    retryCount++;
                    if (retryCount < MAX_RETRY_COUNT) {
                        System.out.println("线程 " + threadId + " 准备第 " + (retryCount + 1) + " 次重试...");
                        Thread.sleep(RETRY_WAIT_TIME);
                    }
                } catch (Exception e) {
                    retryCount++;
                    System.out.println("线程 " + threadId + " 第 " + retryCount + " 次尝试失败: " + e.getMessage());
                    if (retryCount < MAX_RETRY_COUNT) {
                        try {
                            System.out.println("线程 " + threadId + " 等待 " + (RETRY_WAIT_TIME / 1000) + " 秒后重试...");
                            Thread.sleep(RETRY_WAIT_TIME);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }
            }

            System.out.println("线程 " + threadId + " 达到最大重试次数，下载失败");
            return false;
        }

        /**
         * 执行下载（单次尝试）
         */
        private boolean downloadWithRetry() throws Exception {
            Session session = null;
            ChannelSftp sftpChannel = null;
            InputStream inputStream = null;
            RandomAccessFile randomAccessFile = null;

            try {
                // 检查是否已有部分下载
                File tempFile = new File(filePath);
                long downloadedSize = 0;

                if (tempFile.exists()) {
                    downloadedSize = tempFile.length();
                    if (downloadedSize > 0) {
                        System.out.println("线程 " + threadId + " 从断点继续: " + formatSize(downloadedSize));
                    }
                }

                // 如果已下载完成，直接返回
                long totalSize = endPos - startPos + 1;
                if (downloadedSize >= totalSize) {
                    System.out.println("线程 " + threadId + " 已完成下载");
                    return true;
                }

                // 计算实际开始位置（断点续传）
                long actualStartPos = startPos + downloadedSize;

                // 创建SSH连接
                JSch jsch = new JSch();
                session = jsch.getSession(SSH_USERNAME, SERVER_IP, SSH_PORT);
                session.setPassword(SSH_PASSWORD);

                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "no");
                // 关键：增加心跳保活机制
                config.put("ServerAliveInterval", "60");  // 每60秒发送心跳
                config.put("ServerAliveCountMax", "10");   // 最多10次无响应
                session.setConfig(config);
                session.setTimeout(CONNECT_TIMEOUT);
                session.connect();

                // 打开SFTP通道
                Channel channel = session.openChannel("sftp");
                channel.connect();
                sftpChannel = (ChannelSftp) channel;

                // 打开远程文件的输入流，并定位到指定位置
                inputStream = sftpChannel.get(REMOTE_FILE_PATH, null, actualStartPos);

                // 打开本地文件
                randomAccessFile = new RandomAccessFile(tempFile, "rw");
                randomAccessFile.seek(downloadedSize);

                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                long currentDownloaded = downloadedSize;
                long lastLogTime = System.currentTimeMillis();
                long lastSaveTime = System.currentTimeMillis();
                long remainingBytes = endPos - actualStartPos + 1;
                long downloadStartTime = System.currentTimeMillis();

                while (currentDownloaded < totalSize) {
                    // 计算本次读取的最大字节数
                    int maxRead = (int) Math.min(buffer.length, remainingBytes);

                    try {
                        bytesRead = inputStream.read(buffer, 0, maxRead);
                        if (bytesRead == -1) {
                            break;  // 流结束
                        }
                    } catch (IOException e) {
                        // 读取出错，可能是网络问题，抛出异常让外层重试
                        System.out.println("线程 " + threadId + " 读取数据出错，将重试");
                        throw e;
                    }

                    randomAccessFile.write(buffer, 0, bytesRead);
                    currentDownloaded += bytesRead;
                    remainingBytes -= bytesRead;

                    // 每5秒强制flush一次，确保数据写入磁盘（防止数据丢失）
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastSaveTime > 5000) {
                        randomAccessFile.getFD().sync();  // 强制同步到磁盘
                        lastSaveTime = currentTime;
                    }

                    // 每5秒输出一次进度
                    if (currentTime - lastLogTime > 5000) {
                        double progress = (double) currentDownloaded / totalSize * 100;
                        long elapsedTime = (currentTime - downloadStartTime) / 1000;  // 秒
                        double speed = currentDownloaded / (elapsedTime > 0 ? elapsedTime : 1.0);
                        System.out.println(String.format("线程 %d 进度: %.2f%% (%s/%s) 速度: %s/s",
                                threadId, progress, formatSize(currentDownloaded), formatSize(totalSize), formatSize((long)speed)));
                        lastLogTime = currentTime;
                    }
                }

                // 最后强制刷新一次
                randomAccessFile.getFD().sync();

                System.out.println("线程 " + threadId + " 下载完成 (" + formatSize(currentDownloaded) + ")");
                return true;

            } finally {
                // 确保资源关闭
                try {
                    if (randomAccessFile != null) {
                        randomAccessFile.close();
                    }
                } catch (IOException e) {
                    // 忽略关闭异常
                }

                try {
                    if (inputStream != null) {
                        inputStream.close();
                    }
                } catch (IOException e) {
                    // 忽略关闭异常
                }

                if (sftpChannel != null && sftpChannel.isConnected()) {
                    sftpChannel.disconnect();
                }

                if (session != null && session.isConnected()) {
                    session.disconnect();
                }
            }
        }

        private String formatSize(long size) {
            if (size < 1024) {
                return size + " B";
            } else if (size < 1024 * 1024) {
                return String.format("%.2f KB", size / 1024.0);
            } else if (size < 1024 * 1024 * 1024) {
                return String.format("%.2f MB", size / 1024.0 / 1024.0);
            } else {
                return String.format("%.2f GB", size / 1024.0 / 1024.0 / 1024.0);
            }
        }
    }
}