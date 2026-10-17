package com.wys.orderingserver.utils;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * 图片上传工具类（本地磁盘存储方案）
 *
 * 方案选择：按项目方案第 8 章，图片存服务器本地磁盘 + 静态资源映射，
 * 不使用 MinIO / 阿里云 OSS，半天即可搞定。
 *
 * 两个配置项（application.yaml → ordering.upload.*）：
 *   ordering.upload.dir         图片落盘根目录，必须以 / 结尾，如 D:/idea/Project/ordering-server/upload/
 *   ordering.upload.url-prefix  对外访问前缀，如 /upload/ ，配合 WebConfig 的 addResourceHandlers 生效
 *
 * 为什么用 setUploadDir 而不是 @Value 字段注入？
 *   本类是全静态方法工具类（与 JwtUtil / Md5Util 风格一致），
 *   所以目录由调用方注入一次：让 FileUploadController 加 @Value 后调用 setUploadDir(init)。
 */
public class FileUploadUtil {

    /** 对外访问前缀（与 WebConfig 静态资源映射保持一致） */
    public static final String URL_PREFIX = "/upload/";

    /** 图片落盘根目录，以 / 结尾；由 FileUploadController 从配置文件注入 */
    private static String uploadDir = "D:/idea/Project/ordering-server/upload/";

    private FileUploadUtil() {
    }

    /** 注入落盘目录（在 FileUploadController 中用 @Value 调用一次） */
    public static void setUploadDir(String dir) {
        if (dir != null && !dir.isBlank()) {
            uploadDir = dir.endsWith("/") || dir.endsWith("\\") ? dir : dir + "/";
        }
    }

    /**
     * 上传图片
     *
     * @param file 前端 multipart/form-data 传来的文件
     * @return 图片对外访问地址（相对路径），如 /upload/8f3c1b2a-....jpg
     *         前端直接把它存进菜品记录的 image 字段即可
     */
    public static String uploadImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        // 1. 用 UUID 重命名，避免多个用户上传同名文件互相覆盖
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (originalFilename != null && originalFilename.lastIndexOf('.') > -1) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + suffix;

        // 2. 确保目录存在（mkdirs 幂等，缺目录时 transferTo 会直接失败）
        File dir = new File(uploadDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("创建上传目录失败：" + uploadDir);
        }

        // 3. 落盘
        file.transferTo(new File(dir, filename));

        // 4. 返回相对路径，由前端拼域名访问
        return URL_PREFIX + filename;
    }
}
