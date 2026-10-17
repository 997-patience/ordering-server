package com.wys.orderingserver.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5 加密工具类
 *
 * 用途：用户/员工密码入库前加密，避免明文存储。
 * 注意：MD5 不可逆但可撞库（123456 的密文网上随便搜得到），
 * 真实生产建议换成 BCrypt；本项目按方案要求用 MD5 即可，
 * 面试被问到时如实回答"MD5 已不安全，生产会用 BCrypt"。
 */
public class Md5Util {

    /** 16 进制字符表 */
    private static final char[] HEX_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    /**
     * 生成字符串的 MD5 值（32 位小写）
     * 例：getMD5String("123456") = "e10adc3949ba59abbe56e057f20f883e"
     */
    public static String getMD5String(String s) {
        if (s == null) {
            return null;
        }
        return getMD5String(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验明文密码与已知 MD5 密文是否匹配
     * 例：checkPassword("123456", "e10adc3949ba59abbe56e057f20f883e") = true
     */
    public static boolean checkPassword(String password, String md5PwdStr) {
        String s = getMD5String(password);
        return s != null && s.equals(md5PwdStr);
    }

    public static String getMD5String(byte[] bytes) {
        try {
            //每次新建实例：MessageDigest 非线程安全，共用静态实例在并发下会算出错误摘要
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            return bufferToHex(messageDigest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 MD5 算法", e);
        }
    }

    private static String bufferToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX_DIGITS[(b & 0xf0) >> 4]);
            sb.append(HEX_DIGITS[b & 0x0f]);
        }
        return sb.toString();
    }
}
