package com.vv.voj;

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
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
@Component
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {

    private static final String IMAGE_NAME = "openjdk:8-alpine";
    private static final long TIME_OUT = 5000;

    //因为doctor的执行代码部分不同，所以要重写子类方法
    @Override
    public List<ExecuteMessage> runCode(List<String> inputList, File userCodeFile) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        // 3. 创建 Docker 客户端
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        // 拉取镜像（首次）
        try {
            List<Image> images = dockerClient.listImagesCmd().withImageNameFilter(IMAGE_NAME).exec();
            if (images == null || images.isEmpty()) {
                // 本地没有才拉取
                PullImageCmd pullImageCmd = dockerClient.pullImageCmd(IMAGE_NAME);
                PullImageResultCallback pullImageResultCallback = new PullImageResultCallback();
                try {
                    pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
                } catch (InterruptedException e) {
                    throw new RuntimeException("拉取 Docker 镜像失败");
                }
                System.out.println("镜像拉取完成");
            } else {
                System.out.println("本地已有镜像，无需拉取");
            }
        } catch (Exception e) {
            throw new RuntimeException("拉取 Docker 镜像失败");
        }

        // 4. 创建并启动容器（只一次）
        String containerId;
        try {
            HostConfig hostConfig = new HostConfig().withBinds(new Bind(userCodeParentPath, new Volume("/app"))).withMemory(100 * 1024 * 1024L)//限制最大内存100MB
                    .withCpuCount(1L);//限制cup核心数
            CreateContainerResponse container = dockerClient.createContainerCmd(IMAGE_NAME).withHostConfig(hostConfig).withReadonlyRootfs(true)//禁止向root根目录写文件
                    .withNetworkDisabled(true)//禁用网络
                    .withReadonlyRootfs(true).withAttachStderr(true).withAttachStdout(true).withTty(true).exec();

            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();
        } catch (Exception e) {
            throw new RuntimeException("容器启动失败");
        }

        // 5. 执行代码（多次 docker exec）
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        long maxTime = 0;
        long maxMemory = 0;

        DockerMemoryUtils.MemoryMonitor memoryMonitor = new DockerMemoryUtils.MemoryMonitor(dockerClient, containerId);
        memoryMonitor.start();

        for (String inputArgs : inputList) {
            try {
                String[] inputArgsArray = inputArgs.trim().split(" ");
                String[] command = ArrayUtil.append(new String[]{"java", "-cp", "/app", "Main"}, inputArgsArray);

                ExecCreateCmdResponse execCmd = dockerClient.execCreateCmd(containerId).withCmd(command).withAttachStderr(true).withAttachStdout(true).exec();

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
                ExecuteMessage executeMessage = new ExecuteMessage();
                //记录程序执行时间(超时处理)
                boolean timeOut = false;
                stopWatch.start();
                timeOut = dockerClient.execStartCmd(execId).exec(callback).awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
                stopWatch.stop();
                long usedTime = stopWatch.getLastTaskTimeMillis();
                if (!timeOut) {
                    // 超时，强制kill容器
                    System.out.println("代码运行超时，强制终止容器");
                    dockerClient.killContainerCmd(containerId).exec();
                    executeMessage.setErrorMessage("代码运行超时");
                }

                //最大内存
                long usedMemory = memoryMonitor.getMaxMemory();
                executeMessage.setMessage(output.toString().trim());
                executeMessage.setErrorMessage(error.toString().trim());
                executeMessage.setTime(usedTime);
                executeMessage.setMemory(usedMemory);
                executeMessageList.add(executeMessage);

                maxTime = Math.max(maxTime, usedTime);
                maxMemory = Math.max(maxMemory, usedMemory);
            } catch (Exception e) {
                throw new RuntimeException("执行失败", e);
            }
        }

        memoryMonitor.stop();

        // 6. 删除容器
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

        // 7. 生成响应
        List<String> outputList = new ArrayList<>();
        for (ExecuteMessage msg : executeMessageList) {
            if (StrUtil.isNotBlank(msg.getErrorMessage())) {
                throw new RuntimeException(msg.getErrorMessage());
            }
            outputList.add(msg.getMessage());
        }
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(outputList);
        executeCodeResponse.setStatus("成功");

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);
        executeCodeResponse.setJudgeInfo(judgeInfo);

        return executeMessageList;
    }



    public static void main(String[] args) {
        JavaDockerCodeSandbox sandbox = new JavaDockerCodeSandbox();
        ExecuteCodeRequest request = new ExecuteCodeRequest();
        request.setInputList(Arrays.asList("4 4", "1 3"));
        String code = ResourceUtil.readStr("testCode/simpleComputeArgs/Main.java", StandardCharsets.UTF_8);
        request.setCode(code);
        request.setLanguage("java");

        ExecuteCodeResponse response = sandbox.executeCode(request);
        System.out.println(response);
    }


}
