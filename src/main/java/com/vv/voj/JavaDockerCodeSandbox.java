package com.vv.voj;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.vv.voj.model.ExecuteCodeRequest;
import com.vv.voj.model.ExecuteCodeResponse;
import com.vv.voj.model.ExecuteMessage;
import com.vv.voj.model.JudgeInfo;
import com.vv.voj.utils.DockerMemoryUtils;
import com.vv.voj.utils.ProcessUtils;
import org.springframework.util.StopWatch;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class JavaDockerCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";
    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";
    private static final String IMAGE_NAME = "openjdk:8-alpine";

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        long t1 = System.currentTimeMillis();
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        long start = System.currentTimeMillis();

        // 1. 写入用户代码到本地文件
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        long t2 = System.currentTimeMillis();
        System.out.println("[耗时日志] 写入代码耗时: " + (t2 - t1) + " ms");

        // 2. 编译代码
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage compileResult = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            if (StrUtil.isNotBlank(compileResult.getErrorMessage())) {
                return errorResponse(compileResult.getErrorMessage());
            }
        } catch (Exception e) {
            return getErrorResponse(e);
        }
        long t3 = System.currentTimeMillis();
        System.out.println("[耗时日志] 编译代码耗时: " + (t3 - t2) + " ms");

        // 3. 创建 Docker 客户端
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();
        long t4 = System.currentTimeMillis();
        System.out.println("[耗时日志] 创建 Docker 客户端耗时: " + (t4 - t3) + " ms");

        // 拉取镜像（首次）
        try {
            List<Image> images = dockerClient.listImagesCmd().withImageNameFilter(IMAGE_NAME).exec();
            if (images == null || images.isEmpty()) {
                // 本地没有才拉取
                PullImageCmd pullImageCmd = dockerClient.pullImageCmd(IMAGE_NAME);
                PullImageResultCallback pullImageResultCallback = new PullImageResultCallback();
                try {
                    pullImageCmd
                            .exec(pullImageResultCallback)
                            .awaitCompletion();
                } catch (InterruptedException e) {
                    return errorResponse("拉取 Docker 镜像失败");
                }
                System.out.println("镜像拉取完成");
            } else {
                System.out.println("本地已有镜像，无需拉取");
            }
        } catch (Exception e) {
            return errorResponse("拉取 Docker 镜像失败");
        }
        long t5 = System.currentTimeMillis();
        System.out.println("[耗时日志] 拉取镜像耗时: " + (t5 - t4) + " ms");

        // 4. 创建并启动容器（只一次）
        String containerId;
        try {
            HostConfig hostConfig = new HostConfig()
                    .withBinds(new Bind(userCodeParentPath, new Volume("/app")))
                    .withMemory(100 * 1024 * 1024L)
                    .withCpuCount(1L);

            CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE_NAME)
                    .withHostConfig(hostConfig)
                    .withNetworkDisabled(true)
                    .withReadonlyRootfs(true)
                    .withAttachStderr(true)
                    .withAttachStdout(true)
                    .withTty(true)
                    .exec();

            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();
        } catch (Exception e) {
            return errorResponse("容器启动失败：" + e.getMessage());
        }
        long t6 = System.currentTimeMillis();
        System.out.println("[耗时日志] 创建并启动容器耗时: " + (t6 - t5) + " ms");

        // 5. 执行代码（多次 docker exec）
        long t7 = System.currentTimeMillis();
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        long maxTime = 0;
        long maxMemory = 0;

        DockerMemoryUtils.MemoryMonitor memoryMonitor = new DockerMemoryUtils.MemoryMonitor(dockerClient, containerId);
        memoryMonitor.start();

        for (String inputArgs : inputList) {
            try {
                String[] inputArgsArray = inputArgs.trim().split(" ");
                String[] command = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);

                ExecCreateCmdResponse execCmd = dockerClient.execCreateCmd(containerId)
                        .withCmd(command)
                        .withAttachStderr(true)
                        .withAttachStdout(true)
                        .exec();

                String execId = execCmd.getId();
                final StringBuilder output = new StringBuilder();
                final StringBuilder error = new StringBuilder();
                StopWatch stopWatch = new StopWatch();

                ExecStartResultCallback callback = new ExecStartResultCallback() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getStreamType() == StreamType.STDERR) {
                            error.append(new String(frame.getPayload()));
                        } else {
                            output.append(new String(frame.getPayload()));
                        }
                        super.onNext(frame);
                    }
                };

                stopWatch.start();
                dockerClient.execStartCmd(execId).exec(callback).awaitCompletion();
                stopWatch.stop();
                long usedTime = stopWatch.getLastTaskTimeMillis();
                long usedMemory = memoryMonitor.getMaxMemory();

                ExecuteMessage message = new ExecuteMessage();
                message.setMessage(output.toString().trim());
                message.setErrorMessage(error.toString().trim());
                message.setTime(usedTime);
                message.setMemory(usedMemory);
                executeMessageList.add(message);

                maxTime = Math.max(maxTime, usedTime);
                maxMemory = Math.max(maxMemory, usedMemory);
            } catch (Exception e) {
                return errorResponse("执行失败：" + e.getMessage());
            }
        }
        long t8 = System.currentTimeMillis();
        System.out.println("[耗时日志] 执行代码耗时: " + (t8 - t7) + " ms");

        memoryMonitor.stop();

        // 6. 删除容器
        long t9_start = System.currentTimeMillis();
        try {
            dockerClient.killContainerCmd(containerId).exec();
        } catch (Exception e) {
            System.err.println("Kill容器失败：" + e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            System.err.println("删除容器失败：" + e.getMessage());
        }
        long t9 = System.currentTimeMillis();
        System.out.println("[耗时日志] 删除容器耗时: " + (t9 - t9_start) + " ms");

        // 7. 生成响应
        long t10_start = System.currentTimeMillis();
        List<String> outputList = new ArrayList<>();
        for (ExecuteMessage msg : executeMessageList) {
            if (StrUtil.isNotBlank(msg.getErrorMessage())) {
                return errorResponse(msg.getErrorMessage());
            }
            outputList.add(msg.getMessage());
        }

        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setOutputList(outputList);
        response.setStatus("成功");

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);
        response.setJudgeInfo(judgeInfo);
        long t10 = System.currentTimeMillis();
        System.out.println("[耗时日志] 生成响应耗时: " + (t10 - t10_start) + " ms");

        // 8. 清理文件
        if (userCodeFile.getParentFile() != null) {
            FileUtil.del(userCodeParentPath);
        }

        System.out.println("总耗时: " + (System.currentTimeMillis() - start) + " ms");

        return response;
    }

    private ExecuteCodeResponse errorResponse(String msg) {
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setStatus("程序执行异常");
        response.setMessage(msg);
        response.setOutputList(Collections.emptyList());
        response.setJudgeInfo(new JudgeInfo());
        return response;
    }

    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        return errorResponse(e.getMessage());
    }

    public static void main(String[] args) {
        JavaDockerCodeSandbox sandbox = new JavaDockerCodeSandbox();
        ExecuteCodeRequest request = new ExecuteCodeRequest();
        request.setInputList(Arrays.asList("2 2", "4 3"));
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        request.setCode(code);
        request.setLanguage("java");

        ExecuteCodeResponse response = sandbox.executeCode(request);
        System.out.println(response);
    }
}
