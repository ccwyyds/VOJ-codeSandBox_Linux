package com.vv.voj.model;

import lombok.Data;

/**
 * @Title: 判题信息
 * @Author: vv
 * @Date: 2025/6/14 0:40
 */

@Data
public class JudgeInfo {
    //程序执行信息
    private String message;
    //执行时间(ms)
    private Long time;
    //执行内存(kb)
    private Long memory;
}
