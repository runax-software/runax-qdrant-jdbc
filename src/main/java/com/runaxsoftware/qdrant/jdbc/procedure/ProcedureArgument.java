package com.runaxsoftware.qdrant.jdbc.procedure;

/**
 * One argument of a procedure.
 *
 * @param name     the argument's name, which is also Qdrant's for the value
 * @param kind     what it takes
 * @param required whether a call must give it
 * @param target   where its value goes in the request
 * @param remarks  a description shown as the argument's comment
 */
public record ProcedureArgument(String name, ArgumentKind kind, boolean required,
                                ArgumentTarget target, String remarks) {

    static ProcedureArgument path(String name, ArgumentKind kind, String remarks) {
        return new ProcedureArgument(name, kind, true, ArgumentTarget.PATH, remarks);
    }

    static ProcedureArgument required(String name, ArgumentKind kind, String remarks) {
        return new ProcedureArgument(name, kind, true, ArgumentTarget.BODY, remarks);
    }

    static ProcedureArgument optional(String name, ArgumentKind kind, String remarks) {
        return new ProcedureArgument(name, kind, false, ArgumentTarget.BODY, remarks);
    }

    static ProcedureArgument query(String name, ArgumentKind kind, String remarks) {
        return new ProcedureArgument(name, kind, false, ArgumentTarget.QUERY, remarks);
    }
}
