package com.garganttua.api.core.repository;

import com.garganttua.api.commons.ApiException;

public class RepositoryException extends ApiException {

    private static final long serialVersionUID = 1L;

    private static final int REPOSITORY_ERROR_CODE = 200;

    public RepositoryException(String message) {
        super(RepositoryException.REPOSITORY_ERROR_CODE, message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(RepositoryException.REPOSITORY_ERROR_CODE, message, cause);
    }

}
