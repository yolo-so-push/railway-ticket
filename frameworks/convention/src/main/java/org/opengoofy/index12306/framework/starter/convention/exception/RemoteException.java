package org.opengoofy.index12306.framework.starter.convention.exception;

import org.opengoofy.index12306.framework.starter.convention.errorcode.IErrorCode;

/**
 * 远程调用异常
 */
public class RemoteException extends AbstractException{

    public RemoteException(IErrorCode code, String errorMessage, Throwable throwable) {
        super(code, errorMessage, throwable);
    }

    public RemoteException(String message,IErrorCode errorCode){
        this(errorCode,message,null);
    }

    public RemoteException(String message){
        this(null,message,null);
    }

    @Override
    public String toString() {
        return "RemoteException{" +
                "code='" + code + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
