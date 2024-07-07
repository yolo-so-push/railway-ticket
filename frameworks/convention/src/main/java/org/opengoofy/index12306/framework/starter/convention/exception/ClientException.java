package org.opengoofy.index12306.framework.starter.convention.exception;

import org.opengoofy.index12306.framework.starter.convention.errorcode.IErrorCode;

/**
 * 客户端异常
 */
public class ClientException extends AbstractException{

    public ClientException(IErrorCode errorCode){
        this(errorCode,null,null);
    }

    public ClientException(IErrorCode code, String errorMessage, Throwable throwable) {
        super(code, errorMessage, throwable);
    }

    public ClientException(String message,IErrorCode errorCode){
        this(errorCode,message,null);
    }

    public ClientException(String message){
        this(null,message,null);
    }

    @Override
    public String toString() {
        return "ClientException{" +
                "code="+code+","+
                "message="+errorMessage+","+
                "}";
    }
}
