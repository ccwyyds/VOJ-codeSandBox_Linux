package com.vv.voj.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.LogContainerResultCallback;

public class DockerDemo {
    public static void main(String[] args) throws InterruptedException {

        //获取默认的docker 镜像
        DockerClient dockerClient = DockerClientBuilder.getInstance().build();

        //拉取镜像
        String image = "nginx:latest";
        PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
        PullImageResultCallback pullImageResultCallback = new PullImageResultCallback(){
            @Override
            public void onNext(PullResponseItem item) {
                System.out.println("下载镜像：" + item);
                super.onNext(item);
            }
        };
        pullImageCmd
                .exec(pullImageResultCallback)
                //堵塞作用,未下载好不继续执行
                .awaitCompletion();
        System.out.println("下载完成");

        //创建容器
        CreateContainerCmd createContainerCmd = dockerClient
                .createContainerCmd(image);
        CreateContainerResponse createContainerResponse = createContainerCmd.exec();
        System.out.println(createContainerResponse);

        //获取容器id
        String containerId = createContainerResponse.getId();

        //启动容器
        dockerClient.startContainerCmd(containerId).exec();

        //输出日志
        LogContainerResultCallback logContainerResultCallback = new LogContainerResultCallback() {
            @Override
            public void onNext(Frame item) {
                System.out.println("日志:" + item.getPayload());
                super.onNext(item);
            }
        };
        dockerClient.logContainerCmd(containerId).exec(logContainerResultCallback).awaitCompletion();

    }
}
