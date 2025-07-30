package com.vv.voj.controller;

import com.vv.voj.JavaNativeCodeSandbox;
import com.vv.voj.model.ExecuteCodeRequest;
import com.vv.voj.model.ExecuteCodeResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
public class MainController {

    @Resource
    private JavaNativeCodeSandbox javaNativeCodeSandbox;


    @GetMapping("/health")
    public String checkHealth() {

        return "ok";
    }

    /**
     * 执行代码
     * @param executeCodeRequest
     * @return
     */
    @PostMapping("/executeCode")
    public ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest) {
        if (executeCodeRequest == null) {
            throw new RuntimeException("参数为空");
        }
        return javaNativeCodeSandbox.executeCode(executeCodeRequest);
    }

}
