package com.garganttua.api.commons.operation;

public enum OperationType {

    standard, usesCase, authentication, refreshAuthorization, workflow,

    /** Replaces the grants of one entity: who else may read or write it. */
    share

}
