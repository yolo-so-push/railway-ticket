package org.opengoofy.index12306.framework.starter.convention.exception;

import lombok.Getter;
import org.opengoofy.index12306.framework.starter.convention.errorcode.IErrorCode;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 抽象客户端，远程调用，服务端异常
 */
@Getter
public abstract class AbstractException extends RuntimeException{
    public final String code;
    public final String errorMessage;

    protected AbstractException(IErrorCode code, String errorMessage, Throwable throwable) {
        super(errorMessage,throwable);
        this.code = code.code();
        this.errorMessage = Optional.ofNullable(StringUtils.hasLength(errorMessage)?errorMessage:null).orElse(code.message());
    }
}
