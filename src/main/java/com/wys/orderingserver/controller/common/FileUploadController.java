package com.wys.orderingserver.controller.common;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.utils.FileUploadUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.List;

/** 11.1 单张图片上传；按接口文档允许不携带 token。 */
@RestController
@RequestMapping("/admin/upload")
public class FileUploadController {
    @Autowired
    private FileUploadUtil fileUploadUtil;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> upload(@RequestParam("file") MultipartFile file,
                                 MultipartHttpServletRequest request) {
        int count = 0;
        for (List<MultipartFile> files : request.getMultiFileMap().values()) {
            count += files.size();
        }
        if (count != 1) throw new BizException("每次只能上传一张图片");
        return Result.success(fileUploadUtil.uploadImage(file));
    }
}
