package com.elmacore.agentplatform.execution.application;
import java.util.concurrent.Semaphore;
import com.elmacore.agentplatform.execution.domain.RunFailure;
/** Shared process-wide paid-call admission for answers and proposal generation. */
public final class ExecutionAdmission {
    private final Semaphore active=new Semaphore(2);
    public void acquire(){if(!active.tryAcquire())throw new RunFailure("EXECUTION_BUSY",429);}
    public void release(){active.release();}
}
