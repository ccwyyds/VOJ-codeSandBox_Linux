package com.vv.voj;

import com.vv.voj.model.ExecuteCodeRequest;
import com.vv.voj.model.ExecuteCodeResponse;


/**
 * @Title: java原生实现继承模板方法
 * @Author: vv
 * @Date: 2025/7/30 14:57
 */

public class JavaNativeCodeSandbox extends JavaCodeSandboxTemplate {
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        return super.executeCode(executeCodeRequest);
    }
}
