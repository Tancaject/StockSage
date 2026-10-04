/**
 * 账号注册与请求身份提取；业务服务接收显式用户 ID，不自行读取请求会话。
 * 持久化到 Redis Session 的 security.AuthenticatedUser 保持原包名与类结构，
 * 避免模块归属调整改变 Java 序列化契约。
 */
package com.stocksage.identity;
