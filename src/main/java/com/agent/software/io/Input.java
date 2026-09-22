package com.agent.software.io;

/**
 * 客户端输入通道。只负责"读"：把问题呈现给用户是另一回事（控制台打印 / Web 实时推送）。
 */
public abstract class Input {

    /** 返回 null 表示无输入/通道关闭。 */
    public abstract String read(String target);
}
