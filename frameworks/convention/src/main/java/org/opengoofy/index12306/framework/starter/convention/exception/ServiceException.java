package org.opengoofy.index12306.framework.starter.convention.exception;

import org.opengoofy.index12306.framework.starter.convention.errorcode.BaseErrorCode;
import org.opengoofy.index12306.framework.starter.convention.errorcode.IErrorCode;

import java.util.Optional;

/**
 * 服务端异常
 */
public class ServiceException extends AbstractException{
    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    public ServiceException(IErrorCode errorCode) {
        this(null, errorCode);
    }

    public ServiceException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(errorCode,Optional.ofNullable(message).orElse(errorCode.message()),throwable);
    }

    @Override
    public String toString() {
        return "ServiceException{" +
                "code='" + code + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
