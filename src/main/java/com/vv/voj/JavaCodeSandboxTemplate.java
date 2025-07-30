package com.vv.voj;


import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.vv.voj.model.ExecuteCodeRequest;
import com.vv.voj.model.ExecuteCodeResponse;
import com.vv.voj.model.ExecuteMessage;
import com.vv.voj.model.JudgeInfo;
import com.vv.voj.utils.ProcessUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @Title: 模板方法
 * @Author: vv
 * @Date: 2025/7/30 13:51
 */

public abstract class JavaCodeSandboxTemplate implements CodeSandbox {


    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final long TIME_OUT = 5000L;


    /**
     * @Title: 1. 把用户的代码保存为文件
     * @Author: vv
     * @Date: 2025/7/30 13:54
     */
    public File saveUserCodeFile(String code) {
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        // 判断全局代码目录是否存在，没有则新建
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        // 把用户的代码隔离存放
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        return userCodeFile;
    }

    /**
     * @Title: 2.编译文件
     * @Author: vv
     * @Date: 2025/7/30 14:01
     */

    public ExecuteMessage compileMessage(File userCodeFile) {
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            if (executeMessage.getExitValue() != 0) {
                throw new RuntimeException("编译错误");
            }
            return executeMessage;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @Title: 3.执行代码
     * @Author: vv
     * @Date: 2025/7/30 14:01
     */

    public List<ExecuteMessage> runCode(List<String> inputList, File userCodeFile) {
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        for (String inputArgs : inputList) {
            String runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s", userCodeParentPath, inputArgs);
            try {
                Future<ExecuteMessage> future = executorService.submit(() -> {
                    Process runProcess = Runtime.getRuntime().exec(runCmd);
                    boolean finished = runProcess.waitFor(TIME_OUT, TimeUnit.MILLISECONDS);
                    if (!finished) {
                        runProcess.destroyForcibly(); // 强制终止
                        throw new RuntimeException("代码运行超时");
                    }
                    return ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                });
                ExecuteMessage executeMessage = future.get(); // 若上面 throw 会进 catch
                System.out.println(executeMessage);
                executeMessageList.add(executeMessage);
            } catch (Exception e) {
                throw new RuntimeException("代码执行异常");
            }
        }
        executorService.shutdown(); // 关闭线程池
        return executeMessageList;
    }

    /**
     * @Title: 4.获取输出结果
     * @Author: vv
     * @Date: 2025/7/30 14:33
     */
    public ExecuteCodeResponse getOutputResponseList(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        // 取用时最大值，便于判断是否超时
        long maxTime = 0;
        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // 用户提交的代码执行中存在错误
                executeCodeResponse.setStatus("答案错误");
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long time = executeMessage.getTime();
            if (time != null) {
                maxTime = Math.max(maxTime, time);
            }
        }
        // 正常运行完成
        if (outputList.size() == executeMessageList.size()) {
            executeCodeResponse.setStatus("成功");
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        // 要借助第三方库来获取内存占用，非常麻烦，此处不做实现
//        judgeInfo.setMemory();
        executeCodeResponse.setJudgeInfo(judgeInfo);

        return executeCodeResponse;

    }


    /**
     * @Title: 5.文件清理
     * @Author: vv
     * @Date: 2025/7/30 14:42
     */
    public boolean delFile(File userCodeFile) {
        if (userCodeFile.getParentFile() != null) {
            String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
            boolean del = FileUtil.del(userCodeParentPath);
            System.out.println("删除" + userCodeParentPath + (del ? "成功" : "失败"));
            return del;
        }
        return true;
    }


    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {

        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        //记录时间
        long start = System.currentTimeMillis();

        // 1.把用户的代码保存为文件
        File userCodeFile = saveUserCodeFile(code);

        // 2. 编译代码，得到 class 文件
        ExecuteMessage executeMessage = compileMessage(userCodeFile);
        System.out.println(executeMessage);

        // 3. 执行代码，得到输出结果（使用线程池 + 超时控制）
        List<ExecuteMessage> executeMessages = runCode(inputList, userCodeFile);

        // 4. 收集整理输出结果
        ExecuteCodeResponse executeCodeResponse = getOutputResponseList(executeMessages);

        // 5. 文件清理
        delFile(userCodeFile);

        //输出时间
        System.out.println("总耗时: " + (System.currentTimeMillis() - start) + " ms");

        return executeCodeResponse;
    }

    /**
     * 获取错误响应
     *
     * @param e
     * @return
     */
    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(new ArrayList<>());
        executeCodeResponse.setMessage(e.getMessage());
        // 表示代码沙箱错误
        executeCodeResponse.setStatus("错误");
        executeCodeResponse.setJudgeInfo(new JudgeInfo());
        return executeCodeResponse;
    }


}
